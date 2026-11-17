import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { RecipientSelection } from '../../../components/recipient-transfer/recipient-transfer';
import { MailingListRecipientsDrawer } from './mailing-list-recipients-drawer';
import type { MailingList } from './mailing-list.model';

@Component({
  imports: [MailingListRecipientsDrawer],
  template: `
    <app-mailing-list-recipients-drawer
      [mailingList]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<MailingList | undefined>(undefined);
  savedCount = 0;
}

interface Internals {
  usersSelection: { (): RecipientSelection; set: (v: RecipientSelection) => void };
  usersDirty: () => boolean;
  saveUsers: () => void;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function drawerOf(fixture: ReturnType<typeof setup>['fixture']): Internals {
  return fixture.debugElement.children[0].componentInstance as unknown as Internals;
}

const list: MailingList = { mailingListId: 'nm', active: true, descs: [] };

describe('MailingListRecipientsDrawer', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads both partitions on open and maps names per shape (user first+last, contact name)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(list);
    fixture.detectChanges();

    httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm/users').flush({
      selectedRecipients: [{ username: 'u1', email: 'u1@x.dk', firstName: 'Ann', lastName: 'Ng' }],
      availableRecipients: [],
    });
    httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm/contacts').flush({
      selectedRecipients: [],
      availableRecipients: [{ email: 'c1@x.dk', name: 'Contact One' }],
    });

    const internals = drawerOf(fixture);
    expect(internals.usersSelection().selected[0]).toMatchObject({
      email: 'u1@x.dk',
      name: 'Ann Ng',
    });
  });

  it('per-tab save PUTs the selected raw array, stays open, keeps the OTHER tab; emits saved', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(list);
    fixture.detectChanges();

    httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm/users').flush({
      selectedRecipients: [],
      availableRecipients: [{ username: 'u1', email: 'u1@x.dk', firstName: 'Ann' }],
    });
    httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm/contacts').flush({
      selectedRecipients: [],
      availableRecipients: [],
    });

    const internals = drawerOf(fixture);
    // Move the available user into the selected pane, then save the Users tab.
    internals.usersSelection.set({
      selected: [{ key: 'u1', email: 'u1@x.dk', name: 'Ann' }],
      available: [],
    });
    expect(internals.usersDirty()).toBe(true);
    internals.saveUsers();

    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm/users');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { username: string }[]).map((u) => u.username)).toEqual(['u1']);
    req.flush({});

    // Save cleared the dirty flag but the drawer is still open (model still set) — D2.
    expect(internals.usersDirty()).toBe(false);
    expect(host.model()).toBeDefined();
    expect(host.savedCount).toBe(1);
  });
});
