import type { UserSearchResult } from './user-search-result.model';

/**
 * `UserSearchResult` augmented with a precomputed `displayName` so the user
 * AutoComplete can render and echo a single field uniformly.
 */
export interface DisplayUser extends UserSearchResult {
  readonly displayName: string;
}

/** Adapter: maps the transport DTO to the UI model, joining first/last name (→ username fallback). */
export function toDisplayUser(user: UserSearchResult): DisplayUser {
  const name = [user.firstName, user.lastName]
    .filter((p): p is string => !!p && p.length > 0)
    .join(' ')
    .trim();
  return { ...user, displayName: name || user.username };
}

/** Builds a UI model from a bare username (free-text entry / CVA writeback). */
export function usernameStubDisplayUser(username: string): DisplayUser {
  return { username, displayName: username };
}
