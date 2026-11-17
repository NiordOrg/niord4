import { computed, inject, Injectable, signal, type Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { map } from 'rxjs';

/**
 * Query-param key the dialog service uses for deep linking. Exported so the
 * filter URL sync (and any other URL-watching code) can recognise and skip it
 * — `messageId` is NOT a search filter; feeding it into the search facade
 * would refetch the list with a single-message filter the moment the dialog
 * opens, wiping the user's scroll position.
 */
export const MESSAGE_ID_QUERY_PARAM = 'messageId';

/** Full open navigation state — captured/restored across a replace-&-restore hand-off. */
export interface DetailsNavSnapshot {
  readonly rootId: string;
  readonly context: readonly string[];
  readonly drill: readonly string[];
}

/**
 * Single source of truth for "is the message details dialog open, and at which
 * root message id?". The URL's `?messageId=` query param is the canonical state;
 * this service mirrors it.
 *
 * The `navigationContext` (id-list for prev/next) and the drill stack are
 * in-memory only — never serialized to URL. Refreshing the page restores the
 * root message but loses any drilldown.
 */
@Injectable({ providedIn: 'root' })
export class MessageDetailsDialogService {
  private readonly router = inject(Router);

  // The service lives at root scope (singleton across the app), so injecting
  // ActivatedRoute here would resolve to the root `/` route, not the currently
  // active leaf. Reading query params from `Router.routerState.root` is the
  // route-tree-agnostic way that always reflects the live URL.
  private readonly rootRoute = this.router.routerState.root;

  // ── URL-mirrored state ────────────────────────────────────────────────
  readonly rootMessageId: Signal<string | null> = toSignal(
    this.rootRoute.queryParamMap.pipe(map((p) => p.get(MESSAGE_ID_QUERY_PARAM))),
    { initialValue: null },
  );

  readonly isOpen: Signal<boolean> = computed(() => this.rootMessageId() !== null);

  // ── In-memory state (cleared on close) ────────────────────────────────
  private readonly _navigationContext = signal<readonly string[]>([]);
  readonly navigationContext = this._navigationContext.asReadonly();

  private readonly drillStack = signal<readonly string[]>([]);

  readonly navigationStack: Signal<readonly string[]> = computed(() => {
    const root = this.rootMessageId();
    if (root === null) return [] as readonly string[];
    return [root, ...this.drillStack()];
  });

  readonly currentMessageId: Signal<string | null> = computed(
    () => this.navigationStack().at(-1) ?? null,
  );

  // ── Public API ────────────────────────────────────────────────────────

  /** Open the dialog at `rootId`, freezing the surrounding list as the prev/next context. */
  open(rootId: string, navigationContext: readonly string[]): void {
    this._navigationContext.set(navigationContext);
    this.drillStack.set([]);
    this.navigateMessageId(rootId, { replaceUrl: false });
  }

  /** Close the dialog. Clears URL param and all in-memory dialog state. */
  close(): void {
    this._navigationContext.set([]);
    this.drillStack.set([]);
    this.navigateMessageId(null, { replaceUrl: false });
  }

  /**
   * Snapshot the full open state — root id, prev/next context, AND the
   * in-memory drill stack — so a replace-&-restore hand-off (the tag manager
   * drawer borrowing the screen) can reopen exactly what was showing, drilled
   * reference included. Null when the dialog is closed.
   */
  snapshot(): DetailsNavSnapshot | null {
    const rootId = this.rootMessageId();
    if (rootId === null) return null;
    return { rootId, context: this._navigationContext(), drill: this.drillStack() };
  }

  /**
   * Reopen from a {@link snapshot}, restoring the drill stack that plain
   * `open()` would discard (so back/prev-next state and the visible drilled
   * message are preserved).
   */
  restore(snap: DetailsNavSnapshot): void {
    this._navigationContext.set(snap.context);
    this.drillStack.set(snap.drill);
    this.navigateMessageId(snap.rootId, { replaceUrl: false });
  }

  /** Move root to the previous id in `navigationContext` (no-op at index 0 / out-of-list). */
  selectPrev(): void {
    const ctx = this._navigationContext();
    const i = this.rootIndex();
    if (i <= 0 || i >= ctx.length) return;
    this.drillStack.set([]);
    this.navigateMessageId(ctx[i - 1], { replaceUrl: true });
  }

  /** Move root to the next id in `navigationContext` (no-op at end / out-of-list). */
  selectNext(): void {
    const ctx = this._navigationContext();
    const i = this.rootIndex();
    if (i < 0 || i >= ctx.length - 1) return;
    this.drillStack.set([]);
    this.navigateMessageId(ctx[i + 1], { replaceUrl: true });
  }

  /** Push a referenced message onto the in-memory drill stack (URL unchanged). */
  selectReference(messageId: string): void {
    this.drillStack.update((stack) => [...stack, messageId]);
  }

  /** Pop the drill stack (URL unchanged). No-op when stack is at root. */
  back(): void {
    this.drillStack.update((stack) => (stack.length === 0 ? stack : stack.slice(0, -1)));
  }

  // ── Internals ─────────────────────────────────────────────────────────

  private rootIndex(): number {
    const root = this.rootMessageId();
    if (root === null) return -1;
    return this._navigationContext().indexOf(root);
  }

  private navigateMessageId(messageId: string | null, opts: { replaceUrl: boolean }): void {
    // No `relativeTo` and empty `commands` means navigate against the current URL
    // (path preserved). With `queryParamsHandling: 'merge'`, only the `messageId`
    // param is added/removed; all filter params survive. Setting `relativeTo` to
    // a service-injected ActivatedRoute would resolve to `/` (root) here and
    // wipe the active route on every open/close.
    void this.router.navigate([], {
      queryParams: { [MESSAGE_ID_QUERY_PARAM]: messageId },
      queryParamsHandling: 'merge',
      replaceUrl: opts.replaceUrl,
    });
  }
}
