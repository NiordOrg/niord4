import { inject, Injectable, SecurityContext } from '@angular/core';
import { DomSanitizer, type SafeHtml } from '@angular/platform-browser';

import { MESSAGE_HTML_CLASSES } from './message-html-classes';
import { allowedDeclarations } from './message-html-declarations';

/**
 * The class token that carries a recorded declaration across Angular's sanitiser.
 *
 * The alternative — remembering the elements by their position in the tree and matching them up
 * again afterwards — cannot work, because the sanitiser does not only drop attributes: it drops
 * every element it does not know and hoists that element's children into the parent. Every
 * position after such an element shifts, and the indent would land on the wrong paragraph. A data attribute cannot carry the mark either: the
 * sanitiser's attribute allow-list has no `data-*` in it. `class` is on that allow-list, it
 * survives verbatim, and it travels with the element — so an element the sanitiser removes takes
 * its mark with it and is simply never styled.
 */
const MARKER_PREFIX = 'niord-inline-style-';
const MARKER = /^niord-inline-style-\d+$/;

/**
 * What a link that opens another browsing context carries, whatever the message said. `noopener`
 * keeps the opened page from reaching back into this one through `window.opener` and navigating
 * it elsewhere; `noreferrer` sends the opened page no Referer.
 */
const NEW_CONTEXT_REL = 'noopener noreferrer';

/** Whether the sanitised markup has anything the final pass would change. */
const NEEDS_FINAL_PASS = /\s(?:class|target)\s*=/i;

/** Whether the markup carries an inline style. */
const HAS_STYLE = /\sstyle\s*=/i;

/**
 * Whether the markup may carry an inline style or, in development, a leftover of
 * {@link removeLeftovers}: a comment (`<!` or `<?`), an element whose name has a colon, the one
 * leftover custom element, or one of the leftover attributes. A string that merely looks like one
 * costs a parse; a leftover it misses costs a console warning, never a different result.
 *
 * Angular's sanitiser warns only in development, so only there are the leftovers taken out. A
 * production build folds `ngDevMode` to false, and this pattern, {@link removeLeftovers} and its
 * constants leave the bundle — the public site's first visit carries none of it.
 */
const NEEDS_PREPARING =
  typeof ngDevMode === 'undefined' || ngDevMode
    ? /\sstyle\s*=|<[!?]|<\/?[^\s/>]*:|<render-message-part|\s(?:data|mce|ng)-|\s(?:id|caption|publication|verid|recno)\s*=/i
    : HAS_STYLE;

/**
 * The attributes in the stored markup that the display never renders, none of which Angular's
 * sanitiser keeps: `data-*` (the positions' `data-latitude` and `data-longitude` among them), `id`,
 * the `mce-*` of a TinyMCE editor, the `ng-*` of an AngularJS template, `caption` on a table, the
 * `publication` reference by which a message cites a publication (the stored text keeps it; only
 * the rendered copy loses it), and the `VerID` and `recno` of links pasted from the case system.
 */
const LEFTOVER_ATTRIBUTE = /^(?:data-|mce-|ng-)|^(?:id|caption|publication|verid|recno)$/i;

/**
 * The one custom element in the stored markup: an AngularJS directive that was saved along with
 * the message part it rendered.
 */
const LEFTOVER_ELEMENT = 'render-message-part';

/**
 * The HTML a message carries, sanitised.
 *
 * This service IS the sanitiser for message content, and it is the one place in the workspace
 * that is allowed to say a string of message HTML is safe. It runs Angular's own sanitiser over
 * the markup — which decides every element, every attribute and every URL — and then puts back
 * the two style declarations of {@link allowedDeclarations}, rebuilt from a validated allow-list
 * rather than copied from the input. Because the result has been sanitised here, {@link sanitize}
 * marks it safe: binding a plain string with `[innerHTML]` would sanitise it a second time and take
 * those two declarations straight back out again.
 *
 * What the message contract needs and the sanitiser keeps: the classes of
 * {@link MESSAGE_HTML_CLASSES} — the position tables and the rest of the vocabulary the shared
 * message-HTML stylesheet styles by class — as well as links, images and the ordinary text marks.
 * What it drops, `<script>` and event attributes and `javascript:` URLs, is nothing a message
 * editor produces — and in development it warns in the console each time, so there what earlier
 * editors left in the stored markup and it would drop just the same (comments, `<o:p>`, `data-*`
 * and a few more, see {@link removeLeftovers}) is taken out before it, silently, and its warning is
 * kept for markup nobody expected. Angular's sanitiser keeps every `class` and `rel` as written, so the final pass
 * here narrows the classes to that vocabulary and gives every link that opens a new browsing
 * context {@link NEW_CONTEXT_REL}.
 */
@Injectable({ providedIn: 'root' })
export class MessageHtmlSanitizer {
  private readonly dom = inject(DomSanitizer);

  /**
   * The sanitised markup, marked safe for an `[innerHTML]` binding to render as it is.
   */
  sanitize(html: string | undefined | null): SafeHtml {
    return this.dom.bypassSecurityTrustHtml(this.sanitizeToString(html));
  }

  /**
   * The same markup as a plain string, for a caller that reads it rather than renders it.
   */
  sanitizeToString(html: string | undefined | null): string {
    if (!html) {
      return '';
    }

    // The overwhelming majority of message parts carry neither an inline style nor a leftover,
    // and skip the preparing pass and its parse.
    const recorded = new Map<string, string>();
    const prepared = NEEDS_PREPARING.test(html) ? prepare(html, recorded) : html;
    const sanitized = this.dom.sanitize(SecurityContext.HTML, prepared) ?? '';

    // The same for the final pass: the sanitiser writes every attribute it keeps as ` name="…"`,
    // so markup without a class or a target has nothing for it to do.
    return NEEDS_FINAL_PASS.test(sanitized) ? finalPass(sanitized, recorded) : sanitized;
  }
}

/**
 * The markup the sanitiser is handed: the inline styles recorded and marked, and in development
 * the leftovers removed, in one parse.
 *
 * Both take out only what the sanitiser would drop anyway, so its result is the same; doing it
 * here is what stops it from warning about each one — the difference between a clean console and
 * hundreds of lines of "sanitizing HTML stripped some content" on a message list, which would
 * drown the one warning that matters, about markup nobody expected.
 */
function prepare(html: string, recorded: Map<string, string>): string {
  const body = parseAsTheSanitizerDoes(html);
  markAllowedDeclarations(body, recorded);
  // Markup without a style has nothing to mark, and goes on as it came unless a leftover goes.
  const marked = HAS_STYLE.test(html) ? body.innerHTML : html;
  // A production build folds the first test to true (see NEEDS_PREPARING).
  if (!(typeof ngDevMode === 'undefined' || ngDevMode) || !removeLeftovers(body)) return marked;
  const prepared = body.innerHTML;

  // Taking an element out can let the parser nest what it would otherwise have closed — a
  // heading inside an `<o:p>` inside another heading comes back as two headings side by side — and
  // markup that does not parse back into the same tree would reach the sanitiser as a different
  // one. Such markup keeps its leftovers, and the sanitiser drops them itself, warning as it does.
  return parseAsTheSanitizerDoes(prepared).innerHTML === prepared ? prepared : marked;
}

/**
 * The markup parsed as Angular's sanitiser parses it: into a `<body>` that is already open, so a
 * leading line break stays content and a leading `<title>` is not moved into a `<head>`.
 */
function parseAsTheSanitizerDoes(html: string): HTMLElement {
  const { body } = new DOMParser().parseFromString('<body><remove></remove>' + html, 'text/html');
  body.firstChild?.remove();
  return body;
}

/**
 * Removes what earlier editors left in the stored markup and the sanitiser drops: comments, the
 * attributes of {@link LEFTOVER_ATTRIBUTE}, and the elements a word processor's paste leaves
 * behind (`<o:p>`, whose name has a colon) along with {@link LEFTOVER_ELEMENT}. The sanitiser
 * drops such an element and keeps what it holds, and so does this: the element goes, its children
 * stay where it was. Whether anything was removed is the answer.
 */
function removeLeftovers(body: HTMLElement): boolean {
  let removed = false;

  // The body comes first for the comments at the top level. A comment is taken out of the element
  // holding it before that element can be unwrapped and hand it on to an element already passed.
  for (const element of [body, ...elementsOf(body)]) {
    for (const node of [...element.childNodes]) {
      if (node.nodeType !== Node.COMMENT_NODE) continue;
      node.remove();
      removed = true;
    }

    const name = element.nodeName.toLowerCase();
    if (name.includes(':') || name === LEFTOVER_ELEMENT) {
      element.replaceWith(...element.childNodes);
      removed = true;
      continue;
    }
    for (const attribute of [...element.attributes]) {
      if (!LEFTOVER_ATTRIBUTE.test(attribute.name)) continue;
      element.removeAttributeNode(attribute);
      removed = true;
    }
  }

  return removed;
}

/**
 * Every element of a parsed body but a form. The names of the fields inside a form override the
 * form's own properties — an `<input name="attributes">` makes `form.attributes` that input — so
 * nothing on a form can be read safely; the sanitiser drops a form and keeps only what it holds,
 * so nothing on it would survive anyway, and it warns about the form whatever else is left on it.
 */
function elementsOf(body: HTMLElement): Element[] {
  return [...body.querySelectorAll('*')].filter((element) => !(element instanceof HTMLFormElement));
}

/**
 * Records the allowed declarations of every element that carries a `style`, marks those elements
 * with a class token, and removes the `style` attributes — all of them, including the ones being
 * kept, which the sanitiser would remove anyway.
 */
function markAllowedDeclarations(body: HTMLElement, recorded: Map<string, string>): void {
  for (const element of elementsOf(body)) {
    // A message cannot forge a mark: every token that looks like one is removed before any is
    // handed out, so a token in the sanitised output is one this function put there.
    for (const token of [...element.classList]) {
      if (MARKER.test(token)) element.classList.remove(token);
    }

    const style = element.getAttribute('style');
    if (style === null) continue;
    element.removeAttribute('style');

    const declarations = allowedDeclarations(style);
    if (declarations === '') continue;

    const marker = MARKER_PREFIX + recorded.size;
    recorded.set(marker, declarations);
    element.classList.add(marker);
  }
}

/**
 * The pass over the sanitiser's output: puts the recorded declarations back on the elements the
 * sanitiser kept, narrows every `class` to {@link MESSAGE_HTML_CLASSES}, and gives every element
 * with a `target` the {@link NEW_CONTEXT_REL} relation, replacing whatever `rel` it carried.
 */
function finalPass(sanitized: string, recorded: Map<string, string>): string {
  const document = new DOMParser().parseFromString(sanitized, 'text/html');

  for (const element of document.body.querySelectorAll('[class]')) {
    for (const token of [...element.classList]) {
      if (MARKER.test(token)) {
        element.classList.remove(token);
        const declarations = recorded.get(token);
        if (declarations !== undefined) element.setAttribute('style', declarations);
      } else if (!MESSAGE_HTML_CLASSES.has(token)) {
        element.classList.remove(token);
      }
    }
    // An element left with no class would otherwise keep an empty `class`.
    if (element.classList.length === 0) element.removeAttribute('class');
  }

  // `_self`, `_top` and `_parent` open no new context, and the relation changes nothing for them,
  // so every target is treated alike and none can be named in a way this pass misses.
  for (const element of document.body.querySelectorAll('[target]')) {
    element.setAttribute('rel', NEW_CONTEXT_REL);
  }

  return document.body.innerHTML;
}
