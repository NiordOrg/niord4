import { Editor, Extension, Mark, mergeAttributes, Node } from '@tiptap/core';
import { Highlight } from '@tiptap/extension-highlight';
import { Image } from '@tiptap/extension-image';
import { Link } from '@tiptap/extension-link';
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table';
import { TextAlign } from '@tiptap/extension-text-align';
import { Color, TextStyle } from '@tiptap/extension-text-style';
import { StarterKit } from '@tiptap/starter-kit';

import { allowedClasses } from '@niord/html/message-html-classes';
import { allowedDeclaration } from '@niord/html/message-html-declarations';

/**
 * Framework-agnostic TipTap engine. This module is reached ONLY through a dynamic
 * `import()` from {@link ../rich-text-editor/rich-text-editor}, so every `@tiptap/*`
 * import lands in a lazy chunk and never in the main bundle. Nothing that is eagerly
 * loaded may import this module (or `@tiptap/*`) statically.
 *
 * The HTML the editor is seeded with is stored message HTML, and ProseMirror writes every
 * attribute the schema holds onto the live, editable surface. So every attribute slot below
 * reads and writes an allow-listed value only: a slot never copies an attribute it does not
 * name, and a `class` is narrowed to the message-HTML class vocabulary — at parse time, which
 * every way content gets in (seed, `setValue`, insert, paste) goes through, and again at render
 * time, for an attribute set by a command rather than parsed.
 */

/** Which editing schema + toolbar the control presents. */
export type RichTextPreset = 'full' | 'inline' | 'basic';

/** A `class` attribute slot narrowed to the message-HTML class vocabulary, read and written. */
const vocabularyClassAttribute = {
  default: null,
  parseHTML: (el: HTMLElement) => allowedClasses(el.getAttribute('class')),
  renderHTML: (attrs: Record<string, unknown>) => {
    const kept = allowedClasses(attrs['class'] == null ? null : String(attrs['class']));
    return kept ? { class: kept } : {};
  },
};

/** An attribute slot holding one inert, text-valued attribute as written. */
function textAttribute(name: string) {
  return {
    default: null,
    parseHTML: (el: HTMLElement) => el.getAttribute(name),
    renderHTML: (attrs: Record<string, unknown>) =>
      attrs[name] == null || attrs[name] === '' ? {} : { [name]: String(attrs[name]) },
  };
}

/**
 * The top node for the `inline` preset: content is `inline*` (no block wrapper), so
 * `getHTML()` returns bare inline markup with no root `<p>` — the behaviour the
 * Publication field depends on.
 */
const InlineDocument = Node.create({
  name: 'doc',
  topNode: true,
  content: 'inline*',
});

/**
 * A mark for server-authored `<span publication="…">` fragments. TipTap/ProseMirror
 * normalises unknown attributes away by default; this mark gives `publication` an explicit
 * schema slot so the id round-trips untouched through setContent → edit → getHTML. This is
 * the Publication-field preservation contract.
 *
 * Besides the id the span keeps only what reads as text: `title`, `lang` and `dir`, and a
 * `class` narrowed to the message-HTML vocabulary. Anything else on the span — an event
 * handler, a `style`, a `data-` or any unknown attribute — has no slot and is dropped.
 */
const PublicationSpan = Mark.create({
  name: 'publicationSpan',
  // Outrank TextStyle etc. so a `span[publication]` is claimed here, and don't extend the
  // mark onto adjacent typed text.
  priority: 1001,
  inclusive: false,
  addAttributes() {
    return {
      publication: {
        default: null,
        parseHTML: (el) => el.getAttribute('publication'),
        renderHTML: (attrs) =>
          attrs['publication'] ? { publication: String(attrs['publication']) } : {},
      },
      class: vocabularyClassAttribute,
      title: textAttribute('title'),
      lang: textAttribute('lang'),
      dir: textAttribute('dir'),
    };
  },
  parseHTML() {
    return [{ tag: 'span[publication]' }];
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes), 0];
  },
});

/**
 * Adds a `publication` attribute slot to the built-in `link` mark so server-authored
 * `<a publication="…" href="…">` anchors preserve the id through the same round-trip.
 */
const PublicationLinkAttribute = Extension.create({
  name: 'publicationLinkAttribute',
  addGlobalAttributes() {
    return [
      {
        types: ['link'],
        attributes: {
          publication: {
            default: null,
            parseHTML: (el) => (el as HTMLElement).getAttribute('publication'),
            renderHTML: (attrs) =>
              attrs['publication'] ? { publication: String(attrs['publication']) } : {},
          },
        },
      },
    ];
  },
});

/**
 * Adds a `title` attribute slot to the built-in `link` mark (StarterKit's Link ships `href` /
 * `target` / `rel` / `class`, but not `title`). The Publication editor's Insert/Edit-link dialog
 * writes a title, and this lets it round-trip through parse → serialize like the native anchor.
 */
const LinkTitleAttribute = Extension.create({
  name: 'linkTitleAttribute',
  addGlobalAttributes() {
    return [
      {
        types: ['link'],
        attributes: {
          title: {
            default: null,
            parseHTML: (el) => (el as HTMLElement).getAttribute('title'),
            renderHTML: (attrs) => (attrs['title'] ? { title: String(attrs['title']) } : {}),
          },
        },
      },
    ];
  },
});

/**
 * Class-based styling for the message-content table model (`full` preset): a `class`
 * attribute slot on the table/row/cell nodes, driven by the toolbar class pickers. The
 * sanitizer keeps only the message-HTML class vocabulary and two inline declarations, so table
 * appearance is class-only, and the slot holds the same vocabulary: a stored utility class
 * never reaches the editing surface.
 */
const TableClassAttributes = Extension.create({
  name: 'tableClassAttributes',
  addGlobalAttributes() {
    return [
      {
        types: ['table', 'tableRow', 'tableCell', 'tableHeader'],
        attributes: {
          class: vocabularyClassAttribute,
        },
      },
    ];
  },
});

/**
 * What a link that opens another browsing context carries, whatever the stored anchor said —
 * the same relation the message-HTML sanitiser gives it when the message is rendered.
 */
const NEW_CONTEXT_REL = 'noopener noreferrer';

/**
 * The `link` mark of every preset. TipTap's own Link keeps `class` and `rel` exactly as written,
 * and a global attribute cannot override that — the mark's own attributes are resolved after
 * the global ones and win — so the two slots are replaced on the mark itself: the `class` is
 * narrowed to the message-HTML vocabulary, and a link with a `target` carries
 * {@link NEW_CONTEXT_REL} in place of whatever `rel` it had, whether it was parsed or set by the
 * link dialog.
 */
const MessageLink = Link.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      class: vocabularyClassAttribute,
      rel: {
        default: null,
        parseHTML: (el: HTMLElement) =>
          el.hasAttribute('target') ? NEW_CONTEXT_REL : el.getAttribute('rel'),
        renderHTML: (attrs: Record<string, unknown>) => {
          if (attrs['target']) {
            return { rel: NEW_CONTEXT_REL };
          }
          return attrs['rel'] ? { rel: String(attrs['rel']) } : {};
        },
      },
    };
  },
});

/** The block nodes a stored indent can sit on. */
const INDENTABLE_NODES = [
  'paragraph',
  'heading',
  'blockquote',
  'bulletList',
  'orderedList',
  'listItem',
  'tableCell',
  'tableHeader',
];

/**
 * The inline declarations a message is allowed to keep that no stock extension carries: the
 * `padding-left` of an indented block, and the `text-align` of a table cell. ProseMirror drops
 * whatever its schema has no slot for, so without these a stored indent would be missing from the
 * editing surface and the first keystroke would re-serialise the document without it — while the
 * message details and the public site, which render the stored HTML, would still show it.
 *
 * Both values are read with the message-HTML sanitiser's own rules, so the editor holds exactly
 * what the rendered message shows: an indent beyond the cap, a `calc()` or a `padding` shorthand
 * is dropped here as it is there. Nothing is copied out of the input; the declaration is rebuilt
 * from the validated value.
 *
 * The cell's alignment is its own attribute rather than a TextAlign type on purpose: the toolbar's
 * alignment buttons set every node of every TextAlign type around the cursor, so aligning one
 * paragraph in a cell would realign the whole cell. The cell keeps what it was stored with, and a
 * paragraph aligned inside it overrides it, as it does in the rendered message.
 */
const MessageBlockStyles = Extension.create({
  name: 'messageBlockStyles',
  addGlobalAttributes() {
    return [
      {
        types: INDENTABLE_NODES,
        attributes: {
          indent: {
            default: null,
            parseHTML: (el) =>
              allowedDeclaration((el as HTMLElement).getAttribute('style') ?? '', 'padding-left'),
            renderHTML: (attrs) =>
              attrs['indent'] ? { style: `padding-left: ${String(attrs['indent'])}` } : {},
          },
        },
      },
      {
        types: ['tableCell', 'tableHeader'],
        attributes: {
          cellTextAlign: {
            default: null,
            parseHTML: (el) =>
              allowedDeclaration((el as HTMLElement).getAttribute('style') ?? '', 'text-align'),
            renderHTML: (attrs) =>
              attrs['cellTextAlign']
                ? { style: `text-align: ${String(attrs['cellTextAlign'])}` }
                : {},
          },
        },
      },
    ];
  },
});

/**
 * The "No-wrap" text style — emits `<span class="no-wrap">`, never an inline
 * `white-space` style (the sanitizer strips styles; class-based, never inline style).
 */
const NoWrap = Mark.create({
  name: 'noWrap',
  parseHTML() {
    return [{ tag: 'span.no-wrap' }];
  },
  renderHTML() {
    return ['span', { class: 'no-wrap' }, 0];
  },
});

/**
 * Link options shared by every preset: no navigation on click, no injected target/rel — a
 * server anchor keeps the target it has, and only a link with a target gets a `rel`.
 */
const linkOptions = {
  openOnClick: false,
  HTMLAttributes: { target: null, rel: null },
} as const;

function buildExtensions(preset: RichTextPreset) {
  if (preset === 'inline') {
    return [
      StarterKit.configure({
        // Inline-only schema: drop every block node + the block-oriented marks, keep
        // bold/italic/hardBreak/text/history; the link is MessageLink below.
        document: false,
        paragraph: false,
        heading: false,
        blockquote: false,
        codeBlock: false,
        bulletList: false,
        orderedList: false,
        listItem: false,
        listKeymap: false,
        horizontalRule: false,
        strike: false,
        code: false,
        underline: false,
        trailingNode: false,
        dropcursor: false,
        gapcursor: false,
        link: false,
      }),
      MessageLink.configure(linkOptions),
      InlineDocument,
      PublicationSpan,
      PublicationLinkAttribute,
      LinkTitleAttribute,
    ];
  }

  if (preset === 'basic') {
    return [
      StarterKit.configure({ link: false }),
      MessageLink.configure(linkOptions),
      TextStyle,
      Color,
      Highlight,
      TextAlign.configure({ types: ['heading', 'paragraph'] }),
      Table.configure({ resizable: false }),
      TableRow,
      TableHeader,
      TableCell,
      MessageBlockStyles,
    ];
  }

  // full
  return [
    StarterKit.configure({ heading: { levels: [1, 2, 3, 4] }, link: false }),
    MessageLink.configure(linkOptions),
    TextStyle,
    Color,
    Highlight,
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    Table.configure({ resizable: false }),
    TableRow,
    TableHeader,
    TableCell,
    TableClassAttributes,
    MessageBlockStyles,
    Image,
    NoWrap,
  ];
}

/**
 * Structural emptiness — a lone empty text-block (the `<p></p>` a fresh editor holds) or a
 * childless inline document. Deliberately NOT `editor.isEmpty`, which is text-content based
 * and would treat a table of empty cells (or a lone image) as "empty", dropping real
 * structural content from the value.
 */
function isDocEmpty(editor: Editor): boolean {
  const doc = editor.state.doc;
  if (doc.childCount === 0) {
    return true;
  }
  if (doc.childCount === 1) {
    const first = doc.firstChild;
    return first !== null && first.isTextblock && first.content.size === 0;
  }
  return false;
}

function surfaceAttributes(
  preset: RichTextPreset,
  ariaLabel: string | undefined,
  tabindex: number | undefined,
): Record<string, string> {
  const classes = ['rte-surface'];
  // Only the full (Description) surface wears the read-view vocabulary (WYSIWYG).
  if (preset === 'full') {
    classes.push('message-details-html');
  }
  const attrs: Record<string, string> = { class: classes.join(' ') };
  if (ariaLabel) {
    attrs['aria-label'] = ariaLabel;
  }
  if (tabindex !== undefined) {
    attrs['tabindex'] = String(tabindex);
  }
  return attrs;
}

export interface CreateRichTextEditorOptions {
  readonly element: HTMLElement;
  readonly preset: RichTextPreset;
  readonly content: string;
  readonly editable: boolean;
  readonly ariaLabel?: string;
  readonly tabindex?: number;
  /** Fires on genuine document changes (not on programmatic `setValue`, which is silent). */
  readonly onChange: () => void;
  /** Fires on selection moves — drives toolbar active-state refresh. */
  readonly onSelectionChange: () => void;
  readonly onBlur: () => void;
}

export interface RichTextEditorHandle {
  /** The live editor — the component drives toolbar commands off it directly. */
  readonly editor: Editor;
  /** Current HTML; an empty document normalises to `''` (for the `required` gate). */
  getValue(): string;
  /** Replace the whole document WITHOUT emitting a change (the single-write-path rule). */
  setValue(html: string): void;
  setEditable(editable: boolean): void;
  /** Re-apply the aria-label / tabindex on the contenteditable. */
  setSurfaceAttributes(ariaLabel: string | undefined, tabindex: number | undefined): void;
  destroy(): void;
}

export function createRichTextEditor(options: CreateRichTextEditorOptions): RichTextEditorHandle {
  const editor = new Editor({
    element: options.element,
    extensions: buildExtensions(options.preset),
    content: options.content,
    editable: options.editable,
    editorProps: {
      attributes: surfaceAttributes(options.preset, options.ariaLabel, options.tabindex),
      // Neutralise the line-ending whitespace a clipboard wrapper can insert AROUND the copied
      // fragment — most visibly Windows' CF_HTML (`<html>\r\n<body>\r\n<!--StartFragment-->…`).
      // ProseMirror parses a slice paste (one carrying `data-pm-slice`) with
      // `preserveWhitespace: true`, so newlines BETWEEN the wrapper's block tags would otherwise be
      // converted to `<br>` hard breaks — the reported "line breaks around a pasted word". We drop
      // carriage returns (never significant in HTML) and the newline whitespace between tags
      // (structural, insignificant), while leaving newlines INSIDE text / code blocks intact. Safe
      // and correct on every OS: a no-op on clean macOS/iOS clipboards.
      transformPastedHTML: (html) => html.replace(/\r/g, '').replace(/>\n+</g, '><'),
    },
    onUpdate: () => options.onChange(),
    onSelectionUpdate: () => options.onSelectionChange(),
    onBlur: () => options.onBlur(),
  });

  return {
    editor,
    getValue: () => (isDocEmpty(editor) ? '' : editor.getHTML()),
    setValue: (html) => {
      editor.commands.setContent(html || '', { emitUpdate: false });
    },
    setEditable: (editable) => editor.setEditable(editable),
    setSurfaceAttributes: (ariaLabel, tabindex) => {
      editor.setOptions({
        editorProps: {
          attributes: surfaceAttributes(options.preset, ariaLabel, tabindex),
        },
      });
    },
    destroy: () => editor.destroy(),
  };
}
