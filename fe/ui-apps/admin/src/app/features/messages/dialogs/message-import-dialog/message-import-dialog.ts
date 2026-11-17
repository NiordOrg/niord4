import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { Select } from '../../../../shared/forms/select/select';
import { SingleTagField } from '../../../../shared/forms/single-tag-field/single-tag-field';
import { provideMessageTagsDataSource } from '../../services/api/message-tags-data-source.provider';

/** The import job's deep-link in the Batch Jobs monitor. */
const IMPORT_JOB_LINK = '/sysadmin/batch/msg-archive-import';

/** Only the CVA-bound fields live in the form; the boolean flags are plain toggle signals. */
interface MessageImportFormModel {
  seriesId: string;
  tagId: string | null;
}

/**
 * Dedicated ZIP-import dialog (admin-gated). Reuses the shared import drop-zone look
 * but adds the options form and posts a two-part `FormData` (`file` + a JSON `data`
 * part) to the message-import endpoint, which starts an async batch job. The dialog
 * shows the acknowledgment + a "view batch job" link and stays open (the list is
 * NOT refreshed — the job runs in the background, visible in Batch Jobs).
 */
@Component({
  selector: 'app-message-import-dialog',
  imports: [Dialog, HelpAnchor, FormField, Select, RouterLink, SingleTagField, TranslocoPipe],
  providers: [provideMessageTagsDataSource()],
  templateUrl: './message-import-dialog.html',
  host: { class: 'contents' },
})
export class MessageImportDialog {
  private readonly http = inject(HttpClient);
  private readonly transloco = inject(TranslocoService);
  private readonly domain = inject(DomainFacade);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  protected readonly jobLink = IMPORT_JOB_LINK;

  protected readonly visible = signal(false);
  protected readonly dragOver = signal(false);
  protected readonly uploading = signal(false);
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly resultText = signal<string | undefined>(undefined);

  protected readonly seriesIds = computed(() => [...this.domain.domainMessageSeriesIds()]);

  // The import option flags — plain boolean toggles (no validation), bound to the
  // native radio pairs via [checked]/(change) (Signal Forms radios compare against
  // the string `element.value`, so a boolean field can't drive them).
  protected readonly assignNewUids = signal(false);
  protected readonly preserveStatus = signal(false);
  protected readonly createBaseData = signal(false);
  protected readonly assignDefaultSeries = signal(false);

  private readonly formModel = signal<MessageImportFormModel>({ seriesId: '', tagId: null });
  protected readonly form = form(this.formModel, (path) => {
    required(path.seriesId);
  });

  protected readonly canUpload = computed(() => this.selectedFile() !== null && !this.uploading());

  open(): void {
    this.assignNewUids.set(false);
    this.preserveStatus.set(false);
    this.createBaseData.set(false);
    this.assignDefaultSeries.set(false);
    this.formModel.set({ seriesId: this.domain.domainMessageSeriesIds()[0] ?? '', tagId: null });
    this.form().reset();
    this.selectedFile.set(null);
    this.resultText.set(undefined);
    this.uploading.set(false);
    this.visible.set(true);
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file && this.acceptsFile(file.name)) {
      this.selectedFile.set(file);
    }
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.uploading()) {
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
    if (this.uploading()) {
      return;
    }
    const file = event.dataTransfer?.files?.[0];
    if (file && this.acceptsFile(file.name)) {
      this.selectedFile.set(file);
    }
  }

  private acceptsFile(name: string): boolean {
    return name.toLowerCase().endsWith('.zip');
  }

  protected upload(): void {
    const file = this.selectedFile();
    if (!file || this.form().invalid()) {
      return;
    }
    const { seriesId, tagId } = this.formModel();
    const data: Record<string, unknown> = {
      assignNewUids: this.assignNewUids(),
      preserveStatus: this.preserveStatus(),
      createBaseData: this.createBaseData(),
      assignDefaultSeries: this.assignDefaultSeries(),
      seriesId,
      ...(tagId ? { tagId } : {}),
    };
    const formData = new FormData();
    formData.append('file', file);
    formData.append('data', JSON.stringify(data));
    this.uploading.set(true);
    this.http
      .post(`${this.apiBaseUrl}/message-io/import`, formData, { responseType: 'text' })
      .subscribe({
        next: (text) => {
          this.uploading.set(false);
          this.resultText.set(text);
        },
        error: (error: { status?: number }) => {
          this.uploading.set(false);
          this.resultText.set(
            this.transloco.translate('messages.import.error', { status: error.status ?? 0 }),
          );
        },
      });
  }

  protected close(): void {
    this.visible.set(false);
  }
}
