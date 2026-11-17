import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { latchWhileSettled } from './latch-while-settled';

describe('latchWhileSettled', () => {
  it('adopts the initial live value while settled', () => {
    const settled = signal(true);
    const live = signal('a');
    const latched = latchWhileSettled(settled, live);

    expect(latched()).toBe('a');
  });

  it('flows live updates through while settled', () => {
    const settled = signal(true);
    const live = signal('a');
    const latched = latchWhileSettled(settled, live);

    expect(latched()).toBe('a');
    live.set('b');
    expect(latched()).toBe('b');
  });

  it('holds the last value while unsettled even as the source changes', () => {
    const settled = signal(true);
    const live = signal('a');
    const latched = latchWhileSettled(settled, live);

    expect(latched()).toBe('a');

    settled.set(false);
    expect(latched()).toBe('a');

    // Source keeps changing during the unsettled window; the latch keeps the last good value.
    live.set('b');
    expect(latched()).toBe('a');
    live.set('c');
    expect(latched()).toBe('a');
  });

  it('adopts the current live value once it re-settles', () => {
    const settled = signal(true);
    const live = signal('a');
    const latched = latchWhileSettled(settled, live);

    expect(latched()).toBe('a');

    settled.set(false);
    live.set('c');
    expect(latched()).toBe('a');

    settled.set(true);
    expect(latched()).toBe('c');
  });
});
