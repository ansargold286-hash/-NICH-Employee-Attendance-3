package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.local.OfflineAttendanceStore
import com.example.data.model.FaceBiometricMatcher
import com.example.data.report.AttendanceEmailReporter
import com.example.data.repository.AttendanceRepository
import com.example.ui.screens.EmployeeRegistrationScreen
import com.example.ui.screens.TerminalDashboardScreen
import com.example.ui.theme.NichAttendanceTheme
import com.example.ui.theme.ObsidianVoid
import com.example.ui.viewmodel.AttendanceViewModel
import com.example.ui.viewmodel.DataUiState
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NichAttendanceTheme {
                AppNavigation()
            }
        }
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val firebaseAuth: FirebaseAuth? = remember(context) {
        val ready = FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
        if (ready) Firebase.auth else null
    }

    if (firebaseAuth != null) {
        val currentUser by firebaseAuth.authStateFlow()
            .collectAsStateWithLifecycle(initialValue = firebaseAuth.currentUser)
        val user = currentUser

        key(user?.uid ?: "offline") {
            StrictBiometricTerminalShell(currentUserId = user?.uid)
        }
    } else {
        key("offline_no_firebase") {
            StrictBiometricTerminalShell(currentUserId = null)
        }
    }
}

@Composable
private fun StrictBiometricTerminalShell(
    currentUserId: String?
) {
    val viewModel: AttendanceViewModel = viewModel(
        key = currentUserId ?: "offline",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val offlineStore = OfflineAttendanceStore(app)
                val emailReporter = AttendanceEmailReporter(app)
                val cloudRepository = if (!currentUserId.isNullOrBlank()) {
                    val databaseId = app.getString(R.string.firestore_database_id)
                    val db = FirebaseFirestore.getInstance(databaseId)
                    AttendanceRepository(db)
                } else {
                    null
                }
                AttendanceViewModel(
                    offlineStore = offlineStore,
                    repository = cloudRepository,
                    currentUserId = currentUserId,
                    emailReporter = emailReporter
                )
            }
        }
    )

    val context = LocalContext.current
    val terminalPhase by viewModel.terminalPhase.collectAsStateWithLifecycle()
    val showRegistration by viewModel.showRegistrationScreen.collectAsStateWithLifecycle()
    val employeesUi by viewModel.employeesState.collectAsStateWithLifecycle()
    val employeesList = (employeesUi as? DataUiState.Success)?.data.orEmpty()
    val enrolledFacesCount = remember(employeesList) {
        employeesList.count { FaceBiometricMatcher.parseSignature(it.faceSignature) != null }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showRegistration) {
                EmployeeRegistrationScreen(
                    savedEmployees = employeesList,
                    onRegisterBatch = viewModel::registerEmployeesBatch,
                    onDeleteEmployee = viewModel::deleteRegisteredEmployee,
                    onNavigateToScanner = viewModel::closeRegistrationScreen
                )
            } else {
                TerminalDashboardScreen(
                    terminalPhase = terminalPhase,
                    onStartVipScan = viewModel::startVipBiometricScan,
                    onCameraInitialized = viewModel::onCameraInitialized,
                    onCameraInitFailed = viewModel::onCameraInitFailed,
                    onFrameAnalyzed = viewModel::onLiveFaceFrameAnalyzed,
                    onSendCheckOutEmail = {
                        viewModel.sendEmailAndFinish { intent ->
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    onResetTerminal = viewModel::resetToReady,
                    enrolledFacesCount = enrolledFacesCount,
                    onOpenRegistration = viewModel::openRegistrationScreen
                )
            }
        }
    }
}
