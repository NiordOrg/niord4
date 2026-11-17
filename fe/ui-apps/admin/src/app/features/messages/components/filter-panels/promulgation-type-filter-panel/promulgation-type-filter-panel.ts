import { Component } from '@angular/core';

import { PromulgationTypeField } from '../../promulgation-type-field/promulgation-type-field';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';
import type {
  PromulgationTypeRef,
  SliceValueOf,
} from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';

@Component({
  selector: 'app-promulgation-type-filter-panel',
  templateUrl: './promulgation-type-filter-panel.html',
  imports: [FilterPanel, FilterPanelAutofocus, PromulgationTypeField],
})
export class PromulgationTypeFilterPanel extends EntityFilterPanelBase<
  'promulgationType',
  PromulgationTypeRef,
  string
> {
  protected readonly sliceName = 'promulgationType';
  protected extractValue(slice: SliceValueOf<'promulgationType'>): readonly PromulgationTypeRef[] {
    return slice.promulgationTypes;
  }
  protected idOf(item: PromulgationTypeRef): string {
    return item.typeId;
  }
  protected toSliceValue(items: readonly PromulgationTypeRef[]): SliceValueOf<'promulgationType'> {
    return { promulgationTypes: items };
  }
}
