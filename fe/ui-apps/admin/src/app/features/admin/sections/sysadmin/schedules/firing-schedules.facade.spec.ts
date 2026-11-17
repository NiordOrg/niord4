import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { FiringSchedulesFacade } from './firing-schedules.facade';

function setup(language: WritableSignal<string> = signal('en')) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: LanguageService, useValue: { selectedLanguage: language } },
      FiringSchedulesFacade,
    ],
  });
  const facade = TestBed.inject(FiringSchedulesFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  return {
    facade,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('FiringSchedulesFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the language-filtered list on init and exposes it as signals', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    const req = httpTesting.expectOne('/rest/firing-schedules/all?lang=en');
    req.flush([
      { id: 1, domain: { domainId: 'd' }, targetDomain: { domainId: 't' }, active: true },
    ]);
    expect(facade.loading()).toBe(false);
    expect(facade.schedules().length).toBe(1);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches (post-mutation) and shows the loading shell again', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    facade.refresh();
    TestBed.tick();
    expect(facade.loading()).toBe(true);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([{ id: 2, active: false }]);
    expect(facade.schedules().length).toBe(1);
  });

  it('refetches with the new ?lang= when the UI language switches', () => {
    const language = signal('en');
    const { httpTesting } = setup(language);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);

    language.set('da');
    TestBed.tick();
    httpTesting.expectOne('/rest/firing-schedules/all?lang=da').flush([{ id: 3, active: true }]);
  });

  it('a failed fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/firing-schedules/all?lang=en')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });
});
