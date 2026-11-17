import OlMapImpl from 'ol/Map';
import BaseEvent from 'ol/events/Event';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
  type Mock,
  type MockInstance,
} from 'vitest';

import { installTileLoadGate } from './tile-load-gate';

// jsdom lacks ResizeObserver, which ol.Map's constructor references.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

interface TileQueueInternals {
  loadMoreTiles: (maxTotalLoading: number, maxNewLoads: number) => void;
  reprioritize: () => void;
}

describe('installTileLoadGate', () => {
  let map: OlMapImpl;
  let queue: TileQueueInternals;
  let original: Mock<(maxTotalLoading: number, maxNewLoads: number) => void>;
  let renderSpy: MockInstance<() => void>;
  let dispose: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    map = new OlMapImpl({});
    queue = (map as unknown as { tileQueue_: TileQueueInternals }).tileQueue_;
    // Route the real drain through a spy the gate captures as its `original`,
    // and stub reprioritize so we can assert on it without a real frame state.
    original = vi.fn<(maxTotalLoading: number, maxNewLoads: number) => void>();
    queue.loadMoreTiles = original;
    vi.spyOn(queue, 'reprioritize').mockImplementation(() => undefined);
    renderSpy = vi.spyOn(map, 'render').mockImplementation(() => undefined);
    dispose = installTileLoadGate(map);
  });

  afterEach(() => {
    dispose();
    vi.useRealTimers();
    map.setTarget(undefined);
    map.dispose();
    vi.restoreAllMocks();
  });

  /** Invoke the gated drain the way OL's handlePostRender would. */
  const drain = (): void => queue.loadMoreTiles(16, 16);

  it('passes the drain through (and reprioritizes) when the view is idle', () => {
    drain();
    expect(queue.reprioritize).toHaveBeenCalledTimes(1);
    expect(original).toHaveBeenCalledWith(16, 16);
  });

  it('suppresses the drain while the view is animating', () => {
    vi.spyOn(map.getView(), 'getAnimating').mockReturnValue(true);
    drain();
    expect(original).not.toHaveBeenCalled();
    expect(queue.reprioritize).not.toHaveBeenCalled();
  });

  it('suppresses the drain while the view is interacting', () => {
    vi.spyOn(map.getView(), 'getInteracting').mockReturnValue(true);
    drain();
    expect(original).not.toHaveBeenCalled();
  });

  it('holds during the settle window after moveend, then resumes and kicks a render', () => {
    map.dispatchEvent(new BaseEvent('moveend'));
    drain();
    expect(original).not.toHaveBeenCalled();

    renderSpy.mockClear();
    vi.advanceTimersByTime(150); // SETTLE_MS
    // The render kick is what makes an otherwise-static map paint the held tiles.
    expect(renderSpy).toHaveBeenCalledTimes(1);

    drain();
    expect(original).toHaveBeenCalledWith(16, 16);
  });

  it('cancels the pending settle + render kick on movestart', () => {
    map.dispatchEvent(new BaseEvent('moveend'));
    map.dispatchEvent(new BaseEvent('movestart'));

    // Settle released by movestart; idle view → drain passes through.
    drain();
    expect(original).toHaveBeenCalledTimes(1);

    renderSpy.mockClear();
    vi.advanceTimersByTime(500);
    expect(renderSpy).not.toHaveBeenCalled(); // cancelled timer never rendered
  });

  it('stops gating once disposed (drain no longer suppressed while animating)', () => {
    dispose();
    vi.spyOn(map.getView(), 'getAnimating').mockReturnValue(true);
    queue.loadMoreTiles(16, 16); // restored original — bypasses the gate entirely
    expect(original).toHaveBeenCalledWith(16, 16);
  });

  it('returns a safe no-op disposer when the map exposes no tile queue', () => {
    const off = installTileLoadGate({} as unknown as OlMapImpl);
    expect(() => off()).not.toThrow();
  });
});
