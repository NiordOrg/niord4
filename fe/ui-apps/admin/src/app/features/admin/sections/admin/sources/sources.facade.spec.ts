import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SourcesFacade } from './sources.facade';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { LanguageSettingsService } from '../../../../../core/i18n/language-settings.service';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      SourcesFacade,
      ModelLanguageSelection,
    ],
  });
  TestBed.inject(LanguageService);
  TestBed.inject(LanguageSettingsService).configure({
    modelLanguages: ['da', 'en', 'kl'],
    defaultLanguage: 'da',
  });
  const facade = TestBed.inject(SourcesFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  return {
    facade,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('SourcesFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('refetches model text in KL without changing the UI language', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/sources/all?lang=en').flush([]);
    TestBed.inject(ModelLanguageSelection).select('kl');
    TestBed.tick();
    httpTesting
      .expectOne('/rest/sources/all?lang=kl')
      .flush([{ id: 1, active: true, descs: [{ lang: 'kl', name: 'Aalisartut' }] }]);
    expect(facade.sources()[0].descs[0].name).toBe('Aalisartut');
    expect(TestBed.inject(LanguageService).selectedLanguage()).toBe('en');
  });

  it('fetches the language-filtered list on init and exposes it as signals', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    const req = httpTesting.expectOne('/rest/sources/all?lang=en');
    req.flush([{ id: 1, active: true, descs: [{ lang: 'en', name: 'S' }] }]);
    expect(facade.loading()).toBe(false);
    expect(facade.sources().length).toBe(1);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches (post-mutation / post-import) and shows the loading shell again', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/sources/all?lang=en').flush([]);
    facade.refresh();
    TestBed.tick();
    expect(facade.loading()).toBe(true);
    httpTesting.expectOne('/rest/sources/all?lang=en').flush([{ id: 2, active: false, descs: [] }]);
    expect(facade.sources().length).toBe(1);
  });

  it('a failed fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/sources/all?lang=en')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });
});
