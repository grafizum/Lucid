'use strict';

/**
 * Preview harness.
 *
 * 1. Renders the demo WhatsApp surface off-screen and captures it.
 * 2. Runs the REAL pipeline on the captured pixels: crop -> tesseract OCR ->
 *    translate.
 * 3. Composites the real overlay UI (lens, capture frame, translation card)
 *    over that capture at the exact geometry the app uses, and saves the shot.
 *
 * Nothing here is faked: the translated text in the screenshot is what the app
 * actually produced from the pixels in the screenshot.
 */

const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const fs = require('node:fs');

app.disableHardwareAcceleration();

// Off-screen windows are destroyed between steps; keep the harness alive.
app.on('window-all-closed', () => {});

const { readText } = require('../src/services/ocr');
const { translateText, languageName } = require('../src/services/translate');

const W = 1440;
const H = 900;
const SQUASH = 1; // CSS px per stage px — the stage is authored at 1440x900

const LENS = { size: 74 };
const SIGHT = { x: 387, y: 418, width: 620, height: 240 };
const TARGET = process.argv.includes('--es') ? 'es' : 'en';

const ROOT = path.join(__dirname, '..');
const SHOTS = path.join(ROOT, 'screenshots');
const STAGE_PNG = path.join(ROOT, 'src', 'renderer', 'stage-whatsapp.png');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Render a local page off-screen and return the painted NativeImage. */
async function renderOffscreen(file, { width = W, height = H, settle = 1100, prepare } = {}) {
  const win = new BrowserWindow({
    width,
    height,
    show: false,
    frame: false,
    webPreferences: {
      offscreen: true,
      backgroundThrottling: false,
      webSecurity: false,
    },
  });

  let latest = null;
  win.webContents.setFrameRate(30);
  win.webContents.on('paint', (_event, _dirty, image) => {
    if (image && !image.isEmpty()) latest = image;
  });

  await win.loadFile(file);

  if (prepare) {
    await prepare(win);
  }

  await sleep(settle);

  // Nudge one more frame out in case the compositor settled late.
  win.webContents.invalidate();
  await sleep(320);

  const image = latest;
  win.destroy();

  if (!image) throw new Error(`No frame painted for ${path.basename(file)}`);
  return image;
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  /* ── 1. the surface we are "looking at" ─────────────────────────── */

  console.log('[1/4] rendering the WhatsApp surface…');
  const stageImage = await renderOffscreen(path.join(ROOT, 'demo', 'whatsapp.html'));
  const stageSize = stageImage.getSize();
  fs.writeFileSync(STAGE_PNG, stageImage.toPNG());
  console.log(
    `      captured ${stageSize.width}x${stageSize.height}px -> src/renderer/stage-whatsapp.png`,
  );

  /* ── 2. real OCR on the real pixels ─────────────────────────────── */

  const k = stageSize.width / W; // capture px per stage px

  console.log('[2/4] reading the frame under the lens (tesseract, local)…');
  const crop = stageImage.crop({
    x: Math.round(SIGHT.x * k),
    y: Math.round(SIGHT.y * k),
    width: Math.round(SIGHT.width * k),
    height: Math.round(SIGHT.height * k),
  });

  // Same 2x upscale the app applies before OCR.
  const ocrReady = crop.resize({ width: Math.round(SIGHT.width * 2), quality: 'best' });
  fs.writeFileSync(path.join(SHOTS, '_sight-crop.png'), ocrReady.toPNG());

  const png = ocrReady.toPNG();
  // First pass also boots the WASM engine and downloads language data; time the
  // warm pass so the reported figure matches what a user actually feels.
  await readText(png, { langs: 'eng+spa+fra+deu' });

  const ocrStart = Date.now();
  const ocr = await readText(png, { langs: 'eng+spa+fra+deu' });
  const ocrMs = Date.now() - ocrStart;
  console.log(`      ${ocrMs}ms (warm) · clarity ${ocr.confidence}%`);
  console.log('      ── recognised text ──');
  console.log(ocr.text.split('\n').map((l) => `      │ ${l}`).join('\n'));

  if (!ocr.text) throw new Error('OCR produced no text — check the crop region');

  /* ── 3. real translation ────────────────────────────────────────── */

  console.log(`[3/4] translating into ${TARGET}…`);
  const trStart = Date.now();
  const translated = await translateText(ocr.text, TARGET);
  console.log(`      ${Date.now() - trStart}ms via ${translated.provider}`);
  console.log(`      detected: ${translated.detected} (${languageName(translated.detected)})`);
  console.log('      ── translation ──');
  console.log(translated.text.split('\n').map((l) => `      │ ${l}`).join('\n'));

  /* ── 4. composite the real UI and shoot it ──────────────────────── */

  console.log('[4/4] compositing the overlay and capturing…');

  const payload = {
    source: ocr.text,
    translated: translated.text,
    detected: translated.detected,
    detectedName: languageName(translated.detected),
    target: TARGET,
    targetName: languageName(TARGET),
    provider: translated.provider,
    confidence: ocr.confidence,
    ms: ocrMs + (Date.now() - trStart),
    sameAsSource: false,
  };

  const shot = await renderOffscreen(path.join(ROOT, 'src', 'renderer', 'preview.html'), {
    settle: 900,
    prepare: async (win) => {
      await sleep(700);
      await win.webContents.executeJavaScript(
        `window.__render(${JSON.stringify(payload)}); true;`,
      );
      await sleep(500);
    },
  });

  const out = path.join(SHOTS, 'whatsapp-live-preview.png');
  fs.writeFileSync(out, shot.toPNG());

  const meta = {
    generatedAt: new Date().toISOString(),
    surface: 'demo/whatsapp.html (Spanish conversation)',
    lens: { x: 660, y: 330, size: LENS.size },
    sight: SIGHT,
    ocr: { confidence: ocr.confidence, chars: ocr.text.length, engine: 'tesseract.js (local)' },
    translation: { provider: translated.provider, detected: translated.detected, target: TARGET },
    output: path.relative(ROOT, out),
    size: shot.getSize(),
  };
  fs.writeFileSync(path.join(SHOTS, 'preview-result.json'), JSON.stringify({ meta, payload }, null, 2));

  console.log(`\n✓ saved ${path.relative(ROOT, out)} (${shot.getSize().width}x${shot.getSize().height})`);
}

app.whenReady().then(async () => {
  try {
    await main();
    app.exit(0);
  } catch (err) {
    console.error('\n✗ preview failed:', err && err.stack ? err.stack : err);
    app.exit(1);
  }
});

process.on('unhandledRejection', (err) => {
  console.error('unhandled rejection:', err);
  app.exit(1);
});
