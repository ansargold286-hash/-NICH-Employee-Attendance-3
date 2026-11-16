/**
 * Canvas renderer mirroring Android BiometricCameraScanner.kt:
 * - Precision double-stroked blue/cyan boundary L-brackets
 * - Concentric 3D geometric facial wireframe mesh with pulsing nodes
 * - Oscillating volumetric blue horizontal scan beam
 */

const SCANNER_ELECTRIC_BLUE = '#29B6F6';
const SCANNER_DEEP_BLUE = '#1E88E5';
const SCANNER_NODE_CYAN = '#80D8FF';
const NEON_CYAN = '#00E5FF';
const NODE_MAGENTA = '#FF80EC';

export function startScannerOverlayLoop(canvas) {
  const ctx = canvas.getContext('2d');
  let rafId = 0;
  let running = true;
  let faceDetectedHint = false;
  const startTime = performance.now();

  function resizeCanvas() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const width = canvas.clientWidth || window.innerWidth;
    const height = canvas.clientHeight || window.innerHeight;
    if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    return { width, height };
  }

  function drawFrame(now) {
    if (!running) return;
    const { width: w, height: h } = resizeCanvas();
    ctx.clearRect(0, 0, w, h);

    // Exact proportions matching BiometricCameraScanner.kt
    const boxW = Math.min(w * 0.72, h * 0.44);
    const boxH = Math.min(boxW * 1.24, h * 0.62);
    const left = (w - boxW) * 0.5;
    const top = (h - boxH) * 0.46;
    const right = left + boxW;
    const bottom = top + boxH;
    const cornerLen = boxW * 0.22;

    const sweepFraction = 0.5 - 0.5 * Math.cos(((now - startTime) % 1900) / 1900 * Math.PI * 2);
    const meshPulse = 0.42 + 0.46 * (0.5 - 0.5 * Math.cos(((now - startTime) % 1400) / 1400 * Math.PI * 2));

    drawCornerBrackets(ctx, left, top, right, bottom, cornerLen, faceDetectedHint);
    drawGeometricFacialMesh(ctx, left, top, boxW, boxH, meshPulse, faceDetectedHint);
    drawVolumetricSweepBeam(ctx, left, top, boxW, boxH, sweepFraction);

    rafId = requestAnimationFrame(drawFrame);
  }

  rafId = requestAnimationFrame(drawFrame);

  return {
    setFaceDetected(detected) {
      faceDetectedHint = Boolean(detected);
    },
    stop() {
      running = false;
      cancelAnimationFrame(rafId);
    }
  };
}

function drawCornerBrackets(ctx, left, top, right, bottom, len, locked) {
  const stroke = (color, width) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.beginPath();

    // Top-left
    ctx.moveTo(left, top + len);
    ctx.lineTo(left, top);
    ctx.lineTo(left + len, top);

    // Top-right
    ctx.moveTo(right - len, top);
    ctx.lineTo(right, top);
    ctx.lineTo(right, top + len);

    // Bottom-left
    ctx.moveTo(left, bottom - len);
    ctx.lineTo(left, bottom);
    ctx.lineTo(left + len, bottom);

    // Bottom-right
    ctx.moveTo(right - len, bottom);
    ctx.lineTo(right, bottom);
    ctx.lineTo(right, bottom - len);

    ctx.stroke();
  };

  stroke(locked ? 'rgba(0, 229, 255, 0.34)' : 'rgba(41, 182, 246, 0.30)', 9);
  stroke(locked ? NEON_CYAN : SCANNER_ELECTRIC_BLUE, 3.2);
}

function drawGeometricFacialMesh(ctx, left, top, boxW, boxH, pulse, locked) {
  const cx = left + boxW * 0.5;
  const cy = top + boxH * 0.48;
  const rx = boxW * 0.31;
  const ry = boxH * 0.36;

  const primaryAlpha = (0.36 + 0.34 * pulse).toFixed(2);
  const secondaryAlpha = (0.20 + 0.22 * pulse).toFixed(2);

  ctx.save();
  ctx.strokeStyle = locked
    ? `rgba(0, 229, 255, ${primaryAlpha})`
    : `rgba(41, 182, 246, ${primaryAlpha})`;
  ctx.lineWidth = 1.4;

  // Concentric oval facial contour bands
  for (const scale of [1.0, 0.72, 0.44]) {
    ctx.beginPath();
    ctx.ellipse(cx, cy, rx * scale, ry * scale, 0, 0, Math.PI * 2);
    ctx.stroke();
  }

  // Meridian longitude lines across face
  ctx.strokeStyle = `rgba(30, 136, 229, ${secondaryAlpha})`;
  ctx.lineWidth = 1.1;
  for (const curveFactor of [-0.62, -0.32, 0, 0.32, 0.62]) {
    const xOffset = rx * curveFactor;
    ctx.beginPath();
    ctx.moveTo(cx + xOffset * 0.48, cy - ry);
    ctx.quadraticCurveTo(cx + xOffset * 1.15, cy, cx + xOffset * 0.44, cy + ry);
    ctx.stroke();
  }

  // Latitude contour ribs (forehead, eyes, nose bridge, mouth, chin)
  for (const yFactor of [-0.65, -0.32, 0, 0.34, 0.68]) {
    const yLine = cy + ry * yFactor;
    const halfSpan = rx * Math.sqrt(Math.max(0.05, 1 - yFactor * yFactor * 0.85));
    ctx.beginPath();
    ctx.moveTo(cx - halfSpan, yLine);
    ctx.quadraticCurveTo(cx, yLine + ry * 0.04, cx + halfSpan, yLine);
    ctx.stroke();
  }

  // Facial keypoint anchor nodes matching Android wireframe triangulation
  const nodes = [
    [cx, cy - ry * 0.88],
    [cx - rx * 0.42, cy - ry * 0.44],
    [cx + rx * 0.42, cy - ry * 0.44],
    [cx - rx * 0.38, cy - ry * 0.22],
    [cx + rx * 0.38, cy - ry * 0.22],
    [cx, cy - ry * 0.04],
    [cx - rx * 0.56, cy + ry * 0.14],
    [cx + rx * 0.56, cy + ry * 0.14],
    [cx - rx * 0.28, cy + ry * 0.42],
    [cx + rx * 0.28, cy + ry * 0.42],
    [cx, cy + ry * 0.88]
  ];

  const connections = [
    [0, 1], [0, 2], [1, 3], [2, 4], [3, 5], [4, 5],
    [1, 6], [2, 7], [3, 6], [4, 7], [5, 8], [5, 9],
    [6, 8], [7, 9], [8, 10], [9, 10], [3, 4], [8, 9]
  ];

  ctx.strokeStyle = `rgba(128, 216, 255, ${(0.28 + 0.3 * pulse).toFixed(2)})`;
  ctx.lineWidth = 1.25;
  ctx.beginPath();
  for (const [a, b] of connections) {
    ctx.moveTo(nodes[a][0], nodes[a][1]);
    ctx.lineTo(nodes[b][0], nodes[b][1]);
  }
  ctx.stroke();

  // Glowing node markers (highlight inner eyes/nose/mouth points matching EZFace reference)
  nodes.forEach(([nx, ny], idx) => {
    const isHighlight = idx === 2 || idx === 4 || idx === 5 || idx === 8 || idx === 9;
    ctx.fillStyle = isHighlight
      ? `rgba(255, 128, 236, ${(0.62 + 0.32 * pulse).toFixed(2)})`
      : `rgba(128, 216, 255, ${(0.55 + 0.35 * pulse).toFixed(2)})`;
    ctx.beginPath();
    ctx.arc(nx, ny, isHighlight ? 3.4 : 2.8, 0, Math.PI * 2);
    ctx.fill();
  });

  ctx.restore();
}

function drawVolumetricSweepBeam(ctx, left, top, boxW, boxH, sweepFraction) {
  const beamY = top + 10 + (boxH - 20) * sweepFraction;
  const beamHalfBand = 24;

  const grad = ctx.createLinearGradient(0, beamY - beamHalfBand, 0, beamY + beamHalfBand);
  grad.addColorStop(0, 'rgba(41, 182, 246, 0)');
  grad.addColorStop(0.5, 'rgba(41, 182, 246, 0.34)');
  grad.addColorStop(1, 'rgba(41, 182, 246, 0)');

  ctx.fillStyle = grad;
  ctx.fillRect(left + 6, beamY - beamHalfBand, boxW - 12, beamHalfBand * 2);

  ctx.strokeStyle = 'rgba(128, 216, 255, 0.92)';
  ctx.lineWidth = 2.4;
  ctx.beginPath();
  ctx.moveTo(left + 6, beamY);
  ctx.lineTo(left + boxW - 6, beamY);
  ctx.stroke();
}
