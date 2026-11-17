import { linkedSignal, type Signal } from '@angular/core';

/**
 * Latch `live` while `settled` holds, else keep the last latched value — so a derived list stays
 * visible during a refetch instead of flashing empty. Pull-based (a `linkedSignal`, no effect/tick,
 * glitch-free): the canonical "keep last value while loading" latch.
 *
 * `settled` is the predicate for "the live value is real and adoptable". Two shapes recur:
 * - `!loading` when the source itself zeroes to empty during a load (e.g. a search stream that
 *   emits `[]` while pending) — the latch then also adopts the empty result on error.
 * - `!loading && !error && hasValue` when only a *successful* fetch should replace the previous
 *   rows, so a load OR an error keeps the last good value (e.g. a resource-backed list).
 *
 * `live` must be readable in every state (never throws) — guard resource reads with `hasValue()`.
 */
export function latchWhileSettled<T>(settled: Signal<boolean>, live: Signal<T>): Signal<T> {
  return linkedSignal<{ ok: boolean; value: T }, T>({
    source: () => ({ ok: settled(), value: live() }),
    computation: (source, previous) =>
      source.ok ? source.value : (previous?.value ?? source.value),
  });
}
