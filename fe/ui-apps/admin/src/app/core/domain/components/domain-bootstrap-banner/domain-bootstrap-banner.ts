import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { DomainFacade } from '../../facades/domain.facade';

/**
 * The shell's notice that the start-up read of the domains failed. Without a domain every list of
 * the app is empty for that reason alone, so the notice stands on every page. Its Retry reloads
 * the app, as a desk change does, so every domain-scoped piece of state starts under the domain
 * the reload finds; like the update banner, it offers the reload and never performs it.
 */
@Component({
  selector: 'app-domain-bootstrap-banner',
  imports: [Icon, TranslocoPipe],
  template: `
    @if (domains.bootstrapFailed()) {
      <div
        class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-[var(--niord-notice-warn-bg)] px-4 py-2 text-center text-sm text-[var(--niord-notice-warn-fg)]"
        role="alert"
        data-testid="domain-bootstrap-error"
      >
        <span>
          <app-icon name="lucideTriangleAlert" class="mr-1" />
          {{ 'core.domain.bootstrap.errorTitle' | transloco }}
        </span>
        <button
          type="button"
          class="font-medium underline"
          data-testid="domain-bootstrap-retry"
          (click)="reload()"
        >
          {{ 'common.retry' | transloco }}
        </button>
      </div>
    }
  `,
})
export class DomainBootstrapBanner {
  protected readonly domains = inject(DomainFacade);

  protected reload(): void {
    window.location.reload();
  }
}
