import { Component, inject, input, model } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../facades/domain.facade';
import type { Domain } from '../../models/domain.model';

/**
 * Multi-select over the configured domains (a checkbox list — the domain set is small and fixed).
 * The single-select `DomainField` covers one-domain pickers; this covers the category TEMPLATE
 * `domains[]`. Value is the picked `Domain[]`; the consumer maps to thin `{ domainId, active }` refs
 * on save. Bindable via `[value]`/`(valueChange)`.
 */
@Component({
  selector: 'app-domain-multiselect-field',
  imports: [TranslocoPipe],
  template: `
    <div class="flex flex-col gap-1.5">
      @for (domain of availableDomains(); track domain.domainId) {
        <label class="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            class="checkbox"
            [checked]="isSelected(domain)"
            [disabled]="disabled()"
            (change)="toggle(domain)"
          />
          <span>{{ domain.name || domain.domainId }}</span>
          @if (domain.active === false) {
            <span class="text-xs text-fg-subtle">({{ 'common.inactive' | transloco }})</span>
          }
        </label>
      } @empty {
        <span class="text-sm text-fg-muted">{{
          'admin.categories.field.noDomains' | transloco
        }}</span>
      }
    </div>
  `,
})
export class DomainMultiselectField {
  private readonly facade = inject(DomainFacade);

  readonly availableDomains = this.facade.availableDomains;
  /** The picked domains — two-way via `[value]`/`(valueChange)`. */
  readonly value = model<readonly Domain[]>([]);
  readonly disabled = input(false);

  protected isSelected(domain: Domain): boolean {
    return this.value().some((d) => d.domainId === domain.domainId);
  }

  protected toggle(domain: Domain): void {
    const selected = this.value();
    this.value.set(
      this.isSelected(domain)
        ? selected.filter((d) => d.domainId !== domain.domainId)
        : [...selected, domain],
    );
  }
}
