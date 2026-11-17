import { Directive, ElementRef, HostListener, inject } from '@angular/core';

/**
 * On double-click, selects the host element's ENTIRE text content.
 *
 * Native double-click only selects a single whitespace/punctuation-delimited
 * word, so a hyphenated value like a message short-id ("Local Warning-003-26")
 * only partially selects. This expands the selection to the whole element so the
 * value can be copied in one gesture.
 */
@Directive({
  selector: '[appSelectAllOnDblclick]',
  standalone: true,
})
export class SelectAllOnDblclickDirective {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  @HostListener('dblclick')
  protected selectAll(): void {
    const selection = window.getSelection();
    if (!selection) return;
    const range = document.createRange();
    range.selectNodeContents(this.host.nativeElement);
    selection.removeAllRanges();
    selection.addRange(range);
  }
}
