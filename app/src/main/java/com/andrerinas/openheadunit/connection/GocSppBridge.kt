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

/**
 * One connection to the daemon's SPP socket.
 *
 * The daemon routes what it reads by a peer address at the start of each write: with none it logs
 * `str_to_addr error: str: invalid` / `spp_socket_on_data error: spp error addr:` and drops the
 * data. So every write goes out behind [prefix] — the phone's address by default — and a chunk the
 * daemon hands back starting with the same prefix has it removed before the handshake sees it.
 */
class GocSppLink private constructor(
    private val socket: LocalSocket,
    path: String,
    prefix: ByteArray,
) : HandshakeLink {
    override val inputStream: InputStream = PrefixedInputStream(socket.inputStream, prefix)
    override val outputStream: OutputStream = PrefixedOutputStream(socket.outputStream, prefix)
    override val deviceName: String? = "vendor Bluetooth module"
    override val deviceAddress: String = path

    override fun close() {
        // shutdownInput first: close() alone does not always wake a thread blocked in read().
        try { socket.shutdownInput() } catch (e: Exception) {}
        try { socket.close() } catch (e: Exception) {}
    }

    companion object {
        /**
         * What goes in front of every write. `{mac}` is the phone's address as the daemon prints it
         * (`40DE248C40B8`); `none` sends nothing. Settable over adb for experiments without a rebuild:
         * `setprop persist.diauto.spp_prefix "{mac}"`.
         */
        private const val PREFIX_PROPERTY = "persist.diauto.spp_prefix"
        private const val DEFAULT_PREFIX = "{mac}"

        fun connect(phoneMac: String?): GocSppLink {
            val path = GocSppBridge.socketPath
            val template = SystemProperties.get(PREFIX_PROPERTY, "")?.trim()?.ifEmpty { null } ?: DEFAULT_PREFIX
            val prefixText = when {
                template.equals("none", ignoreCase = true) -> ""
                template.contains("{mac}") && phoneMac.isNullOrEmpty() -> {
                    AppLog.w("GocSpp: The phone's address is not known yet; sending without a prefix.")
                    ""
                }
                else -> template.replace("{mac}", phoneMac.orEmpty())
            }
            AppLog.i("GocSpp: Connecting to $path, prefix per write: '${prefixText.ifEmpty { "<none>" }}'")
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            return GocSppLink(socket, path, prefixText.toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * Hands the handshake whole chunks as the daemon delivered them, minus a leading [prefix].
     * Buffered so a chunk is inspected once, however small the reads the handshake makes.
     */
    private class PrefixedInputStream(
        private val inner: InputStream,
        private val prefix: ByteArray,
    ) : InputStream() {
        private val chunk = ByteArray(4096)
        private var pos = 0
        private var end = 0

        /** Returns false at end of stream. */
        private fun fill(): Boolean {
            while (pos >= end) {
                val count = inner.read(chunk, 0, chunk.size)
                if (count < 0) return false
                if (count == 0) continue
                GocSppBridge.logBytes("RX", chunk, 0, count)
                pos = if (startsWithPrefix(count)) prefix.size else 0
                end = count
            }
            return true
        }

        private fun startsWithPrefix(count: Int): Boolean {
            if (prefix.isEmpty() || count < prefix.size) return false
            for (i in prefix.indices) if (chunk[i] != prefix[i]) return false
            return true
        }

        override fun read(): Int = if (!fill()) -1 else chunk[pos++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val count = minOf(len, end - pos)
            System.arraycopy(chunk, pos, b, off, count)
            pos += count
            return count
        }

        override fun available(): Int = end - pos
        override fun close() = inner.close()
    }

    /** One write on the socket per message: [prefix] and the message together, never split. */
    private class PrefixedOutputStream(
        private val inner: OutputStream,
        private val prefix: ByteArray,
    ) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val packet = ByteArray(prefix.size + len)
            System.arraycopy(prefix, 0, packet, 0, prefix.size)
            System.arraycopy(b, off, packet, prefix.size, len)
            inner.write(packet)
            GocSppBridge.logBytes("TX", packet, 0, packet.size)
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
    private val onConnected: (phoneMac: String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        // The daemon logs each connection twice, "on_spp_connected spp_num=1" and then
        // "on_spp_connected 40DE248C40B8"; the second carries the address every write must start with.
        private val CONNECTED_WITH_MAC = Regex("on_spp_connected ([0-9A-Fa-f]{12})\\b")
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
                            val connected = CONNECTED_WITH_MAC.find(line)
                            when {
                                connected != null -> {
                                    val mac = connected.groupValues[1].uppercase()
                                    AppLog.i("GocSpp: The phone ($mac) opened its SPP channel to the vendor Bluetooth module.")
                                    onConnected(mac)
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
                        "pm grant <this app's package> android.permission.READ_LOGS"
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
