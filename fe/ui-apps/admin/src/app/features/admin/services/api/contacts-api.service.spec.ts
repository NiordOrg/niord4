import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { Contact, ContactsApiService } from './contacts-api.service';

describe('ContactsApiService', () => {
  let service: ContactsApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(ContactsApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('search converts the 1-based UI page to the 0-BASED wire page', () => {
    service.search('', 3, 20).subscribe();
    const req = httpTesting.expectOne('/rest/contacts/search?maxSize=20&page=2');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ data: [], size: 0, total: 0 });
  });

  it('search OMITS the name param when empty and sends it when set', () => {
    service.search('', 1, 20).subscribe();
    const bare = httpTesting.expectOne('/rest/contacts/search?maxSize=20&page=0');
    expect(bare.request.params.has('name')).toBe(false);
    bare.flush({ data: [], size: 0, total: 0 });

    service.search('an', 1, 20).subscribe();
    httpTesting.expectOne('/rest/contacts/search?maxSize=20&page=0&name=an').flush({
      data: [{ id: 1, email: 'a@x.dk', name: null, language: null }],
      size: 1,
      total: 1,
    });
  });

  it('update PUTs with the numeric id as the path id matching the body', () => {
    const contact: Contact = { id: 7, email: 'a@x.dk' };
    service.update(contact).subscribe();
    const req = httpTesting.expectOne('/rest/contacts/contact/7');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as Contact).id).toBe(7);
    req.flush({});
  });

  it('importEmails POSTs the {emails} body and returns the PLAIN-TEXT result', () => {
    let result = '';
    service.importEmails('a@x.dk; b@x.dk').subscribe((text) => (result = text));
    const req = httpTesting.expectOne('/rest/contacts/import-emails');
    expect(req.request.body).toEqual({ emails: 'a@x.dk; b@x.dk' });
    req.flush('Imported 2 new contacts in 5 ms');
    expect(result).toBe('Imported 2 new contacts in 5 ms');
  });

  it('exportUrl is the bare endpoint (the caller attaches the admin-role ticket)', () => {
    expect(service.exportUrl()).toBe('/rest/contacts/export');
  });
});
