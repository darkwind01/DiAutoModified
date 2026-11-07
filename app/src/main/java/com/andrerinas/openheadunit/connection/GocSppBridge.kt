package com.andrerinas.openheadunit.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.SystemProperties
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Whatever carries the Android Auto wireless setup messages between this app and the phone.
 *
 * An RFCOMM socket on a normal unit. On a unit whose Bluetooth is an external module, the phone's
 * RFCOMM channel ends in the vendor daemon instead, and the daemon's SPP socket stands in for it.
 */
interface HandshakeLink {
    val inputStream: InputStream
    val outputStream: OutputStream
    val deviceName: String?
    val deviceAddress: String
    fun close()
}

class BluetoothHandshakeLink(val socket: BluetoothSocket) : HandshakeLink {
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream
    @get:SuppressLint("MissingPermission")
    override val deviceName: String? get() = socket.remoteDevice.name
    override val deviceAddress: String get() = socket.remoteDevice.address
    override fun close() = socket.close()
}

/**
 * The SPP socket of the GOC Bluetooth daemon (`gocsdk_*`) found on units whose Bluetooth is an
 * external module wired to a UART.
 *
 * What the daemon puts on this socket is not documented. The working assumption is that it relays
 * the phone's RFCOMM payload unchanged; every byte in either direction is logged in hex so a log
 * from a unit where that assumption fails shows what the framing actually is.
 */
object GocSppBridge {
    const val SOCKET_PATH = "/dev/socket/goc_spp"

    /**
     * Lets the socket be switched over adb without a rebuild, e.g. to the one the vendor's own
     * projection app uses: `setprop persist.diauto.spp_socket /dev/socket/zj_bt_socket`.
     */
    private const val SOCKET_PROPERTY = "persist.diauto.spp_socket"

    /** Bytes shown per logged chunk; the handshake messages are all well under this. */
    private const val MAX_LOGGED_BYTES = 256
    private val HEX = "0123456789ABCDEF".toCharArray()

    val socketPath: String
        get() = SystemProperties.get(SOCKET_PROPERTY, "")?.trim()?.ifEmpty { null } ?: SOCKET_PATH

    fun isAvailable(): Boolean = try { File(socketPath).exists() } catch (e: Exception) { false }

    internal fun logBytes(direction: String, data: ByteArray, off: Int, len: Int) {
        val shown = minOf(len, MAX_LOGGED_BYTES)
        val hex = StringBuilder(shown * 3)
        val text = StringBuilder(shown)
        for (i in 0 until shown) {
            val b = data[off + i].toInt() and 0xff
            hex.append(HEX[b ushr 4]).append(HEX[b and 0x0f]).append(' ')
            // '%' is left out so the line can never be mistaken for a format string.
            text.append(if (b in 0x20..0x7e && b != '%'.code) b.toChar() else '.')
        }
        val more = if (len > shown) " ...(+${len - shown} bytes)" else ""
        AppLog.i("GocSpp: [$direction raw] $len bytes: ${hex.toString().trimEnd()}$more | $text")
    }
}

class GocSppLink private constructor(private val socket: LocalSocket, path: String) : HandshakeLink {
    override val inputStream: InputStream = LoggingInputStream(socket.inputStream)
    override val outputStream: OutputStream = LoggingOutputStream(socket.outputStream)
    override val deviceName: String? = "vendor Bluetooth module"
    override val deviceAddress: String = path

    override fun close() {
        // shutdownInput first: close() alone does not always wake a thread blocked in read().
        try { socket.shutdownInput() } catch (e: Exception) {}
        try { socket.close() } catch (e: Exception) {}
    }

    companion object {
        fun connect(): GocSppLink {
            val path = GocSppBridge.socketPath
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            return GocSppLink(socket, path)
        }
    }

    private class LoggingInputStream(private val inner: InputStream) : InputStream() {
        override fun read(): Int {
            val value = inner.read()
            if (value >= 0) GocSppBridge.logBytes("RX", byteArrayOf(value.toByte()), 0, 1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = inner.read(b, off, len)
            if (count > 0) GocSppBridge.logBytes("RX", b, off, count)
            return count
        }

        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    private class LoggingOutputStream(private val inner: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            inner.write(b)
            GocSppBridge.logBytes("TX", byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            GocSppBridge.logBytes("TX", b, off, len)
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}

/**
 * Tells us when the phone opens or closes its SPP channel to the vendor Bluetooth module.
 *
 * The daemon announces both only in its own log (`nwdbt: on_spp_connected spp_num=...`), so this
 * follows logcat. Reading another process's log needs READ_LOGS, which has to be granted once over
 * adb; without it nothing ever arrives here, and a warning says how to fix that.
 */
class GocSppEventWatcher(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val CONNECTED_MARKER = "on_spp_connected spp_num"
        private const val DISCONNECTED_MARKER = "on_spp_disconnected spp_num"

        /** How long to see nothing at all from the vendor daemon before saying why. */
        private const val SILENCE_WARNING_MS = 90_000L
    }

    @Volatile private var process: Process? = null
    @Volatile private var sawVendorLine = false
    private var job: Job? = null
    private var silenceJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        sawVendorLine = false
        job = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-GocSppWatcher")) {
            while (isActive) {
                try {
                    val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "nwdbt:*", "*:S")
                        .redirectErrorStream(true)
                        .start()
                    process = p
                    p.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.contains("nwdbt")) sawVendorLine = true
                            when {
                                line.contains(CONNECTED_MARKER) -> {
                                    AppLog.i("GocSpp: The phone opened its SPP channel to the vendor Bluetooth module.")
                                    onConnected()
                                }
                                line.contains(DISCONNECTED_MARKER) -> {
                                    AppLog.i("GocSpp: The phone closed its SPP channel to the vendor Bluetooth module.")
                                    onDisconnected()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) AppLog.w("GocSpp: logcat watcher stopped: ${e.message}")
                } finally {
                    try { process?.destroy() } catch (e: Exception) {}
                    process = null
                }
                if (isActive) delay(2000)
            }
        }
        silenceJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-GocSppSilence")) {
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive && SystemClock.elapsedRealtime() - startedAt < SILENCE_WARNING_MS) delay(1000)
            if (isActive && !sawVendorLine) {
                AppLog.w(
                    "GocSpp: Nothing from the vendor Bluetooth daemon in ${SILENCE_WARNING_MS / 1000}s. " +
                        "This app most likely cannot read the system log. Grant it once over adb: " +
                        "pm grant com.andrerinas.headunitrevived android.permission.READ_LOGS"
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        silenceJob?.cancel()
        job = null
        silenceJob = null
        try { process?.destroy() } catch (e: Exception) {}
        process = null
    }
}
