import { Component, computed, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { PublicationsField } from '../../publications-field/publications-field';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';
import type { PublicationRef, SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';

@Component({
  selector: 'app-publication-filter-panel',
  templateUrl: './publication-filter-panel.html',
  imports: [FilterPanel, FilterPanelAutofocus, PublicationsField, TranslocoPipe],
})
export class PublicationFilterPanel extends EntityFilterPanelBase<
  'publication',
  PublicationRef,
  string
> {
  private readonly domain = inject(DomainFacade);

  protected readonly sliceName = 'publication';

  /**
   * The domain the picker offers publications from — the SEARCH domain, as the
   * area, category and message-series panels beside it use.
   *
   * A filter narrows what is being searched, and what is being searched is a
   * domain's messages: offering publications from a domain the list is not
   * looking at would offer a filter that matches nothing. Resolution of a chosen
   * publication stays unscoped, so a chip restored from a link still renders.
   */
  protected readonly domainId = computed(
    () => this.domain.searchDomainEffective()?.domainId ?? null,
  );

  /**
   * The search's refusal, when it was this filter's fault.
   *
   * Read off the list rather than raised here: the filter panel does not issue
   * the search, and the only place that learns a publication could not be
   * resolved is the response to it.
   */
  protected readonly filterError = computed(() => this.messages.list.publicationFilterError());

  protected extractValue(slice: SliceValueOf<'publication'>): readonly PublicationRef[] {
    return slice.publications;
  }
  protected idOf(item: PublicationRef): string {
    return item.publicationId;
  }
  protected toSliceValue(items: readonly PublicationRef[]): SliceValueOf<'publication'> {
    return { publications: items };
  }
}
