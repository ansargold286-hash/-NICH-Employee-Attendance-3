# NICH Employee Attendance 3 (Android)

Enterprise biometric employee attendance terminal built with **Kotlin**, **Jetpack Compose (Material 3)**, **CameraX + ML Kit Face Detection**, **Offline-First Local Persistence**, and **Firebase Cloud Firestore** (`attendanceapp-f6a0e`).

## Core Features

- **Strict Terminal Mode (Zero Admin/Logs/Login UI):** Launches directly into the dedicated VIP Biometric Attendance terminal with zero Google Sign-In, zero Admin DB tabs, zero Logs tabs, and zero manual enrollment screens. All existing employee directory data in local storage (`nich_offline_attendance`) remains intact and active.
- **Enforced Full-Face Biometric Scanner:** Tapping **VIP ATTENDANCE** forces open the CameraX + ML Kit scanner (`PreviewView.ImplementationMode.COMPATIBLE`, `PERFORMANCE_MODE_ACCURATE` + full landmarks, contours, and classification) to analyze eyes, nose bridge, philtrum/mustache region, mouth, and beard/jawline structure across an 8-dimensional biometric signature.
- **Conditional Fallback Only:** There is zero bypass to skip the camera. The backup employee code keypad (`147` / `369`) unlocks **only** after CameraX runs complete 2-pass processing and the facial recognition engine explicitly rejects the scan (or if hardware camera initialization fails).
- **Pre-Registered Employee Profile:**
  - **Name:** محمد انصار (Muhammad Ansar) (`NICH-0147`)
  - **Check-In Code:** `147` (forces `CHECK_IN`)
  - **Check-Out Code:** `369` (forces `CHECK_OUT`)
  - **Reference Portrait:** `app/src/main/res/drawable/emp_muhammad_ansar.jpg`
- **Automated Time-Window Rules:** Standard passcodes and face scans automatically classify morning hours (`< 12:00`) as `CHECK_IN` and `12:00+` as `CHECK_OUT`.
- **Automatic PDF Attendance Email Report:** On every verified Check-In or Check-Out (via Face Scan or conditional backup code `147`/`369`), the app generates a structured A4 PDF report and dispatches it asynchronously in the background via SMTP TLS to `Nichemployeeattendance@gmail.com` without interrupting the terminal flow. Configure `SMTP_EMAIL` and `SMTP_APP_PASSWORD` (Gmail App Password) in the **Secrets panel in AI Studio** (or `.env` locally).

## Firebase Configuration (`attendanceapp-f6a0e`)

- `app/google-services.json` is configured for Firebase project **`attendanceapp-f6a0e`** (`applicationId = "com.aistudio.nichattendance.vtxkqm"`).
- `app/src/main/res/values/firebase_applet_config.xml` specifies the named Firestore database ID.
- `firestore.rules` enforces authenticated per-operator data isolation and strict schema validation for background cloud sync.

## Building the Debug APK Locally

1. **Prerequisites:** JDK 17+, Android SDK (API 36), and **Gradle 9.3.1**.
2. **Prepare the debug signing keystore** (required by `signingConfigs.debugConfig` in `app/build.gradle.kts`). If `debug.keystore.base64` is present, decode it; otherwise generate a standard debug keystore with `keytool`:
   ```bash
   if [ -f debug.keystore.base64 ]; then
     base64 -d debug.keystore.base64 > debug.keystore
   elif [ ! -f debug.keystore ]; then
     keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   fi
   ```
3. **Run unit tests and compile the Debug APK:**
   ```bash
   gradle :app:testDebugUnitTest \
     --tests 'com.example.ExampleUnitTest' \
     --tests 'com.example.ExampleRobolectricTest' \
     --tests 'com.example.data.model.AttendanceLogicTest' \
     --tests 'com.example.data.local.OfflineAttendanceStoreTest' \
     --tests 'com.example.data.report.AttendanceReportTest' \
     --tests 'com.example.ui.viewmodel.AttendanceViewModelStrictTest'
   gradle :app:assembleDebug
   ```
4. The installable APK is generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions CI & Installing from GitHub

`.github/workflows/android-build.yml` automatically prepares `debug.keystore` (from `secrets.DEBUG_KEYSTORE_BASE64`, `debug.keystore.base64`, or `keytool`), verifies the keystore with `keytool`, restores `app/google-services.json` from `secrets.GOOGLE_SERVICES_JSON` if provided, runs `:app:lintDebug`, builds `app-debug.apk`, verifies `zipalign`, `apksigner verify`, and `aapt2 dump badging`, and publishes:

- **Direct Installable APK (Recommended on Android):** Open the repository's **Releases → `latest-apk`** section and download **`NICH-Attendance-3-v2.0-debug.apk`** directly (no `.zip` extraction needed).
- **Standard Compatible ZIP:** Releases also attach **`NICH-Attendance-3-v2.0-debug-apk.zip`** packaged with standard PKZIP deflate headers (avoiding the ZIP64 streaming headers used by GitHub Actions workflow artifact ZIPs that some Android file managers fail to extract).
- **Install Tip:** If Android reports *"App not installed as package appears to conflict with an existing package"*, uninstall any earlier build signed with a different keystore once before installing the new APK.

> **Important GitHub Push Note:** Automated GitHub exports may exclude `app/google-services.json`. The build succeeds even without it, and if you want CI-generated APKs to include live Firebase (`attendanceapp-f6a0e`) sync, either upload `app/google-services.json` into your GitHub repository or add its contents as a GitHub Repository Secret named `GOOGLE_SERVICES_JSON`.
