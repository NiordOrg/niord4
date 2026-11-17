import type OlMap from 'ol/Map';
import type MapBrowserEvent from 'ol/MapBrowserEvent';
import type { EventsKey } from 'ol/events';
import { unByKey } from 'ol/Observable';

/**
 * The shortest interval between two hover resolutions. pointermove fires once per frame, and
 * each resolution reads back from OpenLayers' hit-detection canvas, which Chrome flags as a
 * frequent readback; thirty times a second still reads as instant and halves the readbacks.
 */
export const HOVER_THROTTLE_MS = 33;

export interface MapHoverHandlers {
  /** The pointer is at this pixel of the map: resolve what is under it. */
  readonly hover: (pixel: [number, number]) => void;
  /** The pointer left the map or started dragging it: whatever a hover showed is stale. */
  readonly leave: () => void;
}

/**
 * Follows the pointer over a map for a hover tooltip, and returns the function that stops.
 *
 * Moves are resolved at most once per `throttleMs`, and the throttle has a trailing edge: a move
 * that arrives inside the interval is kept, and resolved on the first animation frame after the
 * interval has passed — unless a later move has replaced it by then. So the place the pointer
 * comes to rest is always the last one resolved; without that, a pointer that stops within the
 * interval of its previous move keeps the tooltip of a spot it has already left, and invites a
 * click on nothing.
 *
 * Leaving the map's viewport and dragging the map both end the hover, and drop a move that is
 * still waiting.
 */
export function followMapHover(
  map: OlMap,
  handlers: MapHoverHandlers,
  throttleMs = HOVER_THROTTLE_MS,
): () => void {
  let resolvedAt = Number.NEGATIVE_INFINITY;
  let waiting: [number, number] | undefined;
  let frame: number | undefined;

  const cancelFrame = (): void => {
    if (frame !== undefined) cancelAnimationFrame(frame);
    frame = undefined;
  };

  const resolveWaiting = (now: number): void => {
    const pixel = waiting;
    waiting = undefined;
    if (!pixel) return;
    resolvedAt = now;
    handlers.hover(pixel);
  };

  const onFrame = (): void => {
    frame = undefined;
    const now = performance.now();
    if (now - resolvedAt < throttleMs) {
      frame = requestAnimationFrame(onFrame);
      return;
    }
    resolveWaiting(now);
  };

  const end = (): void => {
    waiting = undefined;
    cancelFrame();
    handlers.leave();
  };

  const onMove = (event: MapBrowserEvent): void => {
    if (event.dragging) {
      end();
      return;
    }
    waiting = [event.pixel[0], event.pixel[1]];
    const now = performance.now();
    if (now - resolvedAt >= throttleMs) {
      cancelFrame();
      resolveWaiting(now);
    } else {
      frame ??= requestAnimationFrame(onFrame);
    }
  };

  const keys: EventsKey[] = [map.on('pointermove', onMove), map.on('pointerdrag', end)];
  const viewport = map.getViewport();
  viewport.addEventListener('pointerleave', end);

  return () => {
    waiting = undefined;
    cancelFrame();
    unByKey(keys);
    viewport.removeEventListener('pointerleave', end);
  };
}
