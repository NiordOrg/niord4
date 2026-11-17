import { Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';

/**
 * Placeholder content for an area whose feature work hasn't shipped yet.
 * Pass the heading translation key via `titleKey`. The "Coming Soon"
 * subtitle is shared (`main.comingSoon`).
 */
@Component({
  selector: 'app-coming-soon',
  imports: [TranslocoPipe, Icon],
  template: `
    <div class="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-center">
      <app-icon name="lucideClock" class="text-5xl text-surface-400" />
      <h1 class="text-2xl font-semibold text-surface-700">{{ titleKey() | transloco }}</h1>
      <p class="text-surface-500">{{ 'main.comingSoon' | transloco }}</p>
    </div>
  `,
  host: { class: 'flex flex-1 flex-col' },
})
export class ComingSoonPage {
  readonly titleKey = input.required<string>();
}
