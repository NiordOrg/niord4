import { afterNextRender, Component, computed, ElementRef, inject, Injector } from '@angular/core';
import { CdkDrag, CdkDragHandle, type CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { Select } from '../../../../../shared/forms/select/select';
import { Icon } from '../../../../../shared/icons/icon';
import { MessageIdField } from '../../../../messages/components/message-id-field/message-id-field';
import { MessageReferenceType } from '../../../../messages/components/message-reference-type/message-reference-type';
import {
  type MessageReference,
  REFERENCE_TYPES,
  type ReferenceType,
} from '../../../../messages/models/message.model';
import { MessageDetailsDialogService } from '../../../../messages/services/message-details-dialog.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

interface ReferenceTypeOption {
  readonly value: ReferenceType;
  readonly label: string;
}

/**
 * The `references` field row (sticky-visible): the message's outgoing references.
 *
 * References are structural state, so every mutation drives a cohesive facade mutator directly —
 * `addReference()` (returns the new index to focus), `removeReference(i)`, `moveReference(from, to)`
 * (CDK drag-drop reorder), `setReferenceField(i, patch)` (the per-row
 * `MessageIdField` autocomplete target + the 5-value type select), and `setReferenceDesc(i, lang,
 * text)` (per-language description). No form-model slice — references never mirror into the value
 * layer; every mutation marks the message dirty through the facade. The viewer renders
 * each reference via {@link MessageReferenceType}; clicking one opens the message-details dialog.
 */
@Component({
  selector: 'app-references-field-editor',
  imports: [
    TranslocoPipe,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    Icon,
    MessageReferenceType,
    MessageIdField,
    Select,
    LocalizedInputs,
    EditorFieldRow,
  ],
  template: `
    <app-editor-field-row
      fieldId="references"
      label="msg.field.references"
      [edited]="fields.edited('references')"
      [empty]="references().length === 0"
      emptyLabel="editor.field.references.empty"
    >
      <div fieldViewer class="flex flex-col gap-0.5">
        @for (reference of references(); track $index) {
          <app-message-reference-type
            [reference]="reference"
            [language]="language()"
            (referenceClicked)="openReference($event)"
          />
        }
      </div>

      <div fieldEditor class="flex flex-col gap-2">
        <div cdkDropList class="flex flex-col gap-2" (cdkDropListDropped)="onDrop($event)">
          @for (reference of references(); track $index) {
            <div
              cdkDrag
              data-ref-row
              [cdkDragDisabled]="!facade.editableNow()"
              class="ref-field flex items-start gap-2 rounded border border-surface-200 bg-surface-0 px-2 py-2 dark:border-surface-700 dark:bg-surface-900"
            >
              <button
                type="button"
                cdkDragHandle
                class="icon-button mt-1 shrink-0 cursor-grab text-fg-subtle disabled:cursor-not-allowed disabled:opacity-40"
                [attr.aria-label]="'editor.field.references.reorder' | transloco"
                [disabled]="!facade.editableNow()"
              >
                <app-icon name="lucideGripVertical" />
              </button>

              <div class="flex min-w-0 flex-1 flex-col gap-2">
                <div class="flex flex-wrap items-center gap-2">
                  <app-message-id-field
                    class="min-w-0 flex-1"
                    [value]="reference.messageId || null"
                    [allowFreeText]="true"
                    [placeholder]="'editor.field.references.messageIdPlaceholder'"
                    [disabled]="!facade.editableNow()"
                    (valueChange)="onReferenceId($index, $event)"
                  />
                  <app-select
                    class="w-44"
                    [value]="reference.type"
                    [options]="referenceTypeOptions()"
                    optionValue="value"
                    optionLabel="label"
                    [disabled]="!facade.editableNow()"
                    [ariaLabel]="'editor.field.references.type' | transloco"
                    (valueChange)="onReferenceType($index, $event)"
                  />
                </div>

                <app-localized-inputs
                  [value]="descRecord(reference)"
                  [disabled]="!facade.editableNow()"
                  placeholderKey="editor.field.references.descriptionPlaceholder"
                  (valueInput)="onReferenceDesc($index, $event)"
                />
              </div>

              <button
                type="button"
                class="icon-button mt-1 shrink-0 hover:text-danger disabled:cursor-not-allowed disabled:opacity-40"
                [attr.aria-label]="'editor.field.references.remove' | transloco"
                [disabled]="!facade.editableNow()"
                (click)="remove($index)"
              >
                <app-icon name="lucideTrash2" />
              </button>
            </div>
          }
        </div>

        <button
          type="button"
          class="inline-flex w-fit items-center gap-1.5 rounded border border-dashed border-surface-300 px-3 py-1.5 text-sm text-fg-subtle hover:border-primary hover:text-primary disabled:opacity-50 dark:border-surface-600"
          [disabled]="!facade.editableNow()"
          (click)="add()"
        >
          <app-icon name="lucidePlus" />
          <span>{{ 'editor.field.references.add' | transloco }}</span>
        </button>
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class ReferencesFieldEditor {
  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly languageService = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);
  private readonly detailsDialog = inject(MessageDetailsDialogService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly references = computed<readonly MessageReference[]>(
    () => this.facade.message()?.references ?? [],
  );
  protected readonly language = computed(() => this.languageService.selectedLanguage());

  // The 5 relationship types with their translated labels — the labels live under the flat
  // `msg.reference.*` keyspace (enum values lower-cased to match); re-derives on a language
  // switch via the selected-language read.
  protected readonly referenceTypeOptions = computed<readonly ReferenceTypeOption[]>(() => {
    this.languageService.selectedLanguage();
    return REFERENCE_TYPES.map((value) => ({
      value,
      label: this.transloco.translate(`msg.reference.${value.toLowerCase()}`),
    }));
  });

  /** Build the per-language `Record<lang, description>` the compact inputs render (missing → empty). */
  protected descRecord(reference: MessageReference): Record<string, string> {
    const record: Record<string, string> = {};
    for (const desc of reference.descs ?? []) {
      if (desc.lang) {
        record[desc.lang] = desc.description ?? '';
      }
    }
    return record;
  }

  protected add(): void {
    const index = this.facade.addReference();
    if (index >= 0) {
      // Focus the new row's message-id input once it has rendered.
      afterNextRender(() => this.focusRow(index), { injector: this.injector });
    }
  }

  protected remove(index: number): void {
    this.facade.removeReference(index);
  }

  protected onDrop(event: CdkDragDrop<unknown>): void {
    this.facade.moveReference(event.previousIndex, event.currentIndex);
  }

  protected onReferenceId(index: number, messageId: string | null): void {
    this.facade.setReferenceField(index, { messageId: messageId ?? '' });
  }

  protected onReferenceType(index: number, type: unknown): void {
    this.facade.setReferenceField(index, { type: type as ReferenceType });
  }

  protected onReferenceDesc(index: number, { lang, value }: LocalizedValueInput): void {
    this.facade.setReferenceDesc(index, lang, value || undefined);
  }

  protected openReference(messageId: string): void {
    if (messageId) {
      this.detailsDialog.open(messageId, [messageId]);
    }
  }

  private focusRow(index: number): void {
    const rows = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-ref-row]');
    rows[index]?.querySelector('input')?.focus();
  }
}
