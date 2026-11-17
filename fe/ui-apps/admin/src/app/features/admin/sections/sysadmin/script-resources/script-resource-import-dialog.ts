import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { Subscription } from 'rxjs';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { CodeMergeView } from '../../../components/code-merge-view/code-merge-view';
import type { CodeLanguage } from '../../../components/code-text-input/codemirror-editor';
import { ScriptResourceTypeBadge } from '../../../components/script-resource-type-badge/script-resource-type-badge';
import {
  ScriptResourceImportPreview,
  ScriptResourcesApiService,
  ScriptResourceType,
} from '../../../services/api/script-resources-api.service';
import { backendReason, type ImportFailure, JOB_STARTED } from '../import-failure';

/**
 * The Script resources import: the sysadmin picks a file, sees what importing it would do, and
 * chooses. Picking (or dropping) a `.json` file asks the backend for a preview, which writes
 * nothing: the paths the file would add, each ticked, marked when the missing-path report lists
 * it; the paths it would overwrite, each unticked, with a diff of the deployment's row against the
 * file; how many are unchanged; and the entries it refuses, with the backend's reasons. Import
 * sends the file with the ticked paths, so a path that is not ticked is neither added nor
 * overwritten. The job runs in the background; the dialog shows the backend's answer and stays
 * open. Another file's extension is dropped silently, as in the other sections' import dialog.
 * `closed` fires on every close path, so the page refetches its list.
 *
 * Every answer belongs to the file it was asked for: a new file, `open()` and every close cancel
 * the pending preview and import, and an answer that still arrives after them is dropped, so a
 * late preview is never shown, nor ticked and imported, against another file.
 */
@Component({
  selector: 'app-script-resource-import-dialog',
  imports: [Dialog, ProgressBar, CodeMergeView, ScriptResourceTypeBadge, RouterLink, TranslocoPipe],
  templateUrl: './script-resource-import-dialog.html',
})
export class ScriptResourceImportDialog {
  private readonly api = inject(ScriptResourcesApiService);
  private readonly transloco = inject(TranslocoService);

  readonly closed = output<void>();

  protected readonly visible = signal(false);
  protected readonly busy = signal(false);
  protected readonly dragOver = signal(false);
  protected readonly file = signal<File | undefined>(undefined);
  protected readonly preview = signal<ScriptResourceImportPreview | undefined>(undefined);
  /** The failure of the last preview. */
  protected readonly previewError = signal<ImportFailure | undefined>(undefined);
  /** The backend's answer to an import whose job started; once set, the ticks are locked. */
  protected readonly resultText = signal<string | undefined>(undefined);
  /** The failure of the last import, or an answer that started no job; the ticks stay open. */
  protected readonly importError = signal<ImportFailure | undefined>(undefined);
  protected readonly addTicked = signal<ReadonlySet<string>>(new Set());
  protected readonly overwriteTicked = signal<ReadonlySet<string>>(new Set());
  /** The overwrite whose diff is open; one at a time. */
  protected readonly diffPath = signal<string | undefined>(undefined);

  /** Moves on every reset; an answer asked for under an earlier generation is dropped. */
  private generation = 0;
  /** The generation the shown preview was asked for. */
  private previewGeneration = -1;
  private previewRequest?: Subscription;
  private importRequest?: Subscription;

  protected readonly hasListed = computed(
    () => this.preview()?.add.some((entry) => entry.listed) ?? false,
  );
  protected readonly hasChoices = computed(() => {
    const preview = this.preview();
    return !!preview && preview.add.length + preview.overwrite.length > 0;
  });
  protected readonly tickedCount = computed(
    () => this.addTicked().size + this.overwriteTicked().size,
  );
  protected readonly canImport = computed(
    () =>
      !!this.preview() &&
      !!this.file() &&
      this.tickedCount() > 0 &&
      !this.busy() &&
      this.resultText() === undefined,
  );
  /** The wide layout once there is a preview to read; the picker alone stays narrow. */
  protected readonly panelStyle = computed(() =>
    this.preview() ? { width: '60rem', maxWidth: '95vw' } : { width: '28rem', maxWidth: '92vw' },
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancelRequests());
  }

  open(): void {
    this.reset();
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
    if (!this.busy()) {
      this.dragOver.set(true);
    }
  }

  protected onDragLeave(event: DragEvent): void {
    event.preventDefault();
    // dragleave also fires when crossing INTO a child of the zone.
    const zone = event.currentTarget as HTMLElement;
    if (event.relatedTarget instanceof Node && zone.contains(event.relatedTarget)) {
      return;
    }
    this.dragOver.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file && !this.busy()) {
      this.handleFile(file);
    }
  }

  /** A new file starts over: its preview replaces the last one and nothing is imported yet. */
  private handleFile(file: File): void {
    if (!file.name.toLowerCase().endsWith('.json')) {
      return;
    }
    this.reset();
    const generation = this.generation;
    this.file.set(file);
    this.busy.set(true);
    this.previewRequest = this.api.previewImport(file).subscribe({
      next: (preview) => {
        if (generation !== this.generation) {
          return;
        }
        this.busy.set(false);
        this.previewGeneration = generation;
        this.preview.set(preview);
        this.addTicked.set(new Set(preview.add.map((entry) => entry.path)));
      },
      error: (error: unknown) => {
        if (generation !== this.generation) {
          return;
        }
        this.busy.set(false);
        this.previewError.set(this.failure(error));
      },
    });
  }

  protected toggleAdd(path: string, checked: boolean): void {
    this.addTicked.update((ticked) => toggled(ticked, path, checked));
  }

  protected toggleOverwrite(path: string, checked: boolean): void {
    this.overwriteTicked.update((ticked) => toggled(ticked, path, checked));
  }

  /** Ticks exactly the paths the missing-path report lists, as a release's README advises. */
  protected tickOnlyListed(): void {
    const add = this.preview()?.add ?? [];
    this.addTicked.set(new Set(add.filter((entry) => entry.listed).map((entry) => entry.path)));
  }

  protected toggleDiff(path: string): void {
    this.diffPath.update((open) => (open === path ? undefined : path));
  }

  protected languageOf(type: ScriptResourceType): CodeLanguage {
    return type === 'JS' ? 'javascript' : 'ftl';
  }

  /**
   * Sends the file with the ticked paths, each overwrite with the hash of the row its diff showed.
   * The file and the preview are of one generation: a reset clears both and a late preview is
   * dropped; the generation check below says so outright.
   */
  protected importTicked(): void {
    const file = this.file();
    const preview = this.preview();
    if (!file || !preview || !this.canImport() || this.previewGeneration !== this.generation) {
      return;
    }
    const generation = this.generation;
    // In the preview's order, which is the backend's (sorted by path).
    const add = preview.add.map((e) => e.path).filter((path) => this.addTicked().has(path));
    const overwrite = preview.overwrite
      .filter((entry) => this.overwriteTicked().has(entry.path))
      .map(({ path, basedOn }) => ({ path, basedOn }));
    this.busy.set(true);
    this.importError.set(undefined);
    this.importRequest = this.api.importFile(file, add, overwrite).subscribe({
      next: (text) => {
        if (generation !== this.generation) {
          return;
        }
        this.busy.set(false);
        if (text.startsWith(JOB_STARTED)) {
          this.resultText.set(text);
        } else {
          // "Error executing batch job …": no job exists, so there is nothing to link to.
          this.importError.set({
            message: this.transloco.translate('admin.import.notStarted'),
            reason: text.trim() || undefined,
          });
        }
      },
      error: (error: unknown) => {
        if (generation !== this.generation) {
          return;
        }
        this.busy.set(false);
        this.importError.set(this.failure(error));
      },
    });
  }

  /** The keyed sentence, and for a 4xx the backend's reason as it sends it. */
  private failure(error: unknown): ImportFailure {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    return {
      message: this.transloco.translate('admin.import.error', {
        entity: this.transloco.translate('admin.sections.script-resources.label'),
        status,
      }),
      reason: status >= 400 && status < 500 ? backendReason(error) : undefined,
    };
  }

  private cancelRequests(): void {
    this.previewRequest?.unsubscribe();
    this.importRequest?.unsubscribe();
    this.previewRequest = undefined;
    this.importRequest = undefined;
  }

  /** Starts a new generation: the pending requests are cancelled and their answers dropped. */
  private reset(): void {
    this.cancelRequests();
    this.generation++;
    this.file.set(undefined);
    this.preview.set(undefined);
    this.previewError.set(undefined);
    this.resultText.set(undefined);
    this.importError.set(undefined);
    this.addTicked.set(new Set());
    this.overwriteTicked.set(new Set());
    this.diffPath.set(undefined);
    this.busy.set(false);
  }

  protected close(): void {
    this.visible.set(false);
  }

  /** Every close path ends here; the dialog forgets the file, so nothing pending lands later. */
  protected onHide(): void {
    this.reset();
    this.closed.emit();
  }
}

function toggled(set: ReadonlySet<string>, value: string, on: boolean): ReadonlySet<string> {
  const next = new Set(set);
  if (on) {
    next.add(value);
  } else {
    next.delete(value);
  }
  return next;
}
