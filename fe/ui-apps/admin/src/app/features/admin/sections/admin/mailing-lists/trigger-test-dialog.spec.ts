import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { TriggerTestDialog, type TriggerTestModel } from './trigger-test-dialog';
import type { MailingList } from './mailing-list.model';

@Component({
  imports: [TriggerTestDialog],
  template: `<app-trigger-test-dialog [model]="model()" (closed)="model.set(undefined)" />`,
})
class Host {
  readonly model = signal<TriggerTestModel | undefined>(undefined);
}

interface Internals {
  messageId: { set: (v: string | null) => void };
  canExecute: () => boolean;
  execute: () => void;
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
      // MessageIdField (rendered for STATUS_CHANGE) injects the messages API — stub its typeahead.
      { provide: MessagesApiService, useValue: { searchMessageIds: () => of([]) } },
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

function dialogOf(fixture: ReturnType<typeof setup>['fixture']): Internals {
  return fixture.debugElement.children[0].componentInstance as unknown as Internals;
}

const statusList: MailingList = {
  mailingListId: 'nm',
  active: true,
  descs: [],
  triggers: [
    { type: 'STATUS_CHANGE', descs: [], scriptResourcePaths: [], statusChanges: ['PUBLISHED'] },
  ],
};
const scheduledList: MailingList = {
  mailingListId: 'nm',
  active: true,
  descs: [],
  triggers: [{ type: 'SCHEDULED', descs: [], scriptResourcePaths: [], scheduleType: 'DAILY' }],
};

describe('TriggerTestDialog', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('a STATUS_CHANGE trigger cannot execute without a message; a SCHEDULED trigger always can', () => {
    const { fixture, host } = setup();
    host.model.set({ list: statusList, triggerIndex: 0 });
    fixture.detectChanges();
    expect(dialogOf(fixture).canExecute()).toBe(false);

    host.model.set({ list: scheduledList, triggerIndex: 0 });
    fixture.detectChanges();
    expect(dialogOf(fixture).canExecute()).toBe(true);
  });

  it('execute POSTs the whole list + positional index + messageId, and persists the message', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const { fixture, host, httpTesting } = setup();
    host.model.set({ list: statusList, triggerIndex: 0 });
    fixture.detectChanges();

    const internals = dialogOf(fixture);
    internals.messageId.set('NM-042');
    internals.execute();

    const req = httpTesting.expectOne(
      '/rest/mailing-lists/test-execute-trigger?triggerIndex=0&messageId=NM-042',
    );
    expect(req.request.method).toBe('POST');
    expect((req.request.body as MailingList).mailingListId).toBe('nm');
    req.flush([{ recipients: [{ address: 'a@b.dk' }], subject: 'S', contents: '<p>x</p>' }]);
    expect(setItem).toHaveBeenCalledWith('niord.mailingList.testMessage', 'NM-042');
  });

  it('seeds the message from localStorage for a STATUS_CHANGE trigger on open', () => {
    localStorage.setItem('niord.mailingList.testMessage', 'NM-seed');
    const { fixture, host, httpTesting } = setup();
    host.model.set({ list: statusList, triggerIndex: 0 });
    fixture.detectChanges();

    dialogOf(fixture).execute();
    httpTesting
      .expectOne('/rest/mailing-lists/test-execute-trigger?triggerIndex=0&messageId=NM-seed')
      .flush([]);
  });
});
