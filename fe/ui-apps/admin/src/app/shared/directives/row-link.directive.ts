import { LocationStrategy } from '@angular/common';
import {
  computed,
  DestroyRef,
  Directive,
  ElementRef,
  HostListener,
  inject,
  input,
  output,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  ActivatedRoute,
  NavigationEnd,
  Router,
  type Params,
  type QueryParamsHandling,
} from '@angular/router';
import { filter } from 'rxjs';

import { isInteractiveDescendantEvent } from './interactive-descendant';

/**
 * True for a click the user means to open somewhere else: ctrl/cmd (new tab),
 * shift (new window). Alt is deliberately absent — browsers read alt-click as
 * "download", and on a row it has always meant an ordinary activation.
 */
function isNewTabClick(event: MouseEvent): boolean {
  return event.ctrlKey || event.metaKey || event.shiftKey;
}

/**
 * A clickable row whose target is a URL.
 *
 * Rows are not links: a `<tr>` cannot carry an `href`, and the router navigates
 * a non-anchor host in-tab whatever modifier is held. So the row keeps its own
 * activation — the plain click and Enter still run the host's handler with all
 * of its side effects — and this directive adds the two things a real link
 * gives: it serializes the target to an `href` that {@link RowLinkAnchorDirective}
 * puts on one anchor inside the row (hover URL, context menu, native
 * middle-click), and it opens that same URL in a new tab when the row body
 * itself is ctrl/cmd/shift- or middle-clicked.
 *
 * ```html
 * <tr class="row-interactive" tabindex="-1"
 *     [appRowLink]="[]" [rowLinkQueryParams]="{ messageId: id }"
 *     rowLinkQueryParamsHandling="merge"
 *     (appRowLinkActivate)="open(id)">
 *   <td><a appRowLinkAnchor class="row-link">{{ title }}</a></td>
 * </tr>
 * ```
 */
@Directive({
  selector: '[appRowLink]',
})
export class RowLinkDirective {
  private readonly router = inject(Router);
  private readonly locationStrategy = inject(LocationStrategy);
  private readonly hostRoute = inject(ActivatedRoute, { optional: true });

  /**
   * Router commands, exactly as `router.navigate()` takes them — or `null` for a
   * row that has no address (a synthesized row standing for a period rather than
   * an entity). Such a row keeps its plain activation and gets no href, so
   * nothing offers to open a page that does not exist.
   */
  readonly commands = input.required<readonly unknown[] | string | null | undefined>({
    alias: 'appRowLink',
  });

  readonly rowLinkQueryParams = input<Params | undefined>(undefined);
  readonly rowLinkQueryParamsHandling = input<QueryParamsHandling | undefined>(undefined);

  /** Defaults to the route the row is rendered in, like `routerLink` does. */
  readonly rowLinkRelativeTo = input<ActivatedRoute | null | undefined>(undefined);

  /**
   * The row was activated in place: plain click, Enter or Space on the row body,
   * or a plain click on the row's own anchor. The host navigates and runs
   * whatever else the click has always done.
   */
  readonly appRowLinkActivate = output<void>();

  // `queryParamsHandling: 'merge'` resolves against the CURRENT url, so the href
  // goes stale the moment anything else changes the query string (a filter, the
  // dialog opening). Re-read it after every completed navigation.
  private readonly navigationEnd = toSignal(
    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)),
    { initialValue: null },
  );

  private readonly urlTree = computed(() => {
    this.navigationEnd();
    const commands = this.commands();
    if (commands === null || commands === undefined) return null;
    return this.router.createUrlTree(Array.isArray(commands) ? [...commands] : [commands], {
      relativeTo: this.rowLinkRelativeTo() ?? this.hostRoute,
      queryParams: this.rowLinkQueryParams(),
      queryParamsHandling: this.rowLinkQueryParamsHandling(),
    });
  });

  /** The row's target as an address the browser can open; null when it has none. */
  readonly href = computed(() => {
    const tree = this.urlTree();
    return tree === null
      ? null
      : this.locationStrategy.prepareExternalUrl(this.router.serializeUrl(tree));
  });

  // The anchors that speak for this row. Registered by the anchor directive
  // rather than matched by selector, so the row can tell its own link (whose
  // native behaviour must survive) from any other control inside it.
  private readonly anchors = new Set<Element>();

  registerAnchor(anchor: Element): void {
    this.anchors.add(anchor);
  }

  unregisterAnchor(anchor: Element): void {
    this.anchors.delete(anchor);
  }

  @HostListener('click', ['$event'])
  protected onClick(event: MouseEvent): void {
    if (this.isOwnAnchorEvent(event)) {
      // A modified click on the anchor is the browser's: it opens the href in a
      // new tab and nothing here may interfere. A plain one is already
      // prevented by the anchor and continues as the row's activation.
      if (isNewTabClick(event)) return;
      this.appRowLinkActivate.emit();
      return;
    }
    if (isInteractiveDescendantEvent(event)) return;
    if (isNewTabClick(event) && this.href() !== null) {
      event.preventDefault();
      this.openInNewTab();
      return;
    }
    this.appRowLinkActivate.emit();
  }

  @HostListener('auxclick', ['$event'])
  protected onAuxClick(event: MouseEvent): void {
    // 1 = middle button. `auxclick` is the only click event it produces.
    if (event.button !== 1) return;
    if (this.href() === null) return;
    if (this.isOwnAnchorEvent(event)) return;
    if (isInteractiveDescendantEvent(event)) return;
    event.preventDefault();
    this.openInNewTab();
  }

  @HostListener('mousedown', ['$event'])
  protected onMouseDown(event: MouseEvent): void {
    if (event.button !== 1) return;
    if (this.href() === null) return;
    if (this.isOwnAnchorEvent(event)) return;
    if (isInteractiveDescendantEvent(event)) return;
    // A middle press on something that is not a link arms the browser's
    // autoscroll. The row opens a tab on the release instead, so the press must
    // not also leave a scroll cursor behind.
    event.preventDefault();
  }

  @HostListener('keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    // Enter on the anchor already produces a native click, which arrives here as
    // the plain-click path. Handling the key too would activate the row twice.
    if (this.isOwnAnchorEvent(event)) return;
    if (isInteractiveDescendantEvent(event)) return;
    event.preventDefault();
    if (event.repeat) return;
    this.appRowLinkActivate.emit();
  }

  private openInNewTab(): void {
    const href = this.href();
    if (href === null) return;
    window.open(href, '_blank', 'noopener');
  }

  private isOwnAnchorEvent(event: Event): boolean {
    const target = event.target;
    if (!(target instanceof Element)) return false;
    for (const anchor of this.anchors) {
      if (anchor === target || anchor.contains(target)) return true;
    }
    return false;
  }
}

/**
 * The one real link inside an {@link RowLinkDirective} row — normally the name
 * or title cell, and the row's single tab stop.
 *
 * It carries the row's `href`, so the status bar shows where the row goes and
 * the browser's own "open in new tab" works on it. A plain click is handed back
 * to the row: the anchor suppresses its own navigation and lets the event
 * bubble, so the row activates exactly once, through the handler that owns the
 * side effects.
 */
@Directive({
  selector: 'a[appRowLinkAnchor]',
  host: {
    '[attr.href]': 'href()',
  },
})
export class RowLinkAnchorDirective {
  // Optional because the anchor can live in a child component that is also
  // mounted on its own (its spec, a future non-row use). Without a row above it
  // there is no target: the element stays a plain `<a>` with no href, and every
  // click passes straight through to whatever does handle it.
  private readonly row = inject(RowLinkDirective, { optional: true });
  private readonly element = inject<ElementRef<HTMLAnchorElement>>(ElementRef);

  protected readonly href = computed(() => this.row?.href() ?? null);

  constructor() {
    const row = this.row;
    if (row === null) return;
    const anchor = this.element.nativeElement;
    row.registerAnchor(anchor);
    inject(DestroyRef).onDestroy(() => row.unregisterAnchor(anchor));
  }

  @HostListener('click', ['$event'])
  protected onClick(event: MouseEvent): void {
    if (this.row === null) return;
    // Left the browser's: ctrl/cmd/shift opens a new tab or window from the
    // href, which is the whole point of the anchor being real.
    if (isNewTabClick(event)) return;
    event.preventDefault();
  }
}
