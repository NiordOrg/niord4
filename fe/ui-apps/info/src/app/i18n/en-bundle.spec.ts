import { readdirSync, readFileSync } from 'node:fs';

import { SUPPORTED_PATTERN_TOKENS } from '../shared/utils/zoned-date';

/**
 * The public site's English bundle: the language each key is written in, and the one the build
 * fills every other bundle's gaps from. Every other pack is held to it by the pack check
 * (`npm run languages:check`, which a country's set-up runs on its packs): the same keys,
 * placeholders, markup, date patterns and lists. This spec holds English itself.
 *
 * Read from disk rather than through an import, so it asserts the pack's file and not what a
 * bundler resolved. Paths are relative to the workspace folder, where the runner starts.
 */
const EN_BUNDLE = 'languages/en/i18n/info/en.json';
const SRC_DIR = 'ui-apps/info/src';

const en: Record<string, string> = JSON.parse(readFileSync(EN_BUNDLE, 'utf8'));

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? sourceFiles(`${dir}/${entry.name}`) : [`${dir}/${entry.name}`],
  );
}

/** Everything outside a single-quoted run — the part of a pattern that is tokens. */
function unquotedRuns(pattern: string): string {
  return pattern.replace(/'[^']*'/g, ' ');
}

describe('the English bundle', () => {
  it('has words', () => {
    expect(Object.keys(en).length).toBeGreaterThan(0);
  });

  it('leaves no value empty', () => {
    for (const [key, value] of Object.entries(en)) {
      expect(typeof value, key).toBe('string');
      expect(value.trim(), key).not.toBe('');
    }
  });

  const ARITIES = {
    DATE_MONTHS_WIDE: 12,
    DATE_MONTHS_ABBREVIATED: 12,
    DATE_DAYS_WIDE: 7,
    DATE_DAYS_SHORT: 7,
  };

  it('names every month and every weekday', () => {
    for (const [key, count] of Object.entries(ARITIES)) {
      const words = en[key].split('|');
      expect(words, key).toHaveLength(count);
      for (const word of words) expect(word.trim(), key).not.toBe('');
    }
  });

  // The renderer understands a deliberately small set of tokens and copies anything else
  // through verbatim, so a mistyped pattern would print itself into a date instead of failing.
  for (const key of ['SOURCE_DATE_FORMAT', 'PRINT_DATE_FORMAT']) {
    it(`${key} uses only tokens the renderer knows`, () => {
      const pattern = en[key];
      expect(typeof pattern).toBe('string');
      const letterRuns = unquotedRuns(pattern).match(/[A-Za-z]+/g) ?? [];
      const unsupported = letterRuns.filter((run) => !SUPPORTED_PATTERN_TOKENS.includes(run));
      expect({ pattern, unsupported }).toEqual({ pattern, unsupported: [] });
    });
  }

  // A language is named by its entry in the country language configuration, never by a bundle
  // key; the contents check holds every configuration to its pack's own label.
  it('leaves no reference to a key the bundles no longer carry', () => {
    const gone = [
      'GENERAL_MSGS',
      'ACTIVE_DATE',
      'REMOVE_PUBLICATION',
      'LANGUAGE_DA',
      'LANGUAGE_EN',
      'LANGUAGE_KL',
    ];
    const offenders: string[] = [];
    for (const file of sourceFiles(SRC_DIR)) {
      if (file.endsWith('en-bundle.spec.ts')) continue;
      const text = readFileSync(file, 'utf8');
      for (const key of gone) if (text.includes(key)) offenders.push(`${key} in ${file}`);
    }
    expect(offenders).toEqual([]);
  });
});
