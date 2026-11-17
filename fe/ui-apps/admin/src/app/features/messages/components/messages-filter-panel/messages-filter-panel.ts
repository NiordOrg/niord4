import { Component, computed, inject } from '@angular/core';

import { MessagesFacade } from '../../services/messages-facade.service';
import { AreaFilterPanel } from '../filter-panels/area-filter-panel/area-filter-panel';
import { CategoryFilterPanel } from '../filter-panels/category-filter-panel/category-filter-panel';
import { ChartFilterPanel } from '../filter-panels/chart-filter-panel/chart-filter-panel';
import { CommentsFilterPanel } from '../filter-panels/comments-filter-panel/comments-filter-panel';
import { DateFilterPanel } from '../filter-panels/date-filter-panel/date-filter-panel';
import { DomainFilterPanel } from '../filter-panels/domain-filter-panel/domain-filter-panel';
import { MessageSeriesFilterPanel } from '../filter-panels/message-series-filter-panel/message-series-filter-panel';
import { PromulgationTypeFilterPanel } from '../filter-panels/promulgation-type-filter-panel/promulgation-type-filter-panel';
import { PublicationFilterPanel } from '../filter-panels/publication-filter-panel/publication-filter-panel';
import { ReferenceFilterPanel } from '../filter-panels/reference-filter-panel/reference-filter-panel';
import { StatusFilterPanel } from '../filter-panels/status-filter-panel/status-filter-panel';
import { TagFilterPanel } from '../filter-panels/tag-filter-panel/tag-filter-panel';
import { TextFilterPanel } from '../filter-panels/text-filter-panel/text-filter-panel';
import { TypeFilterPanel } from '../filter-panels/type-filter-panel/type-filter-panel';
import { UserFilterPanel } from '../filter-panels/user-filter-panel/user-filter-panel';

@Component({
  selector: 'app-messages-filter-panel',
  templateUrl: './messages-filter-panel.html',
  // Transparent-wrapper: the template's @if collapses to nothing when no filter is
  // enabled, but the host element itself remains in the DOM with Angular's comment
  // placeholders inside it. `contents` removes the host from the parent flex/grid
  // layout so it doesn't trigger phantom `gap` spacing. See ui-standards.md §6.
  host: { class: 'contents' },
  imports: [
    AreaFilterPanel,
    CategoryFilterPanel,
    ChartFilterPanel,
    CommentsFilterPanel,
    DateFilterPanel,
    DomainFilterPanel,
    MessageSeriesFilterPanel,
    PromulgationTypeFilterPanel,
    PublicationFilterPanel,
    ReferenceFilterPanel,
    StatusFilterPanel,
    TagFilterPanel,
    TextFilterPanel,
    TypeFilterPanel,
    UserFilterPanel,
  ],
})
export class MessagesFilterPanel {
  private readonly messages = inject(MessagesFacade);

  readonly enabledDomain = this.messages.filter.enabled('domain');
  readonly enabledText = this.messages.filter.enabled('text');
  readonly enabledType = this.messages.filter.enabled('type');
  readonly enabledStatus = this.messages.filter.enabled('status');
  readonly enabledTag = this.messages.filter.enabled('tag');
  readonly enabledPublication = this.messages.filter.enabled('publication');
  readonly enabledPromulgationType = this.messages.filter.enabled('promulgationType');
  readonly enabledUser = this.messages.filter.enabled('user');
  readonly enabledComments = this.messages.filter.enabled('comments');
  readonly enabledReference = this.messages.filter.enabled('reference');
  readonly enabledChart = this.messages.filter.enabled('chart');
  readonly enabledMessageSeries = this.messages.filter.enabled('messageSeries');
  readonly enabledArea = this.messages.filter.enabled('area');
  readonly enabledCategory = this.messages.filter.enabled('category');
  readonly enabledDate = this.messages.filter.enabled('date');

  readonly hasAnyEnabled = computed(
    () =>
      this.enabledDomain() ||
      this.enabledText() ||
      this.enabledType() ||
      this.enabledStatus() ||
      this.enabledTag() ||
      this.enabledPublication() ||
      this.enabledPromulgationType() ||
      this.enabledUser() ||
      this.enabledComments() ||
      this.enabledReference() ||
      this.enabledChart() ||
      this.enabledMessageSeries() ||
      this.enabledArea() ||
      this.enabledCategory() ||
      this.enabledDate(),
  );
}
