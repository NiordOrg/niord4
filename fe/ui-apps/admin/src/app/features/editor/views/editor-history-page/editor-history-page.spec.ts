import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageHistory } from '../../../messages/models/message-history.model';
import type { Message } from '../../../messages/models/message.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { TemplateMessageStore } from '../../services/template-message-store';
import { EditorFacade } from '../../services/editor-facade';
import { EditorHistoryPage } from './editor-history-page';

interface HistoryHandle {
  toggle(revision: MessageHistory): void;
  selected(): readonly MessageHistory[];
  attachments(): readonly unknown[] | undefined;
  selectedSnapshots(): readonly string[];
}

const message: Message = {
  id: 'm-1',
  status: 'DRAFT',
  mainType: 'NW',
  type: 'COASTAL_WARNING',
  messageSeries: { seriesId: 's' },
  parts: [{ type: 'DETAILS', indexNo: 0 }],
  created: 'x',
};

function hist(version: number, snapshot: string): MessageHistory {
  return { version, created: '2026-06-01T10:00:00Z', user: 'alice', status: 'DRAFT', snapshot };
}

function setup() {
  const api = {
    editableDetails: vi.fn(() => of(message)),
  };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [{ seriesId: 's', mainType: 'NW' }] }),
    supportsMainType: () => signal(true),
  };
  const route = {
    snapshot: { paramMap: convertToParamMap({ id: 'm-1' }) },
    paramMap: of(convertToParamMap({ id: 'm-1' })),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideFakeAuthFacade({ isAuthenticated: true, editor: true }),
      EditorFacade,
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      {
        provide: TemplateMessageStore,
        useValue: { consume: vi.fn(), set: vi.fn(), clear: vi.fn() },
      },
      { provide: ActivatedRoute, useValue: route },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(EditorHistoryPage);
  fixture.detectChanges();
  return fixture.componentInstance as unknown as HistoryHandle;
}

describe('EditorHistoryPage ≤2-select cap', () => {
  it('selects up to two revisions and ignores a third', () => {
    const view = setup();
    const a = hist(3, '{}');
    const b = hist(2, '{}');
    const c = hist(1, '{}');
    view.toggle(a);
    view.toggle(b);
    view.toggle(c);
    expect(view.selected().map((r) => r.version)).toEqual([3, 2]);
  });

  it('toggling a selected revision deselects it', () => {
    const view = setup();
    const a = hist(3, '{}');
    view.toggle(a);
    view.toggle(a);
    expect(view.selected()).toEqual([]);
  });

  it('orders the two selected snapshots oldest→newest by version regardless of click order', () => {
    const view = setup();
    // Click the NEWER revision first, then the older one.
    view.toggle(hist(2, '{"v":2}'));
    view.toggle(hist(1, '{"v":1}'));
    // Snapshots come out version-ascending (older = left/old, newer = right/new), NOT click order,
    // so the diff direction + "comparing #a and #b" header are deterministic.
    expect(view.selectedSnapshots()).toEqual(['{"v":1}', '{"v":2}']);
  });
});

describe('EditorHistoryPage single-revision attachment extraction (audit C4)', () => {
  it('returns the attachment array for a single revision that carries one', () => {
    const view = setup();
    view.toggle(hist(1, '{"attachments":[{"id":"a1"}]}'));
    expect(view.attachments()).toHaveLength(1);
  });

  it('returns a truthy-but-empty array when the snapshot has an empty attachments array', () => {
    const view = setup();
    view.toggle(hist(1, '{"attachments":[]}'));
    expect(view.attachments()).toEqual([]);
  });

  it('hides (undefined) when the snapshot has no attachments field', () => {
    const view = setup();
    view.toggle(hist(1, '{"title":"x"}'));
    expect(view.attachments()).toBeUndefined();
  });

  it('hides (undefined) when the snapshot fails to parse', () => {
    const view = setup();
    view.toggle(hist(1, 'not-json'));
    expect(view.attachments()).toBeUndefined();
  });

  it('hides (undefined) when two revisions are selected', () => {
    const view = setup();
    view.toggle(hist(2, '{"attachments":[{"id":"a1"}]}'));
    view.toggle(hist(1, '{"attachments":[{"id":"a2"}]}'));
    expect(view.attachments()).toBeUndefined();
  });
});
