import { startScannerOverlayLoop } from './scanner-overlay.js';
import {
  AttendanceTimeRules,
  createBrowserFaceAnalyzer,
  createWebAttendanceStore,
  evaluateFaceSignature
} from './biometric-matcher.js';
import { createFirebaseSyncClient } from './firebase-adapter.js';
import { dispatchCheckOutEmailAndPdf } from './pdf-report.js';

const VERIFIED_CHECK_IN_RESET_MS = 2500;
const SAME_EMPLOYEE_COOLDOWN_MS = 3000;
const FRAME_ANALYSIS_INTERVAL_MS = 240;
const EMAIL_ENFORCEMENT_INTERVAL_MS = 6000;
const MANDATORY_EMAIL_ENFORCEMENT_PROMPT =
  'Please send the email! Without sending the email, your attendance and record will not be saved.';

const els = {
  video: document.getElementById('cameraVideo'),
  captureCanvas: document.getElementById('captureCanvas'),
  overlayCanvas: document.getElementById('scannerOverlayCanvas'),
  badgeBtn: document.getElementById('terminalBadgeButton'),
  cloudSyncDot: document.getElementById('cloudSyncDot'),
  verifiedOverlay: document.getElementById('verifiedOverlay'),
  verifiedCard: document.getElementById('verifiedCard'),
  closeCheckOutOverlayBtn: document.getElementById('closeCheckOutOverlayBtn'),
  verifiedSelfiePreview: document.getElementById('verifiedSelfiePreview'),
  verifiedEmployeeName: document.getElementById('verifiedEmployeeName'),
  verifiedMeta: document.getElementById('verifiedMeta'),
  checkOutActions: document.getElementById('checkOutActions'),
  emailEnforcementBanner: document.getElementById('emailEnforcementBanner'),
  sendEmailFinishBtn: document.getElementById('sendEmailFinishBtn'),
  sendEmailBtnLabel: document.getElementById('sendEmailBtnLabel'),
  cameraFailureOverlay: document.getElementById('cameraFailureOverlay'),
  failureTitle: document.getElementById('failureTitle'),
  failureMessage: document.getElementById('failureMessage'),
  retryCameraBtn: document.getElementById('retryCameraBtn'),
  operatorDrawer: document.getElementById('operatorDrawer'),
  closeOperatorDrawerBtn: document.getElementById('closeOperatorDrawerBtn'),
  operatorAuthStatus: document.getElementById('operatorAuthStatus'),
  windowStatusValue: document.getElementById('windowStatusValue'),
  queuedSyncCount: document.getElementById('queuedSyncCount'),
  googleSignInBtn: document.getElementById('googleSignInBtn'),
  googleSignOutBtn: document.getElementById('googleSignOutBtn'),
  recentLogsBadge: document.getElementById('recentLogsBadge'),
  recentLogsList: document.getElementById('recentLogsList')
};

const store = createWebAttendanceStore();
const firebaseClient = createFirebaseSyncClient();
const faceAnalyzer = createBrowserFaceAnalyzer();
const overlayController = startScannerOverlayLoop(els.overlayCanvas);

const state = {
  phase: 'SCANNING', // 'SCANNING' | 'VERIFIED' | 'CAMERA_ERROR'
  activeStream: null,
  analysisTimer: null,
  resetTimer: null,
  emailReminderTimer: null,
  analysisInFlight: false,
  drawerOpen: false,
  lastVerifiedEmployeeId: null,
  lastVerifiedAtMs: 0,
  currentVerifiedPayload: null,
  checkOutEmailCompleted: true,
  lastSpokenAtMs: 0,
  currentUser: null
};

function speakWebVoicePrompt(text, flush = false) {
  if (typeof window === 'undefined' || !('speechSynthesis' in window) || !text) return;
  try {
    if (flush) window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'en-US';
    utterance.rate = 1.0;
    utterance.pitch = 1.0;
    window.speechSynthesis.speak(utterance);
  } catch {
    // Ignore autoplay restrictions gracefully
  }
}

async function startCameraFeed() {
  hideCameraFailure();
  stopCameraFeed();

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    showCameraFailure(
      'Camera API Unavailable',
      'Open this page over HTTPS (such as GitHub Pages) in a modern browser to enable biometric scanning.'
    );
    return;
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'user' },
        width: { ideal: 1280 },
        height: { ideal: 720 }
      },
      audio: false
    });
    state.activeStream = stream;
    els.video.srcObject = stream;
    await els.video.play();
    state.phase = 'SCANNING';
    speakWebVoicePrompt('Welcome to NICH Attendance 3. Please look straight into the camera.', true);
    startFrameAnalysisLoop();
  } catch (err) {
    const message =
      err && err.name === 'NotAllowedError'
        ? 'Camera permission was denied. Allow browser camera access and tap Allow Camera to continue.'
        : `Could not initialize device camera (${err?.message || 'Permission or hardware error'}).`;
    showCameraFailure('Camera Access Required', message);
  }
}

function stopCameraFeed() {
  if (state.analysisTimer) {
    clearInterval(state.analysisTimer);
    state.analysisTimer = null;
  }
  if (state.activeStream) {
    for (const track of state.activeStream.getTracks()) {
      track.stop();
    }
    state.activeStream = null;
  }
}

function startFrameAnalysisLoop() {
  if (state.analysisTimer) clearInterval(state.analysisTimer);
  state.analysisTimer = setInterval(runSingleFrameCheck, FRAME_ANALYSIS_INTERVAL_MS);
}

async function runSingleFrameCheck() {
  if (state.phase !== 'SCANNING' || state.drawerOpen || state.analysisInFlight) {
    return;
  }
  state.analysisInFlight = true;
  try {
    const analysis = await faceAnalyzer.analyzeVideoFrame(els.video);
    overlayController.setFaceDetected(analysis.faceDetected);

    if (!analysis.faceDetected || !analysis.signature) {
      const nowMs = Date.now();
      if (nowMs - state.lastSpokenAtMs > 9000) {
        state.lastSpokenAtMs = nowMs;
        speakWebVoicePrompt('Please align your face inside the blue scanner frame.');
      }
      return;
    }

    const evaluation = evaluateFaceSignature(analysis.signature, store.getEmployees());
    const match = evaluation.match;
    if (!match) return;

    const nowMs = Date.now();
    if (
      match.employee.id === state.lastVerifiedEmployeeId &&
      nowMs - state.lastVerifiedAtMs < SAME_EMPLOYEE_COOLDOWN_MS
    ) {
      return;
    }

    const selfieDataUrl = captureMirroredSelfieJpeg();
    await commitVerifiedAttendance(match.employee, match.confidencePercent, selfieDataUrl);
  } finally {
    state.analysisInFlight = false;
  }
}

function captureMirroredSelfieJpeg() {
  const vw = els.video.videoWidth || 640;
  const vh = els.video.videoHeight || 480;
  const targetW = 480;
  const targetH = Math.round((targetW * vh) / Math.max(1, vw));
  els.captureCanvas.width = targetW;
  els.captureCanvas.height = targetH;

  const ctx = els.captureCanvas.getContext('2d');
  ctx.save();
  ctx.scale(-1, 1);
  ctx.drawImage(els.video, -targetW, 0, targetW, targetH);
  ctx.restore();
  return els.captureCanvas.toDataURL('image/jpeg', 0.88);
}

async function commitVerifiedAttendance(employee, confidenceScore, selfieDataUrl) {
  clearTimeout(state.resetTimer);
  state.phase = 'VERIFIED';
  state.lastVerifiedEmployeeId = employee.id;
  state.lastVerifiedAtMs = Date.now();

  const now = new Date();
  const eventType = AttendanceTimeRules.determineEventType(now.getHours());
  const loggedTimeLabel = AttendanceTimeRules.formatTimeLabel(now);
  const loggedDateKey = AttendanceTimeRules.formatDateKey(now);

  const logEntry = store.recordAttendance({
    employee,
    eventType,
    verificationMethod: 'FACE_SCAN_1',
    confidenceScore,
    loggedTimeLabel,
    loggedDateKey,
    selfieDataUrl,
    synced: false
  });

  state.currentVerifiedPayload = { employee, logEntry, selfieDataUrl };
  state.checkOutEmailCompleted = eventType !== 'CHECK_OUT';
  renderVerifiedOverlay(employee, logEntry, selfieDataUrl);
  refreshOperatorDrawer();

  speakWebVoicePrompt(
    eventType === 'CHECK_OUT'
      ? `Check-out verified for ${employee.name}. Please send the email to save and finish your record.`
      : `Welcome, ${employee.name}. Check-in recorded successfully.`,
    true
  );

  // Sync to Cloud Firestore in background if signed in
  const uid = state.currentUser?.uid;
  if (uid) {
    firebaseClient
      .writeAttendanceLog(uid, employee, logEntry)
      .then((ok) => {
        if (ok) {
          store.markLogSynced(logEntry.id);
          if (state.currentVerifiedPayload?.logEntry?.id === logEntry.id) {
            state.currentVerifiedPayload.logEntry = { ...logEntry, syncedToCloud: true };
          }
          refreshOperatorDrawer();
        }
      })
      .catch((err) => {
        console.warn('Background Firestore log sync deferred:', err);
      });
  }

  // Check-In: NO email button, auto-return to live scanning after 2.5s
  // Check-Out: KEEP overlay strictly locked with Send Email & Finish + repeating enforcement voice
  if (eventType === 'CHECK_IN') {
    state.resetTimer = setTimeout(resetToScanning, VERIFIED_CHECK_IN_RESET_MS);
  } else {
    startWebCheckOutEnforcementLoop();
  }
}

function triggerCheckOutEnforcementWarning() {
  if (state.phase !== 'VERIFIED' || state.checkOutEmailCompleted) return;
  els.verifiedCard.classList.add('is-enforcement-alert');
  els.emailEnforcementBanner.classList.remove('hidden');
  speakWebVoicePrompt(MANDATORY_EMAIL_ENFORCEMENT_PROMPT, true);
}

function startWebCheckOutEnforcementLoop() {
  clearInterval(state.emailReminderTimer);
  state.emailReminderTimer = setInterval(() => {
    if (state.phase !== 'VERIFIED' || state.checkOutEmailCompleted) {
      clearInterval(state.emailReminderTimer);
      state.emailReminderTimer = null;
      return;
    }
    triggerCheckOutEnforcementWarning();
  }, EMAIL_ENFORCEMENT_INTERVAL_MS);
}

function renderVerifiedOverlay(employee, logEntry, selfieDataUrl) {
  const isCheckOut = logEntry.eventType === 'CHECK_OUT';
  els.verifiedCard.classList.toggle('is-checkout', isCheckOut);
  els.verifiedCard.classList.remove('is-enforcement-alert');
  els.closeCheckOutOverlayBtn.classList.toggle('hidden', !isCheckOut);
  els.emailEnforcementBanner.classList.add('hidden');
  els.verifiedEmployeeName.textContent = employee.name;
  els.verifiedMeta.textContent = `${AttendanceTimeRules.displayLabel(logEntry.eventType)} • ${logEntry.loggedTimeLabel}`;

  if (selfieDataUrl) {
    els.verifiedSelfiePreview.src = selfieDataUrl;
  } else if (employee.avatarKey === 'avatar_ansar') {
    els.verifiedSelfiePreview.src = './assets/emp_muhammad_ansar.jpg';
  } else {
    els.verifiedSelfiePreview.src = './assets/nich_official_logo.png';
  }

  els.checkOutActions.classList.toggle('hidden', !isCheckOut);
  els.sendEmailFinishBtn.disabled = false;
  els.sendEmailBtnLabel.textContent = 'Send Email & Finish';

  els.verifiedOverlay.classList.remove('hidden');
}

function resetToScanning() {
  if (state.phase === 'VERIFIED' && !state.checkOutEmailCompleted) {
    triggerCheckOutEnforcementWarning();
    return;
  }
  clearTimeout(state.resetTimer);
  clearInterval(state.emailReminderTimer);
  state.emailReminderTimer = null;
  state.currentVerifiedPayload = null;
  state.checkOutEmailCompleted = true;
  els.verifiedOverlay.classList.add('hidden');
  state.phase = state.activeStream ? 'SCANNING' : 'CAMERA_ERROR';
}

function showCameraFailure(title, message) {
  state.phase = 'CAMERA_ERROR';
  els.failureTitle.textContent = title;
  els.failureMessage.textContent = message;
  els.cameraFailureOverlay.classList.remove('hidden');
}

function hideCameraFailure() {
  els.cameraFailureOverlay.classList.add('hidden');
}

function refreshOperatorDrawer() {
  const user = state.currentUser;
  els.cloudSyncDot.classList.toggle('online', Boolean(user));
  els.cloudSyncDot.classList.toggle('offline', !user);

  els.operatorAuthStatus.textContent = user
    ? user.email || user.displayName || 'Connected'
    : 'Offline Storage Mode';

  els.googleSignInBtn.classList.toggle('hidden', Boolean(user));
  els.googleSignOutBtn.classList.toggle('hidden', !user);

  els.windowStatusValue.textContent = AttendanceTimeRules.windowDescription(new Date().getHours());
  const pendingCount = store.getPendingLogIds().length;
  els.queuedSyncCount.textContent = `${pendingCount} pending`;

  const logs = store.getLogs();
  els.recentLogsBadge.textContent = String(logs.length);
  els.recentLogsList.replaceChildren();

  if (logs.length === 0) {
    const emptyLi = document.createElement('li');
    emptyLi.className = 'recent-log-item';
    emptyLi.innerHTML =
      '<div><div class="recent-log-name">No logs recorded yet</div><div class="recent-log-sub">Position face inside scanner frame</div></div>';
    els.recentLogsList.appendChild(emptyLi);
    return;
  }

  for (const log of logs.slice(0, 12)) {
    const li = document.createElement('li');
    li.className = 'recent-log-item';

    const info = document.createElement('div');
    const nameEl = document.createElement('div');
    nameEl.className = 'recent-log-name';
    nameEl.textContent = log.employeeName;

    const subEl = document.createElement('div');
    subEl.className = 'recent-log-sub';
    subEl.textContent = `${log.loggedTimeLabel} • ${log.loggedDateKey} • ${
      log.syncedToCloud ? 'Cloud' : 'Local'
    }`;
    info.append(nameEl, subEl);

    const tag = document.createElement('span');
    const isOut = log.eventType === 'CHECK_OUT';
    tag.className = `recent-log-tag ${isOut ? 'checkout' : 'checkin'}`;
    tag.textContent = isOut ? 'OUT' : 'IN';

    li.append(info, tag);
    els.recentLogsList.appendChild(li);
  }
}

async function flushPendingCloudLogs(uid) {
  const pendingIds = store.getPendingLogIds();
  if (!uid || pendingIds.length === 0) return;

  const employeesById = new Map(store.getEmployees().map((e) => [e.id, e]));
  const logsById = new Map(store.getLogs().map((l) => [l.id, l]));

  for (const logId of pendingIds) {
    const log = logsById.get(logId);
    if (!log) {
      store.markLogSynced(logId);
      continue;
    }
    const emp = employeesById.get(log.employeeId) || {
      id: log.employeeId,
      employeeCode: log.employeeCode,
      name: log.employeeName,
      department: log.department,
      roleTitle: 'Authorized Staff',
      passcode: '147',
      avatarKey: log.avatarKey
    };
    try {
      const ok = await firebaseClient.writeAttendanceLog(uid, emp, log);
      if (ok) store.markLogSynced(logId);
    } catch {
      // Keep queued for next sync cycle
    }
  }
  refreshOperatorDrawer();
}

// Event bindings
els.closeCheckOutOverlayBtn.addEventListener('click', triggerCheckOutEnforcementWarning);

els.verifiedOverlay.addEventListener('click', (e) => {
  if (e.target === els.verifiedOverlay && !state.checkOutEmailCompleted) {
    triggerCheckOutEnforcementWarning();
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && state.phase === 'VERIFIED' && !state.checkOutEmailCompleted) {
    e.preventDefault();
    triggerCheckOutEnforcementWarning();
  }
});

els.sendEmailFinishBtn.addEventListener('click', async () => {
  const payload = state.currentVerifiedPayload;
  if (!payload) {
    state.checkOutEmailCompleted = true;
    resetToScanning();
    return;
  }
  els.sendEmailFinishBtn.disabled = true;
  els.sendEmailBtnLabel.textContent = 'Sending Email & Finishing...';
  try {
    await dispatchCheckOutEmailAndPdf({
      employee: payload.employee,
      logEntry: payload.logEntry,
      selfieDataUrl: payload.selfieDataUrl,
      operatorEmail: state.currentUser?.email || ''
    });
    state.checkOutEmailCompleted = true;
    speakWebVoicePrompt('Check-out email and report sent successfully. Attendance saved.', true);
    resetToScanning();
  } catch {
    els.sendEmailFinishBtn.disabled = false;
    els.sendEmailBtnLabel.textContent = 'Send Email & Finish';
    triggerCheckOutEnforcementWarning();
  }
});

els.retryCameraBtn.addEventListener('click', startCameraFeed);

els.badgeBtn.addEventListener('click', () => {
  state.drawerOpen = true;
  refreshOperatorDrawer();
  els.operatorDrawer.classList.remove('hidden');
});

els.closeOperatorDrawerBtn.addEventListener('click', () => {
  state.drawerOpen = false;
  els.operatorDrawer.classList.add('hidden');
});

els.operatorDrawer.addEventListener('click', (e) => {
  if (e.target === els.operatorDrawer) {
    state.drawerOpen = false;
    els.operatorDrawer.classList.add('hidden');
  }
});

els.googleSignInBtn.addEventListener('click', async () => {
  try {
    await firebaseClient.signInWithGoogle();
  } catch (err) {
    els.operatorAuthStatus.textContent = err?.message || 'Sign-in cancelled';
  }
});

els.googleSignOutBtn.addEventListener('click', async () => {
  await firebaseClient.signOutUser();
});

// Wire Firebase Auth state changes seamlessly without obstructing scanner startup
firebaseClient.onAuthChanged((user) => {
  state.currentUser = user;
  if (user) {
    firebaseClient.startRealtimeSync(user.uid, store.getEmployees(), {
      onEmployees(remoteList) {
        store.mergeRemoteEmployees(remoteList);
      },
      onLogs(remoteLogs) {
        store.mergeRemoteLogs(remoteLogs);
        refreshOperatorDrawer();
      },
      onError(err) {
        console.warn('Firestore sync notice:', err);
      }
    });
    flushPendingCloudLogs(user.uid);
  } else {
    firebaseClient.stopRealtimeSync();
  }
  refreshOperatorDrawer();
});

window.addEventListener('pagehide', stopCameraFeed);

// Immediately launch live camera feed on page boot matching Android standalone terminal
refreshOperatorDrawer();
startCameraFeed();
