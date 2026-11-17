import { Component, input, model } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * The message-field checkbox grid shared by the admin entity editors — a labelled two/three-column
 * grid of message-field candidates (labelled via `msg.field.<id>`) toggling membership of a
 * string-list selection. Serves the areas/categories/series `editorFields` pools and the
 * categories template `stdTemplateFields` pool; the candidate list and selection stay with the
 * consumer (typically an out-of-form signal folded into its baseline compare).
 */
@Component({
  selector: 'app-editor-fields-grid',
  imports: [TranslocoPipe],
  template: `
    <div class="flex flex-col gap-2">
      <span class="text-sm font-medium text-fg-title">{{ label() | transloco }}</span>
      <div class="grid grid-cols-2 gap-x-4 gap-y-2 pt-1 sm:grid-cols-3">
        @for (field of candidates(); track field) {
          <label class="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              class="checkbox"
              [checked]="value().includes(field)"
              (change)="toggle(field)"
            />
            <span>{{ 'msg.field.' + field | transloco }}</span>
          </label>
        }
      </div>
    </div>
  `,
  host: { class: 'contents' },
})
export class EditorFieldsGrid {
  /** i18n key for the group heading (translated here). */
  readonly label = input.required<string>();
  /** The offerable field ids, labelled via `msg.field.<id>`. */
  readonly candidates = input.required<readonly string[]>();
  /** The chosen field ids — two-way bindable to the host's selection signal. */
  readonly value = model.required<readonly string[]>();

  protected toggle(field: string): void {
    this.value.update((fields) =>
      fields.includes(field) ? fields.filter((f) => f !== field) : [...fields, field],
    );
  }
}
