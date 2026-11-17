import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { COUNTRY_LANGUAGES as defaultLanguages } from './default/language.config';

/**
 * The page the base build serves is what a screen reader and the browser tab have before the
 * application has started — which, on a slow link, is seconds: the start waits for the
 * deployment's settings and the texts for the bundle. So the page is in the default language and
 * carries the site's name as the bundle words it. Paths are relative to the workspace.
 */
const PAGE = 'ui-apps/info/src/index.html';

const read = (file: string) => readFileSync(file, 'utf8');
const parse = (html: string) => new DOMParser().parseFromString(html, 'text/html');

describe('the page the base build serves', () => {
  it('is in the default language and named as its bundle names the site', () => {
    const page = parse(read(PAGE));
    const lang = defaultLanguages.defaultLanguage;
    expect(page.documentElement.getAttribute('lang')).toBe(lang);
    expect(page.title).toBe(
      JSON.parse(read(`languages/${lang}/i18n/info/${lang}.json`))['SITE_TITLE'],
    );
  });
});
