import {
  afterNextRender,
  Component,
  computed,
  type ElementRef,
  inject,
  Injector,
  input,
  viewChildren,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { Icon } from '../../../../../shared/icons/icon';
import type { DateInterval } from '../../../../messages/models/message.model';
import { MessageDateFormatterService } from '../../../../messages/services/message-date-formatter.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * The part-indexed `event_dates` field row: the part's date-interval table.
 *
 * Structural — drives the facade interval mutators for the part at {@link partIndex}:
 * `addPartEventDate` (all-day defaults true for NM), `removePartEventDate`,
 * `duplicatePartEventDate` (+ optional day offset), and `setPartEventDate` (all-day snaps to the
 * whole-day bounds facade-side). An all-day row uses the date-only picker; a timed row uses the
 * date+time field (shipped suite). Adding a row moves keyboard focus onto the new row's All-day
 * checkbox.
 *
 * Both controls work in the session domain's zone, so a picked day is the domain's day and a
 * typed clock is the domain's clock: the row hands the facade an instant and never a wall clock.
 */
@Component({
  selector: 'app-event-dates-field',
  imports: [TranslocoPipe, DatePicker, DateTimeField, Icon, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="event_dates"
      [index]="partIndex()"
      label="msg.field.event_dates"
      [edited]="fields.edited('event_dates', partIndex())"
      [empty]="intervals().length === 0"
      emptyLabel="editor.field.eventDates.empty"
    >
      <div fieldViewer>
        {{ firstIntervalPhrase() }}
        @if (hasMoreIntervals()) {
          <span class="text-fg-subtle">{{ 'editor.row.more' | transloco }}</span>
        }
      </div>

      <div fieldEditor class="flex flex-col gap-2">
        <div class="overflow-x-auto">
          <table class="w-full border-collapse text-sm">
            <thead>
              <tr class="text-left text-xs font-medium text-fg-subtle">
                <th class="py-1 pr-3">{{ 'editor.field.eventDates.allDay' | transloco }}</th>
                <th class="py-1 pr-3">{{ 'editor.field.eventDates.from' | transloco }}</th>
                <th class="py-1 pr-3">{{ 'editor.field.eventDates.to' | transloco }}</th>
                <th class="py-1"></th>
              </tr>
            </thead>
            <tbody>
              @for (interval of intervals(); track $index) {
                <tr class="align-top">
                  <td class="py-1 pr-3">
                    <input
                      #rowFocus
                      type="checkbox"
                      class="mt-2 h-4 w-4"
                      [checked]="interval.allDay"
                      [disabled]="!facade.editableNow()"
                      [attr.aria-label]="'editor.field.eventDates.allDay' | transloco"
                      (change)="setAllDay($index, $event)"
                    />
                  </td>
                  <td class="py-1 pr-3">
                    @if (interval.allDay) {
                      <!-- Mirror the timed row's layout (date-picker + reserved time column) so the
                           all-day date box lines up with the date box of a not-all-day row. -->
                      <div class="flex w-60 items-center gap-2">
                        <app-date-picker
                          class="min-w-0 flex-1"
                          [value]="toDate(interval.fromDate)"
                          [disabled]="!facade.editableNow()"
                          [ariaLabel]="'editor.field.eventDates.from' | transloco"
                          (valueChange)="setFrom($index, $event)"
                        />
                        <span class="w-28 shrink-0" aria-hidden="true"></span>
                      </div>
                    } @else {
                      <app-date-time-field
                        class="w-60"
                        [value]="toDate(interval.fromDate)"
                        [disabled]="!facade.editableNow()"
                        [ariaLabel]="'editor.field.eventDates.from' | transloco"
                        (valueChange)="setFrom($index, $event)"
                      />
                    }
                  </td>
                  <td class="py-1 pr-3">
                    @if (interval.allDay) {
                      <div class="flex w-60 items-center gap-2">
                        <app-date-picker
                          class="min-w-0 flex-1"
                          [value]="toDate(interval.toDate)"
                          [disabled]="!facade.editableNow()"
                          [ariaLabel]="'editor.field.eventDates.to' | transloco"
                          (valueChange)="setTo($index, $event)"
                        />
                        <span class="w-28 shrink-0" aria-hidden="true"></span>
                      </div>
                    } @else {
                      <app-date-time-field
                        class="w-60"
                        [value]="toDate(interval.toDate)"
                        [disabled]="!facade.editableNow()"
                        [ariaLabel]="'editor.field.eventDates.to' | transloco"
                        (valueChange)="setTo($index, $event)"
                      />
                    }
                  </td>
                  <td class="py-1">
                    <div class="flex items-center gap-0.5">
                      <button
                        type="button"
                        class="icon-button"
                        [disabled]="!facade.editableNow()"
                        [attr.aria-label]="'editor.field.eventDates.duplicate' | transloco"
                        (click)="duplicate($index)"
                      >
                        <app-icon name="lucideCopy" />
                      </button>
                      <button
                        type="button"
                        class="icon-button"
                        [disabled]="!facade.editableNow()"
                        [attr.aria-label]="'editor.field.eventDates.duplicateNextDay' | transloco"
                        (click)="duplicateNextDay($index)"
                      >
                        <app-icon name="lucideCalendar" />
                      </button>
                      <button
                        type="button"
                        class="icon-button hover:text-danger"
                        [disabled]="!facade.editableNow()"
                        [attr.aria-label]="'editor.field.eventDates.remove' | transloco"
                        (click)="remove($index)"
                      >
                        <app-icon name="lucideTrash2" />
                      </button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <button
          type="button"
          class="inline-flex w-fit items-center gap-1.5 rounded border border-dashed border-surface-300 px-3 py-1.5 text-sm text-fg-subtle hover:border-primary hover:text-primary disabled:opacity-50 dark:border-surface-600"
          [disabled]="!facade.editableNow()"
          (click)="add()"
        >
          <app-icon name="lucidePlus" />
          <span>{{ 'editor.field.eventDates.add' | transloco }}</span>
        </button>
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class EventDatesField {
  /** The message-part index this row edits. */
  readonly partIndex = input.required<number>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly formatter = inject(MessageDateFormatterService);
  private readonly injector = inject(Injector);

  // The All-day checkboxes, one per interval row — the last one is focused after an add.
  private readonly rowChecks = viewChildren<ElementRef<HTMLInputElement>>('rowFocus');

  protected readonly intervals = computed<readonly DateInterval[]>(
    () => this.facade.message()?.parts?.[this.partIndex()]?.eventDates ?? [],
  );

  /** Collapsed summary: the first interval's phrase (`(more…)` suffix appended when there are more). */
  protected readonly firstIntervalPhrase = computed<string>(() => {
    const first = this.intervals()[0];
    return first ? this.formatInterval(first) : '';
  });

  /** Whether more than one interval exists — drives the collapsed `(more…)` suffix. */
  protected readonly hasMoreIntervals = computed<boolean>(() => this.intervals().length > 1);

  protected formatInterval(interval: DateInterval): string {
    return this.formatter.formatDateInterval(interval);
  }

  protected toDate(iso: string | undefined): Date | null {
    return iso ? new Date(iso) : null;
  }

  protected setAllDay(dateIndex: number, event: Event): void {
    this.facade.setPartEventDate(this.partIndex(), dateIndex, {
      allDay: (event.target as HTMLInputElement).checked,
    });
  }

  protected setFrom(dateIndex: number, value: Date | null): void {
    this.facade.setPartEventDate(this.partIndex(), dateIndex, {
      fromDate: value ? value.toISOString() : undefined,
    });
  }

  protected setTo(dateIndex: number, value: Date | null): void {
    this.facade.setPartEventDate(this.partIndex(), dateIndex, {
      toDate: value ? value.toISOString() : undefined,
    });
  }

  protected add(): void {
    this.facade.addPartEventDate(this.partIndex());
    // Focus the new (last) row's All-day checkbox once it renders.
    afterNextRender(
      () => {
        const checks = this.rowChecks();
        checks[checks.length - 1]?.nativeElement.focus();
      },
      { injector: this.injector },
    );
  }

  protected remove(dateIndex: number): void {
    this.facade.removePartEventDate(this.partIndex(), dateIndex);
  }

  protected duplicate(dateIndex: number): void {
    this.facade.duplicatePartEventDate(this.partIndex(), dateIndex);
  }

  protected duplicateNextDay(dateIndex: number): void {
    this.facade.duplicatePartEventDate(this.partIndex(), dateIndex, 1);
  }
}
