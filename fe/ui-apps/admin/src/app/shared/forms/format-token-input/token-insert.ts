/** An insertable format token: the literal text spliced in, and its human-friendly menu label. */
export interface FormatToken {
  readonly token: string;
  /** Pre-translated display label (the consumer owns the translation). */
  readonly label: string;
}

/** Where a field's caret is, and what it has selected. */
export interface CaretRange {
  readonly start: number;
  readonly end: number;
}

/** The text after an insert, and where the caret belongs in it. */
export interface TokenInsertion {
  readonly value: string;
  readonly caret: number;
}

/**
 * Splice a token into a field's text at the caret, replacing any selection.
 *
 * `caret` is null for a field that has never held the caret for THIS text — a
 * freshly opened editor, or a value written from outside. A text input reports
 * `selectionStart: 0` before it was ever focused, so an untouched field cannot
 * be told apart from one whose caret sits at the start; appending is the
 * behaviour that is right in both readings of an untouched field, and inserting
 * at position 0 is right in neither.
 *
 * The range is clamped rather than trusted: the selection survives the field's
 * blur while a menu is open, and it can outlive the text it was taken against.
 */
export function insertToken(
  current: string,
  token: string,
  caret: CaretRange | null,
): TokenInsertion {
  const start = caret ? Math.min(Math.max(caret.start, 0), current.length) : current.length;
  const end = caret ? Math.min(Math.max(caret.end, start), current.length) : current.length;
  return {
    value: current.slice(0, start) + token + current.slice(end),
    caret: start + token.length,
  };
}
