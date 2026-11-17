import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { Icon } from '../../../../../shared/icons/icon';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * The `orig_info` field row: marks a message as carrying "original information".
 *
 * Binds the ONE form model's `originalInformation` boolean slice via {@link field}
 * (`form.originalInformation`, a Signal Forms `FieldTree<boolean>`). The editor writes the field
 * signal — never the facade — so the change flows through the host's single facade↔form sync point.
 * Disable reads the single `editableNow()` gate.
 */
@Component({
  selector: 'app-orig-info-field',
  imports: [TranslocoPipe, Icon, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="orig_info"
      label="msg.field.orig_info"
      [edited]="fields.edited('orig_info')"
      [empty]="!control().value()"
      emptyLabel="editor.field.origInfo.empty"
    >
      <span fieldViewer class="inline-flex items-center gap-1.5">
        <app-icon name="lucideStar" class="text-accent" />
        {{ 'editor.field.origInfo.set' | transloco }}
      </span>

      <div fieldEditor>
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded border border-surface-300 px-3 py-1.5 text-sm disabled:opacity-50 dark:border-surface-600"
          [class.text-accent]="control().value()"
          [attr.aria-pressed]="control().value()"
          [disabled]="!facade.editableNow()"
          (click)="toggle()"
        >
          <app-icon [name]="control().value() ? 'lucideStar' : 'lucideCircle'" />
          <span>{{ 'editor.field.origInfo.toggle' | transloco }}</span>
        </button>
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class OrigInfoField {
  /** The `originalInformation` boolean slice of the host's form model. */
  readonly field = input.required<FieldTree<boolean>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);

  /** The resolved Signal Forms field state for the bound slice. */
  protected readonly control = computed(() => this.field()());

  protected toggle(): void {
    if (!this.facade.editableNow()) {
      return;
    }
    this.control().value.set(!this.control().value());
  }
}
