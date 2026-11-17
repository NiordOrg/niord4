import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import type { LanguageConfig, LanguageOption } from '../config/language-config';
import { AppConfigService } from './app-config.service';
import {
  resolveExecutionMode,
  resolveInfoLanguages,
  resolveTileUrl,
  WebSettingsService,
} from './web-settings.service';
import { provideTestNiordConfig } from '../../testing/test-config';

function option(value: string): LanguageOption {
  return { value, label: value, locale: value };
}

/** A build with Danish and English bundles, Danish first. */
const DA_FIRST: LanguageConfig = {
  languages: [option('da'), option('en')],
  siteLanguages: ['da', 'en'],
  defaultLanguage: 'da',
};

/** A build that ships Kalaallisut too but offers it only when the settings ask for it. */
const WITH_KL: LanguageConfig = { ...DA_FIRST, languages: [option('kl'), ...DA_FIRST.languages] };

describe('resolveInfoLanguages()', () => {
  it('keeps the country list when the backend says nothing', () => {
    expect(resolveInfoLanguages(DA_FIRST)).toEqual({
      languages: ['da', 'en'],
      defaultLanguage: 'da',
    });
  });

  it('takes the list and the default from the settings', () => {
    expect(
      resolveInfoLanguages(WITH_KL, {
        infoSiteLanguages: ['kl', 'da', 'en'],
        infoDefaultLanguage: 'kl',
      }),
    ).toEqual({ languages: ['kl', 'da', 'en'], defaultLanguage: 'kl' });
  });

  it('reads a json setting that was stored as a string', () => {
    expect(
      resolveInfoLanguages(WITH_KL, {
        infoSiteLanguages: '["kl","da","en"]',
        infoDefaultLanguage: 'kl',
      }),
    ).toEqual({ languages: ['kl', 'da', 'en'], defaultLanguage: 'kl' });
  });

  it('drops a language this application ships no bundle for, and says so', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(resolveInfoLanguages(DA_FIRST, { infoSiteLanguages: ['da', 'xx'] })).toEqual({
      languages: ['da'],
      defaultLanguage: 'da',
    });
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('drops a language another build ships but this one does not', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(
      resolveInfoLanguages(DA_FIRST, {
        infoSiteLanguages: ['kl', 'da', 'en'],
        infoDefaultLanguage: 'kl',
      }),
    ).toEqual({ languages: ['da', 'en'], defaultLanguage: 'da' });
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('falls back to the country list when nothing requested is shippable', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(resolveInfoLanguages(DA_FIRST, { infoSiteLanguages: ['xx', 'yy'] })).toEqual({
      languages: ['da', 'en'],
      defaultLanguage: 'da',
    });
    warn.mockRestore();
  });

  it('falls back to the first shipped bundle when the country list is unshippable too', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(
      resolveInfoLanguages({ ...DA_FIRST, siteLanguages: ['xx'], defaultLanguage: 'xx' }, {}),
    ).toEqual({
      languages: ['da'],
      defaultLanguage: 'da',
    });
    warn.mockRestore();
  });

  it('ignores a default that is not in the resolved list', () => {
    expect(
      resolveInfoLanguages(DA_FIRST, { infoSiteLanguages: ['da'], infoDefaultLanguage: 'en' }),
    ).toEqual({ languages: ['da'], defaultLanguage: 'da' });
  });

  it('ignores a blank default', () => {
    expect(resolveInfoLanguages(DA_FIRST, { infoDefaultLanguage: '   ' }).defaultLanguage).toBe(
      'da',
    );
  });

  it.each([[[]], [null], [42], ['not json'], [{ da: true }]])(
    'falls back to the country list for %s',
    (value) => {
      expect(resolveInfoLanguages(DA_FIRST, { infoSiteLanguages: value }).languages).toEqual([
        'da',
        'en',
      ]);
    },
  );

  it('collapses duplicates and keeps the order', () => {
    expect(
      resolveInfoLanguages(DA_FIRST, { infoSiteLanguages: ['en', 'da', 'en'] }).languages,
    ).toEqual(['en', 'da']);
  });
});

describe('resolveExecutionMode()', () => {
  it('keeps what the backend says', () => {
    expect(resolveExecutionMode({ mode: 'production' }, 'production')).toBe('production');
    expect(resolveExecutionMode({ mode: 'test' }, 'production')).toBe('test');
  });

  it('trims the value', () => {
    expect(resolveExecutionMode({ mode: '  test  ' }, 'production')).toBe('test');
  });

  it.each([[''], ['   '], [undefined], [42], [null]])(
    'falls back to the build value for %s',
    (mode) => {
      expect(resolveExecutionMode({ mode }, 'production')).toBe('production');
    },
  );

  it('falls back when the setting is absent', () => {
    expect(resolveExecutionMode({}, 'production')).toBe('production');
  });
});

describe('resolveTileUrl()', () => {
  it('takes an https template with the three placeholders', () => {
    expect(resolveTileUrl('https://tiles.example/osm/{z}/{x}/{y}.png')).toBe(
      'https://tiles.example/osm/{z}/{x}/{y}.png',
    );
  });

  it('trims the value', () => {
    expect(resolveTileUrl('  https://tiles.example/{z}/{x}/{y}.png ')).toBe(
      'https://tiles.example/{z}/{x}/{y}.png',
    );
  });

  it.each([
    [undefined],
    [null],
    [42],
    [''],
    ['   '],
    ['http://tiles.example/{z}/{x}/{y}.png'],
    ['//tiles.example/{z}/{x}/{y}.png'],
    ['tiles.example/{z}/{x}/{y}.png'],
    ['https://tiles.example/{z}/{x}.png'],
    ['https://tiles.example/tile.png'],
    ['https://tiles.example/{z}/{x}/{y}.png https://other.example/'],
  ])('leaves the kit default for %s', (value) => {
    expect(resolveTileUrl(value)).toBeUndefined();
  });
});

describe('WebSettingsService', () => {
  let service: WebSettingsService;
  let appConfig: AppConfigService;
  let transloco: {
    setAvailableLangs: ReturnType<typeof vi.fn>;
    setDefaultLang: ReturnType<typeof vi.fn>;
  };
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    transloco = {
      setAvailableLangs: vi.fn(),
      setDefaultLang: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestNiordConfig({
          languages: ['da', 'en'],
          defaultLanguage: 'da',
          executionMode: 'production',
        }),
        { provide: TranslocoService, useValue: transloco },
      ],
    });
    service = TestBed.inject(WebSettingsService);
    appConfig = TestBed.inject(AppConfigService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('asks exactly the anonymous settings endpoint', async () => {
    const loaded = service.load(DA_FIRST);
    const request = httpTesting.expectOne('/rest/settings/web');
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.keys()).toEqual([]);
    request.flush({});
    await loaded;
  });

  it('applies the settings to the configuration and to Transloco', async () => {
    const loaded = service.load(WITH_KL);
    httpTesting.expectOne('/rest/settings/web').flush({
      infoSiteLanguages: ['kl', 'da', 'en'],
      infoDefaultLanguage: 'kl',
      mode: 'test',
    });
    await loaded;

    expect(appConfig.languages()).toEqual(['kl', 'da', 'en']);
    expect(appConfig.defaultLanguage()).toBe('kl');
    expect(appConfig.executionMode()).toBe('test');
    expect(transloco.setAvailableLangs).toHaveBeenCalledWith(['kl', 'da', 'en']);
    expect(transloco.setDefaultLang).toHaveBeenCalledWith('kl');
  });

  it('takes the tile address from the settings', async () => {
    const loaded = service.load(DA_FIRST);
    httpTesting
      .expectOne('/rest/settings/web')
      .flush({ osmUrl: 'https://tiles.example/osm/{z}/{x}/{y}.png' });
    await loaded;

    expect(appConfig.osmUrl()).toBe('https://tiles.example/osm/{z}/{x}/{y}.png');
  });

  it('keeps the kit default for a tile address the policy would block', async () => {
    const loaded = service.load(DA_FIRST);
    httpTesting
      .expectOne('/rest/settings/web')
      .flush({ osmUrl: 'http://tiles.example/osm/{z}/{x}/{y}.png' });
    await loaded;

    expect(appConfig.osmUrl()).toBeUndefined();
  });

  it('boots on the country values when the backend answers 500', async () => {
    const loaded = service.load(DA_FIRST);
    httpTesting
      .expectOne('/rest/settings/web')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await loaded;

    expect(appConfig.languages()).toEqual(['da', 'en']);
    expect(appConfig.defaultLanguage()).toBe('da');
    expect(appConfig.executionMode()).toBe('production');
    expect(appConfig.osmUrl()).toBeUndefined();
  });

  it('boots on the country values when the request fails outright', async () => {
    const loaded = service.load(DA_FIRST);
    httpTesting.expectOne('/rest/settings/web').error(new ProgressEvent('error'));
    await loaded;

    expect(appConfig.languages()).toEqual(['da', 'en']);
    expect(appConfig.executionMode()).toBe('production');
  });

  it('offers Transloco exactly the resolved site languages, English only when they name it', async () => {
    const loaded = service.load(WITH_KL);
    httpTesting.expectOne('/rest/settings/web').flush({ infoSiteLanguages: ['kl', 'da'] });
    await loaded;

    expect(appConfig.languages()).toEqual(['kl', 'da']);
    expect(transloco.setAvailableLangs).toHaveBeenCalledWith(['kl', 'da']);
  });

  it('boots after the timeout when the backend never answers', async () => {
    vi.useFakeTimers();
    try {
      const loaded = service.load(DA_FIRST);
      httpTesting.expectOne('/rest/settings/web');
      await vi.advanceTimersByTimeAsync(5000);
      await loaded;
    } finally {
      vi.useRealTimers();
    }

    expect(appConfig.languages()).toEqual(['da', 'en']);
  });
});

/**
 * What a reader actually sees for a key the active language is missing, against a real Transloco
 * rather than a spy: the key, not another language's words, whatever default the build names or
 * the deployment resolves. The build stages every bundle complete, so no key is missing from one
 * it ships; there is no runtime fallback language.
 */
describe('a key the active language is missing', () => {
  /** A Danish build that ships Kalaallisut too: `da` is the default the country file names. */
  const DANISH_BUILD: LanguageConfig = WITH_KL;

  /** One key, absent from Kalaallisut. */
  const LANGS = {
    en: { A_KEY: 'in English' },
    da: { A_KEY: 'on Danish' },
    kl: {},
  };

  function setUp() {
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: LANGS,
          translocoConfig: {
            defaultLang: DANISH_BUILD.defaultLanguage,
            availableLangs: ['en', 'da', 'kl'],
            missingHandler: { allowEmpty: true },
          },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestNiordConfig({ languages: ['da', 'en'], defaultLanguage: 'da' }),
      ],
    });
    return {
      service: TestBed.inject(WebSettingsService),
      transloco: TestBed.inject(TranslocoService),
      httpTesting: TestBed.inject(HttpTestingController),
    };
  }

  it('is filled from neither English nor the default once the settings resolve', async () => {
    const { service, transloco, httpTesting } = setUp();
    const loaded = service.load(DANISH_BUILD);
    httpTesting
      .expectOne('/rest/settings/web')
      .flush({ infoSiteLanguages: ['kl', 'da'], infoDefaultLanguage: 'da' });
    await loaded;

    await firstValueFrom(transloco.load('kl'));
    transloco.setActiveLang('kl');

    expect(transloco.getAvailableLangs()).toEqual(['kl', 'da']);
    expect(transloco.config.fallbackLang ?? []).toEqual([]);
    expect(transloco.translate('A_KEY')).toBe('A_KEY');
  });
});
