import { Component, computed, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import {
  MESSAGE_TYPES_BY_MAIN_TYPE,
  type MainType,
  type MessageType,
} from '../../../models/message.model';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';

const NW_TYPES: readonly MessageType[] = MESSAGE_TYPES_BY_MAIN_TYPE.NW;
const NM_TYPES: readonly MessageType[] = MESSAGE_TYPES_BY_MAIN_TYPE.NM;

interface MainTypeOption {
  readonly label: string;
  readonly value: '' | MainType;
}

@Component({
  selector: 'app-type-filter-panel',
  templateUrl: './type-filter-panel.html',
  imports: [FilterPanel, TranslocoPipe],
})
export class TypeFilterPanel {
  private readonly messages = inject(MessagesFacade);
  private readonly domain = inject(DomainFacade);

  readonly slice = computed(() => this.messages.filter.slice('type')());
  readonly mainType = computed(() => this.slice().mainType);
  readonly types = computed(() => this.slice().types);
  readonly cancellationWarnings = computed(() => this.slice().cancellationWarnings);

  readonly supportsNwBoth = this.domain.supportsNw(false);
  readonly supportsNmBoth = this.domain.supportsNm(false);
  readonly supportsNwOnly = this.domain.supportsNw(true);
  readonly supportsNmOnly = this.domain.supportsNm(true);
  readonly showMainTypeRadio = computed(() => this.supportsNwBoth() && this.supportsNmBoth());

  readonly mainTypeOptions = computed<MainTypeOption[]>(() => [
    { label: 'messages.filter.type.mainType.all', value: '' },
    { label: 'messages.filter.type.mainType.nw', value: 'NW' },
    { label: 'messages.filter.type.mainType.nm', value: 'NM' },
  ]);

  readonly visibleNwTypes = computed(() => this.filterSupportedTypes(NW_TYPES));
  readonly visibleNmTypes = computed(() => this.filterSupportedTypes(NM_TYPES));

  readonly showNwTypes = computed(
    () => this.mainType() === 'NW' || (this.mainType() === '' && this.supportsNwOnly()),
  );
  /**
   * The Cancellation warnings chip shows beside the NW types, and also whenever it is on (as from an
   * address), so a list narrowed to cancellation warnings always has the chip that turns it off.
   */
  readonly showCancellationWarnings = computed(
    () => this.showNwTypes() || this.cancellationWarnings(),
  );
  readonly showNmTypes = computed(
    () => this.mainType() === 'NM' || (this.mainType() === '' && this.supportsNmOnly()),
  );

  isTypeChecked(type: MessageType): boolean {
    return this.types().includes(type);
  }

  onMainTypeChange(value: '' | MainType): void {
    const cleared =
      value === 'NW'
        ? this.dropTypes(NM_TYPES)
        : value === 'NM'
          ? this.dropTypes(NW_TYPES)
          : this.types();
    // The chip stays on only where it stays in sight: beside the NW types.
    const keepsNwTypes = value === 'NW' || (value === '' && this.supportsNwOnly());
    this.messages.filter.setSliceValue('type', {
      mainType: value,
      types: cleared,
      cancellationWarnings: this.cancellationWarnings() && keepsNwTypes,
    });
  }

  onCancellationWarningsToggle(): void {
    this.messages.filter.setSliceValue('type', {
      ...this.slice(),
      cancellationWarnings: !this.cancellationWarnings(),
    });
  }

  onTypeToggle(type: MessageType, checked: boolean): void {
    const current = this.types();
    const next = checked
      ? current.includes(type)
        ? current
        : [...current, type]
      : current.filter((t) => t !== type);
    this.messages.filter.setSliceValue('type', { ...this.slice(), types: next });
  }

  selectAll(group: 'NW' | 'NM'): void {
    const visible = group === 'NW' ? this.visibleNwTypes() : this.visibleNmTypes();
    const current = this.types();
    const additions = visible.filter((type) => !current.includes(type));
    if (additions.length === 0) {
      return;
    }
    this.messages.filter.setSliceValue('type', {
      ...this.slice(),
      types: [...current, ...additions],
    });
  }

  reset(group: 'NW' | 'NM'): void {
    const drop = group === 'NW' ? NW_TYPES : NM_TYPES;
    this.messages.filter.setSliceValue('type', {
      ...this.slice(),
      types: this.dropTypes(drop),
    });
  }

  clear(): void {
    this.messages.filter.clearFilter('type');
  }

  private filterSupportedTypes(group: readonly MessageType[]): MessageType[] {
    return group.filter((type) => this.domain.supportsType(type)());
  }

  private dropTypes(group: readonly MessageType[]): readonly MessageType[] {
    return this.types().filter((t) => !group.includes(t));
  }
}
