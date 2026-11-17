import { TestBed } from '@angular/core/testing';
import { AppConfigService } from './app-config.service';
import { provideTestNiordConfig, TEST_CONFIG } from '../../testing/test-config';

describe('AppConfigService', () => {
  let service: AppConfigService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideTestNiordConfig()] });
    service = TestBed.inject(AppConfigService);
  });

  it('should expose languages from config', () => {
    expect(service.languages()).toEqual(TEST_CONFIG.languages);
  });

  it('should expose timeZone from config', () => {
    expect(service.timeZone()).toBe(TEST_CONFIG.timeZone);
  });

  it('should expose executionMode from config', () => {
    expect(service.executionMode()).toBe('DEVELOPMENT');
  });

  it('should expose the default language from config', () => {
    expect(service.defaultLanguage()).toBe(TEST_CONFIG.defaultLanguage);
  });

  it('should take the languages the settings initializer resolved', () => {
    service.applyLanguages(['kl', 'da', 'en'], 'kl');
    expect(service.languages()).toEqual(['kl', 'da', 'en']);
    expect(service.defaultLanguage()).toBe('kl');
  });

  it('should take the execution mode the settings initializer resolved', () => {
    service.applyExecutionMode('test');
    expect(service.executionMode()).toBe('test');
  });

  it('should have no tile address until the settings initializer resolves one', () => {
    expect(service.osmUrl()).toBeUndefined();
    service.applyOsmUrl('https://tiles.example/{z}/{x}/{y}.png');
    expect(service.osmUrl()).toBe('https://tiles.example/{z}/{x}/{y}.png');
  });

  it('should expose wmsLayer from config', () => {
    expect(service.wmsLayer()).toBe(false);
  });

  it('should expose rootAreaSpecs from config', () => {
    expect(service.rootAreaSpecs()).toEqual(TEST_CONFIG.rootAreas);
  });

  it('names a language and its flag as the language configuration does', () => {
    expect(service.languageName('en')).toBe('English');
    expect(service.flagSrc('en')).toBe('assets/img/flags/en.png');
    expect(service.languageName('zz')).toBe('Pseudo-English');
    expect(service.flagSrc('zz')).toBeUndefined();
  });

  // Text from the backend may be in any language; a flag path made up from its code would be a
  // broken image, so the caller is told there is none and shows the code.
  it('makes up neither a name nor a flag for a language the build does not ship', () => {
    expect(service.languageName('de')).toBeUndefined();
    expect(service.flagSrc('de')).toBeUndefined();
  });
});
