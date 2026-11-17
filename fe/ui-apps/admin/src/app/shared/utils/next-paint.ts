import { afterNextRender, signal, type Signal } from '@angular/core';

/**
 * Resolves in the first task after the browser has painted its next frame — at once on a page that
 * is not visible, which paints no frames. A frame callback runs just before its frame is painted; a
 * task queued from it runs after.
 */
export function nextPaint(): Promise<void> {
  if (document.visibilityState !== 'visible') return Promise.resolve();
  return new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve)));
}

/**
 * A signal that turns true in the first task after the view that creates it has been painted — for
 * a view that shows its frame at once and builds its heavy part right after, rather than showing
 * nothing until all of it stands. True from the start on a page that is not visible. Create it in
 * an injection context, as a component field.
 */
export function paintedOnce(): Signal<boolean> {
  const painted = signal(document.visibilityState !== 'visible');
  if (!painted()) {
    afterNextRender(() => void nextPaint().then(() => painted.set(true)));
  }
  return painted.asReadonly();
}
