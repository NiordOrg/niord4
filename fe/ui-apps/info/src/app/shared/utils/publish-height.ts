import { afterNextRender, DestroyRef, DOCUMENT, ElementRef, inject, Signal } from '@angular/core';

/**
 * Publishes an element's rendered height, border included, as a custom property on the document
 * root, and keeps it current for as long as the calling component lives.
 *
 * A bar whose height depends on the language and on the width of the screen cannot be cleared
 * with a constant: whatever sits below it or is sized against it reads the property instead.
 * The property is removed with the component, so a route without the bar measures nothing.
 *
 * Call it from a constructor or a field initializer: it injects.
 */
export function publishHeight(element: Signal<ElementRef<HTMLElement>>, property: string): void {
  const root = inject(DOCUMENT).documentElement;
  const destroyRef = inject(DestroyRef);
  afterNextRender(() => {
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => {
      const height = entry.borderBoxSize?.[0]?.blockSize ?? entry.contentRect.height;
      root.style.setProperty(property, `${height}px`);
    });
    observer.observe(element().nativeElement);
    destroyRef.onDestroy(() => {
      observer.disconnect();
      root.style.removeProperty(property);
    });
  });
}
