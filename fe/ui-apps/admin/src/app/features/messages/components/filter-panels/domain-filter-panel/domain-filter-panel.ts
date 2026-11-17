import { Component, computed, inject } from '@angular/core';

import { DomainField } from '../../../../../core/domain/components/domain-field/domain-field';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';

@Component({
  selector: 'app-domain-filter-panel',
  templateUrl: './domain-filter-panel.html',
  imports: [DomainField, FilterPanel],
})
export class DomainFilterPanel {
  private readonly messages = inject(MessagesFacade);

  readonly value = computed(() => this.messages.filter.slice('domain')().domain ?? null);

  // searchDomain is kept in sync with this slice centrally by MessagesFilterFacade's
  // domain-sync effect, so the panel only writes the slice — no separate setSearchDomain
  // call (which previously left searchDomain stale on every non-panel mutation path).
  onChange(domain: Domain | null): void {
    // `value` is a Signal Forms model, so a one-way `[value]` re-push emits `valueChange`
    // too; skip when the selected domain is unchanged so the echo is a no-op.
    if ((this.value()?.domainId ?? null) === (domain?.domainId ?? null)) {
      return;
    }
    this.messages.filter.setSliceValue('domain', { domain: domain ?? undefined });
  }

  clear(): void {
    this.messages.filter.clearFilter('domain');
  }
}
