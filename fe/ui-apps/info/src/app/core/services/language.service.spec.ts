import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/common';
import { TranslocoService } from '@jsverse/transloco';
import { LanguageService } from './language.service';
import type { NiordInfoConfig } from '../../niord-info.config';
import { provideTestNiordConfig } from '../../testing/test-config';

/** Where the reader's choice is kept between visits. */
const STORED = 'niord-info.language';

describe('LanguageService', () => {
  let translocoSpy: {
    setActiveLang: ReturnType<typeof vi.fn>;
    setAvailableLangs: ReturnType<typeof vi.fn>;
  };

  /** A fresh service, as a visit that opens on `search` starts one. */
  function start(options: { search?: string; config?: Partial<NiordInfoConfig> } = {}) {
    TestBed.resetTestingModule();
    translocoSpy = { setActiveLang: vi.fn(), setAvailableLangs: vi.fn() };
    const providers: unknown[] = [
      provideTestNiordConfig(options.config),
      { provide: TranslocoService, useValue: translocoSpy },
    ];
    if (options.search !== undefined) {
      providers.push({
        provide: DOCUMENT,
        useValue: {
          ...document,
          defaultView: { location: { search: options.search } },
          documentElement: document.documentElement,
        },
      });
    }
    TestBed.configureTestingModule({ providers: providers as never[] });
    return TestBed.inject(LanguageService);
  }

  /** Three languages, with Danish as the deployment's default rather than the first. */
  const KL_DA_EN_DEFAULT_DA = { languages: ['kl', 'da', 'en'], defaultLanguage: 'da' };

  beforeEach(() => {
    localStorage.removeItem(STORED);
    localStorage.removeItem('language');
  });

  afterEach(() => {
    localStorage.removeItem(STORED);
    localStorage.removeItem('language');
  });

  it("starts a first visit in the deployment's default, not the first language of the list", () => {
    expect(start({ config: KL_DA_EN_DEFAULT_DA }).language()).toBe('da');
  });

  it("starts in the reader's remembered choice", () => {
    localStorage.setItem(STORED, 'en');
    expect(start({ config: KL_DA_EN_DEFAULT_DA }).language()).toBe('en');
  });

  it('keeps the choice a reader made under the previous deployment', () => {
    localStorage.setItem('language', 'zz');
    expect(start().language()).toBe('zz');
  });

  it('prefers the language the address names over the remembered one', () => {
    localStorage.setItem(STORED, 'zz');
    expect(start({ search: '?language=en' }).language()).toBe('en');
    expect(start({ search: '?lang=en' }).language()).toBe('en');
  });

  // Only a choice is remembered. A site that stored the language it opened in would hold every
  // returning reader to the default of their first visit, and a deployment that later moved it
  // would never reach them.
  it('remembers nothing a reader did not choose', () => {
    const service = start({ config: KL_DA_EN_DEFAULT_DA });
    TestBed.tick();
    expect(service.language()).toBe('da');
    expect(localStorage.getItem(STORED)).toBeNull();

    expect(start({ search: '?lang=kl', config: KL_DA_EN_DEFAULT_DA }).language()).toBe('kl');
    TestBed.tick();
    expect(localStorage.getItem(STORED)).toBeNull();
  });

  it('follows a moved default on the next visit of a reader who never chose', () => {
    start({ config: { languages: ['kl', 'da', 'en'], defaultLanguage: 'kl' } });
    TestBed.tick();
    expect(start({ config: KL_DA_EN_DEFAULT_DA }).language()).toBe('da');
  });

  it('remembers the choice made on the switcher', () => {
    const service = start({ config: KL_DA_EN_DEFAULT_DA });
    service.setLanguage('en');
    expect(service.language()).toBe('en');
    expect(localStorage.getItem(STORED)).toBe('en');
    expect(start({ config: KL_DA_EN_DEFAULT_DA }).language()).toBe('en');
  });

  it('switches for the visit, without remembering, to the language an address asks for', () => {
    const service = start({ config: KL_DA_EN_DEFAULT_DA });
    service.useLanguage('kl');
    expect(service.language()).toBe('kl');
    expect(localStorage.getItem(STORED)).toBeNull();
  });

  // A language the site does not serve falls through to the next thing that names one; it does
  // not throw away the reader's own choice, and it does not land on the first of the list.
  it('passes over an address naming a language the site does not serve', () => {
    localStorage.setItem(STORED, 'en');
    expect(start({ search: '?lang=de', config: KL_DA_EN_DEFAULT_DA }).language()).toBe('en');
    localStorage.removeItem(STORED);
    expect(start({ search: '?lang=de', config: KL_DA_EN_DEFAULT_DA }).language()).toBe('da');
  });

  it('passes over a remembered language the site no longer serves', () => {
    localStorage.setItem(STORED, 'kl');
    expect(start({ config: { languages: ['da', 'en'], defaultLanguage: 'en' } }).language()).toBe(
      'en',
    );
  });

  it("falls back to the deployment's default for a language the site does not serve", () => {
    const service = start({ config: KL_DA_EN_DEFAULT_DA });
    service.setLanguage('en');
    service.setLanguage('fr');
    expect(service.language()).toBe('da');
    // A refused choice leaves the remembered one alone.
    expect(localStorage.getItem(STORED)).toBe('en');

    service.useLanguage('fr');
    expect(service.language()).toBe('da');
  });

  it('should set available langs on Transloco during construction', () => {
    start();
    expect(translocoSpy.setAvailableLangs).toHaveBeenCalledWith(['en', 'zz']);
  });

  // There is no runtime fallback language: every bundle the build ships is complete, so English
  // is listed with Transloco only where the site offers it.
  it('lists exactly the offered languages with Transloco, English only when offered', () => {
    const service = start({ config: { languages: ['kl', 'da'], defaultLanguage: 'kl' } });
    expect(translocoSpy.setAvailableLangs).toHaveBeenCalledWith(['kl', 'da']);
    service.setLanguage('en');
    expect(service.language()).toBe('kl');
  });

  it('should sync the active language and document.documentElement.lang via effect', () => {
    const service = start();
    service.setLanguage('zz');
    TestBed.tick();
    expect(document.documentElement.lang).toBe('zz');
    expect(translocoSpy.setActiveLang).toHaveBeenLastCalledWith('zz');
  });

  it('should fall back to en when no languages configured and no localStorage', () => {
    expect(start({ config: { languages: [], defaultLanguage: 'en' } }).language()).toBe('en');
  });
});
