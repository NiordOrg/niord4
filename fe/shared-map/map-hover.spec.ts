import OlMapImpl from 'ol/Map';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { followMapHover, HOVER_THROTTLE_MS } from './map-hover';

function pointerMove(map: OlMapImpl, pixel: [number, number], dragging = false): void {
  map.dispatchEvent({ type: 'pointermove', pixel, coordinate: [0, 0], dragging } as never);
}

describe('followMapHover', () => {
  let map: OlMapImpl;
  let hovered: [number, number][];
  let left: number;
  let stop: () => void;

  beforeEach(() => {
    // The animation frame and the clock the throttle reads are both faked, so a frame is a
    // step of the fake clock rather than a real repaint.
    vi.useFakeTimers({
      toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'],
    });
    map = new OlMapImpl({});
    hovered = [];
    left = 0;
    stop = followMapHover(map, {
      hover: (pixel) => hovered.push(pixel),
      leave: () => left++,
    });
  });

  afterEach(() => {
    stop();
    map.dispose();
    vi.useRealTimers();
  });

  it('resolves the first move at once', () => {
    pointerMove(map, [10, 20]);

    expect(hovered).toEqual([[10, 20]]);
  });

  it('resolves at most once per interval while the pointer keeps moving', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [11, 21]);
    pointerMove(map, [12, 22]);

    expect(hovered).toHaveLength(1);
  });

  it('resolves the place the pointer came to rest, once the interval has passed', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [11, 21]);
    pointerMove(map, [30, 40]);

    vi.advanceTimersByTime(HOVER_THROTTLE_MS + 20);

    expect(hovered).toEqual([
      [10, 20],
      [30, 40],
    ]);
  });

  it('does not resolve a waiting move before the interval has passed', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [30, 40]);

    vi.advanceTimersByTime(HOVER_THROTTLE_MS / 2);

    expect(hovered).toHaveLength(1);
  });

  it('resolves a move that arrives after the interval straight away, in place of a waiting one', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [11, 21]);
    // Past the interval, but before the frame that would resolve the waiting move.
    vi.advanceTimersByTime(HOVER_THROTTLE_MS + 7);
    expect(hovered).toHaveLength(1);
    pointerMove(map, [50, 60]);
    vi.advanceTimersByTime(HOVER_THROTTLE_MS * 3);

    expect(hovered).toEqual([
      [10, 20],
      [50, 60],
    ]);
  });

  it('ends the hover when the pointer leaves the map, and drops the move still waiting', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [30, 40]);

    map.getViewport().dispatchEvent(new Event('pointerleave'));
    vi.advanceTimersByTime(HOVER_THROTTLE_MS * 3);

    expect(left).toBe(1);
    expect(hovered).toEqual([[10, 20]]);
  });

  it('ends the hover when the reader drags the map', () => {
    pointerMove(map, [10, 20]);
    map.dispatchEvent({ type: 'pointerdrag', pixel: [10, 20], coordinate: [0, 0] } as never);
    pointerMove(map, [30, 40], true);

    expect(left).toBe(2);
    expect(hovered).toEqual([[10, 20]]);
  });

  it('stops listening when it is stopped', () => {
    pointerMove(map, [10, 20]);
    pointerMove(map, [30, 40]);
    stop();

    vi.advanceTimersByTime(HOVER_THROTTLE_MS * 3);
    pointerMove(map, [50, 60]);
    map.getViewport().dispatchEvent(new Event('pointerleave'));

    expect(hovered).toEqual([[10, 20]]);
    expect(left).toBe(0);
  });
});
