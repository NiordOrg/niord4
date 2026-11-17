import {
  afterNextRender,
  DestroyRef,
  Directive,
  ElementRef,
  inject,
  input,
  signal,
} from '@angular/core';

/**
 * A box that scrolls sideways is a named tab stop — for as long as it scrolls.
 *
 * A keyboard reader scrolls a box by focusing it, and a box that holds nothing focusable, such
 * as a position table, cannot be focused unless it is made a tab stop. Only a box whose content
 * is wider than it takes one: a stop on every box that fits would add one to every part of
 * every message in the list, for the few that are wider than a phone. A box that is wider by a
 * pixel counts as fitting — a bordered table overhangs its box by a fraction of one, which the
 * browser rounds up to a whole pixel, and there is nothing in it to scroll to. The name is what
 * a screen reader announces on arrival, so it is the label of what the box holds.
 */
@Directive({
  selector: '[appScrollRegion]',
  host: {
    '[attr.tabindex]': 'scrolls() ? 0 : null',
    '[attr.role]': 'scrolls() ? "region" : null',
    '[attr.aria-label]': 'scrolls() ? appScrollRegion() : null',
  },
})
export class ScrollRegion {
  /** The region's accessible name. */
  readonly appScrollRegion = input.required<string>();

  protected readonly scrolls = signal(false);

  constructor() {
    const element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const measure = () => this.scrolls.set(element.scrollWidth - element.clientWidth > 1);

      // The box is measured whenever its size changes: a narrower screen, or a label that wraps
      // differently after a language switch.
      const resizes = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
      resizes?.observe(element);

      // And whenever what it holds changes, which a resize observer does not see: a language
      // switch keeps the card and puts the other language's table into the same box, and a
      // wider table leaves the box exactly as wide and as tall as before. So is a class or a
      // style changed inside it, and an image, which takes its width only once it has loaded —
      // but not the tab stop, role and name set on the box itself. However many changes land in
      // one frame, the box is measured once, before that frame is drawn.
      let frame = 0;
      const remeasure = () => {
        frame ||= requestAnimationFrame(() => {
          frame = 0;
          measure();
        });
      };
      const mutations = new MutationObserver(remeasure);
      mutations.observe(element, {
        subtree: true,
        childList: true,
        characterData: true,
        attributeFilter: ['class', 'style', 'hidden'],
      });
      element.addEventListener('load', remeasure, true);

      destroyRef.onDestroy(() => {
        resizes?.disconnect();
        mutations.disconnect();
        element.removeEventListener('load', remeasure, true);
        cancelAnimationFrame(frame);
      });
    });
  }
}
