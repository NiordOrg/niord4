import { Component, computed, inject } from '@angular/core';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { AreasField } from '../../areas-field/areas-field';
import type { AreaRef, SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';

/**
 * Areas filter — chips plus an inline name search, with the checkbox tree behind the field's
 * magnifier. `AreasField` owns the search, the id→entity resolution and the tree loading, so this
 * panel only maps the filter slice.
 */
@Component({
  selector: 'app-area-filter-panel',
  templateUrl: './area-filter-panel.html',
  imports: [AreasField, FilterPanel, FilterPanelAutofocus],
})
export class AreaFilterPanel extends EntityFilterPanelBase<'area', AreaRef, number> {
  private readonly domain = inject(DomainFacade);

  /**
   * Scopes the search to the domain being searched — the Domain filter when one is set,
   * otherwise the session domain. A domain with no areas configured imposes no restriction
   * server-side, so this is safe to always send.
   */
  protected readonly domainId = computed<string | null>(
    () => this.domain.searchDomainEffective()?.domainId ?? null,
  );

  protected readonly sliceName = 'area';
  protected extractValue(slice: SliceValueOf<'area'>): readonly AreaRef[] {
    return slice.areas;
  }
  protected idOf(item: AreaRef): number {
    return item.id;
  }
  protected toSliceValue(items: readonly AreaRef[]): SliceValueOf<'area'> {
    return { areas: items };
  }
}
