/**
 * Firebase Web SDK v11 adapter aligned with Android project `attendanceapp-f6a0e`
 * and named Firestore database `ai-studio-android-nichatte-7f6d200a-1161-49bd-b79a-93905545b7f7`.
 */

import { initializeApp } from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js';
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signOut
} from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js';
import {
  collection,
  doc,
  getDoc,
  getDocs,
  initializeFirestore,
  limit,
  onSnapshot,
  orderBy,
  persistentLocalCache,
  persistentMultipleTabManager,
  query,
  serverTimestamp,
  setDoc
} from 'https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js';

export const FIREBASE_WEB_CONFIG = {
  apiKey: 'AIzaSyBW4oQrlBQYVa9J6MyaO0cSbi1Xg3SQvyo',
  authDomain: 'attendanceapp-f6a0e.firebaseapp.com',
  projectId: 'attendanceapp-f6a0e',
  storageBucket: 'attendanceapp-f6a0e.firebasestorage.app',
  messagingSenderId: '939574597887',
  appId: '1:939574597887:android:3185f8b7431431e15bd4ca'
};

export const FIRESTORE_DATABASE_ID =
  'ai-studio-android-nichatte-7f6d200a-1161-49bd-b79a-93905545b7f7';

export function createFirebaseSyncClient() {
  let app = null;
  let auth = null;
  let db = null;
  let unsubEmployees = null;
  let unsubLogs = null;

  try {
    app = initializeApp(FIREBASE_WEB_CONFIG);
    auth = getAuth(app);
    db = initializeFirestore(
      app,
      {
        localCache: persistentLocalCache({
          tabManager: persistentMultipleTabManager()
        })
      },
      FIRESTORE_DATABASE_ID
    );
  } catch (err) {
    console.warn('Firebase Web initialization fell back cleanly:', err);
  }

  function clearListeners() {
    if (unsubEmployees) {
      unsubEmployees();
      unsubEmployees = null;
    }
    if (unsubLogs) {
      unsubLogs();
      unsubLogs = null;
    }
  }

  async function ensureStarterEmployees(uid, starterEmployees) {
    if (!db || !uid) return;
    const empCol = collection(db, 'users', uid, 'employees');
    const existing = await getDocs(query(empCol, limit(1)));
    if (existing.empty) {
      for (const emp of starterEmployees) {
        await upsertEmployee(uid, emp, false);
      }
      return;
    }
    const ansar = starterEmployees.find((e) => e.id === 'emp_muhammad_ansar');
    if (ansar) {
      const ansarRef = doc(db, 'users', uid, 'employees', ansar.id);
      const snap = await getDoc(ansarRef);
      if (!snap.exists()) {
        await upsertEmployee(uid, ansar, false);
      }
    }
  }

  async function upsertEmployee(uid, emp, overwrite = false) {
    if (!db || !uid || !emp?.id) return;
    const ref = doc(db, 'users', uid, 'employees', emp.id);
    if (!overwrite) {
      const snap = await getDoc(ref);
      if (snap.exists()) return;
    }
    await setDoc(
      ref,
      {
        userId: uid,
        employeeCode: String(emp.employeeCode || 'NICH-0000').slice(0, 32),
        name: String(emp.name || 'Employee').slice(0, 80),
        department: String(emp.department || 'Operations').slice(0, 80),
        roleTitle: String(emp.roleTitle || 'Authorized Staff').slice(0, 80),
        passcode: String(emp.passcode || '147').slice(0, 12),
        checkInCode: String(emp.checkInCode || '').slice(0, 12),
        checkOutCode: String(emp.checkOutCode || '').slice(0, 12),
        avatarKey: String(emp.avatarKey || 'avatar_1').slice(0, 64),
        faceSignature: String(emp.faceSignature || '').slice(0, 512),
        isActive: emp.isActive !== false,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      },
      { merge: overwrite }
    );
  }

  async function writeAttendanceLog(uid, employee, logEntry) {
    if (!db || !uid || !logEntry?.id) {
      return false;
    }
    await upsertEmployee(uid, employee, false);
    const ref = doc(db, 'users', uid, 'attendanceLogs', logEntry.id);
    await setDoc(ref, {
      userId: uid,
      employeeId: employee.id,
      employeeCode: String(employee.employeeCode || '').slice(0, 32),
      employeeName: String(employee.name || '').slice(0, 80),
      department: String(employee.department || '').slice(0, 80),
      avatarKey: String(employee.avatarKey || 'avatar_1').slice(0, 64),
      eventType: logEntry.eventType === 'CHECK_OUT' ? 'CHECK_OUT' : 'CHECK_IN',
      verificationMethod: logEntry.verificationMethod || 'FACE_SCAN_1',
      confidenceScore: Math.max(0, Math.min(100, Math.round(logEntry.confidenceScore || 99))),
      loggedTimeLabel: String(logEntry.loggedTimeLabel || '').slice(0, 32),
      loggedDateKey: String(logEntry.loggedDateKey || '').slice(0, 16),
      createdAt: serverTimestamp()
    });
    return true;
  }

  return {
    isConfigured: Boolean(app && auth && db),
    getCurrentUser() {
      return auth?.currentUser || null;
    },
    onAuthChanged(callback) {
      if (!auth) {
        callback(null);
        return () => {};
      }
      return onAuthStateChanged(auth, callback);
    },
    async signInWithGoogle() {
      if (!auth) throw new Error('Firebase Auth unavailable');
      const provider = new GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      return signInWithPopup(auth, provider);
    },
    async signOutUser() {
      if (!auth) return;
      clearListeners();
      await signOut(auth);
    },
    startRealtimeSync(uid, starterEmployees, { onEmployees, onLogs, onError }) {
      if (!db || !uid) return;
      clearListeners();

      ensureStarterEmployees(uid, starterEmployees).catch((err) => {
        if (onError) onError(err);
      });

      const empQuery = query(
        collection(db, 'users', uid, 'employees'),
        orderBy('employeeCode', 'asc')
      );
      unsubEmployees = onSnapshot(
        empQuery,
        (snapshot) => {
          const list = snapshot.docs.map((d) => ({ id: d.id, ...d.data() }));
          if (onEmployees) onEmployees(list);
        },
        (err) => {
          if (onError) onError(err);
        }
      );

      const logsQuery = query(
        collection(db, 'users', uid, 'attendanceLogs'),
        orderBy('createdAt', 'desc'),
        limit(100)
      );
      unsubLogs = onSnapshot(
        logsQuery,
        (snapshot) => {
          const list = snapshot.docs.map((d) => {
            const data = d.data();
            return {
              id: d.id,
              ...data,
              syncedToCloud: true,
              createdAtMs: data.createdAt?.toMillis ? data.createdAt.toMillis() : Date.now()
            };
          });
          if (onLogs) onLogs(list);
        },
        (err) => {
          if (onError) onError(err);
        }
      );
    },
    stopRealtimeSync: clearListeners,
    writeAttendanceLog
  };
}
