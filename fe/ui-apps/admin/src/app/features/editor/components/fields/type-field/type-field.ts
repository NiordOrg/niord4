import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  MESSAGE_TYPES_BY_MAIN_TYPE,
  type MessageType,
} from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/** The two type sets keyed by the immutable `mainType` (the NM switcher mirrors the NW one). */
const TYPE_SETS = MESSAGE_TYPES_BY_MAIN_TYPE;

/**
 * The always-shown `type` field row: a segmented control over the type set permitted by the
 * message's immutable `mainType`. The rendered options are narrowed to those the domain series
 * permit ({@link EditorFacade.isTypeValid}, `validTypes` OR-computed from the domain series) —
 * non-permitted types are deliberately hidden entirely rather than shown disabled.
 *
 * Binds the ONE form model's `type` slice via {@link field} (`form.type`,
 * `FieldTree<'' | MessageType>`). Selecting a type ONLY writes the field signal — the
 * type→sole-permitting-series auto-deduce + `adjust({ inclPromulgations: true })` runs in the
 * host's single sync point by diffing the model; this component adds NO side effects and NO
 * `valueChange` handler. The row shows a red invalid label until `mainType && type`.
 */
@Component({
  selector: 'app-type-field',
  imports: [TranslocoPipe, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="type"
      label="msg.field.type"
      [required]="true"
      [invalid]="invalid()"
      [edited]="fields.edited('type')"
      [empty]="!control().value()"
      emptyLabel="editor.field.typeEmpty"
    >
      <span fieldViewer>{{ typeLabel(control().value()) }} {{ facade.message()?.mainType }}</span>

      <div
        fieldEditor
        class="flex flex-wrap gap-1.5"
        role="group"
        [attr.aria-label]="'msg.field.type' | transloco"
      >
        @for (option of options(); track option) {
          <button
            type="button"
            class="rounded border px-3 py-1.5 text-sm disabled:opacity-40"
            [class.border-primary]="control().value() === option"
            [class.bg-primary]="control().value() === option"
            [class.text-fg-on-primary]="control().value() === option"
            [class.font-semibold]="control().value() === option"
            [class.border-surface-300]="control().value() !== option"
            [attr.aria-pressed]="control().value() === option"
            [disabled]="!facade.editableNow()"
            (click)="select(option)"
          >
            {{ typeLabel(option) }}
          </button>
        }
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class TypeField {
  /** The `type` slice of the host's form model. */
  readonly field = input.required<FieldTree<'' | MessageType>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  protected readonly control = computed(() => this.field()());

  /**
   * The mainType's type set, narrowed to the types the domain series permit (the rest are hidden
   * entirely). Empty until a mainType is set — never at runtime.
   */
  protected readonly options = computed<readonly MessageType[]>(() => {
    const mainType = this.facade.message()?.mainType;
    if (!mainType) {
      return [];
    }
    return TYPE_SETS[mainType].filter((type) => this.facade.isTypeValid(type));
  });

  /** Red invalid label until both the immutable mainType and a type are set. */
  protected readonly invalid = computed<boolean>(() => {
    const message = this.facade.message();
    return !message?.mainType || !message.type;
  });

  protected typeLabel(type: string): string {
    this.language.selectedLanguage();
    return type ? this.transloco.translate(`msg.type.${type}`) : '';
  }

  protected select(type: MessageType): void {
    if (!this.facade.editableNow()) {
      return;
    }
    this.control().value.set(type);
  }
}
