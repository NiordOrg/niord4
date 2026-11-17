import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { FormField, type FieldTree } from '@angular/forms/signals';

import { Select } from '../../../../../shared/forms/select/select';
import type { Message } from '../../../../messages/models/message.model';
import { MessageIdBadge } from '../../../../messages/components/message-id-badge/message-id-badge';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

interface SeriesOption {
  readonly value: string;
  readonly label: string;
}

/**
 * The `id` field row: message-series select + read-only UID badge, plus the mutually-exclusive
 * Number vs Short-ID editing surfaces.
 *
 * Binds three slices of the ONE form model — {@link seriesField} (`form.seriesId`),
 * {@link shortIdField} (`form.shortId`), {@link numberField} (`form.number`). The series select and
 * inputs are bound via `[formField]`, so writes flow through the host's single sync point; a series
 * change adjusts immediately + a number change adjusts debounced, both driven by the host diff —
 * this component adds NO adjust calls. Number is editable only when `facade.messageNumberEditable()`
 * (MANUAL && shortFormat); Short-ID only when `facade.shortIdEditable()` (MANUAL && !shortFormat) —
 * the two are mutually exclusive (regression-sensitive). Otherwise the short-id renders read-only
 * via {@link MessageIdBadge}. Red invalid label until a series is set.
 */
@Component({
  selector: 'app-id-field',
  imports: [TranslocoPipe, FormField, Select, MessageIdBadge, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="id"
      label="msg.field.id"
      [required]="true"
      [invalid]="invalid()"
      [edited]="fields.edited('id')"
    >
      @if (message(); as msg) {
        <app-message-id-badge fieldViewer [message]="msg" />
      }

      <div fieldEditor class="flex flex-col gap-2">
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-fg-subtle">{{ 'editor.field.series' | transloco }}</span>
          <app-select
            [formField]="seriesField()"
            [options]="seriesOptions()"
            optionLabel="label"
            optionValue="value"
            [ariaLabel]="'editor.field.series' | transloco"
          />
        </div>

        @if (message()?.id; as uid) {
          <div class="flex flex-col gap-1 text-sm">
            <span class="text-fg-subtle">{{ 'editor.field.uid' | transloco }}</span>
            <span class="font-mono text-sm text-fg-muted">{{ uid }}</span>
          </div>
        }

        @if (facade.messageNumberEditable()) {
          <div class="flex flex-col gap-1 text-sm">
            <span class="text-fg-subtle">{{ 'editor.field.number' | transloco }}</span>
            <input
              type="number"
              class="text-input w-40"
              [formField]="numberField()"
              [attr.aria-label]="'editor.field.number' | transloco"
              (blur)="facade.flushAdjust()"
            />
          </div>
        }

        @if (facade.numberSequenceType() && facade.numberSequenceType() !== 'NONE') {
          <div class="flex flex-col gap-1 text-sm">
            <span class="text-fg-subtle">{{ 'editor.field.shortId' | transloco }}</span>
            @if (facade.shortIdEditable()) {
              <input
                class="text-input w-56"
                [formField]="shortIdField()"
                [attr.aria-label]="'editor.field.shortId' | transloco"
              />
            } @else if (message(); as msg) {
              <app-message-id-badge [message]="msg" />
            }
          </div>
        }
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class IdField {
  /** The `seriesId` slice of the host's form model. */
  readonly seriesField = input.required<FieldTree<string>>();
  /** The `shortId` slice of the host's form model. */
  readonly shortIdField = input.required<FieldTree<string>>();
  /** The `number` slice of the host's form model. */
  readonly numberField = input.required<FieldTree<number | null>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);

  protected readonly message = computed<Message | undefined>(() => this.facade.message());

  protected readonly seriesOptions = computed<SeriesOption[]>(() =>
    this.facade.messageSeries().map((series) => ({
      value: series.seriesId,
      label: series.name ?? series.seriesId,
    })),
  );

  /** Red invalid label until a series is set. */
  protected readonly invalid = computed<boolean>(() => !this.facade.message()?.messageSeries);
}
