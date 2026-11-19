import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import {
  AuthTicketService,
  type TicketRole,
} from '../../../core/auth/services/auth-ticket.service';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import { provideFakeAuthFacade } from '../../../shared/testing/fake-auth';
import { TagsApiService } from './api/tags-api.service';
import { MESSAGE_EXPORT_LIMIT, MessageExportService } from './message-export.service';

function tag(): MessageTag {
  return { tagId: 'temp-1', name: 'temp-1', type: 'PRIVATE', messageCount: 0, locked: false };
}

function setup(opts: { tempTagError?: boolean; domainUserRole?: boolean } = {}) {
  const navigate = vi.fn();
  class TestMessageExportService extends MessageExportService {
    override readonly navigator = { navigate };
  }
  const createTempMessageTag = vi.fn(() =>
    opts.tempTagError ? throwError(() => new Error('boom')) : of(tag()),
  );
  const error = vi.fn();
  const notify = vi.fn();
  const fetch = vi.fn<(role?: TicketRole) => Observable<string>>(() => of('TICKET-XYZ'));
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: MessageExportService, useClass: TestMessageExportService },
      provideFakeAuthFacade({ isAuthenticated: true, domainUserRole: opts.domainUserRole ?? true }),
      { provide: AuthTicketService, useValue: { fetch } },
      { provide: TagsApiService, useValue: { createTempMessageTag } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: NotificationService,
        useValue: { error, notify, success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
    ],
  });
  return {
    service: TestBed.inject(MessageExportService),
    navigate,
    createTempMessageTag,
    error,
    notify,
    fetch,
  };
}

describe('MessageExportService', () => {
  it('export(search): ticket + lang + filter, same-tab navigate, no tag', () => {
    const { service, navigate } = setup();
    service.export({ filterParams: 'status=PUBLISHED&type=NW' }).subscribe();
    const url = navigate.mock.calls[0][0] as string;
    expect(url).toMatch(/^\/rest\/message-io\/export\.zip\?/);
    expect(url).toContain('lang=en');
    expect(url).toContain('ticket=TICKET-XYZ');
    expect(url).toContain('&status=PUBLISHED&type=NW');
    expect(url).not.toContain('tag=');
  });

  it('exportSelection: temp-tag → tag param, no filter', () => {
    const { service, navigate, createTempMessageTag } = setup();
    service.exportSelection(['m1', 'm2']).subscribe();
    expect(createTempMessageTag).toHaveBeenCalledWith(['m1', 'm2']);
    const url = navigate.mock.calls[0][0] as string;
    expect(url).toContain('tag=temp-1');
    expect(url).toContain('ticket=TICKET-XYZ');
    expect(url).not.toContain('status=');
  });

  it('asks for a user ticket when the user holds the user role in the session domain', () => {
    const { service, fetch } = setup({ domainUserRole: true });
    service.export({ filterParams: '' }).subscribe();
    service.exportSelection(['m1']).subscribe();
    expect(fetch.mock.calls).toEqual([['user'], ['user']]);
  });

  it('asks for a ticket without a role for a signed-in user with no role in the session domain', () => {
    const { service, fetch, navigate } = setup({ domainUserRole: false });
    service.export({ filterParams: '' }).subscribe();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]?.[0]).toBeUndefined();
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('a search that holds more than the archive takes says so with the download, until closed', () => {
    const { service, navigate, notify } = setup();
    service.export({ filterParams: 'status=PUBLISHED', total: 1234 }).subscribe();
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith({
      severity: 'warn',
      summaryKey: 'messages.export.truncated',
      summaryParams: { limit: 1000, total: 1234 },
      life: 0,
    });
  });

  it('a search the archive holds whole says nothing', () => {
    const { service, navigate, notify } = setup();
    service.export({ filterParams: '', total: MESSAGE_EXPORT_LIMIT }).subscribe();
    service.export({ filterParams: '' }).subscribe();
    expect(navigate).toHaveBeenCalledTimes(2);
    expect(notify).not.toHaveBeenCalled();
  });

  it('a selection of more messages than the archive takes says so too', () => {
    const { service, notify } = setup();
    const ids = Array.from({ length: MESSAGE_EXPORT_LIMIT + 1 }, (_, i) => `m${i}`);
    service.exportSelection(ids).subscribe();
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ summaryParams: { limit: 1000, total: 1001 } }),
    );
  });

  it('temp-tag failure → error toast, no navigation', () => {
    const { service, navigate, error } = setup({ tempTagError: true });
    service.exportSelection(['m1']).subscribe();
    expect(navigate).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('messages.export.errorTempTag');
  });
});
