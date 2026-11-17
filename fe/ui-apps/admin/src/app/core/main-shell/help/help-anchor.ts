import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../shared/icons/icon';
import { HelpUrlService } from './help-url.service';

@Component({
  selector: 'app-help-anchor',
  host: { class: 'contents' },
  imports: [TranslocoPipe, Icon],
  template: `
    @if (helpUrl(); as url) {
      <a
        class="icon-button rounded-full text-xs"
        [href]="url"
        target="_blank"
        rel="noopener noreferrer"
        [attr.aria-label]="'core.help.ariaLabel' | transloco"
      >
        <app-icon name="lucideCircleHelp" />
      </a>
    }
  `,
})
export class HelpAnchor {
  private readonly helpUrlService = inject(HelpUrlService);

  /**
   * Explicit help section override. When set, takes precedence over the
   * route-data based detection — required for non-routed surfaces like
   * modal dialogs (e.g. `section="message-details"` for the details modal).
   * When omitted, the deepest `data.help.section` in the active route tree
   * is used.
   */
  readonly section = input<string | undefined>(undefined);

  readonly helpUrl = computed<string | undefined>(() => {
    const explicit = this.section();
    if (explicit) {
      return this.helpUrlService.urlForSection(explicit);
    }
    return this.helpUrlService.routeUrl();
  });
}
