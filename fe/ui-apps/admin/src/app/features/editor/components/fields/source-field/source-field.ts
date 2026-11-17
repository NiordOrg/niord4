import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { Icon } from '../../../../../shared/icons/icon';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';
import { SourcePickerDialog } from '../../source-picker-dialog/source-picker-dialog';

/**
 * The `source` field row: the per-language message source.
 *
 * Binds the `sources` slice of the ONE form model ({@link sourcesField}, `FieldTree<Record<lang,
 * string>>`) — the per-language inputs are the single write path. The "Add sources…" button opens
 * the {@link SourcePickerDialog}, which composes source text and hands it back (via `sourcesAdded`)
 * to append into this same form slice, so a picked source shows immediately and persists on save.
 */
@Component({
  selector: 'app-source-field',
  imports: [TranslocoPipe, Icon, LocalizedInputs, EditorFieldRow, SourcePickerDialog],
  template: `
    <app-editor-field-row
      fieldId="source"
      label="msg.field.source"
      [edited]="fields.edited('source')"
      [empty]="!primarySource()"
      emptyLabel="editor.field.source.empty"
    >
      <span fieldViewer>{{ primarySource() }}</span>

      <div fieldEditor class="flex flex-col gap-2">
        <div>
          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded border border-surface-300 px-2.5 py-1 text-xs text-fg-subtle hover:border-primary hover:text-primary disabled:opacity-50 dark:border-surface-600"
            [disabled]="!facade.editableNow()"
            (click)="dialogVisible.set(true)"
          >
            <app-icon name="lucidePlus" />
            <span>{{ 'editor.field.source.add' | transloco }}</span>
          </button>
        </div>
        <app-localized-inputs
          [value]="sourcesControl().value()"
          [disabled]="!facade.editableNow()"
          placeholderKey="editor.field.source.placeholder"
          (valueInput)="onSourceInput($event)"
        />
      </div>
    </app-editor-field-row>

    <app-source-picker-dialog [(visible)]="dialogVisible" (sourcesAdded)="onSourcesAdded($event)" />
  `,
  host: { class: 'contents' },
})
export class SourceField {
  /** The `sources` (per-language) slice of the host's form model. */
  readonly sourcesField = input.required<FieldTree<Record<string, string>>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);

  protected readonly dialogVisible = signal(false);

  protected readonly sourcesControl = computed(() => this.sourcesField()());

  // The collapsed viewer shows the PRIMARY desc (`descs[0]`) for this message-authored content —
  // not the current-UI-language desc (which is the convention only for catalog entity names like
  // area/category names).
  protected readonly primarySource = computed<string>(
    () => this.facade.message()?.descs?.[0]?.source ?? '',
  );

  protected onSourceInput({ lang, value }: LocalizedValueInput): void {
    this.sourcesControl().value.update((sources) => ({ ...sources, [lang]: value }));
  }

  /** Append the picker's composed per-language source text into the form slice (comma-joined). */
  protected onSourcesAdded(perLanguageText: Record<string, string>): void {
    this.sourcesControl().value.update((sources) => {
      const next = { ...sources };
      for (const [lang, text] of Object.entries(perLanguageText)) {
        if (!text) {
          continue;
        }
        const current = (next[lang] ?? '').trim();
        next[lang] = current ? `${current}, ${text}` : text;
      }
      return next;
    });
  }
}
