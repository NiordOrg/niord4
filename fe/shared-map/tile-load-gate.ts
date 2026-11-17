import type OlMap from 'ol/Map';
import type { EventsKey } from 'ol/events';
import { unByKey } from 'ol/Observable';

/**
 * Grace period after a gesture ends (moveend) before tile loading resumes.
 * It bridges the brief sub-frame gaps between the discrete animations of a
 * multi-step wheel zoom, and the tail of a kinetic pan, so tiles for the
 * intermediate zoom levels / mid-pan positions a gesture passes through are
 * never fetched — only the tiles of the view it finally settles on.
 */
const SETTLE_MS = 150;

/**
 * The slice of OpenLayers' internal `TileQueue` this gate touches. OL exposes
 * no public hook for "hold tile loading while the view is moving": the map
 * option that did this (`loadTilesWhileAnimating: false`) was removed in OL 6
 * when the renderer began loading tiles opportunistically every frame. So we
 * wrap the queue's drain method directly, accepting the internal-field access.
 */
interface GatableTileQueue {
  loadMoreTiles(maxTotalLoading: number, maxNewLoads: number): void;
  reprioritize(): void;
}

/**
 * Holds tile fetches back while the map view is animating, interacting, or
 * within {@link SETTLE_MS} of the gesture ending, so a zoom/pan only ever
 * loads the tiles of its final view. On a fast multi-step gesture this cuts
 * tile requests by roughly an order of magnitude.
 *
 * Mechanism: each frame OL's renderer enqueues the tiles it wants, then the
 * map drains that queue via `TileQueue.loadMoreTiles` (which flips each dequeued
 * tile from IDLE to LOADING and fetches it). While a gesture is in flight we
 * skip the drain, so tiles stay IDLE in the queue rather than being committed
 * to LOADING. OL keeps calling `TileQueue.reprioritize()` every frame, which
 * drops any queued tile no longer in the frame's wanted set — so the obsolete
 * intermediate-zoom / off-screen tiles are discarded for free instead of
 * fetched. When the gesture settles the drain resumes and only the wanted
 * (final-view) tiles are left to load.
 *
 * Leaving tiles IDLE (rather than starting the fetch and trying to cancel it)
 * is what makes obsolete work droppable: OL can only reprioritise tiles that
 * are still in the queue, not ones already handed off to loading.
 *
 * The settle window ends with an explicit `map.render()`. While a gesture holds
 * tiles IDLE, OL's render loop is kept alive by the animation; but once the
 * gesture ends, a map that isn't otherwise re-rendering has nothing to trigger
 * the next frame (the render loop is driven by tile *state changes*, and held
 * IDLE tiles never change state) — so the held tiles would sit IDLE and never
 * paint. The messages map hides this by re-rendering on every moveend (its
 * URL/search/feature churn); a dialog or editor map is static, so we kick one
 * render to drain the queue.
 *
 * Install once per map; returns a disposer that unhooks the listeners, cancels
 * a pending settle, and restores the original drain method.
 */
export function installTileLoadGate(map: OlMap): () => void {
  // `tileQueue_` is an internal OL field with no public accessor. Guard the
  // access so a future OL rename degrades to "no gating" rather than throwing.
  const queue = (map as unknown as { tileQueue_?: GatableTileQueue }).tileQueue_;
  if (!queue || typeof queue.loadMoreTiles !== 'function') {
    return () => undefined;
  }

  let settling = false;
  let settleTimer: ReturnType<typeof setTimeout> | undefined;
  const clearSettle = (): void => {
    if (settleTimer !== undefined) {
      clearTimeout(settleTimer);
      settleTimer = undefined;
    }
  };

  const keys: EventsKey[] = [
    // A new gesture segment started — the active animation/interaction now holds
    // loading, so drop the prior settle (and its pending render kick).
    map.on('movestart', () => {
      settling = false;
      clearSettle();
    }),
    map.on('moveend', () => {
      settling = true;
      clearSettle();
      settleTimer = setTimeout(() => {
        settling = false;
        settleTimer = undefined;
        map.render(); // drain the now-ungated queue even on an otherwise-static map
      }, SETTLE_MS);
    }),
  ];

  const original = queue.loadMoreTiles.bind(queue);
  queue.loadMoreTiles = (maxTotalLoading, maxNewLoads) => {
    const view = map.getView();
    if (view.getAnimating() || view.getInteracting() || settling) {
      return; // gesture in flight — leave tiles IDLE; reprioritize() drops the obsolete ones
    }
    queue.reprioritize(); // discard anything stale, then load only the settled view
    original(maxTotalLoading, maxNewLoads);
  };

  return () => {
    clearSettle();
    unByKey(keys);
    queue.loadMoreTiles = original;
  };
}
