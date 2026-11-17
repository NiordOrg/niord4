/**
 * Inline-aware HTML pretty-printer for the rich-text source view's "Format" action. Puts each BLOCK
 * element on its own indented line but keeps a block's INLINE content (text plus inline marks like
 * <strong>/<a>/<span>) glued together on a single line. That distinction is what makes the result
 * safe to re-parse through TipTap: `setContent` collapses inter-block whitespace, so the added
 * newlines/indentation vanish, while inline content is never split (which would let ProseMirror
 * inject a stray space between marks). Reached only via a dynamic import from the rich-text editor,
 * so `@tiptap/*`-free and never in the main bundle.
 */

const BLOCK_TAGS = new Set([
  'p',
  'div',
  'blockquote',
  'pre',
  'hr',
  'figure',
  'figcaption',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'ul',
  'ol',
  'li',
  'table',
  'thead',
  'tbody',
  'tfoot',
  'tr',
  'td',
  'th',
  'caption',
  'colgroup',
]);

const VOID_TAGS = new Set(['br', 'hr', 'img', 'input', 'col']);

function isBlock(node: Node): node is Element {
  return (
    node.nodeType === Node.ELEMENT_NODE && BLOCK_TAGS.has((node as Element).tagName.toLowerCase())
  );
}

/** The element's opening tag, rebuilt from its attributes (robust to `>` inside attribute values). */
function openTag(el: Element): string {
  let out = '<' + el.tagName.toLowerCase();
  for (const attr of Array.from(el.attributes)) {
    out += ` ${attr.name}="${attr.value.replace(/"/g, '&quot;')}"`;
  }
  return out + '>';
}

/** Text as markup: `textContent` is decoded, so `&`, `<` and `>` are escaped again. */
function escapeText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function inlineOf(node: Node): string {
  return node.nodeType === Node.TEXT_NODE
    ? escapeText(node.textContent ?? '')
    : (node as Element).outerHTML;
}

/** Emit a block element's own line(s); recurse only when it actually holds block children. */
function formatBlock(el: Element, depth: number, out: string[]): void {
  const indent = '  '.repeat(depth);
  const tag = el.tagName.toLowerCase();
  if (VOID_TAGS.has(tag)) {
    out.push(indent + openTag(el));
    return;
  }
  const children = Array.from(el.childNodes);
  if (!children.some(isBlock)) {
    // Leaf block: open + (untouched) inline content + close, on one line.
    out.push(indent + openTag(el) + el.innerHTML.trim() + `</${tag}>`);
    return;
  }
  out.push(indent + openTag(el));
  formatChildren(children, depth + 1, out);
  out.push(indent + `</${tag}>`);
}

/** Walk a container's children: block children get their own line(s), inline runs are coalesced. */
function formatChildren(children: readonly Node[], depth: number, out: string[]): void {
  const indent = '  '.repeat(depth);
  let inline = '';
  const flush = (): void => {
    const trimmed = inline.trim();
    if (trimmed.length > 0) {
      out.push(indent + trimmed);
    }
    inline = '';
  };
  for (const child of children) {
    if (isBlock(child)) {
      flush();
      formatBlock(child, depth, out);
    } else {
      inline += inlineOf(child);
    }
  }
  flush();
}

/** Pretty-print an HTML fragment for display in the source-view textarea. */
export function formatHtml(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const out: string[] = [];
  formatChildren(Array.from(doc.body.childNodes), 0, out);
  return out.join('\n');
}
