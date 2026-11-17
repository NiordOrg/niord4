import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import type { NiordInfoConfig } from '../niord-info.config';
import { provideNiordInfo } from '../provide-niord-info';
import { TEST_CONFIG } from '../testing/test-config';

/** A build's configuration with the given languages, everything else the test one. */
function config(languages: string[], defaultLanguage: string): NiordInfoConfig {
  return { ...TEST_CONFIG, languages, defaultLanguage, timeZone: 'UTC', rootAreas: [] };
}

describe('provideNiordInfo() Transloco integration', () => {
  it('should configure Transloco with defaultLanguage from config', () => {
    TestBed.configureTestingModule({
      providers: [provideNiordInfo(config(['da', 'en'], 'da'))],
    });
    const transloco = TestBed.inject(TranslocoService);
    expect(transloco).toBeDefined();
    expect(transloco.getDefaultLang()).toBe('da');
  });

  it('should use English as default when configured', () => {
    TestBed.configureTestingModule({
      providers: [provideNiordInfo(config(['en'], 'en'))],
    });
    const transloco = TestBed.inject(TranslocoService);
    expect(transloco.getDefaultLang()).toBe('en');
  });

  it('configures no fallback language, whatever the default is', () => {
    TestBed.configureTestingModule({
      providers: [provideNiordInfo(config(['da', 'en'], 'da'))],
    });
    const transloco = TestBed.inject(TranslocoService);
    expect(transloco.config.fallbackLang ?? []).toEqual([]);
    expect(transloco.config.missingHandler?.useFallbackTranslation).toBeFalsy();
  });
});

/**
 * The build stages every bundle complete, filling a key a pack lacks from English, so the active
 * language's bundle alone holds every text: Transloco fills nothing from another language.
 */
describe('a key the active language is missing', () => {
  let transloco: TranslocoService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideNiordInfo(config(['en', 'kl'], 'en'))],
    });
    transloco = TestBed.inject(TranslocoService);
    // The settings initializer does this at boot. Without it Transloco reads a language code
    // it does not know as a scope name and looks up the wrong translation entirely.
    transloco.setAvailableLangs(['en', 'kl']);
    transloco.setTranslation(
      {
        MENU_NW: 'Navigational Warnings',
        LEGAL_TITLE_DISCLAIMER: 'Content Disclaimer',
        BTN_CLOSE: '',
      },
      'en',
    );
    transloco.setTranslation({ MENU_NW: 'Angalanermut' }, 'kl');
    transloco.setActiveLang('kl');
  });

  it('is not filled from English', () => {
    expect(transloco.translate('LEGAL_TITLE_DISCLAIMER')).toBe('LEGAL_TITLE_DISCLAIMER');
  });

  it('still renders its own value where it has one', () => {
    expect(transloco.translate('MENU_NW')).toBe('Angalanermut');
  });

  it('keeps a deliberately empty value empty rather than reporting it missing', () => {
    transloco.setActiveLang('en');
    expect(transloco.translate('BTN_CLOSE')).toBe('');
  });
});

/**
 * A Danish site, through the application's own loader: loading Danish fetches the Danish bundle
 * and its branding and nothing else, so a build need not ship English to serve Danish.
 */
describe('a Danish site', () => {
  it('loads the Danish bundle alone', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNiordInfo(config(['da', 'en'], 'da')),
      ],
    });
    const transloco = TestBed.inject(TranslocoService);
    const httpTesting = TestBed.inject(HttpTestingController);
    transloco.setAvailableLangs(['da', 'en']);

    const loaded = firstValueFrom(transloco.load('da'));
    httpTesting.expectOne('./assets/i18n/da.json').flush({ MENU_NW: 'Navigationsadvarsler' });
    httpTesting.expectOne('./assets/branding/da.json').flush({});
    await loaded;
    httpTesting.expectNone('./assets/i18n/en.json');
    httpTesting.verify();
    transloco.setActiveLang('da');

    expect(transloco.translate('MENU_NW')).toBe('Navigationsadvarsler');
  });
});
