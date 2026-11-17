import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { NEVER, Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DictionaryOverridesService } from './core/i18n/dictionary-overrides.service';
import { LanguageSettingsService } from './core/i18n/language-settings.service';
import { TranslocoHttpLoader } from './transloco-loader';

interface Build {
  /** The UI bundles the build ships. */
  readonly bundles?: readonly string[];
  /** The languages it ships branding for. */
  readonly branded?: readonly string[];
  readonly defaultLanguage?: string;
}

function setup(
  overrides$: Observable<Record<string, string>> = of({ 'msg.label': 'Kalaallisut' }),
  { bundles = ['da', 'en'], branded = [], defaultLanguage = 'da' }: Build = {},
) {
  const fetch = vi.fn(() => overrides$);
  const setTranslation = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: DictionaryOverridesService, useValue: { fetch } },
      { provide: TranslocoService, useValue: { setTranslation } },
      {
        provide: LanguageSettingsService,
        useValue: {
          defaultLanguage: () => defaultLanguage,
          uiLanguages: () => bundles.map((value) => ({ value })),
          bundleLanguages: bundles,
          brandingLanguages: branded,
        },
      },
    ],
  });
  return {
    loader: TestBed.inject(TranslocoHttpLoader),
    http: TestBed.inject(HttpTestingController),
    fetch,
    setTranslation,
  };
}

describe('translation loading', () => {
  it('merges dictionary overrides and preserves unrelated nested keys', () => {
    const { loader, http } = setup(of({ 'msg.type.LOCAL_WARNING': 'Overridden' }), {
      defaultLanguage: 'en',
    });
    let result: unknown;
    loader.getTranslation('en').subscribe((value) => (result = value));
    http
      .expectOne('assets/i18n/en.json')
      .flush({ 'msg.type.LOCAL_WARNING': 'Local', messages: { keep: 'Kept' } });
    expect(result).toEqual({ 'msg.type.LOCAL_WARNING': 'Overridden', 'messages.keep': 'Kept' });
    http.verify();
  });
  it('uses the base bundle when dictionary overrides fail', () => {
    const { loader, http } = setup(
      throwError(() => new Error('dictionary down')),
      { defaultLanguage: 'en' },
    );
    let result: unknown;
    loader.getTranslation('en').subscribe((value) => (result = value));
    http.expectOne('assets/i18n/en.json').flush({ 'msg.type.LOCAL_WARNING': 'Local' });
    expect(result).toEqual({ 'msg.type.LOCAL_WARNING': 'Local' });
    http.verify();
  });
  it('uses the base bundle when dictionary overrides exceed the timeout', () => {
    vi.useFakeTimers();
    try {
      const { loader, http } = setup(NEVER, { defaultLanguage: 'en' });
      let result: unknown;
      loader.getTranslation('en').subscribe((value) => (result = value));
      http.expectOne('assets/i18n/en.json').flush({ 'msg.type.LOCAL_WARNING': 'Local' });
      vi.advanceTimersByTime(5001);
      expect(result).toEqual({ 'msg.type.LOCAL_WARNING': 'Local' });
      http.verify();
    } finally {
      vi.useRealTimers();
    }
  });
  it('requests only the Danish bundle for Danish, with branding over it and overrides on top', () => {
    const { loader, http } = setup(of({ 'menu.c': 'Ordbog' }), { branded: ['da', 'en'] });
    let result: unknown;
    loader.getTranslation('da').subscribe((value) => (result = value));
    http.expectOne('assets/i18n/da.json').flush({ menu: { a: 'Dansk', b: '', c: 'Dansk c' } });
    http
      .expectOne('assets/branding/da.json')
      .flush({ 'footer.copyright': '© Myndighed', menu: { d: 'Brand d' } });
    http.expectNone('assets/i18n/en.json');
    expect(result).toEqual({
      // A blank text is left out, and nothing from another language takes its place.
      'menu.a': 'Dansk',
      'menu.c': 'Ordbog',
      'menu.d': 'Brand d',
      'footer.copyright': '© Myndighed',
    });
    http.verify();
  });
  it('requests only the English bundle for English, and its branding when the build has it', () => {
    const unbranded = setup(of({}), { defaultLanguage: 'en' });
    unbranded.loader.getTranslation('en').subscribe();
    unbranded.http.expectOne('assets/i18n/en.json').flush({});
    unbranded.http.verify();

    TestBed.resetTestingModule();
    const branded = setup(of({}), { branded: ['da', 'en'] });
    let result: unknown;
    branded.loader.getTranslation('en').subscribe((value) => (result = value));
    branded.http.expectOne('assets/i18n/en.json').flush({ 'footer.link': 'base' });
    branded.http.expectOne('assets/branding/en.json').flush({ 'footer.link': 'https://x.test/' });
    expect(result).toEqual({ 'footer.link': 'https://x.test/' });
    branded.http.verify();
  });
  it('loads a content-only language from the default UI bundle alone', () => {
    const { loader, http, fetch } = setup();
    let result: unknown;
    loader.getTranslation('kl').subscribe((value) => (result = value));
    http.expectOne('assets/i18n/da.json').flush({ msg: { missing: 'Dansk' } });
    http.expectNone('assets/i18n/en.json');
    http.expectNone('assets/i18n/kl.json');
    expect(result).toEqual({ 'msg.missing': 'Dansk', 'msg.label': 'Kalaallisut' });
    expect(fetch).toHaveBeenCalledWith('kl', 'da');
    http.verify();
  });
  it('loads a content-only language from the first UI bundle when the default has none', () => {
    const { loader, http } = setup(of({}), { bundles: ['en'], defaultLanguage: 'da' });
    loader.getTranslation('da').subscribe();
    // Danish is content only here: the English bundle serves it, and nothing else is asked for.
    http.expectOne('assets/i18n/en.json').flush({});
    http.expectNone((request) => request.url.startsWith('assets/branding/'));
    http.verify();
  });
  it('degrades to the bundle when the branding file is missing', () => {
    const { loader, http } = setup(of({}), { branded: ['da'] });
    let result: unknown;
    loader.getTranslation('da').subscribe((value) => (result = value));
    http.expectOne('assets/i18n/da.json').flush({ a: 'Dansk' });
    http
      .expectOne('assets/branding/da.json')
      .flush('missing', { status: 404, statusText: 'Not Found' });
    expect(result).toEqual({ a: 'Dansk' });
    http.verify();
  });
  // Without a fallback language Transloco's fallback strategy throws, so a failed load must
  // never reach it: the loader asks again, then completes with the layers above the bundle.
  it('asks again for a bundle that did not arrive, and uses the one that then does', async () => {
    vi.useFakeTimers();
    try {
      const { loader, http } = setup(of({}));
      let result: unknown;
      loader.getTranslation('da').subscribe((value) => (result = value));
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(1000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(2000);
      http.expectOne('assets/i18n/da.json').flush({ a: 'Dansk' });
      expect(result).toEqual({ a: 'Dansk' });
      http.verify();
    } finally {
      vi.useRealTimers();
    }
  });
  it('completes with branding and overrides alone when the bundle stays away, asking no other language', async () => {
    vi.useFakeTimers();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const { loader, http } = setup(of({ 'menu.c': 'Ordbog' }), { branded: ['da'] });
      const result: { value?: unknown; error?: unknown } = {};
      loader.getTranslation('da').subscribe({
        next: (value) => (result.value = value),
        error: (error) => (result.error = error),
      });
      http.expectOne('assets/branding/da.json').flush({ 'footer.copyright': '© Myndighed' });
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(1000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(2000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      expect(result.error).toBeUndefined();
      expect(result.value).toEqual({ 'footer.copyright': '© Myndighed', 'menu.c': 'Ordbog' });
      expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('"da"'), expect.anything());
      http.expectNone((request) => request.url.startsWith('assets/i18n/'));
      http.verify();
    } finally {
      consoleError.mockRestore();
      vi.useRealTimers();
    }
  });
  // Transloco keeps what the load completed with for the whole session, so without this every
  // label would show its key name until a reload.
  it('puts the bundle on screen when it arrives later, with the layers above it', async () => {
    vi.useFakeTimers();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const { loader, http, fetch, setTranslation } = setup(of({ 'menu.c': 'Ordbog' }), {
        branded: ['da'],
      });
      loader.getTranslation('da').subscribe();
      http.expectOne('assets/branding/da.json').flush({});
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(1000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(2000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      expect(setTranslation).not.toHaveBeenCalled();

      // Still away half a minute later, and answering a minute after that; the layers above it
      // are asked for again once it is in.
      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      http.expectNone('assets/branding/da.json');
      await vi.advanceTimersByTimeAsync(60_000);
      http.expectOne('assets/i18n/da.json').flush({ menu: { a: 'Dansk', c: 'Dansk c' } });
      http.expectOne('assets/branding/da.json').flush({ 'footer.copyright': '© Myndighed' });

      expect(fetch).toHaveBeenCalledTimes(2);
      expect(setTranslation).toHaveBeenCalledOnce();
      expect(setTranslation).toHaveBeenCalledWith(
        { 'menu.a': 'Dansk', 'menu.c': 'Ordbog', 'footer.copyright': '© Myndighed' },
        'da',
        { merge: false },
      );
      http.verify();
    } finally {
      consoleError.mockRestore();
      vi.useRealTimers();
    }
  });
  it('stops asking in the background once the attempts run out', async () => {
    vi.useFakeTimers();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const { loader, http, setTranslation } = setup(of({}));
      loader.getTranslation('da').subscribe();
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(1000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(2000);
      http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));

      for (const pause of [30_000, 60_000, 120_000, 240_000]) {
        await vi.advanceTimersByTimeAsync(pause);
        http.expectOne('assets/i18n/da.json').error(new ProgressEvent('error'));
      }
      await vi.advanceTimersByTimeAsync(10 * 60_000);
      http.expectNone('assets/i18n/da.json');
      expect(setTranslation).not.toHaveBeenCalled();
      http.verify();
    } finally {
      consoleError.mockRestore();
      vi.useRealTimers();
    }
  });
});
