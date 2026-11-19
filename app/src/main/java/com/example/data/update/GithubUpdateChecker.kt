package com.example.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class VersionMetadata(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val releaseNotes: String = ""
)

object VersionUpdateParser {

    fun isConfiguredVersionJsonUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return false
        val validScheme = url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
        if (!validScheme) return false
        return !url.contains("OWNER/REPO", ignoreCase = true) &&
            !url.contains("YOUR_", ignoreCase = true)
    }

    fun parseVersionJson(rawJson: String): VersionMetadata? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val versionCode = obj.optInt("latestVersionCode", -1)
            val versionName = obj.optString("latestVersionName", "").trim()
            val apkUrl = obj.optString("apkUrl", "").trim()
            val releaseNotes = obj.optString("releaseNotes", "").trim()
            val validUrl = apkUrl.startsWith("https://", ignoreCase = true) ||
                apkUrl.startsWith("http://", ignoreCase = true)
            if (versionCode <= 0 || !validUrl) {
                null
            } else {
                VersionMetadata(
                    latestVersionCode = versionCode,
                    latestVersionName = versionName.ifBlank { versionCode.toString() },
                    apkUrl = apkUrl,
                    releaseNotes = releaseNotes
                )
            }
        }.getOrNull()
    }

    fun isUpdateAvailable(metadata: VersionMetadata?, currentVersionCode: Int): Boolean {
        if (metadata == null) return false
        return metadata.latestVersionCode > currentVersionCode &&
            (metadata.apkUrl.startsWith("https://", ignoreCase = true) ||
                metadata.apkUrl.startsWith("http://", ignoreCase = true))
    }
}

class GithubUpdateManager(
    context: Context,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val versionJsonUrlProvider: () -> String = { DEFAULT_VERSION_JSON_URL },
    private val currentVersionCodeProvider: () -> Int = { BuildConfig.VERSION_CODE }
) {
    private val appContext = context.applicationContext

    fun isConfigured(): Boolean =
        VersionUpdateParser.isConfiguredVersionJsonUrl(versionJsonUrlProvider())

    suspend fun checkForUpdate(): VersionMetadata? = withContext(Dispatchers.IO) {
        val url = versionJsonUrlProvider().trim()
        if (!VersionUpdateParser.isConfiguredVersionJsonUrl(url)) {
            return@withContext null
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Update metadata check returned HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val parsed = VersionUpdateParser.parseVersionJson(body) ?: return@withContext null
                if (VersionUpdateParser.isUpdateAvailable(parsed, currentVersionCodeProvider())) {
                    parsed
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check skipped offline: ${e.message}")
            null
        }
    }

    suspend fun downloadApk(
        apkUrl: String,
        versionName: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = apkUrl.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
            !cleanUrl.startsWith("http://", ignoreCase = true)
        ) {
            return@withContext Result.failure(IllegalArgumentException("Invalid APK download URL"))
        }

        val updatesDir = File(appContext.cacheDir, APK_UPDATES_DIR).apply {
            if (!exists()) mkdirs()
        }
        updatesDir.listFiles()?.forEach { oldFile ->
            runCatching { oldFile.delete() }
        }

        val safeVersion = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "latest" }
        val finalFile = File(updatesDir, "NICH-Attendance-3-v$safeVersion.apk")
        val tempFile = File(updatesDir, "NICH-Attendance-3-v$safeVersion.apk.part")

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed (HTTP ${response.code})")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("Empty APK download response")
                )
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastReportedPercent = -1

                onProgress(0, 0L, totalBytes)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val percent = if (totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (percent != lastReportedPercent || totalBytes <= 0L) {
                                lastReportedPercent = percent
                                onProgress(percent.coerceAtLeast(0), downloadedBytes, totalBytes)
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (!tempFile.exists() || tempFile.length() <= 0L) {
                    runCatching { tempFile.delete() }
                    return@withContext Result.failure(
                        IllegalStateException("Downloaded APK file is empty")
                    )
                }
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(100, finalFile.length(), finalFile.length())
                Result.success(finalFile)
            }
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()
    }

    fun buildUnknownAppSourcesIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private const val TAG = "GithubUpdateManager"
        const val DEFAULT_VERSION_JSON_URL =
            "https://github.com/nich-attendance/nich-attendance-3/releases/download/latest-apk/version.json"
        const val APK_UPDATES_DIR = "apk_updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
