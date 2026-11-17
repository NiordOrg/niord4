import { describe, expect, it } from 'vitest';
import { resolveLanguages } from './language-settings.service';
import type { LanguageConfig } from '../config/app-config.interface';

const defaults: LanguageConfig = {
  defaultLanguage: 'da',
  siteLanguages: ['da', 'en'],
  modelLanguages: ['da', 'en', 'kl'],
  uiLanguages: ['da', 'en'],
  availableLanguages: ['da', 'en', 'kl'].map((value) => ({
    value,
    label: value,
    locale: value,
    flagPath: `assets/img/flags/${value}.png`,
  })),
};

describe('runtime language settings', () => {
  it('has two UI languages and three model languages without a KL UI bundle', () => {
    const result = resolveLanguages(defaults);
    expect(result.uiLanguages.map((l) => l.value)).toEqual(['da', 'en']);
    expect(result.modelLanguages.map((l) => l.value)).toEqual(['da', 'en', 'kl']);
    expect(result.defaultLanguage).toBe('da');
  });
  it('lets persisted settings override deployment defaults independently of list order', () => {
    const result = resolveLanguages(defaults, {
      siteLanguages: ['en'],
      modelLanguages: '["kl","en","da"]',
      defaultLanguage: 'da',
    });
    expect(result.uiLanguages.map((l) => l.value)).toEqual(['en']);
    expect(result.modelLanguages.map((l) => l.value)).toEqual(['kl', 'en', 'da']);
    expect(result.defaultLanguage).toBe('da');
  });
  it('uses deployment defaults when settings are absent or malformed', () => {
    expect(resolveLanguages(defaults, { modelLanguages: [], siteLanguages: 'invalid' })).toEqual(
      resolveLanguages(defaults),
    );
  });
  it('always supplies a shipped UI language when settings and country defaults are unsupported', () => {
    const result = resolveLanguages(
      { ...defaults, siteLanguages: ['fr'] },
      { siteLanguages: ['kl'] },
    );
    expect(result.uiLanguages.map((l) => l.value)).toEqual(['da']);
    expect(result.modelLanguages.map((l) => l.value)).toEqual(['da', 'en', 'kl']);
  });
  it('offers as UI languages only those whose bundle the build ships', () => {
    const englishOnly: LanguageConfig = { ...defaults, uiLanguages: ['en'] };
    const result = resolveLanguages(englishOnly, { siteLanguages: ['da', 'en'] });
    expect(result.uiLanguages.map((l) => l.value)).toEqual(['en']);
  });
  it('gives a language the configuration does not name its code as label and no flag', () => {
    const result = resolveLanguages(defaults, { modelLanguages: ['da', 'fo'] });
    expect(result.modelLanguages[1]).toEqual({ value: 'fo', label: 'FO', locale: 'fo' });
    expect(result.modelLanguages[1].flagPath).toBeUndefined();
  });
});
