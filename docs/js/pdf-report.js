/**
 * Pure client-side binary PDF 1.4 generator + Gmail/Web Share dispatch to
 * `Nichemployeeattendance@gmail.com`, embedding the live captured JPEG selfie.
 */

export const TARGET_RECIPIENT_EMAIL = 'Nichemployeeattendance@gmail.com';

export async function dispatchCheckOutEmailAndPdf({
  employee,
  logEntry,
  selfieDataUrl,
  operatorEmail = ''
}) {
  const filename = buildPdfFilename(employee, logEntry);
  const pdfBlob = await buildAttendancePdfBlob({
    employee,
    logEntry,
    selfieDataUrl,
    operatorEmail
  });

  // 1. Trigger clean local PDF download so the file is always available to attach
  triggerBlobDownload(pdfBlob, filename);

  const subject = `NICH Employee Attendance 3 • CHECK-OUT • ${employee.name} (${employee.employeeCode})`;
  const body = buildEmailBody({ employee, logEntry, operatorEmail });

  // 2. Use Web Share API with file attachment on supported mobile/desktop browsers
  const pdfFile = new File([pdfBlob], filename, { type: 'application/pdf' });
  if (typeof navigator !== 'undefined' && navigator.canShare && navigator.canShare({ files: [pdfFile] })) {
    try {
      await navigator.share({
        title: subject,
        text: `${body}\n\nRecipient: ${TARGET_RECIPIENT_EMAIL}`,
        files: [pdfFile]
      });
      return { method: 'web_share', filename };
    } catch (err) {
      if (err && err.name === 'AbortError') {
        return { method: 'cancelled_share', filename };
      }
    }
  }

  // 3. Fallback: launch pre-filled mailto compose targeting Nichemployeeattendance@gmail.com
  const mailtoUrl =
    `mailto:${encodeURIComponent(TARGET_RECIPIENT_EMAIL)}` +
    `?subject=${encodeURIComponent(subject)}` +
    `&body=${encodeURIComponent(body)}`;

  const anchor = document.createElement('a');
  anchor.href = mailtoUrl;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);

  return { method: 'mailto', filename };
}

function buildPdfFilename(employee, logEntry) {
  const safeCode = String(employee.employeeCode || 'EMP').replace(/[^a-zA-Z0-9_-]/g, '_');
  const safeId = String(logEntry.id || Date.now()).replace(/[^a-zA-Z0-9_-]/g, '_');
  return `NICH_${safeCode}_${logEntry.eventType || 'CHECK_OUT'}_${safeId}.pdf`;
}

function buildEmailBody({ employee, logEntry, operatorEmail }) {
  const lines = [
    'NICH Employee Attendance 3 — Automated Attendance Report',
    '==========================================================',
    `Employee Name: ${employee.name}`,
    `Employee ID: ${employee.employeeCode}`,
    `Department: ${employee.department}`,
    `Role: ${employee.roleTitle || 'Authorized Staff'}`,
    `Event Type: CHECK-OUT (${logEntry.eventType || 'CHECK_OUT'})`,
    `Verification Method: Biometric Face Scan (Web Terminal)`,
    `Match Confidence: ${logEntry.confidenceScore ?? 99}%`,
    `Timestamp: ${logEntry.loggedTimeLabel} (${logEntry.loggedDateKey})`,
    `Cloud Sync: ${logEntry.syncedToCloud ? 'Cloud Firestore Live' : 'Saved Offline (Queued)'}`
  ];
  if (operatorEmail) {
    lines.push(`Dispatched From Operator Account: ${operatorEmail}`);
  }
  lines.push('');
  lines.push('The structured A4 PDF attendance report (with live verification selfie) has been generated.');
  return lines.join('\n');
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/**
 * Generates an A4 PDF 1.4 binary document with optional embedded JPEG selfie XObject.
 */
export async function buildAttendancePdfBlob({
  employee,
  logEntry,
  selfieDataUrl,
  operatorEmail = ''
}) {
  const jpegInfo = await extractJpegInfoFromDataUrl(selfieDataUrl);

  const pageWidth = 595;
  const pageHeight = 842;

  const contentCommands = [];
  // Top dark navy header bar
  contentCommands.push('0.043 0.078 0.149 rg 0 746 595 96 re f');
  // Bottom accent strip (#0091EA cyan-blue for Check-Out)
  contentCommands.push('0.000 0.569 0.918 rg 0 742 595 4 re f');
  // White title & subtitle text
  contentCommands.push('BT /F2 18 Tf 1 1 1 rg 40 798 Td (NICH EMPLOYEE ATTENDANCE 3) Tj ET');
  contentCommands.push(
    'BT /F1 10.5 Tf 0.541 0.643 0.784 rg 40 778 Td (AUTOMATED BIOMETRIC ATTENDANCE REPORT  •  WEB TERMINAL) Tj ET'
  );

  // Section header
  contentCommands.push('BT /F2 13 Tf 0.059 0.110 0.204 rg 40 710 Td (ATTENDANCE RECORD SUMMARY) Tj ET');

  // Optional live selfie box on the top-right below header
  if (jpegInfo) {
    contentCommands.push('0.94 0.96 0.99 rg 415 570 140 140 re f');
    contentCommands.push('0.82 0.86 0.92 RG 1 w 415 570 140 140 re S');
    contentCommands.push(`q 128 0 0 128 421 576 cm /Im1 Do Q`);
    contentCommands.push('BT /F1 8.5 Tf 0.34 0.43 0.56 rg 438 558 Td (LIVE VERIFIED PHOTO) Tj ET');
  }

  const asciiClean = (text) =>
    String(text || '')
      .replace(/[^\x20-\x7E]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim() || String(employee.employeeCode || 'Staff');

  const escapePdfStr = (str) =>
    asciiClean(str).replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');

  const rows = [
    ['Report ID', logEntry.id],
    ['Employee', `${employee.name} (${employee.employeeCode})`],
    ['Department', employee.department],
    ['Role Title', employee.roleTitle || 'Authorized Staff'],
    ['Attendance Type', 'CHECK-OUT (Verified)'],
    ['Verification', 'Live Biometric Face Scan'],
    ['Confidence', `${logEntry.confidenceScore ?? 99}%`],
    ['Timestamp', `${logEntry.loggedTimeLabel}  |  ${logEntry.loggedDateKey}`],
    ['Cloud Sync', logEntry.syncedToCloud ? 'Synced (attendanceapp-f6a0e)' : 'Recorded Offline'],
    ['Operator', operatorEmail || 'Device Web Browser'],
    ['Recipient', TARGET_RECIPIENT_EMAIL]
  ];

  let y = 682;
  for (let i = 0; i < rows.length; i++) {
    const [label, value] = rows[i];
    const rowWidth = jpegInfo && y > 545 ? 360 : 515;
    if (i % 2 === 0) {
      contentCommands.push(`0.961 0.973 0.988 rg 40 ${y - 6} ${rowWidth} 22 re f`);
    }
    contentCommands.push(`0.824 0.863 0.922 RG 0.8 w 40 ${y - 6} ${rowWidth} 22 re S`);
    contentCommands.push(`BT /F2 9.5 Tf 0.337 0.431 0.565 rg 48 ${y + 1} Td (${escapePdfStr(label)}) Tj ET`);
    contentCommands.push(`BT /F1 10 Tf 0.071 0.110 0.176 rg 148 ${y + 1} Td (${escapePdfStr(value)}) Tj ET`);
    y -= 24;
  }

  contentCommands.push(
    'BT /F1 8.5 Tf 0.45 0.53 0.65 rg 40 42 Td (Generated by NICH Employee Attendance 3 GitHub Pages Web Terminal) Tj ET'
  );

  const streamString = contentCommands.join('\n');
  const streamBytes = new TextEncoder().encode(streamString);

  // Assemble binary PDF objects safely
  const chunks = [];
  const offsets = [0];
  let byteLength = 0;

  const pushStr = (s) => {
    const encoded = new TextEncoder().encode(s);
    chunks.push(encoded);
    byteLength += encoded.length;
  };
  const pushBytes = (bytes) => {
    chunks.push(bytes);
    byteLength += bytes.length;
  };

  pushStr('%PDF-1.4\n');

  const xobjectRef = jpegInfo ? '/XObject << /Im1 6 0 R >> ' : '';

  // 1: Catalog
  offsets[1] = byteLength;
  pushStr('1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n');

  // 2: Pages
  offsets[2] = byteLength;
  pushStr('2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n');

  // 3: Page
  offsets[3] = byteLength;
  pushStr(
    `3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pageWidth} ${pageHeight}] ` +
      `/Resources << /Font << /F1 4 0 R /F2 5 0 R >> ${xobjectRef}>> /Contents ${jpegInfo ? 7 : 6} 0 R >>\nendobj\n`
  );

  // 4: Helvetica Regular
  offsets[4] = byteLength;
  pushStr('4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n');

  // 5: Helvetica Bold
  offsets[5] = byteLength;
  pushStr('5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n');

  // 6 (optional): JPEG Image XObject
  const contentObjId = jpegInfo ? 7 : 6;
  if (jpegInfo) {
    offsets[6] = byteLength;
    pushStr(
      `6 0 obj\n<< /Type /XObject /Subtype /Image /Width ${jpegInfo.width} /Height ${jpegInfo.height} ` +
        `/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegInfo.bytes.length} >>\nstream\n`
    );
    pushBytes(jpegInfo.bytes);
    pushStr('\nendstream\nendobj\n');
  }

  // Content stream
  offsets[contentObjId] = byteLength;
  pushStr(`${contentObjId} 0 obj\n<< /Length ${streamBytes.length} >>\nstream\n`);
  pushBytes(streamBytes);
  pushStr('\nendstream\nendobj\n');

  // Cross-reference table
  const xrefStart = byteLength;
  const totalObjs = contentObjId + 1;
  pushStr(`xref\n0 ${totalObjs}\n0000000000 65535 f \n`);
  for (let id = 1; id < totalObjs; id++) {
    pushStr(`${String(offsets[id]).padStart(10, '0')} 00000 n \n`);
  }
  pushStr(`trailer\n<< /Size ${totalObjs} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF`);

  return new Blob(chunks, { type: 'application/pdf' });
}

async function extractJpegInfoFromDataUrl(dataUrl) {
  if (!dataUrl || typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/jpeg')) {
    return null;
  }
  try {
    const base64 = dataUrl.split(',')[1];
    if (!base64) return null;
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }

    // Parse JPEG SOF0/SOF2 dimensions
    let i = 2;
    while (i + 9 < bytes.length) {
      if (bytes[i] !== 0xff) {
        i++;
        continue;
      }
      const marker = bytes[i + 1];
      if (marker === 0xc0 || marker === 0xc2) {
        const height = (bytes[i + 5] << 8) | bytes[i + 6];
        const width = (bytes[i + 7] << 8) | bytes[i + 8];
        if (width > 0 && height > 0) {
          return { bytes, width, height };
        }
        return null;
      }
      if (marker === 0xd8 || marker === 0xd9 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        i += 2;
      } else {
        const segLen = (bytes[i + 2] << 8) | bytes[i + 3];
        if (segLen < 2) break;
        i += 2 + segLen;
      }
    }
    return null;
  } catch {
    return null;
  }
}
