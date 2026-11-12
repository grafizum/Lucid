'use strict';

const { desktopCapturer, screen, nativeImage } = require('electron');

/**
 * Every call takes exactly ONE still frame. There is no stream, no recording,
 * no background polling. The user grants access a single time in the setup
 * card; after that we only look at the screen when a read is triggered.
 */

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, n));
}

/**
 * Grab a single rectangular region of the display under `rect`.
 * Coordinates are in DIP (same space as Electron's screen API).
 *
 * @param {{x:number,y:number,width:number,height:number}} rect
 * @param {{scale?:number}} [opts] extra upscale factor for OCR legibility
 * @returns {Promise<{image: Electron.NativeImage, scaleFactor: number}>}
 */
async function grabRegion(rect, opts = {}) {
  const cx = Math.round(rect.x + rect.width / 2);
  const cy = Math.round(rect.y + rect.height / 2);
  const display = screen.getDisplayNearestPoint({ x: cx, y: cy });
  const scaleFactor = display.scaleFactor || 1;

  // Ask for the biggest thumbnail we could possibly need (this display only).
  const thumbSize = {
    width: Math.round(display.size.width * scaleFactor),
    height: Math.round(display.size.height * scaleFactor),
  };

  const sources = await desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: thumbSize,
    fetchWindowIcons: false,
  });

  if (!sources || sources.length === 0) {
    throw Object.assign(new Error('NO_SCREEN_SOURCE'), { code: 'NO_SCREEN_SOURCE' });
  }

  let source =
    sources.find((s) => String(s.display_id) === String(display.id)) ||
    sources.find((s) => /screen|display|monitor/i.test(s.name || '')) ||
    sources[0];

  if (!source || source.thumbnail.isEmpty()) {
    throw Object.assign(new Error('EMPTY_CAPTURE'), { code: 'EMPTY_CAPTURE' });
  }

  const full = source.thumbnail;
  const { width: iw, height: ih } = full.getSize();

  // Map DIP -> image pixels for the display we landed on.
  const kx = iw / display.size.width;
  const ky = ih / display.size.height;

  const localX = rect.x - display.bounds.x;
  const localY = rect.y - display.bounds.y;

  const x = clamp(Math.round(localX * kx), 0, Math.max(0, iw - 2));
  const y = clamp(Math.round(localY * ky), 0, Math.max(0, ih - 2));
  const width = clamp(Math.round(rect.width * kx), 2, iw - x);
  const height = clamp(Math.round(rect.height * ky), 2, ih - y);

  let image = full.crop({ x, y, width, height });

  // Small text is the enemy of OCR. Upscale when the crop is smaller than the
  // sweet spot (~2x) — cheapest possible accuracy win.
  const up = opts.scale || 2;
  const targetW = Math.min(Math.round(width * up), 2400);
  if (targetW > width) {
    image = image.resize({ width: targetW, quality: 'best' });
  }

  return { image, scaleFactor };
}

/** One frame of the whole display — used by the setup card only. */
async function probeCapture() {
  const display = screen.getPrimaryDisplay();
  const { image } = await grabRegion(
    { x: display.bounds.x, y: display.bounds.y, width: 240, height: 140 },
    { scale: 1 },
  );
  return !image.isEmpty();
}

module.exports = { grabRegion, probeCapture };
