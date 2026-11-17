import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { MessageDescService } from '../../../services/message-desc.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * The part-indexed `subject` field row (DETAILS parts only): the per-language subject line + the
 * hide-subject flag.
 *
 * Structural — drives `facade.setPartSubject(partIndex, lang, value)` and
 * `facade.setPartHideSubject(partIndex, boolean)` for the part at {@link partIndex}. Subject is an
 * adjust trigger, so keystrokes call `facade.adjustDebounced()` (500 ms) and blur calls
 * `facade.flushAdjust()` — the blur hook rides the blur output on `LocalizedInputs`.
 */
@Component({
  selector: 'app-subject-field',
  imports: [TranslocoPipe, LocalizedInputs, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="subject"
      [index]="partIndex()"
      label="msg.field.subject"
      [edited]="fields.edited('subject', partIndex())"
      [empty]="!primarySubject()"
      emptyLabel="editor.field.subject.empty"
    >
      <span fieldViewer>{{ primarySubject() }}</span>

      <div fieldEditor class="flex flex-col gap-2">
        <label class="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            class="h-4 w-4"
            [checked]="hideSubject()"
            [disabled]="!facade.editableNow()"
            (change)="onHideToggle($event)"
          />
          <span>{{ 'editor.hide_subject' | transloco }}</span>
        </label>
        <app-localized-inputs
          [value]="subjectValues()"
          [disabled]="!facade.editableNow()"
          placeholderKey="editor.field.subject.placeholder"
          (valueInput)="onSubjectInput($event)"
          (inputBlur)="onSubjectBlur()"
        />
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class SubjectField {
  /** The message-part index this row edits. */
  readonly partIndex = input.required<number>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly desc = inject(MessageDescService);

  private readonly part = computed(() => this.facade.message()?.parts?.[this.partIndex()]);

  protected readonly hideSubject = computed<boolean>(() => this.part()?.hideSubject ?? false);

  /** Per-language subject values keyed by language code (the `LocalizedInputs` controlled value). */
  protected readonly subjectValues = computed<Record<string, string>>(() => {
    const values: Record<string, string> = {};
    for (const entry of this.part()?.descs ?? []) {
      if (entry.lang) {
        values[entry.lang] = entry.subject ?? '';
      }
    }
    return values;
  });

  // The collapsed viewer shows the CHOSEN content language's subject, falling back to the first desc
  // when that language has none.
  protected readonly primarySubject = computed<string>(
    () =>
      this.desc.descForLangOrDefault(this.part()?.descs, this.language.selectedLanguage())
        ?.subject ?? '',
  );

  protected onHideToggle(event: Event): void {
    this.facade.setPartHideSubject(this.partIndex(), (event.target as HTMLInputElement).checked);
  }

  protected onSubjectInput({ lang, value }: LocalizedValueInput): void {
    this.facade.setPartSubject(this.partIndex(), lang, value);
    this.facade.adjustDebounced();
  }

  /** Blur flushes the pending debounced adjust immediately (adjust-trigger blur hook). */
  protected onSubjectBlur(): void {
    this.facade.flushAdjust();
  }
}
