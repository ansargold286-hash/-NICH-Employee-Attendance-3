/**
 * Optional REST/Supabase fetch adapter for GitHub Pages web terminal.
 * Reads runtime-configured serverUrl & publishableKey from
 * window.__NICH_SERVER_CONFIG__ or localStorage('nich3_server_config_v1')
 * so secret keys are never embedded in public static assets.
 */

const CONFIG_KEY = 'nich3_server_config_v1';
const QUEUE_KEY = 'nich3_web_pending_server_queue_v1';

function loadRuntimeConfig() {
  try {
    if (typeof window !== 'undefined' && window.__NICH_SERVER_CONFIG__) {
      const cfg = window.__NICH_SERVER_CONFIG__;
      if (cfg?.serverUrl && cfg?.publishableKey) {
        return {
          serverUrl: String(cfg.serverUrl).replace(/\/+$/, ''),
          publishableKey: String(cfg.publishableKey).trim()
        };
      }
    }
    const raw = localStorage.getItem(CONFIG_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed?.serverUrl || !parsed?.publishableKey) return null;
    const url = String(parsed.serverUrl).trim().replace(/\/+$/, '');
    const key = String(parsed.publishableKey).trim();
    if (!/^https?:\/\//i.test(url) || url.includes('YOUR_PROJECT_REF') || key.startsWith('YOUR_SUPABASE_')) {
      return null;
    }
    return { serverUrl: url, publishableKey: key };
  } catch {
    return null;
  }
}

function loadQueue() {
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveQueue(items) {
  try {
    localStorage.setItem(QUEUE_KEY, JSON.stringify(items.slice(-200)));
  } catch {
    // Ignore storage quota errors gracefully
  }
}

export function createServerSyncClient() {
  let flushing = false;

  async function postUpsert(cfg, table, payload) {
    const headers = {
      apikey: cfg.publishableKey,
      'Content-Type': 'application/json',
      Accept: 'application/json',
      Prefer: 'resolution=merge-duplicates,return=minimal'
    };
    if ((cfg.publishableKey.match(/\./g) || []).length === 2) {
      headers.Authorization = `Bearer ${cfg.publishableKey}`;
    }
    const res = await fetch(`${cfg.serverUrl}/rest/v1/${table}?on_conflict=id`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload)
    });
    return res.ok;
  }

  function enqueueItem(table, payload) {
    const queue = loadQueue().filter(
      (item) => !(item.table === table && item.payload?.id && item.payload.id === payload?.id)
    );
    queue.push({ table, payload });
    saveQueue(queue);
  }

  async function flushQueue() {
    if (flushing) return;
    const cfg = loadRuntimeConfig();
    if (!cfg) return;
    const queue = loadQueue();
    if (queue.length === 0) return;

    flushing = true;
    try {
      const remaining = [];
      for (const item of queue) {
        try {
          const ok = await postUpsert(cfg, item.table, item.payload);
          if (!ok) remaining.push(item);
        } catch {
          remaining.push(item);
        }
      }
      saveQueue(remaining);
    } finally {
      flushing = false;
    }
  }

  return {
    isConfigured() {
      return Boolean(loadRuntimeConfig());
    },
    async syncAttendancePayload({ employee, logEntry, selfieDataUrl, emailDispatchStatus, operatorUid }) {
      const epochMillis = Date.now();
      enqueueItem('employees', {
        id: employee.id,
        employee_code: employee.employeeCode || '',
        name: employee.name || '',
        department: employee.department || 'Authorized Staff',
        role_title: employee.roleTitle || 'Authorized Staff',
        passcode: employee.passcode || '',
        avatar_key: employee.avatarKey || 'avatar_1',
        face_signature: employee.faceSignature || '',
        is_active: employee.isActive !== false,
        device_operator_id: operatorUid || null,
        updated_at: new Date(epochMillis).toISOString()
      });
      enqueueItem('attendance_logs', {
        id: logEntry.id,
        employee_id: employee.id,
        employee_code: employee.employeeCode || '',
        employee_name: employee.name || '',
        department: employee.department || '',
        avatar_key: employee.avatarKey || 'avatar_1',
        event_type: logEntry.eventType,
        verification_method: logEntry.verificationMethod || 'FACE_SCAN_1',
        confidence_score: Math.round(logEntry.confidenceScore || 95),
        logged_time_label: logEntry.loggedTimeLabel || '',
        logged_date_key: logEntry.loggedDateKey || '',
        gps_coordinates: logEntry.gpsCoordinates || '',
        location_address: logEntry.locationAddress || '',
        time_source: logEntry.timeSource || 'Browser Network Clock',
        ntp_epoch_millis: epochMillis,
        ntp_timestamp_iso: new Date(epochMillis).toISOString(),
        live_photo_base64: selfieDataUrl || '',
        email_dispatch_status: emailDispatchStatus || 'PENDING',
        email_recipient: 'Nichemployeeattendance@gmail.com',
        device_operator_id: operatorUid || null,
        synced_at: new Date(epochMillis).toISOString()
      });
      await flushQueue();
    },
    async syncEmailReport({ employee, logEntry, dispatchStatus, operatorUid }) {
      const epochMillis = Date.now();
      enqueueItem('email_reports', {
        id: `email_${logEntry.id}`,
        attendance_log_id: logEntry.id,
        employee_id: employee.id,
        employee_code: employee.employeeCode || '',
        employee_name: employee.name || '',
        department: employee.department || '',
        event_type: logEntry.eventType,
        recipient_email: 'Nichemployeeattendance@gmail.com',
        dispatch_status: dispatchStatus,
        dispatched_at: new Date(epochMillis).toISOString(),
        device_operator_id: operatorUid || null
      });
      await flushQueue();
    },
    flushQueue
  };
}
