/**
 * The classes message HTML may carry: the ones the shared message-HTML stylesheet styles under
 * `.message-details-html` — the rich-text editor's table, row and cell classes, the position
 * lists and tables the backend's geometry templates write, and the no-wrap span. Every other
 * class is dropped. Both applications' stylesheets carry global utilities, `fixed`, `inset-0` and
 * `z-50` among them, and a message that reached them could lay itself over the whole page.
 *
 * The sanitiser narrows the markup it renders to this vocabulary, and the administration app's
 * rich-text editor narrows what it parses to it, so the editing surface is held to the same
 * classes as the rendered message. The module has no framework import for that reason: the
 * editor's engine is framework-agnostic and reads it too.
 */
export const MESSAGE_HTML_CLASSES: ReadonlySet<string> = new Set([
  'condensed',
  'no-border',
  'no-wrap',
  'pos-col',
  'pos-index',
  'position-table',
  'positions',
  'underline',
]);

/** The separators of a `class` attribute: ASCII whitespace, as the DOM splits it. */
const CLASS_SEPARATOR = /[\t\n\f\r ]+/;

/**
 * The tokens of a `class` attribute value that are in {@link MESSAGE_HTML_CLASSES}, in the order
 * they were written and each once, or `null` when none is left — so a caller that writes the
 * result back as an attribute writes no empty `class`.
 */
export function allowedClasses(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const kept = new Set(value.split(CLASS_SEPARATOR).filter((t) => MESSAGE_HTML_CLASSES.has(t)));
  return kept.size > 0 ? [...kept].join(' ') : null;
}
