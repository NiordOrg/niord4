import { DestroyRef, Signal, inject, signal } from '@angular/core';

/**
 * How often a wall-clock reading is refreshed by default.
 *
 * A minute, because everything this is for is answered in dates and windows
 * measured in days: a second-by-second clock would wake the application 60 times
 * as often to change nothing anybody can see.
 */
export const DEFAULT_NOW_TICK_MS = 60_000;

/**
 * The current instant, as a signal that keeps moving.
 *
 * `Date.now()` read inside a `computed()` is not a dependency — nothing tells
 * the graph the clock advanced, so the derived value is pinned to whenever it
 * first ran. A screen that answers "what is live right now" from such a
 * computation answers it once, at first render, and then keeps saying that for
 * as long as the page stays open. This makes the clock a real dependency.
 *
 * Must be called from an injection context: the interval is cleared with the
 * injector that created it, so nothing keeps ticking behind a destroyed view.
 */
export function nowTick(periodMs: number = DEFAULT_NOW_TICK_MS): Signal<number> {
  const now = signal(Date.now());
  const handle = setInterval(() => now.set(Date.now()), periodMs);
  inject(DestroyRef).onDestroy(() => clearInterval(handle));
  return now.asReadonly();
}
