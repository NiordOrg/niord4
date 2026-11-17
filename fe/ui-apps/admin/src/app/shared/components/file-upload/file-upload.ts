import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal, WritableSignal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { catchError, concatMap, EMPTY, Observable, Subject, tap } from 'rxjs';

import { NotificationService } from '../../../core/notifications/notification.service';
import { Icon } from '../../icons/icon';
import { formatByteSize } from '../../utils/format-byte-size';
import { ProgressBar } from '../progress-bar/progress-bar';

/** A failed per-file upload, emitted so the caller can react (mutation policy). */
export interface FileUploadFailure {
  readonly fileName: string;
  /** HTTP status of the failure (0 when the request never reached the server). */
  readonly status: number;
}

/** One file's live state in the upload queue. Status is a per-item signal. */
interface UploadItem {
  readonly id: number;
  readonly fileName: string;
  readonly size: number;
  readonly status: WritableSignal<'uploading' | 'error'>;
}

/** A picked file waiting for its turn, with its row in the queue. */
interface PendingUpload {
  readonly file: File;
  readonly item: UploadItem;
}

/**
 * Generic file uploader: a drop-zone plus a browse button that **auto-uploads**
 * each selected/dropped file to the caller-supplied {@link url} as a multipart
 * request, rendering per-file activity through `<app-progress-bar>`. The bar is
 * INDETERMINATE: the app's fetch HTTP backend cannot report upload percentages
 * (Angular throws NG02824 on `reportUploadProgress` under `FetchBackend`, and
 * switching the whole app to `withXhr()` for a percentage is not worth it).
 *
 * Multipart shape: **one request per file**, each posting a single part under the
 * form field name `file` (accepted by both backend targets — the attachments endpoint
 * iterates all file parts and returns a JSON `AttachmentVo[]`; the message-map-image endpoint reads
 * `uploadForm.get("file")` and returns a plain-text path). Set {@link responseType}
 * to `'text'` for the map-image endpoint. Per-file uploads keep success and
 * failure independent, so `(uploaded)` fires once per completed file.
 *
 * Files upload **one at a time, in pick order**: every picked file is listed at once,
 * but its request starts only when the one before it has finished (or failed), also
 * across picks made in a row. `(uploaded)` therefore fires in pick order, which the
 * attachments field relies on, since the backend keeps the list order it is sent.
 * Leaving the page (destroying the component) cancels the request in flight and drops
 * the files still waiting.
 *
 * The upload {@link url} is re-read when each file's request starts — callers may change
 * it between uploads (e.g. after a save bumps the edit-repo revision). Auth + domain headers
 * are applied by the app interceptors; this component never sets headers.
 */
@Component({
  selector: 'app-file-upload',
  imports: [Icon, ProgressBar, TranslocoPipe],
  templateUrl: './file-upload.html',
  host: { class: 'contents' },
})
export class FileUpload {
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);

  /** Upload target URL. Re-read on every upload — may change between selections. */
  readonly url = input.required<string>();
  /** Allow selecting/dropping more than one file at a time. */
  readonly multiple = input(false);
  /** Native `accept` filter, e.g. `'image/png,image/jpeg,image/gif'`. */
  readonly accept = input<string | undefined>(undefined);
  /** Block all input (drag, drop, browse) when true. */
  readonly disabled = input(false);
  /** How the response body is parsed: `'json'` (default) or `'text'` (map-image). */
  readonly responseType = input<'json' | 'text'>('json');

  /** Transloco key for the browse button label. */
  readonly browseLabel = input('shared.fileUpload.browse');
  /** Transloco key for the drop-zone hint text. */
  readonly hint = input('shared.fileUpload.dropHint');
  /** Transloco key for the drop-zone accessible name. */
  readonly ariaLabel = input('shared.fileUpload.dropZoneLabel');

  /** Emits the parsed response body once per completed upload (caller narrows). */
  readonly uploaded = output<unknown>();
  /** Emits per failed upload; a toast is also raised. */
  readonly failed = output<FileUploadFailure>();

  protected readonly dragOver = signal(false);
  protected readonly queue = signal<readonly UploadItem[]>([]);

  private nextId = 0;
  private readonly pending = new Subject<PendingUpload>();

  constructor() {
    this.pending
      .pipe(
        concatMap((upload) => this.upload(upload)),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.enqueue(input.files);
    // Reset so re-selecting the same file fires `change` again.
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.disabled()) {
      this.dragOver.set(true);
    }
  }

  protected onDragLeave(event: DragEvent): void {
    event.preventDefault();
    const zone = event.currentTarget as HTMLElement;
    if (event.relatedTarget instanceof Node && zone.contains(event.relatedTarget)) {
      return;
    }
    this.dragOver.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    if (this.disabled()) {
      return;
    }
    this.enqueue(event.dataTransfer?.files);
  }

  /** Every picked file lists at once, then uploads in pick order (see the class comment). */
  private enqueue(files: FileList | null | undefined): void {
    if (this.disabled() || !files || files.length === 0) {
      return;
    }
    const picked = this.multiple() ? Array.from(files) : [files[0]];
    for (const file of picked) {
      if (file) {
        const item: UploadItem = {
          id: this.nextId++,
          fileName: file.name,
          size: file.size,
          status: signal<'uploading' | 'error'>('uploading'),
        };
        this.queue.update((list) => [...list, item]);
        this.pending.next({ file, item });
      }
    }
  }

  /** One file's request; it never errors, so a failed file does not stop the files after it. */
  private upload({ file, item }: PendingUpload): Observable<unknown> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.request(this.url(), formData).pipe(
      tap({
        next: (body) => {
          // Completed rows leave the queue — the caller renders the uploaded result itself.
          // Failed rows stay visible so the error is diagnosable.
          this.queue.update((list) => list.filter((queued) => queued !== item));
          this.uploaded.emit(body);
        },
        error: (error: HttpErrorResponse) => {
          item.status.set('error');
          this.notifications.error('shared.fileUpload.error', { fileName: item.fileName });
          this.failed.emit({ fileName: item.fileName, status: error.status ?? 0 });
        },
      }),
      catchError(() => EMPTY),
    );
  }

  private request(target: string, formData: FormData): Observable<unknown> {
    if (this.responseType() === 'text') {
      return this.http.post(target, formData, { responseType: 'text' });
    }
    return this.http.post(target, formData);
  }

  protected readonly formatSize = formatByteSize;
}
