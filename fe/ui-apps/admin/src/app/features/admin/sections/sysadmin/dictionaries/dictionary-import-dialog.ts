import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { Subscription } from 'rxjs';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import {
  DictionariesApiService,
  DictionaryImportAdd,
  DictionaryImportOverwrite,
  DictionaryImportPreview,
  DictionaryKeyRef,
} from '../../../services/api/dictionaries-api.service';
import { backendReason, type ImportFailure, JOB_STARTED } from '../import-failure';

/** One line of an overwrite's table: a value the import would write, beside the deployment's. */
interface OverwriteLine {
  readonly kind: 'value' | 'longValue' | 'atonFilter';
  /** The language of a value or long value. */
  readonly lang?: string;
  readonly current: string;
  readonly incoming: string;
  readonly changed: boolean;
  /** The import empties what the deployment has: the table says so rather than show nothing. */
  readonly cleared: boolean;
}

interface OverwriteRow {
  readonly id: string;
  readonly entry: DictionaryImportOverwrite;
  readonly lines: readonly OverwriteLine[];
}

interface AddRow {
  readonly id: string;
  readonly entry: DictionaryImportAdd;
  /** The file's values, `[lang, value]`, in the file's order. */
  readonly values: readonly (readonly [string, string])[];
}

interface AddGroup {
  readonly dictionary: string;
  readonly entries: readonly AddRow[];
}

/** A key's identity in the tick sets; JSON keeps any character of a name or key apart. */
const refId = (ref: DictionaryKeyRef): string => JSON.stringify([ref.dictionary, ref.key]);

/**
 * The Dictionaries import: the sysadmin picks a file, sees what importing it would do, and
 * chooses. Picking (or dropping) a `.json` file asks the backend for a preview, which writes
 * nothing: the keys the file would add, grouped by dictionary and ticked, except a key an operator
 * deleted here, which is offered unticked; the keys it would overwrite, unticked, each with what
 * the overwrite writes beside the deployment's values (a language the file does not carry is
 * kept); how many keys are unchanged; and the entries it refuses, with the backend's reasons.
 * Import sends the file with the ticked keys, so a key that is not ticked is neither added nor
 * overwritten. The job runs in the background; the dialog shows the backend's answer and stays
 * open. Another file's extension is dropped silently, as in the other sections' import dialog.
 * `closed` fires on every close path, so the page refetches its list.
 *
 * Every answer belongs to the file it was asked for: a new file, `open()` and every close cancel
 * the pending preview and import, and an answer that still arrives after them is dropped, so a
 * late preview is never shown, nor ticked and imported, against another file.
 */
@Component({
  selector: 'app-dictionary-import-dialog',
  imports: [Dialog, ProgressBar, RouterLink, TranslocoPipe],
  templateUrl: './dictionary-import-dialog.html',
})
export class DictionaryImportDialog {
  private readonly api = inject(DictionariesApiService);
  private readonly transloco = inject(TranslocoService);

  readonly closed = output<void>();

  protected readonly visible = signal(false);
  protected readonly busy = signal(false);
  protected readonly dragOver = signal(false);
  protected readonly file = signal<File | undefined>(undefined);
  protected readonly preview = signal<DictionaryImportPreview | undefined>(undefined);
  /** The failure of the last preview. */
  protected readonly previewError = signal<ImportFailure | undefined>(undefined);
  /** The backend's answer to an import whose job started; once set, the ticks are locked. */
  protected readonly resultText = signal<string | undefined>(undefined);
  /** The failure of the last import, or an answer that started no job; the ticks stay open. */
  protected readonly importError = signal<ImportFailure | undefined>(undefined);
  protected readonly addTicked = signal<ReadonlySet<string>>(new Set());
  protected readonly overwriteTicked = signal<ReadonlySet<string>>(new Set());

  /** Moves on every reset; an answer asked for under an earlier generation is dropped. */
  private generation = 0;
  /** The generation the shown preview was asked for. */
  private previewGeneration = -1;
  private previewRequest?: Subscription;
  private importRequest?: Subscription;

  /** The adds by dictionary, in the preview's order. */
  protected readonly addGroups = computed<readonly AddGroup[]>(() => {
    const groups = new Map<string, AddRow[]>();
    for (const entry of this.preview()?.add ?? []) {
      const group = groups.get(entry.dictionary) ?? [];
      group.push({ id: refId(entry), entry, values: Object.entries(entry.values) });
      groups.set(entry.dictionary, group);
    }
    return [...groups].map(([dictionary, entries]) => ({ dictionary, entries }));
  });

  protected readonly overwriteRows = computed<readonly OverwriteRow[]>(() =>
    (this.preview()?.overwrite ?? []).map((entry) => ({
      id: refId(entry),
      entry,
      lines: overwriteLines(entry),
    })),
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
        this.addTicked.set(
          new Set(preview.add.filter((entry) => !entry.deleted).map((entry) => refId(entry))),
        );
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

  protected toggleAdd(id: string, checked: boolean): void {
    this.addTicked.update((ticked) => toggled(ticked, id, checked));
  }

  protected toggleOverwrite(id: string, checked: boolean): void {
    this.overwriteTicked.update((ticked) => toggled(ticked, id, checked));
  }

  /**
   * Sends the file with the ticked keys, each overwrite with the hash of the values its table
   * showed. The file and the preview are of one generation: a reset clears both and a late preview
   * is dropped; the generation check below says so outright.
   */
  protected importTicked(): void {
    const file = this.file();
    const preview = this.preview();
    if (!file || !preview || !this.canImport() || this.previewGeneration !== this.generation) {
      return;
    }
    const generation = this.generation;
    const add = preview.add.filter((entry) => this.addTicked().has(refId(entry)));
    const overwrite = preview.overwrite.filter((entry) => this.overwriteTicked().has(refId(entry)));
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
        entity: this.transloco.translate('admin.sections.dictionaries.label'),
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

/**
 * What a ticked overwrite writes. It writes each language the file carries whole, value and long
 * value, so a long value the file omits or leaves empty clears the deployment's, and that shows as
 * a line of its own; a long value the file carries for a language without a value shows too; the
 * AtoN filter shows when it differs, since the import replaces it. Languages the file does not
 * carry are kept and not shown.
 */
function overwriteLines(entry: DictionaryImportOverwrite): OverwriteLine[] {
  const { current, incoming } = entry;
  const line = (
    kind: OverwriteLine['kind'],
    lang: string | undefined,
    before: string | undefined,
    after: string | undefined,
  ): OverwriteLine => ({
    kind,
    lang,
    current: before ?? '',
    incoming: after ?? '',
    changed: (before ?? '') !== (after ?? ''),
    cleared: (before ?? '') !== '' && (after ?? '') === '',
  });
  const lines: OverwriteLine[] = [];
  for (const [lang, value] of Object.entries(incoming.values)) {
    lines.push(line('value', lang, current.values[lang], value));
    const before = current.longValues?.[lang] ?? '';
    const after = incoming.longValues?.[lang] ?? '';
    if (after !== before) {
      lines.push(line('longValue', lang, before, after));
    }
  }
  for (const [lang, longValue] of Object.entries(incoming.longValues ?? {})) {
    if (!(lang in incoming.values) && longValue !== (current.longValues?.[lang] ?? '')) {
      lines.push(line('longValue', lang, current.longValues?.[lang], longValue));
    }
  }
  if ((current.atonFilter ?? '') !== (incoming.atonFilter ?? '')) {
    lines.push(line('atonFilter', undefined, current.atonFilter, incoming.atonFilter));
  }
  return lines;
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
