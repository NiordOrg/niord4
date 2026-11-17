/**
 * The id rule a new publication category or series is held to (PUBCAT2).
 *
 * The id is the last segment of every address that reads, changes or deletes the
 * record. The server's request firewall refuses, before any handler runs, a
 * segment that holds a slash, a back slash, a percent sign, a semicolon, a
 * control character or a line or paragraph separator, and it reads `.` and `..`
 * as steps in the path. A record created under such an id could never be opened,
 * changed or deleted again, so the create refuses it, and the editors say so
 * before the save is sent.
 *
 * Exactly that and nothing more: no slug pattern. The ids deployments and
 * countries already hold have not been read, and a stricter rule here would
 * refuse an id the server takes. The backend's rule is `PathSegmentId`; the two
 * must name the same characters.
 */

/** The Signal Forms error kind the editors read to show {@link UNSERVABLE_ID_KEY}. */
export const UNSERVABLE_ID = 'unservableId';

/** The one sentence both editors show under the id. */
export const UNSERVABLE_ID_KEY = 'admin.publications.error.idUnservable';

/** `/`, `\`, `%` and `;`. */
const UNSERVABLE_PUNCTUATION = new Set(['/', '\\', '%', ';']);

/**
 * A character the firewall refuses: the punctuation above, a C0 or C1 control
 * character (as Java's `Character.isISOControl`), or U+2028 and U+2029.
 */
function isUnservable(character: string): boolean {
  if (UNSERVABLE_PUNCTUATION.has(character)) {
    return true;
  }
  const code = character.codePointAt(0) ?? 0;
  return code <= 0x1f || (code >= 0x7f && code <= 0x9f) || code === 0x2028 || code === 0x2029;
}

/**
 * Whether the id, as it would be sent (trimmed), can be served as one address
 * segment. A blank id is servable here: whether an id is required is the
 * form's own rule.
 */
export function isServableId(id: string | null | undefined): boolean {
  const trimmed = (id ?? '').trim();
  if (trimmed === '.' || trimmed === '..') {
    return false;
  }
  for (const character of trimmed) {
    if (isUnservable(character)) {
      return false;
    }
  }
  return true;
}
