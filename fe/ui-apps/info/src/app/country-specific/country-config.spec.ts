import { existsSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { COUNTRY_INFO as defaultInfo } from './default/info.config';
import { COUNTRY_LANGUAGES as defaultLanguages } from './default/language.config';
import type { InfoConfig } from '../niord-info.config';
import type { LanguageConfig } from '../core/config/language-config';

/**
 * The base product's default configurations, which a country's set-up replaces with the files it
 * generates from its manifest. Nothing swaps them in the base build, so a field left out is not a
 * compile error anywhere: it is a missing flag, a missing time zone or a site in the wrong
 * language at release time. This spec is not in the zip, so it holds the default files alone.
 */

/**
 * Where a configuration's files are found: a language's bundle and flag in its pack, which the
 * build stages; anything else in the default overlay or the product's own assets. Relative to
 * the workspace folder, where the runner starts.
 */
const PACKS = 'languages';
const ASSETS = 'ui-apps/info/public/assets';

/** Everything else in `InfoConfig` is required of every configuration. */
const OPTIONAL_INFO_FIELDS = ['footerComponent', 'backendHosts', 'accessibilityStatement'];
const REQUIRED_INFO_FIELDS = ['timeZone', 'executionMode', 'wmsLayer', 'rootAreas'];

const languageConfigs: Record<string, LanguageConfig> = {
  default: defaultLanguages,
};

const infoConfigs: Record<string, InfoConfig> = {
  default: defaultInfo,
};

/**
 * A file the build of `country` serves at `assets/<path>`: a bundle or a flag from its language's
 * pack, anything else from the country's overlay or the product's assets.
 */
function shipped(country: string, path: string): boolean {
  const relative = path.replace(/^assets\//, '');
  const bundle = /^i18n\/([a-z]{2,3})\.json$/.exec(relative)?.[1];
  const flag = /^img\/flags\/([a-z]{2,3})\.png$/.exec(relative)?.[1];
  if (bundle) return existsSync(`${PACKS}/${bundle}/i18n/info/${bundle}.json`);
  if (flag) return existsSync(`${PACKS}/${flag}/img/flags/${flag}.png`);
  return existsSync(`${ASSETS}/${country}/${relative}`) || existsSync(`${ASSETS}/${relative}`);
}

describe('country language configurations', () => {
  for (const [country, config] of Object.entries(languageConfigs)) {
    const shippedLanguages = config.languages.map((option) => option.value);

    it(`${country} offers at least one language and defaults to one of them`, () => {
      expect(config.siteLanguages.length).toBeGreaterThan(0);
      expect(config.siteLanguages).toContain(config.defaultLanguage);
    });

    it(`${country} offers only languages it ships a bundle for`, () => {
      for (const code of config.siteLanguages) expect(shippedLanguages).toContain(code);
      expect(shippedLanguages).toContain(config.defaultLanguage);
    });

    // The loader asks for a branding file in exactly these languages: one named here that the
    // build does not ship is a 404 on every load, and one it ships but does not name is never read.
    it(`${country} names the branding it ships, in languages it ships a bundle for`, () => {
      const branded = config.brandingLanguages ?? shippedLanguages;
      for (const code of branded) {
        expect(shippedLanguages).toContain(code);
        expect(shipped(country, `assets/branding/${code}.json`), code).toBe(true);
      }
      for (const code of shippedLanguages.filter((code) => !branded.includes(code))) {
        expect(existsSync(`${ASSETS}/${country}/branding/${code}.json`), code).toBe(false);
      }
    });

    it(`${country} lists every language once`, () => {
      expect(new Set(config.siteLanguages).size).toBe(config.siteLanguages.length);
      expect(new Set(shippedLanguages).size).toBe(shippedLanguages.length);
    });

    it(`${country} names a bundle and a flag that are there`, () => {
      for (const option of config.languages) {
        expect(shipped(country, `assets/i18n/${option.value}.json`), option.value).toBe(true);
        if (option.flagPath) expect(shipped(country, option.flagPath), option.flagPath).toBe(true);
      }
    });
  }
});

describe('country info configurations', () => {
  for (const [country, config] of Object.entries(infoConfigs)) {
    it(`${country} names a time zone, a mode and the WMS decision`, () => {
      expect(config.timeZone).toMatch(/^[A-Za-z]+(\/[A-Za-z_+-]+)*$/);
      expect(Intl.DateTimeFormat(undefined, { timeZone: config.timeZone })).toBeTruthy();
      expect(config.executionMode.length).toBeGreaterThan(0);
      expect(typeof config.wmsLayer).toBe('boolean');
    });

    it(`${country} describes every root area completely`, () => {
      expect(Array.isArray(config.rootAreas)).toBe(true);
      for (const area of config.rootAreas) {
        expect(typeof area.areaId).toBe('string');
        expect(area.areaId.length).toBeGreaterThan(0);
        expect(typeof area.latitude).toBe('number');
        expect(typeof area.longitude).toBe('number');
        expect(typeof area.zoomLevel).toBe('number');
      }
    });

    // A repository link is matched on the host exactly as an address writes it, so a listed
    // host with a scheme, a path or a capital letter would match nothing.
    it(`${country} lists each backend host as an address writes it`, () => {
      for (const host of config.backendHosts ?? [])
        expect(new URL(`https://${host}`).host).toBe(host);
    });

    // A footer links its accessibility statement, and the map's credits do too.
    it(`${country} names an https accessibility statement when it has a footer of its own`, () => {
      if (!config.footerComponent) return;
      expect(new URL(config.accessibilityStatement ?? '').protocol).toBe('https:');
    });

    it(`${country} carries every required field and no field outside the configuration`, () => {
      const names = Object.keys(config);
      for (const required of REQUIRED_INFO_FIELDS) expect(names).toContain(required);
      for (const name of names)
        expect([...REQUIRED_INFO_FIELDS, ...OPTIONAL_INFO_FIELDS]).toContain(name);
    });
  }
});
