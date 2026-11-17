import { Component, signal } from '@angular/core';
import { JsonPipe } from '@angular/common';

import { AREA_GEOMETRY_SOURCE } from '../../../../shared/components/geometry-editor/area-geometry-source.token';
import { GeometryEditor } from '../../../../shared/components/geometry-editor/geometry-editor';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import { MessagesAreaGeometrySource } from '../../../messages/services/area-geometry-source';

interface Seed {
  readonly label: string;
  readonly value: GeometryFeatureCollection | undefined;
}

function fc(geometry: object): GeometryFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry }],
  } as unknown as GeometryFeatureCollection;
}

/**
 * Dev-only sandbox for the standalone geometry editor. Reachable at
 * `/dev/geo-editor` in non-production builds only (the route is registered behind
 * `!environment.production` in `app.routes.ts`). Seed a shape, edit it, press OK,
 * and inspect the emitted GeoJSON — the standalone test loop that lets the editor
 * be developed + Playwright-tested without a hosting feature surface.
 */
@Component({
  selector: 'app-geometry-editor-sandbox',
  imports: [JsonPipe, GeometryEditor],
  template: `
    <div class="flex h-full min-h-0">
      <div class="min-h-0 flex-1 border-r border-surface-200 dark:border-surface-700">
        <app-geometry-editor
          [geometry]="seed()"
          [editType]="'message'"
          drawControls="point,polyline,polygon,box,select,remove,measure"
          (geometryChange)="onCommit($event)"
          (cancelled)="onCancel()"
        />
      </div>
      <aside class="flex w-96 min-h-0 flex-col gap-3 overflow-auto p-3">
        <h2 class="text-sm font-semibold">Geometry editor sandbox</h2>
        <p class="text-xs text-fg-muted">
          Dev-only harness. Seed a shape or pick a draw mode and draw on the map; select +
          drag/modify/shift-delete a vertex; ctrl-drag to box-select; drop a GeoJSON/KML/WKT file.
          Press <b>OK</b> to see the emitted GeoJSON.
        </p>
        <div class="flex flex-wrap gap-2">
          @for (s of seeds; track s.label) {
            <button type="button" class="btn-secondary text-sm" (click)="seed.set(s.value)">
              {{ s.label }}
            </button>
          }
        </div>
        <h3 class="mt-2 text-xs font-semibold">Committed geometry</h3>
        <pre
          data-testid="committed-json"
          class="max-h-[60vh] overflow-auto rounded bg-surface-100 p-2 text-[0.7rem] leading-snug dark:bg-surface-800"
          >{{ committed() | json }}</pre
        >
      </aside>
    </div>
  `,
  // Anchor the harness to the viewport below the ~59px app header. The app shell is
  // min-h-screen (page-scroll, growable), so a plain h-full would let a tall child (the
  // tree panel or the JSON pane) grow the page and collapse the map; a fixed height makes
  // the inner min-h-0/overflow-auto panels scroll internally instead.
  host: { class: 'block h-[calc(100dvh-3.75rem)] overflow-hidden' },
  providers: [{ provide: AREA_GEOMETRY_SOURCE, useClass: MessagesAreaGeometrySource }],
})
export class GeometryEditorSandbox {
  protected readonly seed = signal<GeometryFeatureCollection | undefined>(undefined);
  protected readonly committed = signal<GeometryFeatureCollection | undefined>(undefined);

  protected readonly seeds: readonly Seed[] = [
    { label: 'Seed: Empty', value: undefined },
    { label: 'Seed: Point', value: fc({ type: 'Point', coordinates: [11, 56] }) },
    {
      label: 'Seed: LineString',
      value: fc({
        type: 'LineString',
        coordinates: [
          [10, 55],
          [12, 57],
        ],
      }),
    },
    {
      label: 'Seed: Polygon',
      value: fc({
        type: 'Polygon',
        coordinates: [
          [
            [10, 55],
            [12, 55],
            [12, 57],
            [10, 57],
            [10, 55],
          ],
        ],
      }),
    },
  ];

  protected onCommit(geometry: GeometryFeatureCollection): void {
    this.committed.set(geometry);
  }

  protected onCancel(): void {
    this.committed.set(undefined);
  }
}
