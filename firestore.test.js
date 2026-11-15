const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_admin_123";
const BOB_UID = "bob_admin_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validEmployee(userId) {
  const now = new Date();
  return {
    userId,
    employeeCode: "NICH-0147",
    name: "محمد انصار (Muhammad Ansar)",
    department: "Executive Operations",
    roleTitle: "Authorized Staff",
    passcode: "147",
    checkInCode: "147",
    checkOutCode: "369",
    avatarKey: "avatar_ansar",
    faceSignature: "0.42:0.51:0.38:0.29",
    isActive: true,
    createdAt: now,
    updatedAt: now,
  };
}

function validAttendanceLog(userId, employeeId) {
  return {
    userId,
    employeeId,
    employeeCode: "NICH-1042",
    employeeName: "Dr. Ananya Rao",
    department: "Biometric Systems",
    avatarKey: "avatar_1",
    eventType: "CHECK_IN",
    verificationMethod: "FACE_SCAN_1",
    confidenceScore: 98,
    loggedTimeLabel: "09:15:00 AM",
    loggedDateKey: "2026-11-13",
    createdAt: new Date(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot list or write employees or attendanceLogs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/employees`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/attendanceLogs`).get());
  await assertFails(
    unauthDb.doc(`users/${ALICE_UID}/employees/emp_1`).set(validEmployee(ALICE_UID))
  );
});

test("Owner: can create, read, update, and delete employee with valid schema", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const empRef = aliceDb.doc(`users/${ALICE_UID}/employees/emp_1042`);

  await assertSucceeds(empRef.set(validEmployee(ALICE_UID)));
  await assertSucceeds(empRef.get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/employees`).get());
  await assertSucceeds(
    empRef.update({
      department: "Quantum Security",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(empRef.delete());
});

test("Cross-user isolation: Bob cannot read or write Alice employees or logs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/employees/emp_1042`).set(validEmployee(ALICE_UID))
  );

  await assertFails(bobDb.collection(`users/${ALICE_UID}/employees`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/employees/emp_1042`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/employees/emp_9999`).set(validEmployee(BOB_UID))
  );
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/employees/emp_1042`).delete()
  );
});

test("Dirty Dozen: rejects ghost field, short passcode, and immutable field mutation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const empRef = aliceDb.doc(`users/${ALICE_UID}/employees/emp_1042`);

  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/employees/emp_ghost`).set({
      ...validEmployee(ALICE_UID),
      isAdmin: true,
    })
  );

  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/employees/emp_short`).set({
      ...validEmployee(ALICE_UID),
      passcode: "12",
    })
  );

  await assertSucceeds(empRef.set(validEmployee(ALICE_UID)));
  await assertFails(
    empRef.update({
      userId: BOB_UID,
      updatedAt: new Date(),
    })
  );
});

test("AttendanceLog: requires existing employee, validates enums, and blocks updates/deletes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Orphaned log without existing employee must fail
  await assertFails(
    aliceDb
      .doc(`users/${ALICE_UID}/attendanceLogs/log_orphan`)
      .set(validAttendanceLog(ALICE_UID, "emp_missing"))
  );

  // Create employee first
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/employees/emp_1042`).set(validEmployee(ALICE_UID))
  );

  // Invalid enum eventType must fail
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/attendanceLogs/log_bad_enum`).set({
      ...validAttendanceLog(ALICE_UID, "emp_1042"),
      eventType: "LUNCH_BREAK",
    })
  );

  // Valid attendance log create succeeds
  const logRef = aliceDb.doc(`users/${ALICE_UID}/attendanceLogs/log_001`);
  await assertSucceeds(logRef.set(validAttendanceLog(ALICE_UID, "emp_1042")));
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/attendanceLogs`).get());

  // Append-only: update and delete must fail
  await assertFails(logRef.update({ eventType: "CHECK_OUT" }));
  await assertFails(logRef.delete());
});
