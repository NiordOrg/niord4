import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomPage } from './secom-page';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideRouter([]),
    ],
  });
  const fixture = TestBed.createComponent(SecomPage);
  fixture.detectChanges();
  return { fixture, httpTesting: TestBed.inject(HttpTestingController) };
}

/** Answer every mount-time load, optionally failing the status probe. */
function flushInitial(httpTesting: HttpTestingController, statusStatus = 200): void {
  httpTesting.expectOne(SERIES_URL).flush([]);
  httpTesting.expectOne('/rest/secom/series-config').flush([]);
  const status = httpTesting.expectOne('/rest/secom/status');
  if (statusStatus === 200) {
    status.flush({ enabled: true, serviceCertificate: { subject: 'CN=node' } });
  } else {
    status.flush('nope', { status: statusStatus, statusText: 'Error' });
  }
  for (const url of [
    '/rest/secom/subscriptions',
    '/rest/secom/nodes',
    '/rest/secom/datasets',
    '/rest/secom/outbox',
  ]) {
    httpTesting.expectOne(url).flush([]);
  }
}

describe('SecomPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('renders the five tabs when the subsystem is deployed', async () => {
    const { fixture, httpTesting } = await setup();
    flushInitial(httpTesting);
    await fixture.whenStable();

    const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]');
    expect(tabs.length).toBe(5);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('replaces the tabs with an explanatory note when the /secom API is absent (404)', async () => {
    const { fixture, httpTesting } = await setup();
    flushInitial(httpTesting, 404);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('[role="tab"]').length).toBe(0);
  });

  it('keeps the tabs (and shows an inline error) when the status probe fails for another reason', async () => {
    const { fixture, httpTesting } = await setup();
    flushInitial(httpTesting, 500);
    await fixture.whenStable();

    // A 500 is a failure, not "the subsystem is switched off" — the page stays.
    expect(fixture.nativeElement.querySelectorAll('[role="tab"]').length).toBe(5);
  });
});
