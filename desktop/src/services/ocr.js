'use strict';

const path = require('node:path');
const { app } = require('electron');

/**
 * OCR runs fully locally in a tesseract.js (WebAssembly) worker.
 * No system packages, no image ever leaves the machine.
 */

let worker = null;
let workerLangs = null;
let booting = null;

const MIN_CONFIDENCE = 35;

function cachePath() {
  return path.join(app.getPath('userData'), 'tessdata');
}

function loadWorker(langs, onProgress) {
  // Lazy require so the app boots instantly.
  const { createWorker } = require('tesseract.js');

  return createWorker(langs, 1, {
    cachePath: cachePath(),
    logger: (m) => {
      if (onProgress) {
        if (m.status === 'loading tesseract core') onProgress({ phase: 'engine', progress: m.progress });
        else if (m.status === 'loading language traineddata') onProgress({ phase: 'language', progress: m.progress });
      }
    },
  });
}

/**
 * @param {Buffer} pngBuffer  a PNG of the region to read
 * @param {{langs?: string, onProgress?: Function}} [opts]
 */
async function readText(pngBuffer, opts = {}) {
  const langs = opts.langs || 'eng';

  if (!worker || workerLangs !== langs) {
    if (worker) {
      try {
        await worker.terminate();
      } catch {
        /* ignore */
      }
      worker = null;
    }
    if (!booting) {
      booting = loadWorker(langs, opts.onProgress)
        .then((w) => {
          worker = w;
          workerLangs = langs;
          return w;
        })
        .finally(() => {
          booting = null;
        });
    }
    await booting;
  }

  const { data } = await worker.recognize(pngBuffer);

  // Tesseract loves to wrap a single visual line into several lines when the
  // capture is wide. Re-join lines that were broken mid-sentence.
  const raw = String(data.text || '')
    .replace(/\u000c/g, '')
    .replace(/[|]/g, 'I')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .join('\n');

  return {
    text: raw,
    confidence: Math.round(data.confidence || 0),
    words: data.words ? data.words.length : 0,
    ok: raw.length >= 2 && (data.confidence || 0) >= MIN_CONFIDENCE,
  };
}

async function warmUp(langs, onProgress) {
  await readText(Buffer.alloc(0), { langs, onProgress }).catch(() => {});
}

async function shutdown() {
  if (worker) {
    try {
      await worker.terminate();
    } catch {
      /* ignore */
    }
    worker = null;
    workerLangs = null;
  }
}

module.exports = { readText, warmUp, shutdown };
