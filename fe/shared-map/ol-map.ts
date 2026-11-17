import {
  AfterViewInit,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import OlMapImpl from 'ol/Map';
import OlView from 'ol/View';
import { defaults as defaultControls } from 'ol/control';
import Attribution from 'ol/control/Attribution';
import type Control from 'ol/control/Control';
import type Collection from 'ol/Collection';
import {
  defaults as defaultInteractions,
  DragPan,
  KeyboardPan,
  MouseWheelZoom,
} from 'ol/interaction';
import type Interaction from 'ol/interaction/Interaction';

import { MapProjectionService } from './map-projection.service';
import { MapShellService } from './map-shell.service';
import { installTileLoadGate } from './tile-load-gate';

export interface OlMapView {
  readonly zoom?: number;
  readonly center?: readonly [number, number];
  readonly extent?: readonly [number, number, number, number];
}

export interface OlMapViewChange {
  readonly zoom: number;
  readonly center: readonly [number, number];
  readonly extent: readonly [number, number, number, number];
}

const RESIZE_DEBOUNCE_MS = 100;
const MOVEEND_DEBOUNCE_MS = 250;
const ZOOM_TOLERANCE = 0.01;
const COORD_TOLERANCE = 1; // metres in EPSG:3857

/**
 * The controls of a read-only map: the credit line and nothing else.
 *
 * A tile provider's credit is owed wherever its tiles are drawn — OpenStreetMap's data licence
 * requires it — so a read-only map drops the zoom buttons and keeps the attribution. It is never
 * collapsed: a collapsed credit is a button to press before it can be read, and on a map that is
 * one link as a whole, pressing it would follow the link instead.
 */
function readOnlyControls(): Control[] {
  return [new Attribution({ collapsible: false })];
}

/** How long a wheel zoom glides, where the reader has not asked for less motion. */
const WHEEL_ZOOM_DURATION_MS = 250;

/**
 * Whether the reader has asked the system for less motion. Read once, when a map is built: the
 * setting is changed in the operating system, not while a map is being looked at, and the next
 * map built follows it.
 */
function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false;
}

/**
 * The same interactions without their motion, for a reader who asked for less of it: every zoom
 * lands at once instead of gliding there — the wheel, a double click, a pinch, the keyboard and
 * a shift-drag box alike — a keyboard pan steps rather than slides, and a drag stops where the
 * pointer lets go instead of coasting on. What each gesture does is unchanged, and so is the
 * order the interactions see an event in, because each is replaced where it stands.
 */
function withoutMotion(interactions: Collection<Interaction>): Collection<Interaction> {
  interactions.forEach((interaction, index) => {
    if (interaction instanceof DragPan) interactions.setAt(index, new DragPan());
    else if (interaction instanceof KeyboardPan) {
      interactions.setAt(index, new KeyboardPan({ duration: 0 }));
    }
  });
  return interactions;
}

/**
 * Whether an element is laid out with room for a map: a width and a height, both above zero.
 *
 * An element without room is an ordinary state, not a fault: a page that sizes its map after its
 * first render starts it at no height, a map in a hidden tab measures nothing, and a page that is
 * being left can collapse before it is taken down.
 */
function hasRoom(element: HTMLElement): boolean {
  return element.offsetWidth > 0 && element.offsetHeight > 0;
}

/**
 * An OpenLayers map that does not measure its element while the element has no room.
 *
 * OpenLayers measures its element when it is attached to it, whenever it resizes — through a
 * ResizeObserver of its own — and on every `updateSize` call, and it warns "No map visible because
 * the map container's width or height are 0." each time it finds the element laid out with no
 * width or no height. Skipping the measurement keeps the size the map last had, so a map that
 * loses its room stays as it was, without a warning, and takes its new size as soon as it has
 * room again.
 */
class RoomAwareMap extends OlMapImpl {
  override updateSize(): void {
    const element: HTMLElement | null = this.getTargetElement();
    if (element && !hasRoom(element)) return;
    super.updateSize();
  }
}

@Component({
  selector: 'app-ol-map',
  template: `
    <div #mapContainer class="ol-map-container h-full w-full"></div>
    <ng-content></ng-content>
  `,
  styles: [
    `
      :host {
        display: block;
        position: relative;
        width: 100%;
        height: 100%;
      }
      .ol-map-container {
        position: absolute;
        inset: 0;
      }
    `,
  ],
  // Inherit-or-create: if the host component already provides MapShellService,
  // use that instance so siblings positioned next to OlMap (a layer switcher
  // overlay, a tooltip) share the same shell. With no parent provider, OlMap
  // creates its own, so a map that has nothing beside it is self-contained.
  providers: [
    {
      provide: MapShellService,
      useFactory: () =>
        inject(MapShellService, { skipSelf: true, optional: true }) ?? new MapShellService(),
    },
  ],
})
export class OlMap implements AfterViewInit {
  private readonly projection = inject(MapProjectionService);
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly hostElement = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly view = input<OlMapView | undefined>(undefined);
  /**
   * A read-only map shows no zoom buttons and hosts no editing, but it still pans and zooms:
   * every preview lets the user look closer without opening the editor. It keeps the credit line
   * of the tiles it draws, which no map may drop.
   */
  readonly readOnly = input<boolean>(false);
  /**
   * A non-interactive map neither pans nor zooms. `readOnly` drops the zoom buttons only, so
   * every preview still navigates; but a map embedded as one big link is not a map the reader
   * navigates, and a drag that panned it instead of following the link would break the link.
   */
  readonly interactive = input<boolean>(true);
  readonly viewChange = output<OlMapViewChange>();

  private readonly mapContainer = viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');

  private map: OlMapImpl | undefined;
  private disposeTileGate: (() => void) | undefined;
  private pixelRatioQuery: MediaQueryList | undefined;
  private armedPixelRatio: number | undefined;
  private resizeObserver: ResizeObserver | undefined;
  private resizeTimer: ReturnType<typeof setTimeout> | undefined;
  private moveEndTimer: ReturnType<typeof setTimeout> | undefined;
  /**
   * The last view this component emitted via viewChange. If the parent echoes
   * the exact same values back via the `view` input, applyView short-circuits —
   * otherwise the lon/lat-rounding round-trip introduces sub-meter drift that
   * triggers setCenter, which fires another moveend, which emits another
   * viewChange, which writes another URL, which fires another search. The echo
   * never settles without this guard.
   */
  private lastEmittedView: OlMapViewChange | undefined;

  constructor() {
    effect(() => {
      const view = this.view();
      if (!this.map || !view) return;
      this.applyView(view);
    });

    // Single disposal path: DestroyRef only (the previous extra ngOnDestroy hook
    // called disposeMap a second time — harmless but redundant).
    this.destroyRef.onDestroy(() => this.disposeMap());
  }

  ngAfterViewInit(): void {
    const element = this.mapContainer().nativeElement;
    const isReadOnly = this.readOnly();

    const initialView = this.buildInitialView();

    // Replace the default MouseWheelZoom so we can explicitly pass
    // constrainResolution: true (matching the View setting) and keep the option
    // surface in one place for future tuning. The previous flicker problem was
    // solved by constrainResolution + the tile-load gate holding tiles until the
    // gesture settles, not by the animation duration — 250 ms (OL default) reads
    // smooth now.
    //
    // A read-only map still navigates: pan, wheel zoom and pinch work in every
    // preview so a user can look closer at a geometry without opening the editor.
    // Editing interactions are never part of this set; the geometry editor adds
    // its own.
    //
    // A reader who asked the system for less motion gets the same map without
    // it: zoomDuration reaches every zoom the defaults build, and the zoom
    // buttons and the wheel are given theirs here.
    const reduceMotion = prefersReducedMotion();
    const defaults = defaultInteractions({
      altShiftDragRotate: false,
      pinchRotate: false,
      mouseWheelZoom: false,
      zoomDuration: reduceMotion ? 0 : undefined,
    });
    const interactions = (reduceMotion ? withoutMotion(defaults) : defaults).extend([
      new MouseWheelZoom({
        duration: reduceMotion ? 0 : WHEEL_ZOOM_DURATION_MS,
        constrainResolution: true,
      }),
    ]);

    this.map = new RoomAwareMap({
      // An element with room takes the map at once, so it draws in the first frame; one without
      // is given it the moment it has room (see attachOnceItHasRoom).
      target: hasRoom(element) ? element : undefined,
      controls: isReadOnly
        ? readOnlyControls()
        : defaultControls({ rotate: false, zoomOptions: reduceMotion ? { duration: 0 } : {} }),
      interactions: this.interactive() ? interactions : [],
      view: initialView,
    });

    this.shell.setMap(this.map);
    // Suppress tile fetches while the view is animating/interacting so a
    // gesture only loads the tiles of its final view (see installTileLoadGate).
    this.disposeTileGate = installTileLoadGate(this.map);
    this.map.on('moveend', () => this.scheduleMoveEnd());
    this.followPixelRatio();

    // Watch the host, not the container the map draws in: the container fills the host, so the
    // two resize together, but attaching the map makes OpenLayers start observing the container.
    // An observation started from inside a ResizeObserver callback at the depth being delivered is
    // held back a frame and reported as "ResizeObserver loop completed with undelivered
    // notifications", which the applications' global error listeners log; one level deeper it is
    // delivered in the same frame.
    this.resizeObserver = new ResizeObserver(() => this.onResize());
    this.resizeObserver.observe(this.hostElement.nativeElement);
  }

  private onResize(): void {
    this.attachOnceItHasRoom();
    this.scheduleResize();
  }

  /**
   * Attaches a map that is still waiting for room, the moment its element has some. It runs in the
   * resize notification itself, not after the debounce, so the map draws in the frame that gave it
   * room. A map is attached once and stays attached until it is disposed.
   */
  private attachOnceItHasRoom(): void {
    if (!this.map || this.map.getTarget()) return;
    const element = this.mapContainer().nativeElement;
    if (hasRoom(element)) this.map.setTarget(element);
  }

  /**
   * Keeps the map's canvas backing store at the screen's true pixel ratio.
   *
   * OpenLayers reads `window.devicePixelRatio` once, when its module graph is evaluated, and
   * every map built afterwards inherits that one number. So a map that is already running when
   * the ratio changes — a browser zoom, or the window dragged to a screen with a different
   * scale factor — keeps sizing its canvas by the old ratio, and the compositor stretches the
   * whole map: tiles, strokes and symbols all go soft at once, however the map itself is zoomed.
   * Handing the map the current ratio re-renders it at the right resolution; it does not rebuild
   * the map, the view or the layers, and it costs nothing when the ratio has not moved.
   *
   * Two triggers, because neither covers the other: a browser zoom also resizes the CSS
   * viewport, which the map's ResizeObserver already sees, while a move to another screen
   * changes only the ratio and is caught by a media query pinned to the current value. That
   * query matches until the ratio moves, so each change re-arms it at the new one. Only the
   * query needs `matchMedia`; without it the map still tracks the ratio across a resize.
   *
   * What decides whether the query is re-armed is the ratio as a number, held here. The query's
   * own `media` string cannot answer the question: the browser normalises it, so a ratio of
   * 1.7999999523162842 comes back as `(resolution: 1.8dppx)` and never equals the string that
   * asked for it — at any fractional ratio a string comparison would tear the listener down and
   * rebuild it on every resize tick, forever.
   */
  private followPixelRatio(): void {
    const ratio = window.devicePixelRatio;
    if (this.armedPixelRatio !== ratio) {
      this.pixelRatioQuery?.removeEventListener('change', this.onPixelRatioChange);
      this.pixelRatioQuery = window.matchMedia?.(`(resolution: ${ratio}dppx)`);
      this.pixelRatioQuery?.addEventListener('change', this.onPixelRatioChange);
      this.armedPixelRatio = ratio;
    }
    this.map?.setPixelRatio(ratio);
  }

  private readonly onPixelRatioChange = (): void => this.followPixelRatio();

  private buildInitialView(): OlView {
    const initial = this.view();
    const center =
      initial?.center !== undefined ? this.projection.fromLonLat(initial.center) : [0, 0];
    const zoom = initial?.zoom ?? 4;
    // constrainResolution: true makes wheel/pinch gestures land on integer zoom
    // levels — this prevents tiles from being loaded for in-between zoom levels
    // during a wheel-scroll burst.
    return new OlView({ center, zoom, constrainResolution: true });
  }

  private applyView(desired: OlMapView): void {
    if (!this.map) return;
    if (this.isEchoOfLastEmit(desired)) return;
    const olView = this.map.getView();
    const currentZoom = olView.getZoom() ?? 0;
    const currentCenter = olView.getCenter() ?? [0, 0];

    if (desired.center !== undefined) {
      const target = this.projection.fromLonLat(desired.center);
      const dx = Math.abs(target[0] - currentCenter[0]);
      const dy = Math.abs(target[1] - currentCenter[1]);
      if (dx > COORD_TOLERANCE || dy > COORD_TOLERANCE) {
        olView.setCenter(target);
      }
    }
    if (desired.zoom !== undefined && Math.abs(desired.zoom - currentZoom) > ZOOM_TOLERANCE) {
      olView.setZoom(desired.zoom);
    }
  }

  private isEchoOfLastEmit(desired: OlMapView): boolean {
    const last = this.lastEmittedView;
    if (!last) return false;
    if (desired.zoom !== last.zoom) return false;
    if (desired.center === undefined) return false;
    if (desired.center[0] !== last.center[0] || desired.center[1] !== last.center[1]) {
      return false;
    }
    return true;
  }

  private scheduleResize(): void {
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeTimer = setTimeout(() => {
      this.resizeTimer = undefined;
      this.followPixelRatio();
      // A no-op while the element has no room: RoomAwareMap keeps the last size it measured.
      this.map?.updateSize();
    }, RESIZE_DEBOUNCE_MS);
  }

  private scheduleMoveEnd(): void {
    if (this.moveEndTimer) clearTimeout(this.moveEndTimer);
    this.moveEndTimer = setTimeout(() => {
      this.moveEndTimer = undefined;
      this.emitViewChange();
    }, MOVEEND_DEBOUNCE_MS);
  }

  private emitViewChange(): void {
    if (!this.map) return;
    const olView = this.map.getView();
    const zoom = olView.getZoom();
    const center = olView.getCenter();
    if (zoom === undefined || !center) return;
    const sourceExtent = olView.calculateExtent(this.map.getSize() ?? undefined);
    const lonLatCenter = this.projection.toLonLat(center);
    const lonLatExtent = this.projection.toLonLatExtent(sourceExtent);
    const r = (v: number) => this.projection.round(v);
    const change: OlMapViewChange = {
      zoom: r(zoom),
      center: [r(lonLatCenter[0]), r(lonLatCenter[1])],
      extent: [r(lonLatExtent[0]), r(lonLatExtent[1]), r(lonLatExtent[2]), r(lonLatExtent[3])],
    };
    this.lastEmittedView = change;
    this.viewChange.emit(change);
  }

  private disposeMap(): void {
    if (this.resizeTimer) {
      clearTimeout(this.resizeTimer);
      this.resizeTimer = undefined;
    }
    if (this.moveEndTimer) {
      clearTimeout(this.moveEndTimer);
      this.moveEndTimer = undefined;
    }
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = undefined;
    }
    if (this.pixelRatioQuery) {
      this.pixelRatioQuery.removeEventListener('change', this.onPixelRatioChange);
      this.pixelRatioQuery = undefined;
    }
    this.armedPixelRatio = undefined;
    if (this.map) {
      // Unhook the tile-load gate (listeners + queue wrapper) BEFORE disposing.
      this.disposeTileGate?.();
      this.disposeTileGate = undefined;
      this.map.setTarget(undefined);
      this.map.dispose();
      this.map = undefined;
    }
    this.shell.clearMap();
  }
}
