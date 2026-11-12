'use strict';

const { app, BrowserWindow, ipcMain, screen, globalShortcut, Menu, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

const SELFTEST = process.argv.includes('--selftest');
if (SELFTEST) {
  // Never touch the user's real settings or grant state during a test run.
  app.setPath('userData', path.join(os.tmpdir(), 'lucid-selftest'));
}

const { grabRegion, probeCapture } = require('./services/capture');
const { readText, shutdown: shutdownOcr } = require('./services/ocr');
const { translateText, languageName, TARGETS } = require('./services/translate');

/* ──────────────────────────────────────────────────────────────────────
   Geometry
   ────────────────────────────────────────────────────────────────────── */

const LENS = 74; // lens window (square, DIP)
const READ_W = 620; // width of the area the lens reads, in DIP
const READ_H = 240; // height of the area
const GAP = 14; // gap between lens and its reading frame
const CAPTURE_INSET = 8; // ignore the frame's own pixels when reading

const PANEL_W = 400;
const PANEL_GAP = 16;

/* ──────────────────────────────────────────────────────────────────────
   Settings (persisted next to the app's user data)
   ────────────────────────────────────────────────────────────────────── */

const DEFAULT_SETTINGS = {
  target: 'en',
  ocrLangs: 'eng+spa+fra+deu',
  granted: false,
  auto: false,
  autoDelay: 1700,
  showSight: true,
  lens: null, // {x, y}
};

let settingsPath = null;
let settings = { ...DEFAULT_SETTINGS };

function loadSettings() {
  settingsPath = path.join(app.getPath('userData'), 'settings.json');
  try {
    const raw = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    settings = { ...DEFAULT_SETTINGS, ...raw };
  } catch {
    settings = { ...DEFAULT_SETTINGS };
  }
}

function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsPath), { recursive: true });
    fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2));
  } catch {
    /* best effort */
  }
}

/* ──────────────────────────────────────────────────────────────────────
   Windows
   ────────────────────────────────────────────────────────────────────── */

let lensWin = null;
let sightWin = null;
let panelWin = null;
let setupWin = null;

let busy = false;
let dragging = null;
let dragTimer = null;
let autoTimer = null;
let panelHideTimer = null;
let lastResult = null;
let lastReadKey = '';

const PRELOAD = path.join(__dirname, 'preload.js');

function lensPosition() {
  return settings.lens || { x: 220, y: 220 };
}

function clampToWorkArea(x, y, w, h) {
  const display = screen.getDisplayNearestPoint({ x: x + w / 2, y: y + h / 2 });
  const wa = display.workArea;
  return {
    x: Math.round(Math.min(Math.max(x, wa.x), wa.x + wa.width - w)),
    y: Math.round(Math.min(Math.max(y, wa.y), wa.y + wa.height - h)),
  };
}

/** The rectangle the lens will read, derived from the lens position. */
function readRect() {
  const lens = lensPosition();
  const display = screen.getDisplayNearestPoint({ x: lens.x + LENS / 2, y: lens.y + LENS / 2 });
  const wa = display.workArea;

  const x = lens.x + LENS / 2 - READ_W / 2;

  // Prefer reading below the lens; flip above when there is no room.
  let y = lens.y + LENS + GAP;
  if (y + READ_H > wa.y + wa.height) {
    y = lens.y - GAP - READ_H;
  }
  // Last resort: centre over the lens.
  if (y < wa.y) y = Math.max(wa.y, lens.y + LENS / 2 - READ_H / 2);

  return {
    x: Math.round(Math.min(Math.max(x, wa.x + 4), wa.x + wa.width - READ_W - 4)),
    y: Math.round(y),
    width: READ_W,
    height: READ_H,
  };
}

function panelPosition(height) {
  const rect = readRect();
  const display = screen.getDisplayNearestPoint({ x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 });
  const wa = display.workArea;

  const h = Math.max(120, height || 220);
  let x = rect.x + rect.width + PANEL_GAP;
  let y = rect.y + rect.height - h;

  if (x + PANEL_W > wa.x + wa.width) {
    x = rect.x - PANEL_GAP - PANEL_W; // left side
  }
  if (x < wa.x) {
    // no room either side — drop under the reading frame
    x = rect.x + rect.width - PANEL_W;
    y = rect.y + rect.height + PANEL_GAP;
  }

  y = Math.min(Math.max(y, wa.y + 8), wa.y + wa.height - h - 8);
  x = Math.min(Math.max(x, wa.x + 8), wa.x + wa.width - PANEL_W - 8);
  return { x: Math.round(x), y: Math.round(y) };
}

function createLens() {
  const pos = clampToWorkArea(lensPosition().x, lensPosition().y, LENS, LENS);

  lensWin = new BrowserWindow({
    width: LENS,
    height: LENS,
    x: pos.x,
    y: pos.y,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    hasShadow: false,
    focusable: true,
    acceptFirstMouse: true,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  lensWin.setAlwaysOnTop(true, 'screen-saver');
  lensWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  lensWin.loadFile(path.join(__dirname, 'renderer', 'lens.html'));

  lensWin.once('ready-to-show', () => lensWin.showInactive());
  lensWin.on('closed', () => {
    lensWin = null;
  });

  settings.lens = { x: pos.x, y: pos.y };
  saveSettings();
}

function createSight() {
  sightWin = new BrowserWindow({
    width: READ_W,
    height: READ_H,
    x: 0,
    y: 0,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    skipTaskbar: true,
    hasShadow: false,
    focusable: false,
    show: false,
    enabled: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  sightWin.setAlwaysOnTop(true, 'screen-saver');
  sightWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  sightWin.setIgnoreMouseEvents(true, { forward: false });
  sightWin.loadFile(path.join(__dirname, 'renderer', 'sight.html'));

  sightWin.once('ready-to-show', () => positionSight());
  sightWin.on('closed', () => {
    sightWin = null;
  });
}

function createPanel() {
  panelWin = new BrowserWindow({
    width: PANEL_W,
    height: 260,
    x: 0,
    y: 0,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    skipTaskbar: true,
    hasShadow: false,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  panelWin.setAlwaysOnTop(true, 'screen-saver');
  panelWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  panelWin.loadFile(path.join(__dirname, 'renderer', 'panel.html'));

  panelWin.on('closed', () => {
    panelWin = null;
  });
}

function createSetup() {
  if (setupWin) {
    setupWin.show();
    setupWin.focus();
    return;
  }

  setupWin = new BrowserWindow({
    width: 470,
    height: 560,
    frame: false,
    transparent: true,
    resizable: false,
    skipTaskbar: false,
    hasShadow: false,
    backgroundColor: '#00000000',
    title: 'Lucid — screen access',
    webPreferences: {
      preload: PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  setupWin.loadFile(path.join(__dirname, 'renderer', 'setup.html'));
  setupWin.on('closed', () => {
    setupWin = null;
  });
}

/* ──────────────────────────────────────────────────────────────────────
   Placement
   ────────────────────────────────────────────────────────────────────── */

function positionSight() {
  if (!sightWin || sightWin.isDestroyed()) return;
  const rect = readRect();
  sightWin.setBounds({ x: rect.x, y: rect.y, width: rect.width, height: rect.height });
}

function showSight() {
  if (!settings.showSight || !sightWin || sightWin.isDestroyed()) return;
  positionSight();
  if (!sightWin.isVisible()) sightWin.showInactive();
  sightWin.webContents.send('sight:mode', busy ? 'scan' : 'idle');
}

function hideSight() {
  if (sightWin && !sightWin.isDestroyed() && sightWin.isVisible() && !busy) sightWin.hide();
}

function moveLensTo(x, y) {
  const pos = clampToWorkArea(x, y, LENS, LENS);
  if (!lensWin || lensWin.isDestroyed()) return;
  lensWin.setPosition(pos.x, pos.y);
  settings.lens = { x: pos.x, y: pos.y };

  if (dragging) {
    positionSight();
  }
}

function syncAfterMove() {
  saveSettings();
  positionSight();
  if (panelWin && panelWin.isVisible()) {
    const [w, h] = panelWin.getSize();
    const p = panelPosition(h);
    panelWin.setPosition(p.x, p.y);
  }
}

/* ──────────────────────────────────────────────────────────────────────
   Panel plumbing
   ────────────────────────────────────────────────────────────────────── */

function sendPanel(payload) {
  if (panelWin && !panelWin.isDestroyed()) panelWin.webContents.send('panel:state', payload);
}

function sendLens(payload) {
  if (lensWin && !lensWin.isDestroyed()) lensWin.webContents.send('lens:state', payload);
}

function showPanel(height) {
  if (!panelWin || panelWin.isDestroyed()) return;
  const h = Math.max(140, Math.round(height || panelWin.getSize()[1]));
  const p = panelPosition(h);
  panelWin.setBounds({ x: p.x, y: p.y, width: PANEL_W, height: h });
  if (!panelWin.isVisible()) panelWin.showInactive();

  clearTimeout(panelHideTimer);
  panelHideTimer = setTimeout(() => {
    if (!busy && panelWin && !panelWin.isDestroyed()) panelWin.hide();
  }, 30000);
}

function hidePanel() {
  clearTimeout(panelHideTimer);
  if (panelWin && !panelWin.isDestroyed()) panelWin.hide();
}

/* ──────────────────────────────────────────────────────────────────────
   The read pipeline — one still frame per trigger
   ────────────────────────────────────────────────────────────────────── */

async function readNow(trigger) {
  if (busy) return;

  if (!settings.granted) {
    createSetup();
    return;
  }

  busy = true;
  sendLens({ status: 'scanning' });
  sendPanel({
    status: 'scanning',
    trigger: trigger || 'manual',
    target: settings.target,
    targetName: languageName(settings.target),
  });
  showPanel(200);
  showSight();
  if (sightWin) sightWin.webContents.send('sight:mode', 'scan');

  const started = Date.now();

  try {
    const rect = readRect();
    const inset = CAPTURE_INSET;
    const { image } = await grabRegion(
      {
        x: rect.x + inset,
        y: rect.y + inset,
        width: rect.width - inset * 2,
        height: rect.height - inset * 2,
      },
      { scale: 2 },
    );

    const ocr = await readText(image.toPNG(), {
      langs: settings.ocrLangs,
      onProgress: (p) => sendPanel({ status: 'scanning', phase: p.phase, progress: p.progress }),
    });

    if (!ocr.text || !ocr.ok) {
      sendPanel({
        status: 'empty',
        target: settings.target,
        targetName: languageName(settings.target),
        confidence: ocr.confidence,
      });
      sendLens({ status: 'empty' });
      showPanel(190);
      return;
    }

    const key = ocr.text.replace(/\s+/g, ' ').trim();
    const translation = await translateText(ocr.text, settings.target);

    lastResult = {
      source: ocr.text,
      translated: translation.text,
      detected: translation.detected,
      detectedName: languageName(translation.detected),
      target: settings.target,
      targetName: languageName(settings.target),
      provider: translation.provider,
      confidence: ocr.confidence,
      ms: Date.now() - started,
      sameAsSource: key.toLowerCase() === (translation.text || '').replace(/\s+/g, ' ').trim().toLowerCase(),
    };
    lastReadKey = key;

    sendPanel({ status: 'result', ...lastResult });
    sendLens({ status: 'ok' });
    showPanel(250);
  } catch (err) {
    const code = err && err.code ? err.code : 'UNKNOWN';
    const messages = {
      NO_SCREEN_SOURCE: 'No screen was available to read.',
      EMPTY_CAPTURE: 'The screen returned an empty frame.',
      TRANSLATE_FAILED: 'Translation service unreachable — check your connection.',
      TIMEOUT: 'That took too long. Try again.',
    };
    sendPanel({ status: 'error', message: messages[code] || 'Something went wrong while reading.' });
    sendLens({ status: 'error' });
    showPanel(190);
  } finally {
    busy = false;
    setTimeout(() => sendLens({ status: 'idle' }), 900);
    if (sightWin && !sightWin.isDestroyed()) sightWin.webContents.send('sight:mode', 'idle');
  }
}

function stopAuto() {
  clearInterval(autoTimer);
  autoTimer = null;
  settings.auto = false;
  saveSettings();
  sendLens({ auto: false });
}

function startAuto() {
  if (!settings.granted) {
    createSetup();
    return;
  }
  clearInterval(autoTimer);
  settings.auto = true;
  saveSettings();
  sendLens({ auto: true });
  readNow('auto');
  autoTimer = setInterval(() => {
    if (!busy) readNow('auto');
  }, Math.max(900, settings.autoDelay));
}

function toggleAuto() {
  if (settings.auto) stopAuto();
  else startAuto();
}

/* ──────────────────────────────────────────────────────────────────────
   Context menu (right-click the lens)
   ────────────────────────────────────────────────────────────────────── */

function showLensMenu() {
  const targets = TARGETS.map((t) => ({
    label: t.label,
    type: 'radio',
    checked: settings.target === t.code,
    click: () => {
      settings.target = t.code;
      saveSettings();
      sendLens({ target: t.code, targetName: languageName(t.code) });
      sendPanel({ status: 'idle', target: t.code, targetName: languageName(t.code) });
      if (lastResult) readNow('target-change');
    },
  }));

  const sets = [
    { label: 'Latin (EN·ES·FR·DE)', value: 'eng+spa+fra+deu' },
    { label: 'Latin + Italian, Portuguese', value: 'eng+spa+fra+deu+ita+por' },
    { label: 'Latin + Dutch, Polish, Turkish', value: 'eng+spa+fra+deu+ita+por+nld+pol+tur' },
    { label: 'Cyrillic (RU·UK)', value: 'eng+rus+ukr' },
    { label: 'Greek', value: 'eng+ell' },
    { label: 'Arabic', value: 'eng+ara' },
    { label: 'Japanese', value: 'eng+jpn' },
    { label: 'Korean', value: 'eng+kor' },
    { label: 'Chinese (Simplified)', value: 'eng+chi_sim' },
  ];

  const menu = Menu.buildFromTemplate([
    { label: 'Read this area', accelerator: 'CmdOrCtrl+Alt+T', click: () => readNow('menu') },
    { type: 'separator' },
    {
      label: settings.auto ? 'Stop continuous reading' : 'Read continuously',
      type: 'checkbox',
      checked: settings.auto,
      click: () => toggleAuto(),
    },
    { label: 'Translate into', submenu: targets },
    {
      label: 'Text languages',
      submenu: sets.map((s) => ({
        label: s.label,
        type: 'radio',
        checked: settings.ocrLangs === s.value,
        click: () => {
          settings.ocrLangs = s.value;
          saveSettings();
        },
      })),
    },
    { type: 'separator' },
    {
      label: 'Show capture frame',
      type: 'checkbox',
      checked: settings.showSight,
      click: (item) => {
        settings.showSight = item.checked;
        saveSettings();
        if (item.checked) showSight();
        else if (sightWin) sightWin.hide();
      },
    },
    {
      label: 'Screen access…',
      click: () => createSetup(),
    },
    { type: 'separator' },
    { label: 'Quit Lucid', role: 'quit' },
  ]);

  menu.popup({ window: lensWin });
}

/* ──────────────────────────────────────────────────────────────────────
   IPC
   ────────────────────────────────────────────────────────────────────── */

ipcMain.on('read:now', () => readNow('lens'));

ipcMain.on('lens:drag-start', (_e, { dx, dy }) => {
  dragging = { dx, dy };
  showSight();
  clearInterval(dragTimer);
  dragTimer = setInterval(() => {
    const p = screen.getCursorScreenPoint();
    moveLensTo(p.x - dragging.dx, p.y - dragging.dy);
  }, 12);
});

ipcMain.on('lens:drag-end', () => {
  clearInterval(dragTimer);
  dragTimer = null;
  const wasDragging = !!dragging;
  dragging = null;
  if (wasDragging) syncAfterMove();
  setTimeout(() => hideSight(), 700);
});

ipcMain.on('lens:hover', (_e, hovering) => {
  if (hovering) showSight();
  else if (!dragging) hideSight();
});

ipcMain.on('lens:menu', () => showLensMenu());

ipcMain.on('lens:state-request', (e) => {
  e.sender.send('lens:state', {
    status: 'idle',
    auto: settings.auto,
    target: settings.target,
    targetName: languageName(settings.target),
    granted: settings.granted,
  });
});

ipcMain.on('panel:height', (_e, h) => {
  if (!panelWin || panelWin.isDestroyed()) return;
  const [, cur] = panelWin.getSize();
  if (Math.abs(cur - h) < 2) return;
  const p = panelPosition(h);
  panelWin.setBounds({ x: p.x, y: p.y, width: PANEL_W, height: Math.round(h) });
});

ipcMain.on('panel:close', () => hidePanel());
ipcMain.handle('panel:copy', (_e, text) => {
  require('electron').clipboard.writeText(String(text || ''));
  return true;
});
ipcMain.on('panel:retry', () => readNow('retry'));
ipcMain.on('panel:auto', () => toggleAuto());

ipcMain.handle('setup:grant', async () => {
  try {
    await probeCapture();
    settings.granted = true;
    saveSettings();
    if (setupWin && !setupWin.isDestroyed()) setupWin.close();
    showWelcomePanel();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: (err && err.code) || 'CAPTURE_FAILED' };
  }
});

ipcMain.handle('setup:state', () => ({
  granted: settings.granted,
  target: settings.target,
  targetName: languageName(settings.target),
  platform: process.platform,
  session: process.env.XDG_SESSION_TYPE || '',
}));

ipcMain.on('setup:close', () => {
  if (setupWin && !setupWin.isDestroyed()) setupWin.close();
});

ipcMain.on('app:quit', () => app.quit());

function showWelcomePanel() {
  sendPanel({
    status: 'ready',
    target: settings.target,
    targetName: languageName(settings.target),
  });
  showPanel(210);
  setTimeout(() => hidePanel(), 6000);
}

/* ──────────────────────────────────────────────────────────────────────
   Self-test (only with --selftest): boot the real app, capture its own
   windows, prove capture + IPC + rendering work, then exit.
   ────────────────────────────────────────────────────────────────────── */

function runSelfTest() {
  const outDir = path.join(__dirname, '..', 'screenshots', '_selftest');
  fs.mkdirSync(outDir, { recursive: true });

  for (const [name, win] of Object.entries({ lens: lensWin, sight: sightWin, panel: panelWin })) {
    if (!win) continue;
    win.webContents.on('console-message', (_e, level, message) => {
      if (level >= 2) console.log(`[selftest][${name}] console: ${message}`);
    });
    win.webContents.on('render-process-gone', (_e, details) => {
      console.log(`[selftest][${name}] renderer gone: ${details.reason}`);
    });
  }

  setTimeout(async () => {
    let captureOk = null;
    try {
      captureOk = await probeCapture();
    } catch (err) {
      captureOk = false;
    }
    // Only the boolean leaves this machine — no pixels are stored.
    console.log(`[selftest] one-shot screen capture: ${captureOk ? 'OK' : 'FAILED'}`);

    sendLens({ status: 'scanning' });
    sendPanel({ status: 'scanning' });
    showPanel(210);

    setTimeout(async () => {
      sendPanel({
        status: 'result',
        source:
          'El paquete llegó esta mañana. ¿Puedes pasar a recogerlo antes de las siete?',
        translated: 'The package arrived this morning. Can you pick it up before seven?',
        detected: 'es',
        detectedName: 'Spanish',
        target: 'en',
        targetName: 'English',
        provider: 'MyMemory',
        confidence: 96,
        ms: 812,
        sameAsSource: false,
      });
      sendLens({ status: 'ok' });
      showPanel(262);

      setTimeout(async () => {
        for (const [name, win] of Object.entries({
          lens: lensWin,
          panel: panelWin,
          sight: sightWin,
          setup: setupWin,
        })) {
          if (!win || win.isDestroyed()) {
            console.log(`[selftest] ${name}: not open`);
            continue;
          }
          try {
            const image = await win.webContents.capturePage();
            const file = path.join(outDir, `${name}.png`);
            fs.writeFileSync(file, image.toPNG());
            const [w, h] = win.getSize();
            const size = image.getSize();
            const bmp = image.getBitmap();
            const alphaOf = (px, py) => {
              const i = (py * size.width + px) * 4 + 3;
              // BGRA premultiplied
              return bmp[i];
            };
            const cornerAlpha = alphaOf(1, 1);
            console.log(
              `[selftest] ${name}: window ${w}x${h} -> ${path.relative(process.cwd(), file)} (${size.width}x${size.height}px, corner alpha ${cornerAlpha})`,
            );
          } catch (err) {
            console.log(`[selftest] ${name}: capture failed (${err.message})`);
          }
        }
        console.log('[selftest] done');
        app.exit(0);
      }, 1600);
    }, 1400);
  }, 2600);
}

/* ──────────────────────────────────────────────────────────────────────
   Lifecycle
   ────────────────────────────────────────────────────────────────────── */

if (process.platform === 'linux') {
  app.commandLine.appendSwitch('enable-transparent-visuals');
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (lensWin) {
      lensWin.showInactive();
      lensWin.moveTop();
    }
  });

  app.whenReady().then(() => {
    app.setName('Lucid');
    loadSettings();

    createLens();
    createSight();
    createPanel();

    const hotkeyOk = globalShortcut.register('CommandOrControl+Alt+T', () => readNow('hotkey'));
    globalShortcut.register('CommandOrControl+Alt+L', () => {
      if (!lensWin) return;
      if (lensWin.isVisible()) {
        hideSight();
        hidePanel();
        lensWin.hide();
      } else {
        lensWin.showInactive();
      }
    });

    if (!settings.granted) createSetup();

    // Warm the OCR engine in the background so the first read feels instant.
    if (settings.granted && !SELFTEST) {
      setTimeout(() => {
        readText(Buffer.alloc(0), { langs: settings.ocrLangs }).catch(() => {});
      }, 1200);
    }

    if (SELFTEST) {
      console.log(`[selftest] hotkey Ctrl+Alt+T registered: ${hotkeyOk ? 'yes' : 'NO (taken by another app)'}`);
      runSelfTest();
    }
  });

  app.on('window-all-closed', () => app.quit());

  app.on('will-quit', () => {
    globalShortcut.unregisterAll();
    clearInterval(dragTimer);
    clearInterval(autoTimer);
    shutdownOcr();
  });
}
