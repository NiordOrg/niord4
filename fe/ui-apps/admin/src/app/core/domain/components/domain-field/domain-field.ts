import { Component, computed, inject, input, model, output } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Select } from '../../../../shared/forms/select/select';
import { DomainFacade } from '../../facades/domain.facade';
import type { Domain } from '../../models/domain.model';

@Component({
  selector: 'app-domain-field',
  templateUrl: './domain-field.html',
  imports: [Select, TranslocoPipe],
})
export class DomainField implements FormValueControl<Domain | null> {
  private readonly facade = inject(DomainFacade);

  readonly placeholder = input('');

  readonly availableDomains = computed<Domain[]>(() => [...this.facade.availableDomains()]);
  /** Two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<Domain | null>(null);
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  onChangeValue(value: Domain | null): void {
    this.value.set(value);
    this.touch.emit();
  }
}
