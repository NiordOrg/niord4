import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { MailingList } from '../../sections/admin/mailing-lists/mailing-list.model';
import { MailingListsApiService } from './mailing-lists-api.service';

const API_BASE = '/rest';

describe('MailingListsApiService (admin CRUD)', () => {
  let service: MailingListsApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(MailingListsApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('getAllDetailed keeps active/recipientNo/descs and carries lang; name is optional', () => {
    let lists: MailingList[] = [];
    service.getAllDetailed('en').subscribe((result) => (lists = result));
    httpTesting.expectOne(`${API_BASE}/mailing-lists/search?lang=en`).flush([
      { mailingListId: 'nm', active: true, recipientNo: 3, descs: [{ lang: 'en', name: 'NM' }] },
      { mailingListId: 'x', active: null },
    ]);
    expect(lists).toHaveLength(2);
    expect(lists[0]).toMatchObject({ mailingListId: 'nm', active: true, recipientNo: 3 });
    expect(lists[0].descs[0]).toEqual({ lang: 'en', name: 'NM', description: '' });
    expect(lists[1].active).toBe(false);
    expect(lists[1].recipientNo).toBeUndefined();
  });

  it('getAllDetailed adds the name filter when provided', () => {
    service.getAllDetailed('da', 'nav').subscribe();
    httpTesting.expectOne(`${API_BASE}/mailing-lists/search?lang=da&name=nav`).flush([]);
  });

  it('create POSTs to the trailing-slash collection path with the list body', () => {
    const list: MailingList = { mailingListId: 'nm', active: true, descs: [] };
    service.create(list).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/mailing-lists/mailing-list/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(list);
    req.flush({});
  });

  it('update PUTs with the URL-encoded id as the path', () => {
    const list: MailingList = { mailingListId: 'a/b 1', active: true, descs: [] };
    service.update(list).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/mailing-lists/mailing-list/a%2Fb%201`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('remove DELETEs the URL-encoded id', () => {
    service.remove('a/b').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/mailing-lists/mailing-list/a%2Fb`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('recipient partitions GET; updates PUT the selected array', () => {
    service.getRecipientUsers('nm').subscribe();
    httpTesting
      .expectOne(`${API_BASE}/mailing-lists/mailing-list/nm/users`)
      .flush({ selectedRecipients: [], availableRecipients: [] });

    service.updateRecipientContacts('nm', [{ email: 'a@b.dk' } as never]).subscribe();
    const put = httpTesting.expectOne(`${API_BASE}/mailing-lists/mailing-list/nm/contacts`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual([{ email: 'a@b.dk' }]);
    put.flush({});
  });

  it('testExecuteTrigger POSTs the whole list with a positional triggerIndex (+ optional messageId)', () => {
    const list: MailingList = { mailingListId: 'nm', active: true, descs: [] };
    service.testExecuteTrigger(list, 2, 'NM-042').subscribe();
    const req = httpTesting.expectOne(
      `${API_BASE}/mailing-lists/test-execute-trigger?triggerIndex=2&messageId=NM-042`,
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(list);
    req.flush([]);
  });

  // Reports (getReports / executeReport) moved to the messages-local
  // MailingListReportsApiService — covered by its dialog spec.

  it('export + upload URLs point at the mailing-lists endpoints', () => {
    expect(service.exportUrl()).toBe(`${API_BASE}/mailing-lists/export`);
    expect(service.uploadUrl()).toBe(`${API_BASE}/mailing-lists/upload-mailing-lists`);
  });
});
