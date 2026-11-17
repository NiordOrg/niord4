import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { BaseMapLayers } from '../../../../shared/components/ol-map/base-map-layers';
import { computeInitialMapView } from '../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { OlMap, type OlMapView, type OlMapViewChange } from '@niord/map/ol-map';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { MapFilterPanel } from '../../components/filter-panels/map-filter-panel/map-filter-panel';
import { MapResultList } from '../../components/map-result-list/map-result-list';
import { MessageMapTooltip } from '../../components/map-layers/map-tooltip';
import { OlAreasLayer } from '../../components/map-layers/ol-areas-layer';
import { OlChartsLayer } from '../../components/map-layers/ol-charts-layer';
import { OlMessageListLayer } from '../../components/map-layers/ol-message-list-layer';
import { MapExtentService } from '../../services/map-extent.service';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { MessageSelectionService } from '../../services/message-selection.service';
import { MessagesFacade } from '../../services/messages-facade.service';

@Component({
  selector: 'app-messages-map-view',
  templateUrl: './messages-map-view.html',
  imports: [
    BaseMapLayers,
    MapFilterPanel,
    MapResultList,
    MessageMapTooltip,
    OlAreasLayer,
    OlChartsLayer,
    OlLayerSwitcher,
    OlMap,
    OlMessageListLayer,
    TranslocoPipe,
  ],
  // Provide MapShellService at the view level (rather than inside OlMap) so
  // siblings of OlMap — specifically the layer switcher, which we lift out of
  // OlMap's content slot into a shared left-column overlay — share the same
  // shell instance and see the same layer registrations.
  providers: [MapShellService],
})
export class MessagesMapView {
  protected readonly facade = inject(MessagesFacade);
  private readonly domain = inject(DomainFacade);
  private readonly dialog = inject(MessageDetailsDialogService);
  private readonly selection = inject(MessageSelectionService);
  private readonly mapExtent = inject(MapExtentService);
  private readonly config = inject(APP_CONFIG);
  private readonly hostEl = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  private readonly listLayer = viewChild.required(OlMessageListLayer);
  private readonly tooltip = viewChild.required(MessageMapTooltip);

  protected readonly messages = computed(() => this.facade.list.messages());
  protected readonly total = computed(() => this.facade.list.total());
  protected readonly loading = computed(() => this.facade.list.loading());

  protected readonly chartsName = translateSignal('messages.map.layers.charts');
  protected readonly areasName = translateSignal('messages.map.layers.areas');
  protected readonly messagesName = translateSignal('messages.map.layers.messages');

  protected readonly selectionSet = computed<ReadonlySet<string>>(() => {
    return new Set(this.selection.selectedIds());
  });

  /** Message currently hovered in the result-list overlay (undefined = none). */
  protected readonly hoveredMessageId = signal<string | undefined>(undefined);

  protected readonly chartsFilter = computed(() => {
    const enabled = this.facade.filter.enabled('chart')();
    if (!enabled) return [] as const;
    return this.facade.filter.slice('chart')().charts;
  });

  protected readonly areasFilter = computed(() => {
    const enabled = this.facade.filter.enabled('area')();
    if (!enabled) return [] as const;
    return this.facade.filter.slice('area')().areas;
  });

  protected readonly chartsLayerVisible = computed(
    () => this.facade.filter.enabled('chart')() && this.chartsFilter().length > 0,
  );
  protected readonly areasLayerVisible = computed(
    () => this.facade.filter.enabled('area')() && this.areasFilter().length > 0,
  );

  protected readonly initialView = computed<OlMapView | undefined>(
    () =>
      this.mapExtent.extent() ?? computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  /**
   * Pixel height that makes the map's visual bottom land exactly on the
   * footer's top edge. Computed as `footer.top - host.top`. Recomputes on
   * mount, window resize, and whenever the filter panel toggles (which
   * shifts the host in flow).
   */
  protected readonly mapHeightPx = signal(0);
  private resizeObserver: ResizeObserver | undefined;
  private previousBodyOverflowY = '';

  constructor() {
    // Wire the tooltip resolver once the tooltip viewChild is available.
    // initialView() already tracks MapExtentService.extent() and is bound to
    // <app-ol-map>'s view input, so inbound URL changes flow into the map
    // automatically — no separate "reload" signal subscription needed.
    effect(() => {
      const tooltip = this.tooltip();
      const listLayer = this.listLayer();
      if (!tooltip || !listLayer) return;
      tooltip.setResolver((pixel) => listLayer.messagesAtPixel(pixel));
    });

    afterNextRender(() => {
      this.recomputeHeight();
      // Observe the section (host's parent) — its size tracks every layout
      // change above the map (filter-panel show/hide, individual filters
      // being added or removed inside the panel, etc). Observing the body
      // only catches grows, since `min-h-screen` pins the body at viewport
      // height on shrink, so it misses filter removals.
      this.resizeObserver = new ResizeObserver(() => this.recomputeHeight());
      const sectionEl = this.hostEl.nativeElement.parentElement;
      if (sectionEl) this.resizeObserver.observe(sectionEl);
      window.addEventListener('resize', this.recomputeHeight);

      // Suppress the transient page scrollbar that would otherwise flicker
      // when a filter panel opens: layout grows for one frame before the
      // recompute shrinks the map back. With body overflow hidden the
      // overshoot is silently clipped — no visible scrollbar, no jitter.
      this.previousBodyOverflowY = document.body.style.overflowY;
      document.body.style.overflowY = 'hidden';
    });

    this.destroyRef.onDestroy(() => {
      this.resizeObserver?.disconnect();
      window.removeEventListener('resize', this.recomputeHeight);
      document.body.style.overflowY = this.previousBodyOverflowY;
    });
  }

  private readonly recomputeHeight = (): void => {
    const top = this.hostEl.nativeElement.getBoundingClientRect().top;
    // Subtract the footer's *height* (stable across layout reflows) rather
    // than its `.top` (transient — grows while main is briefly oversized
    // before this recompute shrinks the map back to fit). The footer is
    // 65 px today (min-h-16 = 64 + border-t = 1); measured dynamically so
    // it survives localisation that may wrap content into a taller footer.
    const footer = document.querySelector('app-main-footer');
    const footerHeight = footer instanceof HTMLElement ? footer.getBoundingClientRect().height : 64;
    const height = window.innerHeight - top - footerHeight;
    this.mapHeightPx.set(Math.max(height, 0));
  };

  protected onViewChange(change: OlMapViewChange): void {
    this.mapExtent.setFromMap(change.zoom, change.center, change.extent);
  }

  protected onFeatureClicked(messageId: string): void {
    this.dialog.open(
      messageId,
      this.messages().map((m) => m.id),
    );
  }

  protected onListMessageClicked(messageId: string): void {
    this.dialog.open(
      messageId,
      this.messages().map((m) => m.id),
    );
  }

  protected onListMessageHovered(messageId: string | undefined): void {
    this.hoveredMessageId.set(messageId);
  }

  protected onListMessageZoom(messageId: string): void {
    // Drop the hover halo before the map moves. The pointer is still resting on the row it
    // was clicked from, so no `mouseleave` follows and nothing else would ever clear it —
    // the halo would survive on a map the user has just re-framed. Clearing here means the
    // halo returns only when the pointer enters a DIFFERENT row, which is the intent: a
    // highlight answers "which one am I pointing at", not "which one did I zoom to".
    this.hoveredMessageId.set(undefined);
    this.listLayer().fitToMessage(messageId);
  }
}
