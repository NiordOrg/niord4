/**
 * The script of the two pages a reader is given when a PDF cannot be made. The backend makes only
 * a few PDFs at a time for readers without an identity and answers every further request 503 at
 * once; the server answers that 503 with pdf-busy.html instead of the backend's error, and any
 * other failure of a PDF — the message no longer published, an error while the document was made,
 * a backend that does not answer — with pdf-failed.html, at the address the reader asked for
 * (`ui-apps/info/nginx/default.conf.template` says where and for which answers).
 *
 * Each page is written in English, and this script puts it in the language the PDF was asked in:
 * both PDF addresses name it in `lang`. It reads that language's bundle, the one the application
 * reads, and takes the page's words from it — the page's sentence, the link that asks again and
 * the title. A language the build ships no bundle for, a bundle that does not arrive or one without
 * the page's sentence leaves the page in English, which it says it is in.
 *
 * The application's own code is bundled into its chunks, which a page without the application
 * cannot load, so this file stands alone, and `scripts/info-pdf-busy.test.mjs` holds it to the
 * bundle and to the pages.
 */

/** The bundle key of each page's sentence, by the sentence's element id; a page holds one. */
export const SENTENCES = {
  'pdf-busy-text': 'PDF_BUSY',
  'pdf-failed-text': 'DOCUMENT_LOAD_FAILED',
};

/** The bundle key of each element both pages hold, by the element's id. */
export const SHARED_WORDS = {
  'pdf-retry': 'MENU_PRINT',
  'pdf-title': 'SITE_TITLE',
};

/** The language the PDF was asked in: its address's `lang`, when that is a language code. */
export function requestedLanguage(search) {
  const lang = new URLSearchParams(search).get('lang');
  return lang !== null && /^[a-z]{2,3}$/.test(lang) ? lang : null;
}

/**
 * Words the page from a bundle, and says which language it is then in. A bundle without the page's
 * sentence changes nothing: the page stays English and says so. A word the bundle lacks keeps its
 * English, which a build never ships: each bundle is staged complete.
 *
 * @returns whether the page was worded from the bundle
 */
export function wordPage(document, lang, bundle) {
  const sentence = Object.entries(SENTENCES).find(([id]) => document.getElementById(id));
  if (!sentence || typeof bundle?.[sentence[1]] !== 'string') return false;
  for (const [id, key] of [sentence, ...Object.entries(SHARED_WORDS)]) {
    const element = document.getElementById(id);
    const value = bundle[key];
    if (element && typeof value === 'string' && value !== '') element.textContent = value;
  }
  document.documentElement.lang = lang;
  return true;
}

/** Reads the bundle of the language asked in, and words the page from it; never throws. */
export async function localise(document, search, fetchBundle) {
  const lang = requestedLanguage(search);
  if (lang === null) return false;
  try {
    const response = await fetchBundle(`/assets/i18n/${lang}.json`);
    return response.ok ? wordPage(document, lang, await response.json()) : false;
  } catch {
    return false;
  }
}

// Guarded, so that a test can import the functions outside a browser.
if (typeof location === 'object' && typeof document === 'object') {
  void localise(document, location.search, (url) => fetch(url));
}
