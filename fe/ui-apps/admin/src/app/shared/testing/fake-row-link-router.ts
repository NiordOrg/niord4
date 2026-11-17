import { EMPTY } from 'rxjs';

/**
 * The parts of `Router` a row link reads, for specs that stub the router in
 * order to watch `navigate()`.
 *
 * A row that leads somewhere serializes its target into an href, so a stub with
 * `navigate()` alone is no longer enough to render one. Commands and query
 * params are serialized into a plausible URL, so an href a spec asserts on still
 * says what the row points at.
 *
 * ```ts
 * { provide: Router, useValue: { navigate, ...fakeRowLinkRouter() } }
 * ```
 */
export function fakeRowLinkRouter(): Record<string, unknown> {
  return {
    // No navigation ever completes in a spec, so there is nothing to re-resolve
    // a merged href against.
    events: EMPTY,
    createUrlTree: (
      commands: readonly unknown[],
      extras?: { queryParams?: Record<string, unknown> },
    ) => ({ commands, queryParams: extras?.queryParams }),
    serializeUrl: (tree: {
      commands: readonly unknown[];
      queryParams?: Record<string, unknown>;
    }): string => {
      const path = `/${tree.commands.filter((command) => command !== null && command !== undefined).join('/')}`;
      const query = Object.entries(tree.queryParams ?? {})
        .filter(([, value]) => value !== null && value !== undefined)
        .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
        .join('&');
      return query === '' ? path : `${path}?${query}`;
    },
  };
}
