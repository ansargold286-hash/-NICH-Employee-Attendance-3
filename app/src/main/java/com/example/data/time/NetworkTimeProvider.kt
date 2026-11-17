package com.example.data.time

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class NetworkTimeSnapshot(
    val epochMillis: Long,
    val sourceLabel: String,
    val clockDriftMs: Long,
    val tamperingDetected: Boolean,
    val isNetworkVerified: Boolean
)

interface NetworkTimeProvider {
    suspend fun syncNow(): NetworkTimeSnapshot
    fun currentSnapshot(): NetworkTimeSnapshot
    fun currentCalendar(): Calendar {
        val snap = currentSnapshot()
        return Calendar.getInstance(timeZone()).apply {
            timeInMillis = snap.epochMillis
        }
    }

    fun timeZone(): TimeZone = TimeZone.getDefault()
}

/**
 * Fallback deterministic provider using device clock only (used cleanly in unit tests or when
 * explicitly injected with a custom Calendar source).
 */
class DeviceCalendarTimeProvider(
    private val calendarSource: () -> Calendar = { Calendar.getInstance() }
) : NetworkTimeProvider {
    override suspend fun syncNow(): NetworkTimeSnapshot = currentSnapshot()

    override fun currentSnapshot(): NetworkTimeSnapshot {
        val cal = calendarSource()
        return NetworkTimeSnapshot(
            epochMillis = cal.timeInMillis,
            sourceLabel = "System Calendar Source",
            clockDriftMs = 0L,
            tamperingDetected = false,
            isNetworkVerified = false
        )
    }

    override fun currentCalendar(): Calendar = calendarSource()
}

/**
 * Tamper-resistant RFC 4330 SNTP UDP + HTTPS Date network time client anchored to monotonic
 * `SystemClock.elapsedRealtime()`.
 *
 * Once synced with an authoritative NTP server, subsequent attendance timestamps advance strictly
 * via monotonic uptime (`elapsedRealtime`), making manual phone Date & Time changes ineffective.
 */
class SntpNetworkTimeProvider(
    private val ntpHosts: List<String> = DEFAULT_NTP_HOSTS,
    private val ntpPort: Int = NTP_PORT,
    private val timeoutMs: Int = 2500,
    private val allowHttpFallback: Boolean = true,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() }
) : NetworkTimeProvider {

    @Volatile
    private var syncedNetworkUtcMs: Long? = null

    @Volatile
    private var syncedAnchorElapsedMs: Long = 0L

    @Volatile
    private var syncedHostName: String = "Unverified Device Clock"

    @Volatile
    private var lastObservedDriftMs: Long = 0L

    override fun timeZone(): TimeZone = timeZoneProvider()

    override suspend fun syncNow(): NetworkTimeSnapshot = withContext(Dispatchers.IO) {
        val nowElapsed = elapsedRealtimeMs()
        val cachedUtc = syncedNetworkUtcMs
        if (cachedUtc != null && (nowElapsed - syncedAnchorElapsedMs) in 0L until RESYNC_TTL_MS) {
            return@withContext currentSnapshot()
        }

        for (host in ntpHosts) {
            val sntpUtc = runCatching { querySntpServer(host, ntpPort, timeoutMs) }.getOrNull()
            if (sntpUtc != null && sntpUtc > MIN_VALID_EPOCH_MS) {
                recordAuthoritativeSync(sntpUtc, "NTP Verified ($host)")
                return@withContext currentSnapshot()
            }
        }

        if (allowHttpFallback) {
            val httpUtc = runCatching { queryHttpDateFallback(timeoutMs) }.getOrNull()
            if (httpUtc != null && httpUtc > MIN_VALID_EPOCH_MS) {
                recordAuthoritativeSync(httpUtc, "HTTPS Network Time")
                return@withContext currentSnapshot()
            }
        }

        currentSnapshot()
    }

    private fun recordAuthoritativeSync(networkUtcMs: Long, sourceLabel: String) {
        val elapsedAnchor = elapsedRealtimeMs()
        val deviceNow = wallClockMs()
        syncedNetworkUtcMs = networkUtcMs
        syncedAnchorElapsedMs = elapsedAnchor
        syncedHostName = sourceLabel
        lastObservedDriftMs = networkUtcMs - deviceNow
        if (kotlin.math.abs(lastObservedDriftMs) > CLOCK_TAMPER_THRESHOLD_MS) {
            Log.w(TAG, "Device clock differs from $sourceLabel by ${lastObservedDriftMs}ms; enforcing network time.")
        }
    }

    override fun currentSnapshot(): NetworkTimeSnapshot {
        val anchorUtc = syncedNetworkUtcMs
        if (anchorUtc != null) {
            val elapsedDelta = (elapsedRealtimeMs() - syncedAnchorElapsedMs).coerceAtLeast(0L)
            val authoritativeNow = anchorUtc + elapsedDelta
            val currentDrift = authoritativeNow - wallClockMs()
            val tampered = kotlin.math.abs(currentDrift) > CLOCK_TAMPER_THRESHOLD_MS
            val label = if (tampered) {
                "$syncedHostName • Clock Tamper Blocked"
            } else {
                syncedHostName
            }
            return NetworkTimeSnapshot(
                epochMillis = authoritativeNow,
                sourceLabel = label,
                clockDriftMs = currentDrift,
                tamperingDetected = tampered,
                isNetworkVerified = true
            )
        }
        return NetworkTimeSnapshot(
            epochMillis = wallClockMs(),
            sourceLabel = "Pending NTP Sync (Device Clock)",
            clockDriftMs = 0L,
            tamperingDetected = false,
            isNetworkVerified = false
        )
    }

    internal fun querySntpServer(host: String, port: Int = NTP_PORT, socketTimeoutMs: Int = 2500): Long? {
        DatagramSocket().use { socket ->
            socket.soTimeout = socketTimeoutMs
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)
            buffer[0] = NTP_MODE_CLIENT_VERSION_3

            val requestTime = wallClockMs()
            val requestTicks = elapsedRealtimeMs()
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)

            val request = DatagramPacket(buffer, buffer.size, address, port)
            socket.send(request)

            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val responseTicks = elapsedRealtimeMs()
            val responseTime = requestTime + (responseTicks - requestTicks).coerceAtLeast(0L)

            val originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET)

            if (transmitTime <= 0L) return null

            val roundTripOffset = if (originateTime > 0L && receiveTime > 0L) {
                ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2L
            } else {
                transmitTime - responseTime
            }
            return responseTime + roundTripOffset
        }
    }

    private fun queryHttpDateFallback(timeoutMs: Int): Long? {
        val url = URL("https://clients3.google.com/generate_204")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }
        return try {
            conn.connect()
            val headerDate = conn.date
            if (headerDate > MIN_VALID_EPOCH_MS) {
                headerDate
            } else {
                val rawDate = conn.getHeaderField("Date")
                if (!rawDate.isNullOrBlank()) {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                        .parse(rawDate)
                        ?.time
                } else {
                    null
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SntpNetworkTime"
        const val NTP_PORT = 123
        private const val NTP_PACKET_SIZE = 48
        private const val NTP_MODE_CLIENT_VERSION_3: Byte = 0x1B
        private const val ORIGINATE_TIME_OFFSET = 24
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40
        private const val OFFSET_1900_TO_1970_SECONDS = ((365L * 70L) + 17L) * 24L * 60L * 60L
        private const val MIN_VALID_EPOCH_MS = 1_700_000_000_000L // Late 2023 sanity floor
        private const val RESYNC_TTL_MS = 5 * 60 * 1000L
        const val CLOCK_TAMPER_THRESHOLD_MS = 120_000L // 2 minutes drift triggers tamper alert

        val DEFAULT_NTP_HOSTS = listOf(
            "time.google.com",
            "pool.ntp.org",
            "time.nist.gov"
        )

        internal fun read32Unsigned(buffer: ByteArray, offset: Int): Long {
            val b0 = buffer[offset].toLong() and 0xFFL
            val b1 = buffer[offset + 1].toLong() and 0xFFL
            val b2 = buffer[offset + 2].toLong() and 0xFFL
            val b3 = buffer[offset + 3].toLong() and 0xFFL
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        internal fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
            val seconds = read32Unsigned(buffer, offset)
            val fraction = read32Unsigned(buffer, offset + 4)
            if (seconds == 0L && fraction == 0L) return 0L
            return ((seconds - OFFSET_1900_TO_1970_SECONDS) * 1000L) +
                ((fraction * 1000L) / 0x1_0000_0000L)
        }

        internal fun writeTimeStamp(buffer: ByteArray, offset: Int, timeMs: Long) {
            if (timeMs <= 0L) {
                for (i in 0 until 8) buffer[offset + i] = 0
                return
            }
            var seconds = timeMs / 1000L
            val milliseconds = timeMs - seconds * 1000L
            seconds += OFFSET_1900_TO_1970_SECONDS

            var off = offset
            buffer[off++] = (seconds shr 24).toByte()
            buffer[off++] = (seconds shr 16).toByte()
            buffer[off++] = (seconds shr 8).toByte()
            buffer[off++] = seconds.toByte()

            val fraction = milliseconds * 0x1_0000_0000L / 1000L
            buffer[off++] = (fraction shr 24).toByte()
            buffer[off++] = (fraction shr 16).toByte()
            buffer[off++] = (fraction shr 8).toByte()
            buffer[off] = fraction.toByte()
        }
    }
}
