import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { FormField, type FieldTree } from '@angular/forms/signals';

import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { MessageDateFormatterService } from '../../../../messages/services/message-date-formatter.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * The always-shown `publish_date` ("Dates") field row: the publish From/To range + the Follow-up
 * date.
 *
 * Binds three slices of the ONE form model — {@link fromField} (`form.publishDateFrom`),
 * {@link toField} (`form.publishDateTo`), {@link followUpField} (`form.followUpDate`), each
 * `FieldTree<Date | null>`, via `[formField]`. From/To are date+time (`app-date-time-field`);
 * Follow-up is date-only (`app-date-picker`, time pinned 00:00:00). The **per-date
 * readonly-by-status** matrix lives in the HOST schema (`disabled(path.publishDateFrom, …)` — From
 * editable only DRAFT|VERIFIED; To + Follow-up DRAFT|VERIFIED|PUBLISHED) rather than a per-callsite
 * gate; `from ≤ to` stays a save-time validation (`facade.validateForSave`), no inline surface here.
 */
@Component({
  selector: 'app-dates-field',
  imports: [TranslocoPipe, FormField, DatePicker, DateTimeField, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="publish_date"
      label="editor.field.publishDate.label"
      [edited]="fields.edited('publish_date')"
      [empty]="!rangeSummary()"
      emptyLabel="editor.field.publishDate.empty"
    >
      <span fieldViewer>{{ rangeSummary() }}</span>

      <div fieldEditor class="flex flex-col gap-2">
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-fg-subtle">{{ 'editor.field.publishFrom' | transloco }}</span>
          <app-date-time-field
            class="w-72"
            [formField]="fromField()"
            [ariaLabel]="'editor.field.publishFrom' | transloco"
            [defaultToCurrentTime]="true"
            [showClear]="true"
            [clearLabel]="'common.clear' | transloco"
          />
        </div>
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-fg-subtle">{{ 'editor.field.publishTo' | transloco }}</span>
          <app-date-time-field
            class="w-72"
            [formField]="toField()"
            [ariaLabel]="'editor.field.publishTo' | transloco"
            [defaultToCurrentTime]="true"
            [showClear]="true"
            [clearLabel]="'common.clear' | transloco"
          />
        </div>
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-fg-subtle">{{ 'msg.field.follow_up_date' | transloco }}</span>
          <app-date-picker
            class="w-72"
            [formField]="followUpField()"
            [ariaLabel]="'msg.field.follow_up_date' | transloco"
            [showClear]="true"
            [clearLabel]="'common.clear' | transloco"
          />
        </div>
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class DatesField {
  /** The `publishDateFrom` slice of the host's form model. */
  readonly fromField = input.required<FieldTree<Date | null>>();
  /** The `publishDateTo` slice of the host's form model. */
  readonly toField = input.required<FieldTree<Date | null>>();
  /** The `followUpDate` slice of the host's form model. */
  readonly followUpField = input.required<FieldTree<Date | null>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly formatter = inject(MessageDateFormatterService);

  /** Compact from–to summary for the collapsed viewer. */
  protected readonly rangeSummary = computed<string>(() => {
    const message = this.facade.message();
    return this.formatter.formatDateInterval({
      fromDate: message?.publishDateFrom,
      toDate: message?.publishDateTo,
    });
  });
}
