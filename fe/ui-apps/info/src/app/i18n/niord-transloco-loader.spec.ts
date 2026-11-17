import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoService } from '@jsverse/transloco';
import { NiordTranslocoLoader } from './niord-transloco-loader';
import { provideTestNiordConfig } from '../testing/test-config';

describe('NiordTranslocoLoader', () => {
  let loader: NiordTranslocoLoader;
  let httpTesting: HttpTestingController;
  let setTranslation: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setTranslation = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // A Danish deployment: Danish by default, English beside it.
        provideTestNiordConfig({ languages: ['da', 'en'], defaultLanguage: 'da' }),
        { provide: TranslocoService, useValue: { setTranslation } },
      ],
    });
    loader = TestBed.inject(NiordTranslocoLoader);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
    vi.useRealTimers();
  });

  /** Subscribes to one language's load and hands back what it produced, once it has. */
  function load(lang: string) {
    const result: { value?: Record<string, string>; error?: unknown } = {};
    loader.getTranslation(lang).subscribe({
      next: (value) => (result.value = value as Record<string, string>),
      error: (error) => (result.error = error),
    });
    return result;
  }

  const fail = (url: string) => httpTesting.expectOne(url).error(new ProgressEvent('error'));

  it('should merge the bundle with the branding overlay', () => {
    const result = load('en');

    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });
    httpTesting.expectOne('./assets/branding/en.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    expect(result.value!['MENU_NW']).toBe('Navigational Warnings');
    expect(result.value!['FOOTER_COPYRIGHT']).toBe('brand');
  });

  it('should let branding override the bundle', () => {
    const result = load('en');

    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'from-bundle' });
    httpTesting.expectOne('./assets/branding/en.json').flush({ MENU_NW: 'from-branding' });

    expect(result.value!['MENU_NW']).toBe('from-branding');
  });

  it('should fetch the language it was asked for', () => {
    load('kl');

    httpTesting.expectOne('./assets/i18n/kl.json').flush({});
    httpTesting.expectOne('./assets/branding/kl.json').flush({});
  });

  it('should survive a missing branding file — the default build ships none', () => {
    const result = load('en');

    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });
    httpTesting.expectOne('./assets/branding/en.json').error(new ProgressEvent('error'));

    expect(result.value).toEqual({ MENU_NW: 'Navigational Warnings' });
  });

  // Transloco keeps what the loader returns for the whole visit, so an answer given up on too
  // early is key names on every label until the reader reloads.
  it('asks again for a bundle that did not arrive, and uses the one that then does', async () => {
    vi.useFakeTimers();
    const result = load('da');
    httpTesting.expectOne('./assets/branding/da.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(2000);
    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });

    expect(result.value).toEqual({ MENU_NW: 'Navigationsadvarsler', FOOTER_COPYRIGHT: 'brand' });
    expect(result.error).toBeUndefined();
  });

  it("shows another site language's words, never key names, when the bundle stays away", async () => {
    vi.useFakeTimers();
    const result = load('da');
    httpTesting.expectOne('./assets/branding/da.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(2000);
    fail('./assets/i18n/da.json');
    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });

    expect(result.value).toEqual({ MENU_NW: 'Navigational Warnings', FOOTER_COPYRIGHT: 'brand' });
    expect(result.error).toBeUndefined();
  });

  it("tries the site's default language first for a language that is not the default", async () => {
    vi.useFakeTimers();
    const result = load('en');
    httpTesting.expectOne('./assets/branding/en.json').flush({});

    fail('./assets/i18n/en.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/en.json');
    await vi.advanceTimersByTimeAsync(2000);
    fail('./assets/i18n/en.json');
    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });

    expect(result.value).toEqual({ MENU_NW: 'Navigationsadvarsler' });
  });

  it('completes with the branding alone when no bundle answers at all', async () => {
    vi.useFakeTimers();
    const result = load('da');
    httpTesting.expectOne('./assets/branding/da.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(2000);
    fail('./assets/i18n/da.json');
    fail('./assets/i18n/en.json');

    expect(result.value).toEqual({ FOOTER_COPYRIGHT: 'brand' });
    expect(result.error).toBeUndefined();
  });

  // The stand-in is another language's words; the page is only right again once its own arrive.
  it('puts the bundle on screen when it arrives later, in place of the stand-in', async () => {
    vi.useFakeTimers();
    load('da');
    httpTesting.expectOne('./assets/branding/da.json').flush({});
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(2000);
    fail('./assets/i18n/da.json');
    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });
    expect(setTranslation).not.toHaveBeenCalled();

    // Still away half a minute later — the branding asked for beside it is dropped with it —
    // and answering a minute after that.
    await vi.advanceTimersByTimeAsync(30_000);
    fail('./assets/i18n/da.json');
    expect(httpTesting.expectOne('./assets/branding/da.json').cancelled).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });
    httpTesting.expectOne('./assets/branding/da.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    expect(setTranslation).toHaveBeenCalledOnce();
    expect(setTranslation).toHaveBeenCalledWith(
      { MENU_NW: 'Navigationsadvarsler', FOOTER_COPYRIGHT: 'brand' },
      'da',
      { merge: false },
    );
  });

  // Transloco stores what the load completes with. A bundle put on screen before a slow stand-in
  // completed would be overwritten by the stand-in, and nothing would ask for it again.
  it('asks for the bundle in the background only once the stand-in is in', async () => {
    vi.useFakeTimers();
    const result = load('da');
    httpTesting.expectOne('./assets/branding/da.json').flush({});
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(1000);
    fail('./assets/i18n/da.json');
    await vi.advanceTimersByTimeAsync(2000);
    fail('./assets/i18n/da.json');

    await vi.advanceTimersByTimeAsync(45_000);
    httpTesting.expectNone('./assets/i18n/da.json');
    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });
    expect(result.value).toEqual({ MENU_NW: 'Navigational Warnings' });

    await vi.advanceTimersByTimeAsync(30_000);
    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });
    httpTesting.expectOne('./assets/branding/da.json').flush({});
    expect(setTranslation).toHaveBeenCalledWith({ MENU_NW: 'Navigationsadvarsler' }, 'da', {
      merge: false,
    });
  });
});

/** A build that ships branding in Danish only, beside its Danish and English bundles. */
describe('NiordTranslocoLoader in a build that names its branding languages', () => {
  let loader: NiordTranslocoLoader;
  let httpTesting: HttpTestingController;

  function setUp(brandingLanguages: readonly string[]) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestNiordConfig({
          languages: ['da', 'en'],
          defaultLanguage: 'da',
          brandingLanguages,
        }),
        { provide: TranslocoService, useValue: { setTranslation: vi.fn() } },
      ],
    });
    loader = TestBed.inject(NiordTranslocoLoader);
    httpTesting = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpTesting.verify());

  it('asks for no branding file in a language outside them', () => {
    setUp(['da']);
    let result: unknown;
    loader.getTranslation('en').subscribe((value) => (result = value));

    httpTesting.expectOne('./assets/i18n/en.json').flush({ MENU_NW: 'Navigational Warnings' });
    httpTesting.expectNone('./assets/branding/en.json');

    expect(result).toEqual({ MENU_NW: 'Navigational Warnings' });
  });

  it('asks for the branding file in a language among them', () => {
    setUp(['da']);
    let result: unknown;
    loader.getTranslation('da').subscribe((value) => (result = value));

    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });
    httpTesting.expectOne('./assets/branding/da.json').flush({ FOOTER_COPYRIGHT: 'brand' });

    expect(result).toEqual({ MENU_NW: 'Navigationsadvarsler', FOOTER_COPYRIGHT: 'brand' });
  });

  it('asks for no branding file at all in a build that ships none', () => {
    setUp([]);
    loader.getTranslation('da').subscribe();

    httpTesting.expectOne('./assets/i18n/da.json').flush({});
    httpTesting.expectNone((request) => request.url.startsWith('./assets/branding/'));
  });
});
