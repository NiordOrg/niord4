/**
 * The script of the page a reader is given when a PDF cannot be made just now. The backend makes
 * only a few PDFs at a time for readers without an identity and answers every further request 503
 * at once; the server answers that 503 with pdf-busy.html instead of the backend's error, at the
 * address the reader asked for (`ui-apps/info/nginx/default.conf.template` says where).
 *
 * The page is written in English, and this script puts it in the language the PDF was asked in:
 * both PDF addresses name it in `lang`. It reads that language's bundle, the one the application
 * reads, and takes the page's words from it — the sentence, the link that asks again and the
 * title. A language the build ships no bundle for, a bundle that does not arrive or one without the
 * sentence leaves the page in English, which it says it is in.
 *
 * The application's own code is bundled into its chunks, which a page without the application
 * cannot load, so this file stands alone, and `scripts/info-pdf-busy.test.mjs` holds it to the
 * bundle and to the page.
 */

/** The bundle key of each element the script words, by the element's id. */
export const WORDS = {
  'pdf-busy-text': 'PDF_BUSY',
  'pdf-busy-retry': 'MENU_PRINT',
  'pdf-busy-title': 'SITE_TITLE',
};

/** The language the PDF was asked in: its address's `lang`, when that is a language code. */
export function requestedLanguage(search) {
  const lang = new URLSearchParams(search).get('lang');
  return lang !== null && /^[a-z]{2,3}$/.test(lang) ? lang : null;
}

/**
 * Words the page from a bundle, and says which language it is then in. A bundle without the
 * sentence changes nothing: the page stays English and says so. A word the bundle lacks keeps its
 * English, which a build never ships: each bundle is staged complete.
 *
 * @returns whether the page was worded from the bundle
 */
export function wordPage(document, lang, bundle) {
  if (typeof bundle?.[WORDS['pdf-busy-text']] !== 'string') return false;
  for (const [id, key] of Object.entries(WORDS)) {
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
