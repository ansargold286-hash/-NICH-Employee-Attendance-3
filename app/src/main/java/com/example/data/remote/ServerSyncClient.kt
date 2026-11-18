package com.example.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.OfflineAttendanceStore
import com.example.data.local.PendingServerEmailReport
import com.example.data.model.AttendanceLog
import com.example.data.model.Employee
import com.example.data.report.AttendanceEmailReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

interface ServerAttendanceApi {
    @Headers(
        "Content-Type: application/json",
        "Prefer: resolution=merge-duplicates,return=minimal"
    )
    @POST("rest/v1/employees")
    suspend fun upsertEmployee(
        @Query("on_conflict") onConflict: String = "id",
        @Body payload: RequestBody
    ): Response<ResponseBody>

    @Headers(
        "Content-Type: application/json",
        "Prefer: resolution=merge-duplicates,return=minimal"
    )
    @POST("rest/v1/attendance_logs")
    suspend fun upsertAttendanceLog(
        @Query("on_conflict") onConflict: String = "id",
        @Body payload: RequestBody
    ): Response<ResponseBody>

    @Headers(
        "Content-Type: application/json",
        "Prefer: resolution=merge-duplicates,return=minimal"
    )
    @POST("rest/v1/email_reports")
    suspend fun upsertEmailReport(
        @Query("on_conflict") onConflict: String = "id",
        @Body payload: RequestBody
    ): Response<ResponseBody>
}

data class ServerSyncSummary(
    val configured: Boolean,
    val syncedEmployees: Int = 0,
    val syncedLogs: Int = 0,
    val syncedEmailReports: Int = 0
)

class ServerSyncClient(
    private val serverUrlProvider: () -> String = { BuildConfig.SERVER_API_URL },
    private val publishableKeyProvider: () -> String = { BuildConfig.SUPABASE_PUBLISHABLE_KEY },
    private val secretKeyProvider: () -> String = { BuildConfig.SUPABASE_SECRET_KEY },
    private val clientBuilderOverride: (() -> OkHttpClient.Builder)? = null
) {
    private val syncMutex = Mutex()
    private var cachedEndpointSignature: String? = null
    private var cachedApi: ServerAttendanceApi? = null

    fun resolveNormalizedBaseUrl(): String? {
        val raw = serverUrlProvider().trim()
        if (raw.isEmpty() || raw.contains("YOUR_PROJECT_REF", ignoreCase = true)) {
            return null
        }
        val lower = raw.lowercase(Locale.US)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return null
        }
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    fun resolvePublishableKey(): String? {
        val raw = publishableKeyProvider().trim()
        if (raw.isEmpty() || raw.startsWith("YOUR_SUPABASE_", ignoreCase = true)) {
            return null
        }
        return raw
    }

    fun resolveSecretKey(): String? {
        val raw = secretKeyProvider().trim()
        if (raw.isEmpty() || raw.startsWith("YOUR_SUPABASE_", ignoreCase = true)) {
            return null
        }
        return raw
    }

    fun resolveActiveApiKey(): String? = resolveSecretKey() ?: resolvePublishableKey()

    fun isConfigured(): Boolean {
        return resolveNormalizedBaseUrl() != null && !resolveActiveApiKey().isNullOrBlank()
    }

    private fun getOrCreateApi(): ServerAttendanceApi? {
        val baseUrl = resolveNormalizedBaseUrl() ?: return null
        val activeKey = resolveActiveApiKey() ?: return null
        val signature = "$baseUrl|$activeKey"
        cachedApi?.let { existing ->
            if (cachedEndpointSignature == signature) return existing
        }

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val reqBuilder = original.newBuilder()
                .header("apikey", activeKey)
                .header("Accept", "application/json")
                .header("X-Client-Info", "nich-attendance-android/${BuildConfig.VERSION_NAME}")

            // Only legacy JWT tokens (header.payload.signature) belong in Authorization Bearer.
            // Modern sb_publishable_ / sb_secret_ keys authenticate strictly via the apikey header.
            if (activeKey.count { it == '.' } == 2) {
                reqBuilder.header("Authorization", "Bearer $activeKey")
            }

            chain.proceed(reqBuilder.build())
        }

        val httpClient = (clientBuilderOverride?.invoke() ?: OkHttpClient.Builder())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .build()

        val created = retrofit.create(ServerAttendanceApi::class.java)
        cachedEndpointSignature = signature
        cachedApi = created
        return created
    }

    suspend fun flushPending(
        offlineStore: OfflineAttendanceStore,
        operatorUid: String? = null
    ): ServerSyncSummary = withContext(Dispatchers.IO) {
        val api = getOrCreateApi() ?: return@withContext ServerSyncSummary(configured = false)

        syncMutex.withLock {
            var syncedEmployees = 0
            var syncedLogs = 0
            var syncedReports = 0

            val employeesById = offlineStore.employees.value.associateBy { it.id }
            val pendingEmpIds = offlineStore.pendingServerEmployeeIds.value.toList()
            for (empId in pendingEmpIds) {
                val emp = employeesById[empId]
                if (emp == null) {
                    offlineStore.markServerEmployeeSynced(empId)
                    continue
                }
                val payload = buildEmployeeJson(emp, operatorUid).toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
                val ok = runCatching {
                    val response = api.upsertEmployee(payload = payload)
                    response.isSuccessful.also { success ->
                        if (!success) {
                            Log.w(TAG, "Employee sync HTTP ${response.code()} for $empId")
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Employee sync deferred for $empId: ${err.message}")
                }.getOrDefault(false)

                if (ok) {
                    offlineStore.markServerEmployeeSynced(empId)
                    syncedEmployees++
                }
            }

            val logsById = offlineStore.logs.value.associateBy { it.id }
            val selfiePaths = offlineStore.getSelfiePathsByLogId()
            val pendingLogIds = offlineStore.pendingServerLogIds.value.toList()
            for (logId in pendingLogIds) {
                val log = logsById[logId]
                if (log == null) {
                    offlineStore.markServerLogSynced(logId)
                    continue
                }
                val meta = offlineStore.getServerLogMetadata(logId)
                val selfiePath = selfiePaths[logId]
                val encodedPhoto = encodeLivePhotoBase64(selfiePath)
                val payload = buildAttendanceLogJson(
                    log = log,
                    ntpEpochMillis = meta.ntpEpochMillis,
                    emailDispatchStatus = meta.emailDispatchStatus,
                    livePhotoBase64 = encodedPhoto,
                    operatorUid = operatorUid
                ).toString().toRequestBody(JSON_MEDIA_TYPE)

                val ok = runCatching {
                    val response = api.upsertAttendanceLog(payload = payload)
                    response.isSuccessful.also { success ->
                        if (!success) {
                            Log.w(TAG, "Attendance log sync HTTP ${response.code()} for $logId")
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Attendance log sync deferred for $logId: ${err.message}")
                }.getOrDefault(false)

                if (ok) {
                    offlineStore.markServerLogSynced(logId)
                    syncedLogs++
                }
            }

            val pendingReports = offlineStore.getPendingServerEmailReports()
            for (report in pendingReports) {
                val payload = buildEmailReportJson(report, operatorUid).toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
                val ok = runCatching {
                    val response = api.upsertEmailReport(payload = payload)
                    response.isSuccessful.also { success ->
                        if (!success) {
                            Log.w(TAG, "Email report sync HTTP ${response.code()} for ${report.reportId}")
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Email report sync deferred for ${report.reportId}: ${err.message}")
                }.getOrDefault(false)

                if (ok) {
                    offlineStore.markServerEmailReportSynced(report.reportId)
                    syncedReports++
                }
            }

            ServerSyncSummary(
                configured = true,
                syncedEmployees = syncedEmployees,
                syncedLogs = syncedLogs,
                syncedEmailReports = syncedReports
            )
        }
    }

    internal fun buildEmployeeJson(emp: Employee, operatorUid: String?): JSONObject {
        return JSONObject().apply {
            put("id", emp.id)
            put("employee_code", emp.employeeCode)
            put("name", emp.name)
            put("department", emp.department)
            put("role_title", emp.roleTitle)
            put("passcode", emp.passcode)
            put("check_in_code", emp.checkInCode)
            put("check_out_code", emp.checkOutCode)
            put("avatar_key", emp.avatarKey)
            put("face_signature", emp.faceSignature)
            put("is_active", emp.isActive)
            if (!operatorUid.isNullOrBlank()) {
                put("device_operator_id", operatorUid)
            }
            put("updated_at", formatIsoUtc(System.currentTimeMillis()))
        }
    }

    internal fun buildAttendanceLogJson(
        log: AttendanceLog,
        ntpEpochMillis: Long,
        emailDispatchStatus: String,
        livePhotoBase64: String,
        operatorUid: String?
    ): JSONObject {
        val effectiveEpoch = if (ntpEpochMillis > 0L) {
            ntpEpochMillis
        } else {
            (log.createdAt?.seconds ?: (System.currentTimeMillis() / 1000L)) * 1000L
        }
        return JSONObject().apply {
            put("id", log.id)
            put("employee_id", log.employeeId)
            put("employee_code", log.employeeCode)
            put("employee_name", log.employeeName)
            put("department", log.department)
            put("avatar_key", log.avatarKey)
            put("event_type", log.eventType)
            put("verification_method", log.verificationMethod)
            put("confidence_score", log.confidenceScore)
            put("logged_time_label", log.loggedTimeLabel)
            put("logged_date_key", log.loggedDateKey)
            put("gps_coordinates", log.gpsCoordinates)
            put("location_address", log.locationAddress)
            put("time_source", log.timeSource)
            put("ntp_epoch_millis", effectiveEpoch)
            put("ntp_timestamp_iso", formatIsoUtc(effectiveEpoch))
            put("live_photo_base64", livePhotoBase64)
            put("email_dispatch_status", emailDispatchStatus.ifBlank {
                if (log.eventType == "CHECK_IN") "NOT_APPLICABLE" else "PENDING"
            })
            put("email_recipient", AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL)
            if (!operatorUid.isNullOrBlank()) {
                put("device_operator_id", operatorUid)
            }
            put("synced_at", formatIsoUtc(System.currentTimeMillis()))
        }
    }

    internal fun buildEmailReportJson(
        report: PendingServerEmailReport,
        operatorUid: String?
    ): JSONObject {
        return JSONObject().apply {
            put("id", report.reportId)
            put("attendance_log_id", report.logId)
            put("employee_id", report.employeeId)
            put("employee_code", report.employeeCode)
            put("employee_name", report.employeeName)
            put("department", report.department)
            put("event_type", report.eventType)
            put("recipient_email", report.recipientEmail)
            put("dispatch_status", report.dispatchStatus)
            put("pdf_file_name", report.pdfFileName)
            put("gps_coordinates", report.gpsCoordinates)
            put("location_address", report.locationAddress)
            put("time_source", report.timeSource)
            put("ntp_epoch_millis", report.ntpEpochMillis)
            put("dispatched_at", formatIsoUtc(report.dispatchedAtEpochMs))
            if (!operatorUid.isNullOrBlank()) {
                put("device_operator_id", operatorUid)
            }
        }
    }

    internal fun encodeLivePhotoBase64(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return ""

        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val bytes = if (bitmap != null) {
                val maxSide = 480
                val width = bitmap.width.coerceAtLeast(1)
                val height = bitmap.height.coerceAtLeast(1)
                val scaled = if (width > maxSide || height > maxSide) {
                    val ratio = maxSide.toFloat() / maxOf(width, height).toFloat()
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (width * ratio).toInt().coerceAtLeast(1),
                        (height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                val compressed = ByteArrayOutputStream().use { baos ->
                    val ok = scaled.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    if (ok) baos.toByteArray() else ByteArray(0)
                }
                if (compressed.isNotEmpty()) {
                    compressed
                } else {
                    file.readBytes().take(MAX_RAW_IMAGE_BYTES).toByteArray()
                }
            } else {
                file.readBytes().take(MAX_RAW_IMAGE_BYTES).toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun formatIsoUtc(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    companion object {
        private const val TAG = "ServerSyncClient"
        private const val MAX_RAW_IMAGE_BYTES = 180_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
