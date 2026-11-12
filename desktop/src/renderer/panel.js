'use strict';

// `window.lucid` is the Electron bridge. When this page is opened standalone
// (e.g. inside the preview harness) the calls become no-ops instead of throwing.
const api = window.lucid || {
  onPanelState: () => {},
  setPanelHeight: () => {},
  closePanel: () => {},
  copy: async () => false,
  retry: () => {},
  toggleAuto: () => {},
};

const el = {
  shell: document.getElementById('shell'),
  core: document.getElementById('core'),
  statusText: document.getElementById('statusText'),
  langs: document.getElementById('langs'),
  srcName: document.getElementById('srcName'),
  dstName: document.getElementById('dstName'),
  body: document.getElementById('body'),
  autoBtn: document.getElementById('autoBtn'),
  closeBtn: document.getElementById('closeBtn'),
};

let lastPayload = null;
let measureFrame = 0;

/* ── height reporting ──────────────────────────────────────────────── */

function reportHeight() {
  cancelAnimationFrame(measureFrame);
  measureFrame = requestAnimationFrame(() => {
    const h = Math.ceil(el.shell.getBoundingClientRect().height + 12);
    api.setPanelHeight(h);
    // Embedded in the preview harness: report the height to the parent page.
    if (!window.lucid && window.parent !== window) {
      window.parent.postMessage({ type: 'lucid-panel-height', height: h }, '*');
    }
  });
}

const observer = new ResizeObserver(reportHeight);
observer.observe(el.core);

/* ── helpers ───────────────────────────────────────────────────────── */

function setState(name) {
  document.body.className = name ? `is-${name}` : '';
}

function esc(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function clear() {
  el.body.replaceChildren();
}

function skeleton(phase) {
  const wrap = document.createElement('div');
  wrap.className = 'skeleton';
  wrap.innerHTML = `
    <div class="skeleton__line"></div>
    <div class="skeleton__line"></div>
    <div class="skeleton__line"></div>
    <div class="skeleton__meta">${phase === 'engine' ? 'Warming OCR engine…' : phase === 'language' ? 'Loading language data…' : 'Reading the screen…'}</div>
  `;
  return wrap;
}

function copyIcon() {
  return `<svg viewBox="0 0 16 16"><rect x="5.5" y="5.5" width="8" height="8" rx="2"/><path d="M10.5 5.5V4a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v4.5a2 2 0 0 0 2 2h1.5"/></svg>`;
}

/* ── renderers ─────────────────────────────────────────────────────── */

function renderScanning(payload) {
  setState('scanning');
  el.statusText.textContent = 'Reading';
  el.langs.hidden = true;
  clear();
  el.body.append(skeleton(payload.phase));
  syncAuto(payload);
}

function renderResult(payload) {
  setState('result');
  el.statusText.textContent = payload.confidence >= 70 ? 'Translated' : 'Translated · low clarity';

  el.langs.hidden = false;
  el.srcName.textContent = payload.detectedName || 'Detected';
  el.dstName.textContent = payload.targetName || 'English';

  clear();

  const translation = document.createElement('div');
  translation.className = 'translation';
  if ((payload.translated || '').length > 220) translation.classList.add('is-long');
  translation.textContent = payload.translated || '';

  el.body.append(translation);

  if (payload.source) {
    const original = document.createElement('div');
    original.className = 'original';
    original.textContent = payload.source;
    el.body.append(original);
  }

  if (payload.sameAsSource) {
    const note = document.createElement('div');
    note.className = 'notice__hint';
    note.style.marginTop = '8px';
    note.textContent = `Already ${payload.targetName || 'in that language'} — shown as read.`;
    el.body.append(note);
  }

  const foot = document.createElement('div');
  foot.className = 'foot';
  foot.innerHTML = `
    <div class="meta">
      <span>${esc(payload.provider || 'local OCR')}</span>
      <span class="meta__sep">·</span>
      <span>${payload.confidence ?? 0}% clarity</span>
      <span class="meta__sep">·</span>
      <span>${payload.ms ?? 0}ms</span>
    </div>
    <button class="copy" id="copyBtn">
      <span>Copy</span>
      <span class="copy__orb">${copyIcon()}</span>
    </button>
  `;
  el.body.append(foot);

  foot.querySelector('#copyBtn').addEventListener('click', async (event) => {
    const btn = event.currentTarget;
    await api.copy(payload.translated || '');
    btn.classList.add('is-done');
    btn.querySelector('span').textContent = 'Copied';
    setTimeout(() => {
      btn.classList.remove('is-done');
      btn.querySelector('span').textContent = 'Copy';
    }, 1600);
  });

  syncAuto(payload);
}

function renderEmpty(payload) {
  setState('empty');
  el.statusText.textContent = 'Nothing found';
  el.langs.hidden = true;
  clear();

  const notice = document.createElement('div');
  notice.className = 'notice';
  notice.innerHTML = `
    <strong>No readable text found</strong>
    <span>Move the lens over text — a chat, a caption, a menu — and press it again.</span>
    <div class="notice__hint">Clarity ${payload.confidence ?? 0}% · try larger text or a smaller window</div>
  `;
  el.body.append(notice);
  syncAuto(payload);
}

function renderError(payload) {
  setState('error');
  el.statusText.textContent = 'Something broke';
  el.langs.hidden = true;
  clear();

  const notice = document.createElement('div');
  notice.className = 'notice';
  notice.innerHTML = `<strong>${esc(payload.message || 'Something went wrong.')}</strong>`;
  el.body.append(notice);

  const foot = document.createElement('div');
  foot.className = 'foot';
  foot.innerHTML = `<div class="meta"><span>Lucid</span></div>
    <button class="copy" id="retryBtn"><span>Try again</span><span class="copy__orb">${copyIcon()}</span></button>`;
  el.body.append(foot);

  foot.querySelector('#retryBtn').addEventListener('click', () => api.retry());
  syncAuto(payload);
}

function renderReady(payload) {
  setState('ready');
  el.statusText.textContent = 'Screen access granted';
  el.langs.hidden = true;
  clear();

  const notice = document.createElement('div');
  notice.className = 'notice';
  notice.innerHTML = `
    <strong>You're ready</strong>
    <span>Drag the lens over any text and click it. One still frame is read, translated, and shown here — nothing is recorded.</span>
  `;
  el.body.append(notice);
  syncAuto(payload);
}

function syncAuto(payload) {
  if (payload && typeof payload.auto === 'boolean') {
    el.autoBtn.classList.toggle('is-on', payload.auto);
  }
  reportHeight();
}

/* ── wiring ───────────────────────────────────────────────────────── */

function handlePayload(payload) {
  if (!payload) return;
  lastPayload = payload;

  switch (payload.status) {
    case 'scanning':
      renderScanning(payload);
      break;
    case 'result':
      renderResult(payload);
      break;
    case 'empty':
      renderEmpty(payload);
      break;
    case 'error':
      renderError(payload);
      break;
    case 'ready':
      renderReady(payload);
      break;
    case 'idle':
      setState('idle');
      el.statusText.textContent = 'Ready';
      break;
    default:
      break;
  }

  reportHeight();
}

api.onPanelState(handlePayload);
window.__lucidPanel = handlePayload;

el.closeBtn.addEventListener('click', () => api.closePanel());

el.autoBtn.addEventListener('click', () => {
  api.toggleAuto();
  el.autoBtn.classList.toggle('is-on');
});

window.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') api.closePanel();
});

// first paint: nothing until the main process sends a state
setState('idle');
el.body.innerHTML =
  '<div class="notice"><strong>Lucid</strong><span>Point the lens at text and click.</span></div>';
reportHeight();
