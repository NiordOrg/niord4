import { Injectable, InjectionToken, computed, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_NOW_TICK_MS, nowTick } from './now-tick';

const DEADLINE = new InjectionToken<number>('deadline');

/** A consumer of the shape this exists for: a verdict derived from the clock. */
@Injectable()
class Verdict {
  private readonly deadline = inject(DEADLINE);
  private readonly now = nowTick();
  readonly expired = computed(() => this.now() >= this.deadline);
}

describe('nowTick', () => {
  afterEach(() => vi.useRealTimers());

  /**
   * The reading moves without anybody touching the data.
   *
   * This is the whole point: `Date.now()` inside a `computed()` is not a
   * dependency, so a verdict derived from it is pinned to whenever the
   * computation first ran — which on a long-lived admin screen is "when the page
   * was opened", for as long as it stays open.
   */
  it('re-derives a verdict as the clock passes the deadline', () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [Verdict, { provide: DEADLINE, useValue: Date.now() + 30_000 }],
    });
    const verdict = TestBed.inject(Verdict);

    expect(verdict.expired()).toBe(false);

    vi.advanceTimersByTime(DEFAULT_NOW_TICK_MS);

    expect(verdict.expired()).toBe(true);
  });

  /**
   * Nothing keeps ticking behind a destroyed injector.
   *
   * The interval lives as long as whatever created it — a facade scoped to a
   * component, in every current use — so tearing that down has to stop it.
   */
  it('stops ticking when its injector is destroyed', () => {
    vi.useFakeTimers();
    const clearSpy = vi.spyOn(globalThis, 'clearInterval');
    TestBed.configureTestingModule({
      providers: [Verdict, { provide: DEADLINE, useValue: Date.now() + 30_000 }],
    });
    TestBed.inject(Verdict);

    TestBed.resetTestingModule();

    expect(clearSpy).toHaveBeenCalled();
    clearSpy.mockRestore();
  });
});
