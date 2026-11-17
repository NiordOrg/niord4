import { afterRenderEffect, inject, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ViewportScroller } from '@angular/common';
import { Router, Scroll } from '@angular/router';
import { filter, map } from 'rxjs';

/**
 * Puts the window back where the reader left it when they come Back to a view whose content is
 * read again on arrival.
 *
 * The router restores the position as soon as the navigation ends, but a list that is read
 * afresh is not there yet, the page is too short to hold the position, and the reader lands at
 * the top of a list they had scrolled far down. So the position a Back or Forward navigation
 * restores is applied once more, once `settled` says the content has arrived and it has been
 * drawn — and only once, so a later read on the same page does not pull the reader back to it.
 *
 * Call it in the constructor of the component that hosts the view.
 */
export function restoreScrollWhenSettled(settled: () => boolean): void {
  const scroller = inject(ViewportScroller);
  const restored = toSignal(
    inject(Router).events.pipe(
      filter((event): event is Scroll => event instanceof Scroll),
      map((event) => event.position),
    ),
    { initialValue: null },
  );
  // Re-armed by every navigation's own position, and disarmed once it has been applied.
  const pending = linkedSignal(() => restored());

  afterRenderEffect(() => {
    const position = pending();
    if (!position || !settled()) return;
    scroller.scrollToPosition(position);
    pending.set(null);
  });
}
