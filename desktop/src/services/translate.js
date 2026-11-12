'use strict';

/**
 * Translation backends, tried in order.
 *
 * 1. MyMemory  — free, no API key, supports source auto-detect + reports the
 *                detected language. ~5k words/day anonymous.
 * 2. Google gtx — the public endpoint; occasionally rate limits.
 * 3. Lingva    — community Google proxy.
 *
 * Every backend returns { text, detected, provider }.
 */

const MAX_CHARS = 480; // MyMemory's practical per-request limit

const TARGETS = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Spanish' },
  { code: 'fr', label: 'French' },
  { code: 'de', label: 'German' },
  { code: 'pt', label: 'Portuguese' },
  { code: 'it', label: 'Italian' },
  { code: 'nl', label: 'Dutch' },
  { code: 'pl', label: 'Polish' },
  { code: 'tr', label: 'Turkish' },
  { code: 'ru', label: 'Russian' },
  { code: 'uk', label: 'Ukrainian' },
  { code: 'ar', label: 'Arabic' },
  { code: 'fa', label: 'Persian' },
  { code: 'hi', label: 'Hindi' },
  { code: 'ja', label: 'Japanese' },
  { code: 'ko', label: 'Korean' },
  { code: 'zh-CN', label: 'Chinese (Simp.)' },
  { code: 'zh-TW', label: 'Chinese (Trad.)' },
  { code: 'id', label: 'Indonesian' },
  { code: 'vi', label: 'Vietnamese' },
];

function clean(text) {
  return String(text || '')
    .replace(/\r/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

async function withTimeout(promise, ms) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('TIMEOUT')), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function viaMyMemory(text, target) {
  const url =
    'https://api.mymemory.translated.net/get?q=' +
    encodeURIComponent(text) +
    '&langpair=' +
    encodeURIComponent(`Autodetect|${target}`);

  const res = await fetch(url, { headers: { 'User-Agent': 'Lucid/1.0' } });
  if (!res.ok) throw new Error(`MYMEMORY_HTTP_${res.status}`);

  const json = await res.json();
  const data = json?.responseData;
  if (!data || typeof data.translatedText !== 'string') throw new Error('MYMEMORY_SHAPE');

  const upper = data.translatedText.toUpperCase();
  if (upper.includes('MYMEMORY WARNING') || upper.includes('INVALID LANGUAGE PAIR')) {
    throw new Error('MYMEMORY_QUOTA');
  }

  return {
    text: data.translatedText,
    detected: (data.detectedLanguage || '').toLowerCase() || null,
    provider: 'MyMemory',
  };
}

async function viaGoogle(text, target) {
  const url =
    'https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&ie=UTF-8&oe=UTF-8&sl=auto&tl=' +
    encodeURIComponent(target) +
    '&q=' +
    encodeURIComponent(text);

  const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
  if (!res.ok) throw new Error(`GOOGLE_HTTP_${res.status}`);

  const json = await res.json();
  const out = Array.isArray(json?.[0])
    ? json[0].map((chunk) => chunk?.[0] || '').join('')
    : '';
  if (!out) throw new Error('GOOGLE_SHAPE');

  return { text: out, detected: (json?.[2] || '').toLowerCase() || null, provider: 'Google' };
}

async function viaLingva(text, target) {
  const url =
    'https://lingva.ml/api/v1/auto/' +
    encodeURIComponent(target) +
    '/' +
    encodeURIComponent(text);

  const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
  if (!res.ok) throw new Error(`LINGVA_HTTP_${res.status}`);

  const json = await res.json();
  if (!json?.translation) throw new Error('LINGVA_SHAPE');

  return {
    text: json.translation,
    detected: (json?.info?.detectedSource || '').toLowerCase() || null,
    provider: 'Lingva',
  };
}

/**
 * @param {string} raw  recognised on-screen text
 * @param {string} target  target language code
 */
async function translateText(raw, target = 'en') {
  const text = clean(raw);
  if (!text) return { text: '', detected: null, provider: null, empty: true };

  const clipped = text.length > MAX_CHARS ? text.slice(0, MAX_CHARS) : text;
  const attempts = [viaMyMemory, viaGoogle, viaLingva];
  let lastError = null;

  for (const fn of attempts) {
    try {
      const result = await withTimeout(fn(clipped, target), 9000);
      return { ...result, text: clean(result.text), source: text, truncated: text.length > MAX_CHARS };
    } catch (err) {
      lastError = err;
    }
  }

  throw Object.assign(new Error('TRANSLATE_FAILED'), { code: 'TRANSLATE_FAILED', cause: lastError });
}

/** "es" -> "Spanish"; falls back to the raw code. */
function languageName(code) {
  if (!code) return 'Unknown';
  try {
    const base = code.split('-')[0];
    const dn = new Intl.DisplayNames(['en'], { type: 'language' });
    return dn.of(base) || code;
  } catch {
    return code;
  }
}

module.exports = { translateText, languageName, TARGETS, MAX_CHARS };
