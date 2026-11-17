import { Component, computed, inject } from '@angular/core';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { MessageSeriesField } from '../../message-series-field/message-series-field';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';
import type { MessageSeries } from '../../../models/message.model';
import type { SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';

@Component({
  selector: 'app-message-series-filter-panel',
  templateUrl: './message-series-filter-panel.html',
  imports: [FilterPanel, FilterPanelAutofocus, MessageSeriesField],
})
export class MessageSeriesFilterPanel extends EntityFilterPanelBase<
  'messageSeries',
  MessageSeries,
  string
> {
  private readonly domain = inject(DomainFacade);

  protected readonly sliceName = 'messageSeries';
  protected extractValue(slice: SliceValueOf<'messageSeries'>): readonly MessageSeries[] {
    return slice.messageSeries;
  }
  protected idOf(item: MessageSeries): string {
    return item.seriesId ?? '';
  }
  protected toSliceValue(items: readonly MessageSeries[]): SliceValueOf<'messageSeries'> {
    return { messageSeries: items };
  }

  // initialIds filters out entries without a seriesId; override the base.
  override readonly initialIds = computed(() =>
    this.value()
      .map((s) => s.seriesId)
      .filter((id): id is string => !!id),
  );

  readonly domainId = computed(() => this.domain.searchDomainEffective()?.domainId ?? null);

  // MessageSeriesField is dual-mode; in this panel `[multiple]="true"`, so the
  // emitted value is always an array. Normalize defensively just in case.
  override onChange(value: MessageSeries | readonly MessageSeries[] | null): void {
    if (Array.isArray(value)) {
      super.onChange(value);
      return;
    }
    super.onChange(value ? [value as MessageSeries] : []);
  }
}
