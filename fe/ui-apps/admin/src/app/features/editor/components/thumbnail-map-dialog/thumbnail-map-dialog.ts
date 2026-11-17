import { Component, computed, inject, input, model, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { BaseMapLayers } from '../../../../shared/components/ol-map/base-map-layers';
import { computeInitialMapView } from '../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { Icon } from '../../../../shared/icons/icon';
import { OlMessageDetailsLayer } from '../../../messages/components/map-layers/ol-message-details-layer';
import { OlMessageLabelsLayer } from '../../../messages/components/map-layers/ol-message-labels-layer';
import type { Message } from '../../../messages/models/message.model';
import { captureMapThumbnail } from '../../util/map-thumbnail.util';

/**
 * "Select thumbnail from map" dialog. Renders the message's geometry over the OSM base map (fitted
 * to the geometry extent), lets the user pan/zoom, and on Snapshot captures the rendered map as a
 * 256×256 PNG data URL — emitted via `thumbnailCaptured` for the host to persist.
 *
 * The map is square so the 256×256 capture is undistorted. OpenSeaMap is excluded
 * (`[openSeaMap]="false"`): its tiles load without CORS and would taint the canvas, making the
 * snapshot's `toDataURL` throw. OSM (CORS-enabled) and the WMS overlay (protected tiles arrive as
 * same-origin blob URLs; unprotected ones load in CORS mode) are both canvas-safe, so they stay in
 * the layer switcher.
 *
 * Provides its OWN {@link MapShellService} so the embedded map is isolated and never leaks its layers
 * into (or inherits layers from) another view's map shell.
 */
@Component({
  selector: 'app-thumbnail-map-dialog',
  imports: [
    TranslocoPipe,
    Dialog,
    Icon,
    OlMap,
    BaseMapLayers,
    OlLayerSwitcher,
    OlMessageDetailsLayer,
    OlMessageLabelsLayer,
  ],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      [modal]="true"
      [header]="'editor.layout.selectFromMapTitle' | transloco"
      [style]="{ width: '34rem' }"
    >
      <div class="flex flex-col gap-3">
        <p class="text-sm text-fg-subtle">{{ 'editor.layout.selectFromMapHelp' | transloco }}</p>

        <!-- Square map: a 1:1 source keeps the 256×256 capture undistorted. Built only while the
             dialog is open so the OpenLayers map is never instantiated behind a closed dialog. -->
        @if (visible()) {
          <div
            class="relative aspect-square w-full overflow-hidden rounded border border-surface-300"
          >
            <div class="absolute inset-0">
              <app-ol-map [view]="initialView()">
                <app-base-map-layers [openSeaMap]="false" />
                <app-ol-message-details-layer [message]="message()" [fitExtent]="true" />
                <app-ol-message-labels-layer [message]="message()" />
              </app-ol-map>
            </div>
            <app-ol-layer-switcher
              class="pointer-events-auto absolute bottom-2 left-2 z-10"
              [title]="'messages.map.layerSwitcher.title' | transloco"
            />
          </div>
        }
      </div>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" [disabled]="capturing()" (click)="snapshot()">
          <app-icon name="lucideCamera" />
          {{ 'editor.layout.snapshot' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
  providers: [MapShellService],
})
export class ThumbnailMapDialog {
  readonly visible = model(false);
  /** The message whose geometry is rendered (and fitted) in the capture map. */
  readonly message = input<Message | undefined>();
  /** The captured PNG data URL (`data:image/png;base64,…`) for the host to PUT + set as the thumbnail. */
  readonly thumbnailCaptured = output<string>();

  private readonly mapShell = inject(MapShellService);
  private readonly notifications = inject(NotificationService);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);

  protected readonly capturing = signal(false);

  /** Deployment default map view — the fallback location before the details layer fits the geometry. */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  protected async snapshot(): Promise<void> {
    const map = this.mapShell.map();
    if (!map || this.capturing()) {
      return;
    }
    this.capturing.set(true);
    try {
      const dataUrl = await captureMapThumbnail(map);
      this.thumbnailCaptured.emit(dataUrl);
      this.visible.set(false);
    } catch {
      this.notifications.error('editor.layout.thumbnailCaptureError');
    } finally {
      this.capturing.set(false);
    }
  }
}
