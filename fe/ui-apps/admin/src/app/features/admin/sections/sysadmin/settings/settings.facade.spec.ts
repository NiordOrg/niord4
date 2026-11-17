import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { SettingsFacade } from './settings.facade';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      SettingsFacade,
    ],
  });
  const facade = TestBed.inject(SettingsFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  const httpTesting = TestBed.inject(HttpTestingController);
  return { facade, httpTesting };
}

describe('SettingsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads the editable settings on init and exposes signals', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    httpTesting
      .expectOne('/rest/settings/editable-settings')
      .flush([{ key: 'a', type: 'String', value: 'x' }]);
    expect(facade.loading()).toBe(false);
    expect(facade.settings().length).toBe(1);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches the list (post-save / cancel / import)', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/settings/editable-settings').flush([]);
    facade.refresh();
    TestBed.tick();
    httpTesting
      .expectOne('/rest/settings/editable-settings')
      .flush([{ key: 'b', type: 'Boolean', value: true }]);
    expect(facade.settings().length).toBe(1);
  });

  it('a failed load sets the error flag (never a silent empty list)', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/settings/editable-settings')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
    expect(facade.settings()).toEqual([]);
  });
});
