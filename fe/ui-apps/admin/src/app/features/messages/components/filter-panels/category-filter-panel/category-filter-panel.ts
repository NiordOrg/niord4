import { Component, computed, inject } from '@angular/core';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { CategoriesField } from '../../categories-field/categories-field';
import type { CategoryRef, SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';

/**
 * Categories filter — chips plus an inline name search, with the checkbox tree behind the field's
 * magnifier. `CategoriesField` owns the search, the id→entity resolution and the tree loading, so
 * this panel only maps the filter slice.
 */
@Component({
  selector: 'app-category-filter-panel',
  templateUrl: './category-filter-panel.html',
  imports: [CategoriesField, FilterPanel, FilterPanelAutofocus],
})
export class CategoryFilterPanel extends EntityFilterPanelBase<'category', CategoryRef, number> {
  private readonly domain = inject(DomainFacade);

  /**
   * Scopes the search to the domain being searched. For categories the backend additionally
   * keeps categories that belong to no domain, which is what scopes the TEMPLATE categories per
   * domain.
   */
  protected readonly domainId = computed<string | null>(
    () => this.domain.searchDomainEffective()?.domainId ?? null,
  );

  protected readonly sliceName = 'category';
  protected extractValue(slice: SliceValueOf<'category'>): readonly CategoryRef[] {
    return slice.categories;
  }
  protected idOf(item: CategoryRef): number {
    return item.id;
  }
  protected toSliceValue(items: readonly CategoryRef[]): SliceValueOf<'category'> {
    return { categories: items };
  }
}
