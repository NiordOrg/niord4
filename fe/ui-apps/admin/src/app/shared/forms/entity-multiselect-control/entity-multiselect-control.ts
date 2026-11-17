import { NgTemplateOutlet } from '@angular/common';
import { Component, TemplateRef, computed, input, viewChild } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import { Autocomplete } from '../autocomplete/autocomplete';
import type { EntityMultiselectField } from '../entity-multiselect-field';

/**
 * The shared markup for `EntityMultiselectField` leaves: the `<app-autocomplete>`
 * glue (state + i18n bindings), the optional magnifier button (`field.magnifier`)
 * and the label + muted-suffix suggestion row (`field.optionSuffix`). Leaf
 * templates reduce to `<app-entity-multiselect-control [field]="this" />` — most
 * reuse the shared `entity-multiselect-field.html` — plus whatever is genuinely
 * leaf-specific: a custom `[optionTemplate]` row, an anchored picker popover.
 *
 * The host is `display: contents`, so the rendered control lays out exactly as
 * if it were declared directly in the leaf's template.
 */
@Component({
  selector: 'app-entity-multiselect-control',
  templateUrl: './entity-multiselect-control.html',
  imports: [Autocomplete, Icon, NgTemplateOutlet, TranslocoPipe],
  host: { class: 'contents' },
})
export class EntityMultiselectControl<TItem, TId extends string | number> {
  /** The owning field — every autocomplete binding reads from / writes to it. */
  readonly field = input.required<EntityMultiselectField<TItem, TId>>();
  /** Leaf-specific suggestion-row template; wins over `field.optionSuffix`. */
  readonly optionTemplate = input<TemplateRef<{ $implicit: TItem }> | undefined>(undefined);

  private readonly suffixRow = viewChild<TemplateRef<{ $implicit: TItem }>>('suffixRow');

  /** Row template forwarded to the autocomplete; `undefined` = its plain label rows. */
  protected readonly rowTemplate = computed(
    () => this.optionTemplate() ?? (this.field().optionSuffix ? this.suffixRow() : undefined),
  );

  protected suffixOf(item: TItem): string {
    return this.field().optionSuffix?.(item) ?? '';
  }
}
