import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';

const TEXT_DEBOUNCE_MS = 500;

@Component({
  selector: 'app-text-filter-panel',
  templateUrl: './text-filter-panel.html',
  imports: [FilterPanel, FilterPanelAutofocus, FormsModule, TranslocoPipe],
})
export class TextFilterPanel {
  private readonly messages = inject(MessagesFacade);

  readonly storedQuery = computed(() => this.messages.filter.slice('text')().query);
  readonly localQuery = signal('');

  constructor() {
    effect(() => {
      const stored = this.storedQuery();
      if (stored !== untracked(this.localQuery)) {
        this.localQuery.set(stored);
      }
    });

    effect((onCleanup) => {
      const value = this.localQuery();
      // Skip when localQuery just received an external stored value (URL apply,
      // clearAll). Without this guard the effect would push that same value
      // back into the facade and create a feedback loop.
      if (value === untracked(this.storedQuery)) {
        return;
      }
      const handle = setTimeout(() => {
        this.messages.filter.setSliceValue('text', { query: value });
      }, TEXT_DEBOUNCE_MS);
      onCleanup(() => clearTimeout(handle));
    });
  }

  onInput(value: string): void {
    this.localQuery.set(value);
  }

  clear(): void {
    this.localQuery.set('');
    this.messages.filter.clearFilter('text');
  }
}
