import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import Attribution from 'ol/control/Attribution';
import Zoom from 'ol/control/Zoom';
import BaseEvent from 'ol/events/Event';
import { DragPan, MouseWheelZoom } from 'ol/interaction';
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';

import { MapShellService } from './map-shell.service';
import { OlMap, type OlMapView, type OlMapViewChange } from './ol-map';

// jsdom lacks ResizeObserver, which ol.Map's constructor + OlMap component reference.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<div style="width: 400px; height: 300px;">
    <app-ol-map
      [view]="view()"
      [readOnly]="readOnly()"
      [interactive]="interactive()"
      (viewChange)="onViewChange($event)"
    />
  </div>`,
  imports: [OlMap],
})
class HostComponent {
  readonly view = signal<OlMapView | undefined>(undefined);
  readonly readOnly = signal(false);
  readonly interactive = signal(true);
  lastChange: OlMapViewChange | undefined;
  readonly mapRef = viewChild.required(OlMap);

  onViewChange(change: OlMapViewChange): void {
    this.lastChange = change;
  }
}

function getShell(host: HostComponent): MapShellService {
  return (host.mapRef() as unknown as { shell: MapShellService }).shell;
}

describe('OlMap', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
  });

  it('mounts an ol.Map via the MapShellService', () => {
    expect(getShell(fixture.componentInstance).map()).toBeInstanceOf(OlMapImpl);
  });

  it('applies the initial view input on mount', () => {
    fixture.destroy();
    const fresh = TestBed.createComponent(HostComponent);
    fresh.componentInstance.view.set({ zoom: 8, center: [12.5, 55.5] });
    fresh.detectChanges();
    const map = getShell(fresh.componentInstance).map();
    expect(map?.getView().getZoom()).toBeCloseTo(8, 1);
    fresh.destroy();
  });

  it('disposes the map and clears the shell on destroy', () => {
    const shell = getShell(fixture.componentInstance);
    expect(shell.map()).toBeDefined();
    fixture.destroy();
    expect(shell.map()).toBeUndefined();
  });

  it('keeps pan and wheel zoom on a read-only map and drops only the zoom buttons', () => {
    fixture.destroy();
    const fresh = TestBed.createComponent(HostComponent);
    fresh.componentInstance.readOnly.set(true);
    fresh.detectChanges();
    const map = getShell(fresh.componentInstance).map();
    const interactions = map?.getInteractions().getArray() ?? [];
    expect(interactions.some((i) => i instanceof DragPan)).toBe(true);
    expect(interactions.some((i) => i instanceof MouseWheelZoom)).toBe(true);
    expect(
      map
        ?.getControls()
        .getArray()
        .some((c) => c instanceof Zoom),
    ).toBe(false);
    fresh.destroy();
  });

  it('keeps the tiles credit on a read-only map, where it cannot be folded away', () => {
    fixture.destroy();
    const fresh = TestBed.createComponent(HostComponent);
    fresh.componentInstance.readOnly.set(true);
    fresh.detectChanges();
    const controls = getShell(fresh.componentInstance).map()?.getControls().getArray() ?? [];
    expect(controls).toHaveLength(1);
    expect(controls[0]).toBeInstanceOf(Attribution);
    expect((controls[0] as Attribution).getCollapsible()).toBe(false);
    fresh.destroy();
  });

  it('keeps the credit on a map that neither pans nor zooms, too', () => {
    fixture.destroy();
    const fresh = TestBed.createComponent(HostComponent);
    fresh.componentInstance.readOnly.set(true);
    fresh.componentInstance.interactive.set(false);
    fresh.detectChanges();
    const controls = getShell(fresh.componentInstance).map()?.getControls().getArray() ?? [];
    expect(controls.some((c) => c instanceof Attribution)).toBe(true);
    fresh.destroy();
  });

  it('gives a non-interactive map no interactions at all', () => {
    fixture.destroy();
    const fresh = TestBed.createComponent(HostComponent);
    fresh.componentInstance.interactive.set(false);
    fresh.detectChanges();
    const map = getShell(fresh.componentInstance).map();
    expect(map?.getInteractions().getLength()).toBe(0);
    fresh.destroy();
  });

  describe('reduced motion', () => {
    // A reader who has asked the system for less motion gets a map that moves at once: every
    // zoom lands where it is going instead of gliding there, and a drag stops where the pointer
    // lets go instead of coasting on. jsdom has no matchMedia, so the query is stubbed.
    let originalMatchMedia: typeof window.matchMedia;

    beforeEach(() => {
      originalMatchMedia = window.matchMedia;
    });

    afterEach(() => {
      window.matchMedia = originalMatchMedia;
    });

    function mount(reduceMotion: boolean): OlMapImpl {
      window.matchMedia = ((media: string) =>
        ({
          media,
          matches: reduceMotion && media === '(prefers-reduced-motion: reduce)',
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
        }) as unknown as MediaQueryList) as typeof window.matchMedia;
      fixture.destroy();
      fixture = TestBed.createComponent(HostComponent);
      fixture.detectChanges();
      return getShell(fixture.componentInstance).map()!;
    }

    /** The animation length each interaction and control that animates was built with. */
    function durations(map: OlMapImpl): number[] {
      return [...map.getInteractions().getArray(), ...map.getControls().getArray()]
        .map((part) => (part as unknown as { duration_?: number }).duration_)
        .filter((duration): duration is number => duration !== undefined);
    }

    function kinetic(map: OlMapImpl): unknown {
      const dragPan = map
        .getInteractions()
        .getArray()
        .find((i) => i instanceof DragPan);
      return (dragPan as unknown as { kinetic_?: unknown }).kinetic_;
    }

    function pressZoomIn(map: OlMapImpl): void {
      map.getViewport().querySelector<HTMLButtonElement>('.ol-zoom-in')!.click();
    }

    it('animates every zoom and lets a drag coast by default', () => {
      const map = mount(false);
      expect(durations(map).length).toBeGreaterThan(0);
      expect(durations(map).every((duration) => duration > 0)).toBe(true);
      expect(kinetic(map)).toBeDefined();

      pressZoomIn(map);
      expect(map.getView().getAnimating()).toBe(true);
    });

    it('moves at once, and keeps every interaction, when the reader asked for less motion', () => {
      const withMotion = mount(false)
        .getInteractions()
        .getArray()
        .map((i) => i.constructor);
      const map = mount(true);

      expect(durations(map).length).toBeGreaterThan(0);
      expect(durations(map).every((duration) => duration === 0)).toBe(true);
      expect(kinetic(map)).toBeUndefined();
      // The same interactions in the same order: only how they move changed.
      expect(
        map
          .getInteractions()
          .getArray()
          .map((i) => i.constructor),
      ).toEqual(withMotion);

      const zoom = map.getView().getZoom()!;
      pressZoomIn(map);
      expect(map.getView().getAnimating()).toBe(false);
      expect(map.getView().getZoom()).toBe(zoom + 1);
    });
  });

  describe('lastEmittedView echo guard', () => {
    // The guard exists because the lon/lat-rounding round-trip introduces
    // sub-meter drift in 3857: without it, the parent echoing setFromMap()
    // values back through the view input triggers setCenter, which fires
    // another moveend, which writes another URL, which fires another search.
    // These tests pin the byte-identical echo path open.

    beforeEach(() => {
      vi.useFakeTimers();
      // Re-mount under fake timers so any setTimeouts the component scheduled
      // during the previous fixture's detectChanges run against the mocked
      // clock from this point onward.
      fixture.destroy();
      fixture = TestBed.createComponent(HostComponent);
      fixture.componentInstance.view.set({ zoom: 10, center: [12, 55] });
      fixture.detectChanges();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    function triggerEmit(): OlMapViewChange {
      const shell = getShell(fixture.componentInstance);
      const map = shell.map()!;
      const view = map.getView();
      // jsdom doesn't measure the container, so calculateExtent / getSize
      // need deterministic stand-ins for emitViewChange's call.
      vi.spyOn(map, 'getSize').mockReturnValue([400, 300]);
      vi.spyOn(view, 'calculateExtent').mockReturnValue([1, 2, 3, 4]);
      // Stub the tile-load gate's post-settle render kick: on a jsdom map that
      // hasn't completed a real frame, map.render() would fire OL's own moveend
      // and re-arm this component's debounce, swallowing the emit under test.
      vi.spyOn(map, 'render').mockImplementation(() => undefined);
      map.dispatchEvent(new BaseEvent('moveend'));
      vi.advanceTimersByTime(250);
      const change = fixture.componentInstance.lastChange;
      expect(change).toBeDefined();
      return change!;
    }

    it('skips setCenter / setZoom when the parent echoes the last-emitted view byte-for-byte', () => {
      const emitted = triggerEmit();

      const view = getShell(fixture.componentInstance).map()!.getView();
      const setCenterSpy = vi.spyOn(view, 'setCenter');
      const setZoomSpy = vi.spyOn(view, 'setZoom');

      // Push the exact same lon/lat values back through the view input.
      fixture.componentInstance.view.set({
        zoom: emitted.zoom,
        center: emitted.center,
        extent: emitted.extent,
      });
      fixture.detectChanges();

      expect(setCenterSpy).not.toHaveBeenCalled();
      expect(setZoomSpy).not.toHaveBeenCalled();
    });

    it('applies setCenter / setZoom when the parent pushes a different view (deep-link, named filter)', () => {
      triggerEmit();

      const view = getShell(fixture.componentInstance).map()!.getView();
      const setCenterSpy = vi.spyOn(view, 'setCenter');
      const setZoomSpy = vi.spyOn(view, 'setZoom');

      // External URL change carries different lon/lat — not an echo.
      fixture.componentInstance.view.set({ zoom: 6, center: [10, 50] });
      fixture.detectChanges();

      expect(setCenterSpy).toHaveBeenCalled();
      expect(setZoomSpy).toHaveBeenCalled();
    });

    it('applies setZoom only when zoom alone differs (center matches echo)', () => {
      const emitted = triggerEmit();

      const view = getShell(fixture.componentInstance).map()!.getView();
      const setCenterSpy = vi.spyOn(view, 'setCenter');
      const setZoomSpy = vi.spyOn(view, 'setZoom');

      fixture.componentInstance.view.set({
        zoom: emitted.zoom + 1,
        center: emitted.center,
        extent: emitted.extent,
      });
      fixture.detectChanges();

      // Zoom differs → not an echo → both methods can fire; specifically zoom
      // is out of tolerance so setZoom must be called.
      expect(setZoomSpy).toHaveBeenCalled();
      // Center matches the existing view at sub-meter precision → setCenter
      // may or may not be called depending on rounding; not asserting either
      // way to keep the test focused on the zoom-differs branch.
      void setCenterSpy;
    });
  });

  describe('device pixel ratio', () => {
    // OpenLayers freezes window.devicePixelRatio when its module graph is evaluated,
    // so a running map has to be handed the current ratio whenever the screen's ratio
    // moves. Two triggers: the map's ResizeObserver (a browser zoom resizes the
    // CSS viewport too) and a media query armed at the current ratio (a move to another
    // screen changes nothing but the ratio). jsdom offers neither a settable ratio nor
    // a usable MediaQueryList, so both are stubbed here and restored afterwards.

    const RESIZE_DEBOUNCE_MS = 100;

    type ChangeListener = () => void;

    /**
     * A stand-in for MediaQueryList that keeps its listeners readable and normalises the
     * media text the way a browser does — Chrome stores `(resolution: 1.8dppx)` for a
     * query asked at a ratio of 1.7999999523162842. The component must therefore decide
     * whether to re-arm from the ratio it holds, never from this string.
     */
    function createStubQuery(media: string) {
      const listeners = new Set<ChangeListener>();
      return {
        media: media.replace(
          /([0-9.]+)dppx/,
          (_match, value: string) => `${Math.round(Number(value) * 1e5) / 1e5}dppx`,
        ),
        matches: true,
        listeners,
        addEventListener: vi.fn((_type: string, listener: ChangeListener) => {
          listeners.add(listener);
        }),
        removeEventListener: vi.fn((_type: string, listener: ChangeListener) => {
          listeners.delete(listener);
        }),
      };
    }

    type StubQuery = ReturnType<typeof createStubQuery>;

    let queries: StubQuery[];
    let matchMedia: ReturnType<typeof vi.fn>;
    let resizeCallbacks: ChangeListener[];
    let originalRatio: PropertyDescriptor | undefined;
    let originalMatchMedia: typeof window.matchMedia;
    let originalResizeObserver: typeof ResizeObserver;

    function setDevicePixelRatio(ratio: number): void {
      Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: ratio });
    }

    function mountAt(ratio: number): OlMapImpl {
      setDevicePixelRatio(ratio);
      fixture = TestBed.createComponent(HostComponent);
      fixture.detectChanges();
      return getShell(fixture.componentInstance).map()!;
    }

    /** A resize notifies every observer on the container, then the component debounces. */
    function fireResize(): void {
      for (const callback of [...resizeCallbacks]) callback();
      vi.advanceTimersByTime(RESIZE_DEBOUNCE_MS);
    }

    function fireMediaQueryChange(): void {
      const armed = queries[queries.length - 1];
      for (const listener of [...armed.listeners]) listener();
    }

    beforeEach(() => {
      // Only the component's own debounce is faked: leaving requestAnimationFrame real
      // keeps OpenLayers' render frame out of the synchronous test body.
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      fixture.destroy();

      queries = [];
      resizeCallbacks = [];
      // Only the resolution queries are recorded: the map also asks, once, whether the reader
      // wants less motion, and that query is not what these tests are about.
      matchMedia = vi.fn((media: string) => {
        const query = createStubQuery(media);
        if (!media.includes('resolution')) {
          return { ...query, matches: false } as unknown as MediaQueryList;
        }
        queries.push(query);
        return query as unknown as MediaQueryList;
      });

      originalRatio = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio');
      originalMatchMedia = window.matchMedia;
      originalResizeObserver = globalThis.ResizeObserver;

      window.matchMedia = matchMedia as unknown as typeof window.matchMedia;
      globalThis.ResizeObserver = class {
        constructor(callback: ChangeListener) {
          resizeCallbacks.push(callback);
        }
        observe = (): void => undefined;
        unobserve = (): void => undefined;
        disconnect = (): void => undefined;
      } as unknown as typeof ResizeObserver;
    });

    afterEach(() => {
      fixture.destroy();
      globalThis.ResizeObserver = originalResizeObserver;
      window.matchMedia = originalMatchMedia;
      if (originalRatio) {
        Object.defineProperty(window, 'devicePixelRatio', originalRatio);
      } else {
        Reflect.deleteProperty(window, 'devicePixelRatio');
      }
      vi.useRealTimers();
    });

    it('renders at the screen ratio on mount, not at the one OpenLayers froze', () => {
      expect(mountAt(2).getPixelRatio()).toBe(2);
    });

    it('follows a ratio change that arrives with a resize (browser zoom)', () => {
      const map = mountAt(1);
      setDevicePixelRatio(2.5);

      fireResize();

      expect(map.getPixelRatio()).toBe(2.5);
    });

    it('follows a ratio change the media query alone reports (window moved to another screen)', () => {
      const map = mountAt(2);
      setDevicePixelRatio(1);

      fireMediaQueryChange();

      expect(map.getPixelRatio()).toBe(1);
    });

    it('re-arms the query at the new ratio and drops the listener on the old one', () => {
      mountAt(2);
      expect(queries).toHaveLength(1);
      const previous = queries[0];

      setDevicePixelRatio(3);
      fireResize();

      expect(queries).toHaveLength(2);
      expect(previous.listeners.size).toBe(0);
      expect(queries[1].media).toBe('(resolution: 3dppx)');
      expect(queries[1].listeners.size).toBe(1);
    });

    it('leaves the armed query in place while the ratio holds, however the browser spells it', () => {
      // The ratio Chrome reports at a 180 % display scale: the query it hands back is
      // spelled differently from the one that was asked for, so a guard comparing media
      // strings would tear the listener down and rebuild it on every resize tick.
      const map = mountAt(1.7999999523162842);
      expect(queries[0].media).toBe('(resolution: 1.8dppx)');

      fireResize();
      fireResize();

      expect(queries).toHaveLength(1);
      expect(queries[0].removeEventListener).not.toHaveBeenCalled();
      expect(queries[0].listeners.size).toBe(1);
      expect(map.getPixelRatio()).toBe(1.7999999523162842);
    });

    it('removes the change listener when the map is destroyed', () => {
      mountAt(2);
      const query = queries[0];
      expect(query.addEventListener).toHaveBeenCalledTimes(1);

      fixture.destroy();

      expect(query.removeEventListener).toHaveBeenCalledWith(
        'change',
        query.addEventListener.mock.calls[0][1],
      );
      expect(query.listeners.size).toBe(0);
    });
  });

  describe('attaching to its element', () => {
    // OpenLayers warns "No map visible because the map container's width or height are 0." each
    // time it measures an element laid out with no width or no height — the messages map starts
    // that way, full width and no height until its page sizes it. So the map is attached only
    // once its element has room, and is never measured while the element has none. jsdom lays
    // nothing out: the container's size is stubbed, every ResizeObserver watching the map's
    // elements is notified by hand, and a stylesheet gives the container the zero borders and
    // padding a browser would report, so that OpenLayers' own measurement comes out as a number.

    const RESIZE_DEBOUNCE_MS = 100;
    const NO_ROOM_WARNING = "No map visible because the map container's width or height are 0.";

    let observers: DrivenResizeObserver[];
    let room: { width: number; height: number };
    let warn: MockInstance<typeof console.warn>;
    let stylesheet: HTMLStyleElement;
    let originalResizeObserver: typeof ResizeObserver;

    /** A ResizeObserver whose notifications the test sends, to the elements it observes. */
    class DrivenResizeObserver {
      readonly targets = new Set<Element>();

      constructor(readonly notify: () => void) {
        observers.push(this);
      }

      observe = (target: Element): void => {
        this.targets.add(target);
      };
      unobserve = (target: Element): void => {
        this.targets.delete(target);
      };
      disconnect = (): void => {
        this.targets.clear();
      };
    }

    function host(): HTMLElement {
      return fixture.nativeElement.querySelector('app-ol-map');
    }

    function container(): HTMLElement {
      return fixture.nativeElement.querySelector('.ol-map-container');
    }

    function mountWithRoom(width: number, height: number): OlMapImpl {
      room = { width, height };
      fixture = TestBed.createComponent(HostComponent);
      fixture.detectChanges();
      return getShell(fixture.componentInstance).map()!;
    }

    /**
     * Lays the container out at a new size and notifies every observer watching the map's host
     * or its container, as the browser does, then lets the component's debounce run out.
     */
    function layOut(width: number, height: number): void {
      room = { width, height };
      for (const observer of [...observers]) {
        if (observer.targets.has(host()) || observer.targets.has(container())) observer.notify();
      }
      vi.advanceTimersByTime(RESIZE_DEBOUNCE_MS);
    }

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      fixture.destroy();

      observers = [];
      originalResizeObserver = globalThis.ResizeObserver;
      globalThis.ResizeObserver = DrivenResizeObserver as unknown as typeof ResizeObserver;

      room = { width: 0, height: 0 };
      const measure = (dimension: 'width' | 'height') =>
        function (this: HTMLElement): number {
          return this.classList.contains('ol-map-container') ? room[dimension] : 0;
        };
      vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(measure('width'));
      vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(measure('height'));

      stylesheet = document.createElement('style');
      stylesheet.textContent = '.ol-map-container { border-width: 0; padding: 0; }';
      document.head.append(stylesheet);

      warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });

    afterEach(() => {
      fixture.destroy();
      vi.restoreAllMocks();
      stylesheet.remove();
      globalThis.ResizeObserver = originalResizeObserver;
      vi.useRealTimers();
    });

    it('attaches at once to an element that has room when the map is built', () => {
      const map = mountWithRoom(400, 300);

      expect(map.getTargetElement()).toBe(container());
      expect(map.getSize()).toEqual([400, 300]);
    });

    it('waits, without a warning, while its element has no room', () => {
      const map = mountWithRoom(400, 0);
      layOut(400, 0);

      expect(warn).not.toHaveBeenCalledWith(NO_ROOM_WARNING);
      expect(map.getTarget()).toBeUndefined();
      expect(map.getSize()).toBeUndefined();
      // The rest of the map is in place meanwhile: the shell holds it for its layers.
      expect(getShell(fixture.componentInstance).map()).toBe(map);
    });

    it('attaches once, and draws, when its element first has room', () => {
      const map = mountWithRoom(400, 0);
      const setTarget = vi.spyOn(map, 'setTarget');

      layOut(400, 300);
      layOut(500, 300);

      expect(setTarget).toHaveBeenCalledTimes(1);
      expect(setTarget).toHaveBeenCalledWith(container());
      expect(map.getSize()).toEqual([500, 300]);
      map.renderSync();
      expect(map.getPixelFromCoordinate(map.getView().getCenter()!)).toEqual([250, 150]);
      expect(warn).not.toHaveBeenCalledWith(NO_ROOM_WARNING);
    });

    it('keeps its size, without measuring, while its element has no room again', () => {
      const map = mountWithRoom(400, 300);
      // OpenLayers' own measurement, behind every path that asks for one: its ResizeObserver,
      // this component's debounced resize, and attaching.
      const measure = vi.spyOn(OlMapImpl.prototype, 'updateSize');

      layOut(400, 0);

      expect(measure).not.toHaveBeenCalled();
      expect(map.getSize()).toEqual([400, 300]);
      expect(warn).not.toHaveBeenCalledWith(NO_ROOM_WARNING);

      layOut(600, 300);

      expect(map.getSize()).toEqual([600, 300]);
    });

    it('watches the host, one level above the container OpenLayers observes', () => {
      // Attaching makes OpenLayers observe the container. Started from inside a notification for
      // the container itself, that observation would be held back a frame and reported as a
      // ResizeObserver loop error; started from one for the host, it is delivered at once.
      mountWithRoom(400, 0);
      const own = observers.find((observer) => observer.targets.has(host()));
      expect(own).toBeDefined();
      expect(own!.targets.has(container())).toBe(false);
    });

    it('disposes a map that never had room', () => {
      const map = mountWithRoom(400, 0);
      const shell = getShell(fixture.componentInstance);

      fixture.destroy();

      expect(shell.map()).toBeUndefined();
      expect(map.getTarget()).toBeFalsy();
      expect(observers.every((observer) => observer.targets.size === 0)).toBe(true);
    });
  });
});
