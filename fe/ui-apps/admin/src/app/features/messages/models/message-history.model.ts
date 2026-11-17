/**
 * A single revision snapshot in a message's edit history.
 *
 * `snapshot` is the raw JSON of the message at that revision — treated as opaque by the
 * history diff (it can contain fields the typed `Message` model omits), so it stays a
 * string and is parsed only where the bespoke JSON-diff renderer needs it. `selected` is
 * UI-only state for the ≤2-revision comparison picker and never travels to the backend.
 */
export interface MessageHistory {
  readonly version: number;
  readonly created: string;
  readonly user?: string | null;
  readonly status: string;
  readonly snapshot: string;
  readonly selected?: boolean;
}
