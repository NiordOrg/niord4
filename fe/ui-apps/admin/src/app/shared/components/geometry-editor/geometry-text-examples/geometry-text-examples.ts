import { Component, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * The collapsible "Show examples" block for the GeoJSON-as-plain-text input format — two worked
 * examples (a simple point, and a named polygon + line-string with per-language coordinate
 * names). Shared by the Edit-as-text dialog and the import dialog's "From Text" tab so the two
 * stay identical. Owns its own toggle state; drop it in with `<app-geometry-text-examples />`.
 */
@Component({
  selector: 'app-geometry-text-examples',
  imports: [TranslocoPipe],
  template: `
    @if (showExamples()) {
      <div class="mb-2 rounded border border-surface-200 p-2 text-xs dark:border-surface-700">
        <p class="mb-1 text-fg-muted">{{ 'geometry.example.simplePoint' | transloco }}</p>
        <pre class="mb-2 overflow-auto rounded bg-surface-50 p-2 dark:bg-surface-900">
56N 11 30.23E</pre
        >
        <p class="mb-1 text-fg-muted">{{ 'geometry.example.named' | transloco }}</p>
        <pre class="overflow-auto rounded bg-surface-50 p-2 dark:bg-surface-900">
Polygon, da: Skydeområde 1, en: Firing area 1
54° 54.967'N 015° 49.961'E
54° 54.969'N 015° 15.029'E
55° 03.006'N 015° 15.029'E
55° 19.930'N 015° 49.942'E
54° 54.967'N 015° 49.961'E

LineString
1) 54° 53.397'N 009° 53.071'E, da: kyst, en: coast
2) 54° 53.982'N 009° 52.170'E, da: kyst, en: coast</pre
        >
      </div>
    }
    <button
      type="button"
      class="link-button mb-2 text-xs"
      (click)="showExamples.set(!showExamples())"
    >
      {{ (showExamples() ? 'geometry.example.hide' : 'geometry.example.show') | transloco }}
    </button>
  `,
  host: { class: 'contents' },
})
export class GeometryTextExamples {
  protected readonly showExamples = signal(false);
}
