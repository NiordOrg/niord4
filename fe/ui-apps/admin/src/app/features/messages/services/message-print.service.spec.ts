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
  /** What the backend answers for the PDF; a PDF named as the backend names a list by default. */
  readonly answer?: () => Promise<Response>;
}

function pdf(headers: Record<string, string> = {}): Response {
  return new Response(new Blob(['%PDF-1.4'], { type: 'application/pdf' }), {
    status: 200,
    headers: { 'Content-Type': 'application/pdf', ...headers },
  });
}

/** Lets the fetch and the reading of its answer run. */
async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve));
}

function tag(): MessageTag {
  return { tagId: 'temp-1', name: 'temp-1', type: 'PRIVATE', messageCount: 0, locked: false };
}

function setup(opts: SetupOpts = {}) {
  // The browser's fetch is the service's one request for the PDF; saving the answer is the
  // service's protected seam, which a subclass records instead of clicking a link.
  const answer =
    opts.answer ??
    (async () => pdf({ 'Content-Disposition': 'attachment; filename="messages.pdf"' }));
  const fetched = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(() => answer());
  vi.stubGlobal('fetch', fetched);
  const save = vi.fn();
  class TestMessagePrintService extends MessagePrintService {
    protected override saveFile(file: Blob, name: string): void {
      save(file, name);
    }
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
    fetched,
    save,
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
  afterEach(() => vi.unstubAllGlobals());

  describe('the PDF', () => {
    it("is fetched with the address alone, kept by nothing, and saved under the backend's name", async () => {
      const { service, fetched, save, notifyError } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      await settle();

      expect(fetched).toHaveBeenCalledTimes(1);
      expect(fetched.mock.calls[0][1]).toEqual({ cache: 'no-store' });
      expect(save).toHaveBeenCalledTimes(1);
      const [file, name] = save.mock.calls[0] as [Blob, string];
      expect(file.size).toBeGreaterThan(0);
      expect(name).toBe('messages.pdf');
      expect(notifyError).not.toHaveBeenCalled();
    });

    it('takes the name the backend wrote encoded, and the default one when it names none', async () => {
      const named = setup({
        dialogResult: printRequest(),
        answer: async () =>
          pdf({ 'Content-Disposition': 'attachment; filename="Notices%20to%20Mariners.pdf"' }),
      });
      named.service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      await settle();
      expect(named.save.mock.calls[0][1]).toBe('Notices to Mariners.pdf');

      TestBed.resetTestingModule();
      const unnamed = setup({ dialogResult: printRequest(), answer: async () => pdf() });
      unnamed.service.printMessage('NM-123').subscribe();
      await settle();
      expect(unnamed.save.mock.calls[0][1]).toBe('message-NM-123.pdf');
    });

    // A navigation showed whatever the backend answered, so a failed print replaced the
    // application with the backend's English error document.
    it('that the backend cannot make is not saved, and a toast says it could not be generated', async () => {
      for (const status of [403, 404, 500, 503]) {
        TestBed.resetTestingModule();
        const { service, save, notifyError } = setup({
          dialogResult: printRequest(),
          answer: async () =>
            new Response(JSON.stringify({ status, detail: 'Message NM-123 not found' }), {
              status,
              headers: { 'Content-Type': 'application/problem+json' },
            }),
        });
        service.printMessage('NM-123').subscribe();
        await settle();

        expect(save, `status ${status}`).not.toHaveBeenCalled();
        expect(notifyError).toHaveBeenCalledWith('messages.print.errorDocument');
      }
    });

    it('that never arrives is not saved, and a toast says it could not be generated', async () => {
      const { service, save, notifyError } = setup({
        dialogResult: printRequest(),
        answer: () => Promise.reject(new TypeError('Failed to fetch')),
      });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      await settle();

      expect(save).not.toHaveBeenCalled();
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorDocument');
    });
  });

  describe('printList', () => {
    it('opens dialog with totalMessageNo + list=true, builds URL, fetches it', () => {
      const { service, open, fetched } = setup({ dialogResult: printRequest() });
      service
        .printList({ totalMessageNo: 42, filterParams: 'status=PUBLISHED&type=NW' })
        .subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 42, list: true });
      expect(fetched).toHaveBeenCalledTimes(1);
      const url = fetched.mock.calls[0][0] as string;
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
      const { service, fetched } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      const url = fetched.mock.calls[0][0] as string;
      expect(url.endsWith('&')).toBe(false);
    });

    it('dialog dismiss → no fetch', () => {
      const { service, fetched } = setup({ dialogResult: undefined });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      expect(fetched).not.toHaveBeenCalled();
    });
  });

  describe('printSelection', () => {
    it('creates a temp tag, appends &tag=<id>, fetches it', () => {
      const { service, createTempMessageTag, fetched } = setup({ dialogResult: printRequest() });
      service.printSelection(['m1', 'm2'], 'status=PUBLISHED').subscribe();

      expect(createTempMessageTag).toHaveBeenCalledWith(['m1', 'm2']);
      const url = fetched.mock.calls[0][0] as string;
      expect(url).toContain('tag=temp-1');
      expect(url).toContain('&status=PUBLISHED');
    });

    it('dialog dismiss → no temp tag, no fetch', () => {
      const { service, createTempMessageTag, fetched } = setup({ dialogResult: undefined });
      service.printSelection(['m1'], '').subscribe();
      expect(createTempMessageTag).not.toHaveBeenCalled();
      expect(fetched).not.toHaveBeenCalled();
    });

    it('temp-tag failure → error toast, no fetch', () => {
      const { service, fetched, notifyError } = setup({
        dialogResult: printRequest(),
        tempTagError: true,
      });
      service.printSelection(['m1'], '').subscribe();
      expect(fetched).not.toHaveBeenCalled();
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorTempTag');
    });
  });

  describe('printMessage', () => {
    it('opens dialog with list=false, fetches the per-message URL (no filter params)', () => {
      const { service, open, fetched } = setup({ dialogResult: printRequest() });
      service.printMessage('NM-123').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 1, list: false });
      const url = fetched.mock.calls[0][0] as string;
      expect(url).toMatch(/^\/rest\/message-reports\/message\/NM-123\.pdf\?/);
      expect(url).toContain('pageSize=A4');
      expect(url).toContain('ticket=TICKET-XYZ');
      expect(url).toContain('lang=en');
      // No filter params should be appended for the single-message endpoint.
      expect(url).not.toContain('status=');
    });

    it('URL-encodes the messageId in the path', () => {
      const { service, fetched } = setup({ dialogResult: printRequest() });
      service.printMessage('NM/123 X').subscribe();
      const url = fetched.mock.calls[0][0] as string;
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

    it('opens the dialog with the hardcoded report, appends the chosen tag, fetches it (no filter)', () => {
      const { service, open, fetched } = setup({ dialogResult: printRequest() });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 0, list: true, report });
      const url = fetched.mock.calls[0][0] as string;
      expect(url).toMatch(/^\/rest\/message-reports\/report\.pdf\?/);
      expect(url).toContain('tag=tag-99');
      // A test-print appends no filter params.
      expect(url).not.toContain('status=');
    });

    it('dialog dismiss → no fetch', () => {
      const { service, fetched } = setup({ dialogResult: undefined });
      service.printReportTest(report, 'tag-99').subscribe();
      expect(fetched).not.toHaveBeenCalled();
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
      const { service, open, fetched, notifyError } = setup({
        dialogResult: printRequest(),
        expandError: true,
      });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(notifyError).toHaveBeenCalledWith('messages.print.errorExpandParams');
      expect(open).not.toHaveBeenCalled();
      expect(fetched).not.toHaveBeenCalled();
    });
  });
});
