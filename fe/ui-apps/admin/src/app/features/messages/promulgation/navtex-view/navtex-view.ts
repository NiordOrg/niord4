import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { PlainTextToHtmlPipe } from '../../../../shared/pipes/plain-text-to-html.pipe';
import type { NavtexPromulgation } from './navtex-promulgation.model';

@Component({
  selector: 'app-navtex-view',
  imports: [TranslocoPipe, PlainTextToHtmlPipe],
  templateUrl: './navtex-view.html',
})
export class NavtexView {
  readonly promulgation = input.required<NavtexPromulgation>();

  protected readonly transmittersOn = computed<readonly string[]>(() => {
    const map = this.promulgation().transmitters;
    if (!map) return [];
    return Object.entries(map)
      .filter(([, enabled]) => enabled)
      .map(([name]) => name);
  });
}
