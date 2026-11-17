/**
 * Selector for elements that should bail a row-level activation handler — the
 * user hit an inner interactive control rather than the row body. The host
 * element (article/card/tr) often carries `role="button"` itself; `closest()`
 * may walk up and match the host, in which case it is NOT a real interactive
 * descendant and the row's own handler must still run.
 */
export const INTERACTIVE_DESCENDANT_SELECTOR =
  'button, a, [role="button"], app-message-details-menu, app-message-selection-toggle';

/**
 * True when the event originated inside an interactive control nested in the
 * host element, so the host must leave the event alone.
 *
 * Matching is done on `Element`, not `HTMLElement`: clicking an icon lands on
 * its inline `<svg>` / `<path>` (an SVGElement), which would otherwise slip
 * past the guard. `closest()` is defined on `Element`, so both apply.
 */
export function isInteractiveDescendantEvent(event: Event): boolean {
  const target = event.target;
  if (target === event.currentTarget) return false;
  if (!(target instanceof Element)) return false;
  const match = target.closest(INTERACTIVE_DESCENDANT_SELECTOR);
  return match !== null && match !== event.currentTarget;
}
