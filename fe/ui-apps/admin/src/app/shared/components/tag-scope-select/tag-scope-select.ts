import { Component, computed, inject, input, output } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import type { MessageTagType } from '../../models/message-tag.model';
import { TAG_TYPE_ICONS } from '../../models/tag-type-icons';
import { Icon } from '../../icons/icon';

interface TypeChip {
  readonly value: MessageTagType;
  readonly label: string;
  readonly icon: string;
}

/**
 * The private/domain/public multi-select chip row shared by every tag surface
 * (manager dialog filter, tag picker, single-tag field). None selected means
 * "all scopes"; the PRIVATE chip only exists for logged-in users (anonymous
 * users have no private tags).
 */
@Component({
  selector: 'app-tag-scope-select',
  imports: [Icon],
  template: `
    <div class="seg" role="group">
      @for (chip of typeChips(); track chip.value) {
        <button
          type="button"
          class="seg-btn"
          [class.is-active]="value().includes(chip.value)"
          [disabled]="disabled()"
          (click)="toggle(chip.value)"
        >
          <span class="inline-flex items-center gap-2">
            <app-icon [name]="chip.icon" />
            <span>{{ chip.label }}</span>
          </span>
        </button>
      }
    </div>
  `,
})
export class TagScopeSelect {
  private readonly auth = inject(AuthFacade);

  readonly value = input.required<readonly MessageTagType[]>();
  readonly disabled = input(false);
  readonly valueChange = output<readonly MessageTagType[]>();

  // `translateSignal` keeps the chip labels reactive to language changes.
  private readonly privateLabel = translateSignal('messages.tags.dialog.filter.type.private');
  private readonly domainLabel = translateSignal('messages.tags.dialog.filter.type.domain');
  private readonly publicLabel = translateSignal('messages.tags.dialog.filter.type.public');

  protected readonly typeChips = computed<TypeChip[]>(() => {
    const chips: TypeChip[] = [];
    if (this.auth.isAuthenticated()) {
      chips.push({ value: 'PRIVATE', label: this.privateLabel(), icon: TAG_TYPE_ICONS.PRIVATE });
    }
    chips.push(
      { value: 'DOMAIN', label: this.domainLabel(), icon: TAG_TYPE_ICONS.DOMAIN },
      { value: 'PUBLIC', label: this.publicLabel(), icon: TAG_TYPE_ICONS.PUBLIC },
    );
    return chips;
  });

  /** Toggle a scope in/out of the multi-selection; empty = all scopes. */
  protected toggle(type: MessageTagType): void {
    const current = this.value();
    const next = current.includes(type) ? current.filter((t) => t !== type) : [...current, type];
    this.valueChange.emit(next);
  }
}
