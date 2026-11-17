import { Component } from '@angular/core';

import { ChartsField } from '../../charts-field/charts-field';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';
import type { ChartRef, SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';

@Component({
  selector: 'app-chart-filter-panel',
  templateUrl: './chart-filter-panel.html',
  imports: [ChartsField, FilterPanel, FilterPanelAutofocus],
})
export class ChartFilterPanel extends EntityFilterPanelBase<'chart', ChartRef, string> {
  protected readonly sliceName = 'chart';
  protected extractValue(slice: SliceValueOf<'chart'>): readonly ChartRef[] {
    return slice.charts;
  }
  protected idOf(item: ChartRef): string {
    return item.chartNumber;
  }
  protected toSliceValue(items: readonly ChartRef[]): SliceValueOf<'chart'> {
    return { charts: items };
  }
}
