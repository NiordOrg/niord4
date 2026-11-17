import { Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * Shared page chrome for every admin/sysadmin section: title + optional one-line
 * description above the projected section content. No breadcrumb — the section
 * rail already provides the location context.
 *
 * ONE HEADER PER LEVEL: a section whose content drills into a record that draws
 * its own header cluster sets `[showHeader]="false"` while that record is open,
 * so the title of the screen the reader has left does not sit above the screen
 * they are on.
 */
@Component({
  selector: 'app-admin-section-page',
  imports: [TranslocoPipe],
  template: `
    @if (showHeader()) {
      <header class="mb-4 border-b border-surface-200 pb-3 dark:border-surface-700">
        <h2 class="text-2xl font-semibold text-fg">{{ titleKey() | transloco }}</h2>
        @if (descriptionKey(); as key) {
          <p class="mt-1 text-sm text-fg-muted">{{ key | transloco: descriptionParams() }}</p>
        }
      </header>
    }
    <ng-content />
  `,
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class AdminSectionPage {
  readonly titleKey = input.required<string>();
  readonly descriptionKey = input<string | undefined>(undefined);

  /** Whether the title + description band is drawn at all. */
  readonly showHeader = input(true);

  /**
   * Interpolation parameters for the description.
   *
   * A section scoped to something the reader chose elsewhere — a domain, a
   * period — names it in the blurb, which needs the value at render time rather
   * than a second key per scope.
   */
  readonly descriptionParams = input<Record<string, unknown> | undefined>();
}
