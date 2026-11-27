import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import type { TestRequest } from '@angular/common/http/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { TICKETED_REQUEST } from '../../../core/interceptors/ticketed-request';
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

function pdf(): Blob {
  return new Blob(['%PDF-1.4'], { type: 'application/pdf' });
}

function tag(): MessageTag {
  return { tagId: 'temp-1', name: 'temp-1', type: 'PRIVATE', messageCount: 0, locked: false };
}

function setup(opts: SetupOpts = {}) {
  // Saving the answer is the service's protected seam, which a subclass records instead of
  // clicking a link.
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
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      { provide: MessagePrintService, useClass: TestMessagePrintService },
      { provide: MessagePrintDialogService, useValue: dialogStub },
      { provide: TagsApiService, useValue: tagsStub },
      { provide: MessageReportsApiService, useValue: reportsStub },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: NotificationService, useValue: notifyStub },
    ],
  });

  const http = TestBed.inject(HttpTestingController);
  return {
    service: TestBed.inject(MessagePrintService),
    http,
    /** The one request for the PDF, of any address. */
    pdfRequest: (): TestRequest => http.expectOne(() => true),
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
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  describe('the PDF', () => {
    it("is fetched as a ticketed request, kept by nothing, and saved under the backend's name", () => {
      const { service, pdfRequest, save, notifyError } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();

      const req = pdfRequest();
      expect(req.request.method).toBe('GET');
      expect(req.request.responseType).toBe('blob');
      expect(req.request.context.get(TICKETED_REQUEST)).toBe(true);
      expect(req.request.cache).toBe('no-store');
      req.flush(pdf(), {
        headers: { 'Content-Disposition': 'attachment; filename="messages.pdf"' },
      });

      expect(save).toHaveBeenCalledTimes(1);
      const [file, name] = save.mock.calls[0] as [Blob, string];
      expect(file.size).toBeGreaterThan(0);
      expect(name).toBe('messages.pdf');
      expect(notifyError).not.toHaveBeenCalled();
    });

    it('takes the name the backend wrote encoded, and the default one when it names none', () => {
      const named = setup({ dialogResult: printRequest() });
      named.service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      named.pdfRequest().flush(pdf(), {
        headers: { 'Content-Disposition': 'attachment; filename="Notices%20to%20Mariners.pdf"' },
      });
      expect(named.save.mock.calls[0][1]).toBe('Notices to Mariners.pdf');

      TestBed.resetTestingModule();
      const unnamed = setup({ dialogResult: printRequest() });
      unnamed.service.printMessage('NM-123').subscribe();
      unnamed.pdfRequest().flush(pdf());
      expect(unnamed.save.mock.calls[0][1]).toBe('message-NM-123.pdf');
    });

    // A navigation showed whatever the backend answered, so a failed print replaced the
    // application with the backend's English error document.
    it('that the backend cannot make is not saved, and a toast says it could not be generated', () => {
      for (const status of [403, 404, 500, 503]) {
        TestBed.resetTestingModule();
        const { service, pdfRequest, save, notifyError } = setup({ dialogResult: printRequest() });
        service.printMessage('NM-123').subscribe();
        pdfRequest().flush(
          new Blob([JSON.stringify({ status, detail: 'Message NM-123 not found' })], {
            type: 'application/problem+json',
          }),
          { status, statusText: 'Error' },
        );

        expect(save, `status ${status}`).not.toHaveBeenCalled();
        expect(notifyError).toHaveBeenCalledWith('messages.print.errorDocument');
      }
    });

    it('that never arrives is not saved, and a toast says it could not be generated', () => {
      const { service, pdfRequest, save, notifyError } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      pdfRequest().error(new ProgressEvent('error'));

      expect(save).not.toHaveBeenCalled();
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorDocument');
    });
  });

  describe('printList', () => {
    it('opens dialog with totalMessageNo + list=true, builds URL, fetches it', () => {
      const { service, open, pdfRequest } = setup({ dialogResult: printRequest() });
      service
        .printList({ totalMessageNo: 42, filterParams: 'status=PUBLISHED&type=NW' })
        .subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 42, list: true });
      const req = pdfRequest();
      const url = req.request.url;
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
      req.flush(pdf());
    });

    it('empty filterParams produces no trailing & after print params', () => {
      const { service, pdfRequest } = setup({ dialogResult: printRequest() });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      const req = pdfRequest();
      expect(req.request.url.endsWith('&')).toBe(false);
      req.flush(pdf());
    });

    it('dialog dismiss → no fetch', () => {
      const { service, http } = setup({ dialogResult: undefined });
      service.printList({ totalMessageNo: 1, filterParams: '' }).subscribe();
      http.expectNone(() => true);
    });
  });

  describe('printSelection', () => {
    it('creates a temp tag, appends &tag=<id>, fetches it', () => {
      const { service, createTempMessageTag, pdfRequest } = setup({ dialogResult: printRequest() });
      service.printSelection(['m1', 'm2'], 'status=PUBLISHED').subscribe();

      expect(createTempMessageTag).toHaveBeenCalledWith(['m1', 'm2']);
      const req = pdfRequest();
      expect(req.request.url).toContain('tag=temp-1');
      expect(req.request.url).toContain('&status=PUBLISHED');
      req.flush(pdf());
    });

    it('dialog dismiss → no temp tag, no fetch', () => {
      const { service, createTempMessageTag, http } = setup({ dialogResult: undefined });
      service.printSelection(['m1'], '').subscribe();
      expect(createTempMessageTag).not.toHaveBeenCalled();
      http.expectNone(() => true);
    });

    it('temp-tag failure → error toast, no fetch', () => {
      const { service, http, notifyError } = setup({
        dialogResult: printRequest(),
        tempTagError: true,
      });
      service.printSelection(['m1'], '').subscribe();
      http.expectNone(() => true);
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorTempTag');
    });

    it('a document that cannot be made says so, not that the selection could not be marked', () => {
      const { service, pdfRequest, notifyError } = setup({ dialogResult: printRequest() });
      service.printSelection(['m1'], '').subscribe();
      pdfRequest().flush(new Blob(), { status: 500, statusText: 'Error' });

      expect(notifyError).toHaveBeenCalledTimes(1);
      expect(notifyError).toHaveBeenCalledWith('messages.print.errorDocument');
    });
  });

  describe('printMessage', () => {
    it('opens dialog with list=false, fetches the per-message URL (no filter params)', () => {
      const { service, open, pdfRequest } = setup({ dialogResult: printRequest() });
      service.printMessage('NM-123').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 1, list: false });
      const req = pdfRequest();
      const url = req.request.url;
      expect(url).toMatch(/^\/rest\/message-reports\/message\/NM-123\.pdf\?/);
      expect(url).toContain('pageSize=A4');
      expect(url).toContain('ticket=TICKET-XYZ');
      expect(url).toContain('lang=en');
      // No filter params should be appended for the single-message endpoint.
      expect(url).not.toContain('status=');
      req.flush(pdf());
    });

    it('URL-encodes the messageId in the path', () => {
      const { service, pdfRequest } = setup({ dialogResult: printRequest() });
      service.printMessage('NM/123 X').subscribe();
      const req = pdfRequest();
      expect(req.request.url).toContain('/message/NM%2F123%20X.pdf');
      req.flush(pdf());
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
      const { service, open, pdfRequest } = setup({ dialogResult: printRequest() });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(open).toHaveBeenCalledWith({ totalMessageNo: 0, list: true, report });
      const req = pdfRequest();
      expect(req.request.url).toMatch(/^\/rest\/message-reports\/report\.pdf\?/);
      expect(req.request.url).toContain('tag=tag-99');
      // A test-print appends no filter params.
      expect(req.request.url).not.toContain('status=');
      req.flush(pdf());
    });

    it('dialog dismiss → no fetch', () => {
      const { service, http } = setup({ dialogResult: undefined });
      service.printReportTest(report, 'tag-99').subscribe();
      http.expectNone(() => true);
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
      const { service, open, http, notifyError } = setup({
        dialogResult: printRequest(),
        expandError: true,
      });
      service.printReportTest(report, 'tag-99').subscribe();

      expect(notifyError).toHaveBeenCalledWith('messages.print.errorExpandParams');
      expect(open).not.toHaveBeenCalled();
      http.expectNone(() => true);
    });
  });
});
