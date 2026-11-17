import { Component, inject, input, model, output, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import { PickerPopover } from '../../components/picker-popover/picker-popover';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../core/domain/models/domain.model';

/**
 * Multi-select over the session's available domains, exposed as a `Domain[]` CVA,
 * styled like the messages area/category filters: each picked domain shows as a
 * removable chip tinted by the domain's own `color`, and an anchored popover
 * holds the checklist of available domains. An empty selection means "all
 * domains" (the consumer/backend interprets it so). Inactive domains are tagged.
 */
@Component({
  selector: 'app-domain-multiselect-field',
  imports: [PickerPopover, FormsModule, Icon, TranslocoPipe],
  templateUrl: './domain-multiselect-field.html',
  host: { class: 'block' },
})
export class DomainMultiselectField implements FormValueControl<readonly Domain[]> {
  private readonly facade = inject(DomainFacade);

  /** Pre-translated placeholder + accessible name (the consumer translates). */
  readonly placeholder = input('');
  readonly ariaLabel = input('');

  private readonly picker = viewChild.required(PickerPopover);

  protected readonly availableDomains = this.facade.availableDomains;
  /** Two-way bound by `[formField]`, or `[value]`/`(valueChange)`. Empty = all domains. */
  readonly value = model<readonly Domain[]>([]);
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  protected openPicker(event: Event): void {
    this.picker().toggle(event.currentTarget as HTMLElement);
  }

  protected isChecked(domain: Domain): boolean {
    return this.value().some((d) => d.domainId === domain.domainId);
  }

  protected toggleDomain(domain: Domain): void {
    const next = this.isChecked(domain)
      ? this.value().filter((d) => d.domainId !== domain.domainId)
      : [...this.value(), domain];
    this.commit(next);
  }

  protected remove(domain: Domain): void {
    this.commit(this.value().filter((d) => d.domainId !== domain.domainId));
  }

  /** Soft tint for the chip body so arbitrary domain hues stay legible. */
  protected chipBackground(color: string | undefined): string | null {
    return color ? `color-mix(in srgb, ${color} 14%, transparent)` : null;
  }

  protected chipBorder(color: string | undefined): string | null {
    return color ?? null;
  }

  private commit(next: Domain[]): void {
    this.value.set(next);
    this.touch.emit();
  }
}
