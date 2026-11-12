'use strict';

const api = window.lucid;

const grantBtn = document.getElementById('grantBtn');
const laterBtn = document.getElementById('laterBtn');
const errorEl = document.getElementById('error');
const hintEl = document.getElementById('hint');

const ERRORS = {
  NO_SCREEN_SOURCE:
    'Your desktop didn\u2019t hand over a screen to read. On Wayland, pick a screen in the system dialog when it appears, then try again.',
  EMPTY_CAPTURE: 'The screen returned an empty frame. Check that you picked a screen, then try again.',
  CAPTURE_FAILED: 'Screen access couldn\u2019t be confirmed. Try again.',
};

function showError(code) {
  errorEl.hidden = false;
  errorEl.textContent = ERRORS[code] || ERRORS.CAPTURE_FAILED;
}

grantBtn.addEventListener('click', async () => {
  grantBtn.disabled = true;
  grantBtn.querySelector('span').textContent = 'Requesting…';
  errorEl.hidden = true;

  const result = await api.grant();

  if (result && result.ok) {
    grantBtn.querySelector('span').textContent = 'Granted';
    return;
  }

  grantBtn.disabled = false;
  grantBtn.querySelector('span').textContent = 'Try again';
  showError(result && result.error);
});

laterBtn.addEventListener('click', () => api.closeSetup());

api.setupState().then((state) => {
  if (!state) return;

  if (state.granted) {
    grantBtn.querySelector('span').textContent = 'Access already granted';
    grantBtn.disabled = true;
  }

  if (state.session === 'wayland') {
    hintEl.innerHTML =
      'You\u2019re on Wayland: your system may show a “share your screen” dialog on each read. Pick <b>Entire screen</b> once and it will be remembered.';
  }
});
