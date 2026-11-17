import { Component } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { EmptyState } from '../../../../shared/components/empty-state/empty-state';

@Component({
  selector: 'app-empty-selection',
  imports: [EmptyState],
  templateUrl: './empty-selection.html',
})
export class EmptySelection {
  readonly title = translateSignal('messages.selected.empty.title');
  readonly body = translateSignal('messages.selected.empty.body');
}
