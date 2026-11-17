import {
  createEnvironmentInjector,
  EnvironmentInjector,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { mediaQuerySignal } from './media-query';

/** A controllable `MediaQueryList` stand-in that lets a test flip `matches`. */
function installMatchMedia(initial: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const mql = {
    matches: initial,
    media: '',
    addEventListener: (_: string, l: (event: MediaQueryListEvent) => void) => listeners.add(l),
    removeEventListener: (_: string, l: (event: MediaQueryListEvent) => void) =>
      listeners.delete(l),
    emit(next: boolean) {
      mql.matches = next;
      listeners.forEach((l) => l({ matches: next } as MediaQueryListEvent));
    },
    get listenerCount() {
      return listeners.size;
    },
  };
  vi.stubGlobal('matchMedia', () => mql);
  return mql;
}

describe('mediaQuerySignal', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reflects the initial match state', () => {
    installMatchMedia(true);
    runInInjectionContext(TestBed.inject(EnvironmentInjector), () => {
      const narrow = mediaQuerySignal('(max-width: 1080px)');
      expect(narrow()).toBe(true);
    });
  });

  it('updates when the media query flips', () => {
    const mql = installMatchMedia(false);
    runInInjectionContext(TestBed.inject(EnvironmentInjector), () => {
      const narrow = mediaQuerySignal('(max-width: 1080px)');
      expect(narrow()).toBe(false);
      mql.emit(true);
      expect(narrow()).toBe(true);
      mql.emit(false);
      expect(narrow()).toBe(false);
    });
  });

  it('detaches the listener when the injection context is destroyed', () => {
    const mql = installMatchMedia(false);
    const child = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
    runInInjectionContext(child, () => mediaQuerySignal('(max-width: 1080px)'));
    expect(mql.listenerCount).toBe(1);
    child.destroy();
    expect(mql.listenerCount).toBe(0);
  });

  it('returns a constant false when matchMedia is unavailable', () => {
    vi.stubGlobal('matchMedia', undefined);
    runInInjectionContext(TestBed.inject(EnvironmentInjector), () => {
      const narrow = mediaQuerySignal('(max-width: 1080px)');
      expect(narrow()).toBe(false);
    });
  });
});
