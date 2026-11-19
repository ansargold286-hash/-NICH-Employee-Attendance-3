package com.example.data.update

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.AppUpdateDialog
import com.example.ui.components.AppUpdateUiState
import com.example.ui.theme.NichAttendanceTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class GithubUpdateCheckerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @org.junit.Before
    fun clearFileProviderCache() {
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @Test
    fun versionUpdateParser_parsesMetadataAndComparesVersionCodesCorrectly() {
        val raw = """
            {
              "latestVersionCode": 5,
              "latestVersionName": "2.4",
              "apkUrl": "https://github.com/nich-org/attendance/releases/download/latest-apk/NICH-Attendance-3-v2.4-debug.apk",
              "releaseNotes": "Faster CNIC OCR and batch PDF fixes"
            }
        """.trimIndent()

        val meta = VersionUpdateParser.parseVersionJson(raw)
        assertNotNull(meta)
        assertEquals(5, meta?.latestVersionCode)
        assertEquals("2.4", meta?.latestVersionName)
        assertTrue(VersionUpdateParser.isUpdateAvailable(meta, currentVersionCode = 2))
        assertFalse(VersionUpdateParser.isUpdateAvailable(meta, currentVersionCode = 5))
        assertFalse(VersionUpdateParser.isUpdateAvailable(meta, currentVersionCode = 7))

        assertNull(VersionUpdateParser.parseVersionJson("""{"latestVersionCode": 0, "apkUrl": "invalid"}"""))
        assertFalse(VersionUpdateParser.isConfiguredVersionJsonUrl("https://github.com/OWNER/REPO/releases/download/latest-apk/version.json"))
        assertTrue(VersionUpdateParser.isConfiguredVersionJsonUrl("https://github.com/acme/nich/releases/download/latest-apk/version.json"))
        assertTrue(GithubUpdateManager(ApplicationProvider.getApplicationContext()).isConfigured())
    }

    @Test
    fun githubUpdateManager_checksVersionJsonAndDownloadsApkWithProgressAndFileProviderIntent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeApkPayload = ("PK\u0003\u0004" + "MOCK_APK_BYTES_".repeat(200)).toByteArray(StandardCharsets.US_ASCII)

        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        val versionJson = """
            {
              "latestVersionCode": 9,
              "latestVersionName": "3.1",
              "apkUrl": "$baseUrl/NICH-Attendance-3-v3.1-debug.apk",
              "releaseNotes": "Build v3.1 release"
            }
        """.trimIndent()

        val serverThread = Thread {
            serverSocket.use { srv ->
                repeat(2) {
                    val client = runCatching { srv.accept() }.getOrNull() ?: return@repeat
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val headerLine = reader.readLine()
                            if (headerLine.isNullOrEmpty()) break
                        }
                        val out = sock.getOutputStream()
                        if (requestLine.contains("version.json")) {
                            val bytes = versionJson.toByteArray(StandardCharsets.UTF_8)
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(StandardCharsets.US_ASCII)
                            )
                            out.write(bytes)
                        } else {
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/vnd.android.package-archive\r\n" +
                                        "Content-Length: ${fakeApkPayload.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(StandardCharsets.US_ASCII)
                            )
                            out.write(fakeApkPayload)
                        }
                        out.flush()
                    }
                }
            }
        }
        serverThread.start()

        try {
            val manager = GithubUpdateManager(
                context = context,
                versionJsonUrlProvider = { "$baseUrl/version.json" },
                currentVersionCodeProvider = { 2 }
            )
            val available = manager.checkForUpdate()
            assertNotNull(available)
            assertEquals(9, available?.latestVersionCode)
            assertEquals("3.1", available?.latestVersionName)

            val progressUpdates = mutableListOf<Int>()
            val downloadResult = manager.downloadApk(
                apkUrl = available!!.apkUrl,
                versionName = available.latestVersionName
            ) { percent, _, _ ->
                progressUpdates += percent
            }

            assertTrue(downloadResult.isSuccess)
            val downloadedFile: File = downloadResult.getOrThrow()
            try {
                assertTrue(downloadedFile.exists())
                assertEquals(fakeApkPayload.size.toLong(), downloadedFile.length())
                assertTrue(progressUpdates.any { it == 100 })

                val installIntent = manager.buildInstallApkIntent(downloadedFile)
                assertEquals(Intent.ACTION_VIEW, installIntent.action)
                assertEquals(GithubUpdateManager.APK_MIME_TYPE, installIntent.type)
                assertTrue((installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                assertEquals("content", installIntent.data?.scheme)
                assertTrue(installIntent.data.toString().contains("fileprovider"))
            } finally {
                downloadedFile.delete()
            }
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun appUpdateDialog_displaysBilingualPromptAndDownloadUpdateButton() {
        val clicks = AtomicInteger(0)
        val meta = VersionMetadata(
            latestVersionCode = 5,
            latestVersionName = "2.5",
            apkUrl = "https://github.com/acme/repo/releases/download/latest-apk/app.apk",
            releaseNotes = "Accurate CNIC name & live-photo-only verification card."
        )

        composeRule.setContent {
            NichAttendanceTheme {
                AppUpdateDialog(
                    state = AppUpdateUiState.Available(meta),
                    onDownloadUpdate = { clicks.incrementAndGet() },
                    onRequestInstallPermission = {},
                    onInstallDownloadedApk = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("app_update_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("نئی اپڈیٹ آ گئی ہے! براہ کرم ایپ اپڈیٹ کریں (New update available — please update the app)")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("download_update_button").assertIsDisplayed()
        composeRule.onNodeWithText("Download Update").assertIsDisplayed()
        composeRule.onNodeWithTag("download_update_button").performClick()
        assertEquals(1, clicks.get())
    }
}
