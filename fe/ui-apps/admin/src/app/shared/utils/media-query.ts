import { DestroyRef, inject, PLATFORM_ID, Signal, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/**
 * A read-only signal that tracks whether a CSS media query currently matches,
 * updating whenever the match state flips. Zoneless-friendly (a plain signal,
 * no `NgZone`) and SSR-safe: on the server, or in an environment without
 * `matchMedia`, it returns a constant `false` (desktop-first default) so callers
 * never branch on platform.
 *
 * Must be called in an injection context — it registers a `DestroyRef` cleanup
 * that detaches the `matchMedia` listener when the owning component/service is
 * destroyed. A generic primitive with no niord knowledge: the editor's ≤1080 px
 * preview gate composes it as `mediaQuerySignal('(max-width: 1080px)')`, but it
 * serves any responsive breakpoint.
 */
export function mediaQuerySignal(query: string): Signal<boolean> {
  const isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  if (!isBrowser || typeof matchMedia !== 'function') {
    return signal(false).asReadonly();
  }

  const list = matchMedia(query);
  const state = signal(list.matches);
  const listener = (event: MediaQueryListEvent): void => state.set(event.matches);
  list.addEventListener('change', listener);
  inject(DestroyRef).onDestroy(() => list.removeEventListener('change', listener));

  return state.asReadonly();
}
