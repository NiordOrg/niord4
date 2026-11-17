import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import prettyBytes from 'pretty-bytes';

/** The size of this week's harbour notes, which the page states in the reader's language. */
export const NOTES_SIZE = 1_337_000;

/**
 * The example country's own page: the harbour notes, reached from its menu entry. It formats the
 * notes' size with pretty-bytes, the example's one country dependency (fe/country/package.json).
 */
@Component({
  selector: 'app-harbour-page',
  imports: [TranslocoPipe],
  template: `
    <section class="p-4">
      <h1 class="text-lg font-semibold">{{ 'country.harbour.title' | transloco }}</h1>
      <p class="mt-2 text-sm">{{ 'country.harbour.text' | transloco }}</p>
      <p class="mt-2 text-xs">{{ 'country.harbour.size' | transloco: { size: size() } }}</p>
    </section>
  `,
})
export class HarbourPage {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });

  /** The notes' size, formatted for the active language's locale. */
  protected readonly size = computed(() => prettyBytes(NOTES_SIZE, { locale: this.lang() }));
}
