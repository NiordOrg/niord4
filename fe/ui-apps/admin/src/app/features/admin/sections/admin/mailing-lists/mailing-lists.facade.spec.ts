import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MailingListsFacade } from './mailing-lists.facade';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      MailingListsFacade,
    ],
  });
  const facade = TestBed.inject(MailingListsFacade);
  TestBed.tick();
  return { facade, httpTesting: TestBed.inject(HttpTestingController) };
}

describe('MailingListsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the language-filtered detailed list on init and exposes it as signals', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    httpTesting
      .expectOne('/rest/mailing-lists/search?lang=en')
      .flush([{ mailingListId: 'nm', active: true, recipientNo: 2, descs: [] }]);
    expect(facade.loading()).toBe(false);
    expect(facade.mailingLists().length).toBe(1);
    expect(facade.mailingLists()[0].recipientNo).toBe(2);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches (post-mutation / post-recipient-save) with the loading shell', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
    facade.refresh();
    TestBed.tick();
    expect(facade.loading()).toBe(true);
    httpTesting
      .expectOne('/rest/mailing-lists/search?lang=en')
      .flush([{ mailingListId: 'x', active: false, descs: [] }]);
    expect(facade.mailingLists().length).toBe(1);
  });

  it('a failed fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/mailing-lists/search?lang=en')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });
});
