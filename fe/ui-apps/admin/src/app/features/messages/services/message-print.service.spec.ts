import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { MessagePrintDialogService } from '../dialogs/message-print-dialog/message-print-dialog.service';
import type { PrintRequest } from '../dialogs/message-print-dialog/message-print-dialog.service';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import type { MessageReport } from '../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from './api/message-reports-api.service';
import { TagsApiService } from './api/tags-api.service';
import { MessagePrintService } from './message-print.service';

const API_BASE = '/rest';

interface SetupOpts {
  readonly dialogResult?: PrintRequest | undefined;
  readonly tempTagError?: boolean;
  /** What the backend answers when asked to expand a report's parameters. */
  readonly expandedParams?: Record<string, string>;
  readonly expandError?: boolean;
}

function tag(): MessageTag {
  return { tagId: 'temp-1', name: 'temp-1', type: 'PRIVATE', messageCount: 0, locked: false };
}

function setup(opts: SetupOpts = {}) {
  const navigate = vi.fn();
  // Provide a stub MessagePrintService that uses our test navigator. The
  // production service exposes `navigator` as a protected field; we override
  // the whole service in the TestBed with a subclass that flips it public.
  class TestMessagePrintService extends MessagePrintService {
    override readonly navigator = { navigate };
  }

  const open = vi.fn(() => of(opts.dialogResult));
  const dialogStub = { open };

  const createTempMessageTag = vi.fn(() =>
    opts.tempTagError ? throwError(() => new Error('boom')) : of(tag()),
  );
  const tagsStub = { createTempMessageTag };

  const expandReportParams = vi.fn((report: MessageReport) =>
    opts.expandError
      ? throwError(() => new Error('boom'))
      : of({ ...report, params: opts.expandedParams ?? report.params }),
  );
  const reportsStub = { expandReportParams };

  const error = vi.fn();
  const notifyStub = { error, success: vi.fn(), info: vi.fn(), warn: vi.fn(), notify: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      { provide: MessagePrintService, useClass: TestMessagePrintService },
      { provide: MessagePrintDialogService, useValue: dialogStub },
      { provide: TagsApiService, useValue: tagsStub },
      { provide: MessageReportsApiService, useValue: reportsStub },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: NotificationService, useValue: notifyStub },
    ],
  });

  return {
    service: TestBed.inject(MessagePrintService),
    navigate,
    open,
    createTempMessageTag,
    expandReportParams,
    notifyError: error,
  };
}

function printRequest(overrides: Partial<PrintRequest['settings']> = {}): PrintRequest {
  return {
    settings: {
      pageSize: 'A4',
      pageOrientation: 'portrait',
      mapThumbnails: false,
      report: 'summary',
      ...overrides,
    },
    reportParams: { foo: 'bar', emptyParam: '' },
    ticket: 'TICKET-XYZ',
  };
}

describe('MessagePrintService', () => {
  beforeEach(() => undefined);
  afterEach(() => undefined);

  describe('printList', () => {
    it('opens dialog with totalMessageNo + list=true, builds URL, navigates', () => {
      const { service, open, navigate } = setup({ dialogResult: printRequest() });
      service
        .printList({ totalMessageNo: 42, filterParams: 'status=PUBLISHED&type=NW' })
        .subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 42, list: true });
      expect(navigate).toHaveBeenCalledTimes(1);
      const url = navigate.mock.calls[0][0] as string;
      expect(url).toMatch(/^\/rest\/message-reports\/report\.pdf\?/);
      expect(url).toContain('report=summary');
      expect(url).toContain('pageSize=A4');
      expect(url).toContain('pageOrientation=portrait');
      expect(url).toContain('mapThumbnails=false');
      expect(url).toContain('param%3Afoo=bar');
      expect(url).toContain('ticket=TICKET-XYZ');
      expect(url).toContain('lang=en');
      expect(url).toContain('&status=PUBLISHED&type=NW');
      // Empty reportParam value is skipped
      expect(url).not.toContain('emptyParam');
    });

    it('empty filterParams produces no trailing & after print params', () => {
      const { service, navigate } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      const url = navigate.mock.calls[0][0] as string;
      expect(url.endsWith('&')).toBe(false);
    });

    it('dialog dismiss → no navigation', () => {
      const { service, navigate } = setup({ dialogResult: undefined });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      expect(navigate).not.toHaveBeenCalled();
    });
  });

  describe('printSelection', () => {
    it('creates a temp tag, appends &tag=<id>, navigates', () => {
      const { service, createTempMessageTag, navigate } = setup({ dialogResult: printRequest() });
      service.printSelection(['m1', 'm2'], 'status=PUBLISHED').subscribe();

      expect(createTempMessageTag).toHaveBeenCalledWith(['m1', 'm2']);
      const url = navigate.mock.calls[0][0] as string;
      expect(url).toContain('tag=temp-1');
      expect(url).toContain('&status=PUBLISHED');
    });

    it('dialog dismiss → no temp tag, no navigation', () => {
      const { service, createTempMessageTag, navigate } = setup({ dialogResult: undefined });
      service.printSelection(['m1'], '').subscribe();
      expect(createTempMessageTag).not.toHaveBeenCalled();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('temp-tag failure → error toast, no navigation', () => {
      const { service, navigate, notifyError } = setup({
        dialogResult: printRequest(),
        tempTagError: true,
      });
      service.printSelection(['m1'], '').subscribe();
      expect(navigate).not.toHaveBeenCalled();
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorTempTag');
    });
  });

  describe('printMessage', () => {
    it('opens dialog with list=false, navigates to per-message URL (no filter params)', () => {
      const { service, open, navigate } = setup({ dialogResult: printRequest() });
      service.printMessage('NM-123').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 1, list: false });
      const url = navigate.mock.calls[0][0] as string;
      expect(url).toMatch(/^\/rest\/message-reports\/message\/NM-123\.pdf\?/);
      expect(url).toContain('pageSize=A4');
      expect(url).toContain('ticket=TICKET-XYZ');
      expect(url).toContain('lang=en');
      // No filter params should be appended for the single-message endpoint.
      expect(url).not.toContain('status=');
    });

    it('URL-encodes the messageId in the path', () => {
      const { service, navigate } = setup({ dialogResult: printRequest() });
      service.printMessage('NM/123 X').subscribe();
      const url = navigate.mock.calls[0][0] as string;
      expect(url).toContain('/message/NM%2F123%20X.pdf');
    });
  });

  describe('printReportTest', () => {
    const report: MessageReport = {
      reportId: 'standard',
      name: 'Standard',
      properties: {},
      params: {},
    };

    it('opens the dialog with the hardcoded report, appends the chosen tag, navigates (no filter)', () => {
      const { service, open, navigate } = setup({ dialogResult: printRequest() });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 0, list: true, report });
      const url = navigate.mock.calls[0][0] as string;
      expect(url).toMatch(/^\/rest\/message-reports\/report\.pdf\?/);
      expect(url).toContain('tag=tag-99');
      // A test-print appends no filter params.
      expect(url).not.toContain('status=');
    });

    it('dialog dismiss → no navigation', () => {
      const { service, navigate } = setup({ dialogResult: undefined });
      service.printReportTest(report, 'tag-99').subscribe();
      expect(navigate).not.toHaveBeenCalled();
    });

    // Only /reports expands a stored report's ${year} and ${week}; the in-edit report is sent to
    // the backend for it, and the dialog opens with the answer's parameters.
    it("opens the dialog with the report's parameters as the backend expanded them", () => {
      const raw: MessageReport = {
        ...report,
        params: { title: 'Notices ${year}', week: '${week}' },
      };
      const { service, open, expandReportParams } = setup({
        dialogResult: undefined,
        expandedParams: { title: 'Notices 2026', week: '46' },
      });
      service.printReportTest(raw).subscribe();

      expect(expandReportParams).toHaveBeenCalledWith(raw);
      expect(open).toHaveBeenCalledWith({
        totalMessageNo: 0,
        list: true,
        report: { ...raw, params: { title: 'Notices 2026', week: '46' } },
      });
    });

    it('says the parameters could not be prepared and opens no dialog when the expansion fails', () => {
      const { service, open, navigate, notifyError } = setup({
        dialogResult: printRequest(),
        expandError: true,
      });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(notifyError).toHaveBeenCalledWith('messages.print.errorExpandParams');
      expect(open).not.toHaveBeenCalled();
      expect(navigate).not.toHaveBeenCalled();
    });
  });
});
