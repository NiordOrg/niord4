import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';
import type {
  PrintSettings,
  ReportParams,
} from '../../../../shared/models/print/print-settings.model';
import type { MessageReport } from '../../../../shared/models/print/message-report.model';

export interface PrintRequest {
  readonly settings: PrintSettings;
  readonly reportParams: ReportParams;
  /** Auth ticket embedded in the download URL. Single-use. */
  readonly ticket: string;
}

export interface OpenPrintDialogOptions {
  readonly totalMessageNo: number;
  readonly list: boolean;
  readonly report?: MessageReport;
}

/**
 * Host-component pattern opener for the print dialog. Each page that prints
 * mounts one <app-message-print-dialog> — the Messages page, the editor shell
 * and the print reports page; this root service drives the visibility of the
 * mounted one and emits the user's submitted PrintRequest (or undefined on
 * dismiss).
 */
@Injectable({ providedIn: 'root' })
export class MessagePrintDialogService extends DialogHostService<PrintRequest> {
  readonly totalMessageNo = signal(0);
  readonly list = signal(true);
  readonly report = signal<MessageReport | undefined>(undefined);

  open(options: OpenPrintDialogOptions): Observable<PrintRequest | undefined> {
    this.totalMessageNo.set(options.totalMessageNo);
    this.list.set(options.list);
    this.report.set(options.report);
    return this.armOpen();
  }
}
