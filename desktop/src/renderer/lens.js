'use strict';

const lens = document.getElementById('lens');

// Real bridge in the app; no-ops when this page is used standalone (preview).
const api = window.lucid || {
  readNow: () => {},
  dragStart: () => {},
  dragEnd: () => {},
  hover: () => {},
  menu: () => {},
  requestState: () => {},
  onLensState: () => {},
};

let pointerDown = false;
let dragging = false;
let startPointer = { x: 0, y: 0 };
let downAt = 0;

const DRAG_THRESHOLD = 4; // px before a press becomes a drag

lens.addEventListener('pointerdown', (event) => {
  if (event.button !== 0) return;

  pointerDown = true;
  dragging = false;
  startPointer = { x: event.screenX, y: event.screenY };
  downAt = performance.now();
  lens.setPointerCapture(event.pointerId);
});

lens.addEventListener('pointermove', (event) => {
  if (!pointerDown) return;

  const dx = event.screenX - startPointer.x;
  const dy = event.screenY - startPointer.y;

  if (!dragging && Math.hypot(dx, dy) > DRAG_THRESHOLD) {
    dragging = true;
    // Offset of the cursor inside our own window.
    api.dragStart(event.screenX - window.screenX, event.screenY - window.screenY);
  }
});

function endPress(event) {
  if (!pointerDown) return;
  pointerDown = false;

  if (lens.hasPointerCapture?.(event.pointerId)) {
    lens.releasePointerCapture(event.pointerId);
  }

  if (dragging) {
    dragging = false;
    api.dragEnd();
    return;
  }

  const quick = performance.now() - downAt < 700;
  if (quick) api.readNow();
}

lens.addEventListener('pointerup', endPress);
lens.addEventListener('pointercancel', () => {
  if (pointerDown && dragging) api.dragEnd();
  pointerDown = false;
  dragging = false;
});

lens.addEventListener('pointerenter', () => api.hover(true));
lens.addEventListener('pointerleave', () => {
  if (!dragging) api.hover(false);
});

lens.addEventListener('contextmenu', (event) => {
  event.preventDefault();
  api.menu();
});

/* ── state ─────────────────────────────────────────────────────────── */

const timers = {};

function setStatus(status) {
  lens.classList.remove('is-scanning', 'is-ok', 'is-error');

  clearTimeout(timers.ok);
  clearTimeout(timers.error);

  if (status === 'scanning') lens.classList.add('is-scanning');
  if (status === 'ok') {
    lens.classList.add('is-ok');
    timers.ok = setTimeout(() => lens.classList.remove('is-ok'), 1600);
  }
  if (status === 'error') {
    lens.classList.add('is-error');
    timers.error = setTimeout(() => lens.classList.remove('is-error'), 2200);
  }
}

function applyState(state) {
  if (!state) return;
  if (state.status) setStatus(state.status);
  if (typeof state.auto === 'boolean') lens.dataset.auto = String(state.auto);
  if (state.targetName) lens.title = `Lucid — click to read into ${state.targetName}`;
  if (state.granted === false) lens.title = 'Lucid — click to set up screen access';
}

api.onLensState(applyState);
api.requestState();
window.__lucidLens = applyState;
