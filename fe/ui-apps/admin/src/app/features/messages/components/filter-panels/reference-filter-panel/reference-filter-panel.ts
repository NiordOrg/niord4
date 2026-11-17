import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { MessageIdField } from '../../message-id-field/message-id-field';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import {
  REFERENCE_LEVELS_DEFAULT,
  REFERENCE_LEVELS_MAX,
  REFERENCE_LEVELS_MIN,
} from '../../../models/messages-filter-params.model';
import { MessagesFacade } from '../../../services/messages-facade.service';

@Component({
  selector: 'app-reference-filter-panel',
  templateUrl: './reference-filter-panel.html',
  imports: [FilterPanel, FormsModule, MessageIdField, TranslocoPipe],
})
export class ReferenceFilterPanel {
  private readonly messages = inject(MessagesFacade);

  constructor() {
    // The level field is spinner-only: it is adjusted with the up/down spinner
    // buttons or arrow keys, not by typing. We swallow value-mutating keystrokes
    // and paste on the number input itself (capture phase) so typing digits,
    // Backspace or Delete can't change it, while ArrowUp/ArrowDown (native
    // increment/decrement) and navigation keys still work. Scoped to the number
    // input so the sibling message-id field stays freely editable.
    const host = inject(ElementRef).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const numberField = host.querySelector('input[type=number]');
      if (!numberField) {
        return;
      }
      // Capture-phase listeners (Renderer2.listen only binds bubble phase, so
      // we use addEventListener directly) — must be torn down on destroy.
      numberField.addEventListener('keydown', this.blockLevelEdit as EventListener, {
        capture: true,
      });
      numberField.addEventListener('paste', this.preventEvent, { capture: true });
      destroyRef.onDestroy(() => {
        numberField.removeEventListener('keydown', this.blockLevelEdit as EventListener, {
          capture: true,
        });
        numberField.removeEventListener('paste', this.preventEvent, { capture: true });
      });
    });
  }

  readonly slice = computed(() => this.messages.filter.slice('reference')());
  readonly messageId = computed(() => this.slice().messageId ?? null);
  readonly referenceLevels = computed(() => this.slice().referenceLevels);

  readonly minLevels = REFERENCE_LEVELS_MIN;
  readonly maxLevels = REFERENCE_LEVELS_MAX;

  onMessageIdChange(value: string | null): void {
    const messageId = value ?? undefined;
    this.messages.filter.setSliceValue('reference', {
      messageId,
      referenceLevels: this.referenceLevels() ?? REFERENCE_LEVELS_DEFAULT,
    });
  }

  // Allow arrow keys (spin), navigation, and shortcuts; swallow anything that
  // would edit the value. preventDefault stops the character/Backspace/Delete
  // from mutating the input; stopImmediatePropagation keeps it from bubbling.
  private readonly blockLevelEdit = (event: KeyboardEvent): void => {
    const mutatesValue =
      (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) ||
      event.key === 'Backspace' ||
      event.key === 'Delete';
    if (mutatesValue) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  };

  private readonly preventEvent = (event: Event): void => {
    event.preventDefault();
    event.stopImmediatePropagation();
  };

  onLevelsChange(value: number | null): void {
    const clamped = Math.min(
      Math.max(value ?? REFERENCE_LEVELS_DEFAULT, REFERENCE_LEVELS_MIN),
      REFERENCE_LEVELS_MAX,
    );
    this.messages.filter.setSliceValue('reference', {
      messageId: this.messageId() ?? undefined,
      referenceLevels: clamped,
    });
  }

  clear(): void {
    this.messages.filter.clearFilter('reference');
  }
}
