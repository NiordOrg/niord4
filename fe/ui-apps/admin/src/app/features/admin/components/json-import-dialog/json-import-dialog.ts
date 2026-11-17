import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';

/**
 * Shared JSON-import dialog for the admin sections: a custom drop zone whose
 * picker button + drop hint use app-language text, auto-uploads the chosen file
 * on selection/drop, and shows the backend's
 * raw response in a read-only result area. The dialog stays open after upload so
 * the operator can read the batch-job submission text; imports run as background
 * jobs server-side.
 *
 * Files whose extension is not in `accept` (default `.json`) are deliberately
 * dropped silently, with no feedback. Both the native `accept` attribute and
 * the silent-rejection guard
 * derive from the same `accept` input, so a consumer that allows other formats
 * (e.g. `.zip`) only sets one place. Consumers refetch their list when `closed`
 * fires, regardless of close path.
 *
 * The upload goes through `HttpClient`, so the auth + domain interceptors add
 * both headers exactly as on every other API call.
 */
@Component({
  selector: 'app-json-import-dialog',
  imports: [Dialog, RouterLink, TranslocoPipe],
  templateUrl: './json-import-dialog.html',
})
export class JsonImportDialog {
  private readonly http = inject(HttpClient);
  private readonly transloco = inject(TranslocoService);

  /** Dialog header, e.g. `admin.sources.import.title` ("Upload Sources File"). */
  readonly titleKey = input.required<string>();
  /** Absolute upload endpoint, e.g. `${apiBaseUrl}/sources/upload-sources`. */
  readonly uploadUrl = input.required<string>();
  /** Entity label key used in the error message (each section supplies its own, never a hardcoded default). */
  readonly entityKey = input.required<string>();
  /**
   * Comma-separated accepted file extensions (the value of the native `accept`
   * attribute). Drives both the picker filter and the silent-rejection guard,
   * so a consumer only sets it once. Defaults to `.json`.
   */
  readonly accept = input<string>('.json');
  /**
   * Optional router link to a related batch job (e.g. `/sysadmin/batch/{name}`).
   * When set AND an upload has produced a result, a "view batch job" link
   * renders in the result area (additive — consumers that omit it see no link).
   */
  readonly jobLink = input<string | undefined>(undefined);

  readonly closed = output<void>();

  protected readonly visible = signal(false);
  protected readonly uploading = signal(false);
  protected readonly dragOver = signal(false);
  protected readonly resultText = signal<string | undefined>(undefined);

  /** Lowercased, dot-stripped extensions derived from `accept`. */
  private readonly acceptedExtensions = computed(() =>
    this.accept()
      .split(',')
      .map((ext) => ext.trim().toLowerCase().replace(/^\./, ''))
      .filter((ext) => ext.length > 0),
  );

  open(): void {
    this.resultText.set(undefined);
    this.uploading.set(false);
    this.visible.set(true);
  }

  protected onFileSelected(event: Event): void {
    const fileInput = event.target as HTMLInputElement;
    const file = fileInput.files?.[0];
    if (file) {
      this.handleFile(file);
    }
    // Allow re-selecting the same file after a rejected or failed attempt.
    fileInput.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    // preventDefault on dragover is required for the subsequent drop to fire.
    event.preventDefault();
    if (this.uploading()) {
      return;
    }
    this.dragOver.set(true);
  }

  protected onDragLeave(event: DragEvent): void {
    event.preventDefault();
    // dragleave also fires when crossing INTO a child of the zone — only clear
    // the highlight when the pointer actually leaves the zone.
    const zone = event.currentTarget as HTMLElement;
    if (event.relatedTarget instanceof Node && zone.contains(event.relatedTarget)) {
      return;
    }
    this.dragOver.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    if (this.uploading()) {
      return;
    }
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.handleFile(file);
    }
  }

  /**
   * Validate against the accepted extensions then auto-upload. A rejected file
   * deliberately produces no state change at all.
   */
  private handleFile(file: File): void {
    if (!this.acceptsFile(file.name)) {
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    this.uploading.set(true);
    this.http.post(this.uploadUrl(), formData, { responseType: 'text' }).subscribe({
      next: (text) => {
        this.uploading.set(false);
        this.resultText.set(text);
      },
      error: (error: { status?: number }) => {
        this.uploading.set(false);
        this.resultText.set(
          this.transloco.translate('admin.import.error', {
            entity: this.transloco.translate(this.entityKey()),
            status: error.status ?? 0,
          }),
        );
      },
    });
  }

  /** True iff the filename ends with one of the accepted extensions. */
  private acceptsFile(fileName: string): boolean {
    const lower = fileName.toLowerCase();
    return this.acceptedExtensions().some((ext) => lower.endsWith(`.${ext}`));
  }

  protected close(): void {
    this.visible.set(false);
  }

  protected onHide(): void {
    this.closed.emit();
  }
}
