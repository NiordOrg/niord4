import { Component, computed, inject } from '@angular/core';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';

import { DomainService } from '../../../../../core/domain/services/domain.service';
import { type DateType, DATE_TYPES } from '../../../models/messages-filter-params.model';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { Select } from '../../../../../shared/forms/select/select';
import { endOfDayInZone } from '../../../../../shared/utils/zoned-date';
import { MessagesFacade } from '../../../services/messages-facade.service';

interface DateTypeOption {
  readonly label: string;
  readonly value: DateType;
}

/** A stored bound as the picker's value — the picker reads the instant in the domain's zone. */
function toPickerValue(ms: number | undefined): Date | null {
  return ms === undefined ? null : new Date(ms);
}

@Component({
  selector: 'app-date-filter-panel',
  templateUrl: './date-filter-panel.html',
  imports: [DatePicker, FilterPanel, Select, TranslocoPipe],
})
export class DateFilterPanel {
  private readonly messages = inject(MessagesFacade);
  private readonly domainService = inject(DomainService);

  private readonly publishLabel = translateSignal('msg.field.publish_date');
  private readonly publishFromLabel = translateSignal('msg.field.publish_from_date');
  private readonly eventLabel = translateSignal('messages.filter.date.type.eventDate');
  private readonly createdLabel = translateSignal('msg.field.created_date');
  private readonly updatedLabel = translateSignal('msg.field.updated_date');

  readonly slice = computed(() => this.messages.filter.slice('date')());
  readonly fromDate = computed(() => toPickerValue(this.slice().fromDate));
  readonly toDate = computed(() => toPickerValue(this.slice().toDate));
  readonly dateType = computed(() => this.slice().dateType);

  readonly dateTypeOptions = computed<DateTypeOption[]>(() => {
    const labels: Record<DateType, string> = {
      PUBLISH_DATE: this.publishLabel(),
      PUBLISH_FROM_DATE: this.publishFromLabel(),
      EVENT_DATE: this.eventLabel(),
      CREATED_DATE: this.createdLabel(),
      UPDATED_DATE: this.updatedLabel(),
    };
    return DATE_TYPES.map((value) => ({ label: labels[value], value }));
  });

  /** The picker already emits the picked day's midnight in the session domain's zone. */
  onFromDateChange(value: Date | null): void {
    this.messages.filter.setSliceValue('date', {
      dateType: this.dateType(),
      fromDate: value?.getTime(),
      toDate: this.slice().toDate,
    });
  }

  onToDateChange(value: Date | null): void {
    const timeZone = this.domainService.sessionDomain()?.timeZone;
    this.messages.filter.setSliceValue('date', {
      dateType: this.dateType(),
      fromDate: this.slice().fromDate,
      // The picker emits the START of the picked day; an inclusive upper bound has to run to
      // the end of that same day, or everything after its midnight falls outside the range
      // the user asked to include. Without a zone the picker is disabled, so the only value
      // that can arrive here is a clear.
      toDate: value && timeZone ? endOfDayInZone(value, timeZone).getTime() : undefined,
    });
  }

  onDateTypeChange(value: DateType): void {
    this.messages.filter.setSliceValue('date', {
      dateType: value,
      fromDate: this.slice().fromDate,
      toDate: this.slice().toDate,
    });
  }

  clear(): void {
    this.messages.filter.clearFilter('date');
  }
}
