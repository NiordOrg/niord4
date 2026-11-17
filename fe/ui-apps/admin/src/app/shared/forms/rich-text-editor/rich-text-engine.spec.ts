// @vitest-environment jsdom
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { MessageHtmlSanitizer } from '@niord/html/message-html-sanitizer';

import {
  createRichTextEditor,
  type RichTextEditorHandle,
  type RichTextPreset,
} from './rich-text-engine';

const noop = (): void => undefined;

/** Every attribute of an element, by name. */
function attributesOf(element: Element): Record<string, string> {
  return Object.fromEntries([...element.attributes].map((a) => [a.name, a.value]));
}

/** The body of a fragment of HTML, parsed inertly. */
function parse(html: string): HTMLElement {
  return new DOMParser().parseFromString(html, 'text/html').body;
}

/**
 * The publication-attribute round-trip spike. Proves the `inline` preset
 * preserves an unknown `publication` attribute on server-authored inline markup through a
 * setContent → unrelated edit → getHTML cycle — the highest-uncertainty item of the slice.
 * Kept as a permanent regression guard.
 */
describe('rich-text-engine — inline preset publication round-trip (spike)', () => {
  let handle: RichTextEditorHandle | undefined;

  function mountInline(content: string): RichTextEditorHandle {
    handle = createRichTextEditor({
      element: document.createElement('div'),
      preset: 'inline',
      content,
      editable: true,
      onChange: noop,
      onSelectionChange: noop,
      onBlur: noop,
    });
    return handle;
  }

  afterEach(() => {
    handle?.destroy();
    handle = undefined;
  });

  it('preserves publication on a <span> through an unrelated edit', () => {
    const h = mountInline('<span publication="123">Some title</span>');

    // Unrelated edit: append text at the end of the document.
    h.editor.commands.insertContentAt(h.editor.state.doc.content.size, ' extra');

    const html = h.editor.getHTML();
    expect(html).toContain('publication="123"');
    expect(html).toContain('Some title');
    // No root <p> wrapper.
    expect(html.startsWith('<p>')).toBe(false);
  });

  it('preserves publication on an <a> anchor through an unrelated edit', () => {
    const h = mountInline('<a publication="123" href="http://x">t</a>');

    h.editor.commands.insertContentAt(h.editor.state.doc.content.size, ' extra');

    const html = h.editor.getHTML();
    expect(html).toContain('publication="123"');
    expect(html).toContain('href="http://x"');
  });

  // A publication span used to keep every attribute it carried, so markup stored in a message
  // could put an event handler or a `style` on the live editing surface. It keeps its id and
  // an allow-list now, and nothing else.
  it('keeps only the allow-listed attributes of a publication span', () => {
    const h = mountInline(
      '<span publication="7" title="Notices" lang="da" dir="ltr" class="fixed inset-0 no-wrap"' +
        ' onclick="void 0" onmouseover="void 0" style="position: fixed; inset: 0"' +
        ' data-foo="bar" foo="baz">Title</span>',
    );

    h.editor.commands.insertContentAt(h.editor.state.doc.content.size, '!');

    const expected = {
      publication: '7',
      title: 'Notices',
      lang: 'da',
      dir: 'ltr',
      class: 'no-wrap',
    };
    const surfaceSpan = h.editor.view.dom.querySelector('span[publication]') as HTMLElement;
    expect(attributesOf(surfaceSpan)).toEqual(expected);

    const html = h.editor.getHTML();
    const serialisedSpan = parse(html).querySelector('span[publication]') as HTMLElement;
    expect(attributesOf(serialisedSpan)).toEqual(expected);
    expect(parse(html).textContent).toBe('Title!');
  });

  it('keeps only the id when a publication span carries nothing else allowed', () => {
    const h = mountInline('<span publication="7" onclick="void 0" style="color: red">Title</span>');

    expect(h.editor.getHTML()).toBe('<span publication="7">Title</span>');
    const surfaceSpan = h.editor.view.dom.querySelector('span[publication]') as HTMLElement;
    expect(attributesOf(surfaceSpan)).toEqual({ publication: '7' });
  });

  it('narrows a class set on a publication span by a command, not only a parsed one', () => {
    const h = mountInline('Title');
    h.editor
      .chain()
      .selectAll()
      .setMark('publicationSpan', { publication: '8', class: 'fixed z-50 no-wrap' })
      .run();

    expect(h.editor.getHTML()).toBe('<span publication="8" class="no-wrap">Title</span>');
  });

  it('reads the publication id off the mark under the selection', () => {
    const h = mountInline('<span publication="99">Title</span>');
    // Put the selection inside the span.
    h.editor.commands.setTextSelection(2);
    const attrs = h.editor.getAttributes('publicationSpan');
    expect(attrs['publication']).toBe('99');
  });
});

describe('rich-text-engine — getValue empty-document normalization', () => {
  let handle: RichTextEditorHandle | undefined;

  afterEach(() => {
    handle?.destroy();
    handle = undefined;
  });

  it('normalises a lone empty paragraph to an empty string (the required gate)', () => {
    handle = createRichTextEditor({
      element: document.createElement('div'),
      preset: 'full',
      content: '<p></p>',
      editable: true,
      onChange: noop,
      onSelectionChange: noop,
      onBlur: noop,
    });
    expect(handle.getValue()).toBe('');
  });

  it('returns the HTML for a non-empty document', () => {
    handle = createRichTextEditor({
      element: document.createElement('div'),
      preset: 'full',
      content: '<p>Body</p>',
      editable: true,
      onChange: noop,
      onSelectionChange: noop,
      onBlur: noop,
    });
    expect(handle.getValue()).toContain('Body');
  });
});

/**
 * The two inline declarations a message may keep. The rendered message (the editor preview, the
 * message details, the public site) shows them, so the editor has to hold them too: otherwise the
 * editing surface disagrees with the preview, and the first keystroke re-serialises the document
 * without them.
 */
describe('rich-text-engine — the inline declarations a message keeps', () => {
  let handle: RichTextEditorHandle | undefined;

  function mount(content: string, preset: 'full' | 'basic' = 'full'): RichTextEditorHandle {
    handle = createRichTextEditor({
      element: document.createElement('div'),
      preset,
      content,
      editable: true,
      onChange: noop,
      onSelectionChange: noop,
      onBlur: noop,
    });
    return handle;
  }

  /** An unrelated edit: one character typed at the end of the document. */
  function typeAtEnd(h: RichTextEditorHandle): string {
    h.editor.commands.focus('end');
    h.editor.commands.insertContent('x');
    return h.getValue();
  }

  afterEach(() => {
    handle?.destroy();
    handle = undefined;
  });

  it('keeps an indented paragraph through an unrelated edit, and shows it on the surface', () => {
    const h = mount(
      '<p><strong>In the periods:</strong></p>' +
        '<p style="padding-left: 30px;"><strong>19 - 20 September</strong></p><p>Closed.</p>',
    );

    const indented = h.editor.view.dom.querySelectorAll('p')[1] as HTMLElement;
    expect(indented.style.paddingLeft).toBe('30px');

    const html = typeAtEnd(h);
    expect(html).toContain('<p style="padding-left: 30px;"><strong>19 - 20 September</strong></p>');
    expect(html).toContain('Closed.x');
  });

  it('keeps an indent and an alignment on the same paragraph', () => {
    const html = typeAtEnd(
      mount('<p style="text-align: center; padding-left: 2em">Both</p><p>End</p>'),
    );
    expect(html).toContain('<p style="text-align: center; padding-left: 2em;">Both</p>');
  });

  it('keeps indents on headings, lists and list items', () => {
    const html = typeAtEnd(
      mount(
        '<h2 style="padding-left: 10px">Heading</h2>' +
          '<ul style="padding-left: 40px"><li style="padding-left: 5px"><p>Item</p></li></ul>' +
          '<p>End</p>',
      ),
    );
    expect(html).toContain('<h2 style="padding-left: 10px;">Heading</h2>');
    expect(html).toContain('<ul style="padding-left: 40px;">');
    expect(html).toContain('<li style="padding-left: 5px;">');
  });

  it('keeps the alignment and the indent of a table cell and a table header', () => {
    const html = typeAtEnd(
      mount(
        '<table><tbody>' +
          '<tr><th style="text-align: right">Depth</th></tr>' +
          '<tr><td style="text-align: center; padding-left: 12px">5 m</td></tr>' +
          '</tbody></table><p>End</p>',
      ),
    );
    expect(html).toMatch(/<th[^>]*style="text-align: right;"[^>]*>/);
    expect(html).toMatch(/<td[^>]*style="padding-left: 12px; text-align: center;"[^>]*>/);
  });

  it('aligns a paragraph inside a cell without realigning the cell', () => {
    const h = mount(
      '<table><tbody><tr><td style="text-align: right"><p>One</p><p>Two</p></td></tr></tbody></table>',
    );
    // The cursor inside the first paragraph of the cell.
    h.editor.commands.setTextSelection(4);
    h.editor.commands.setTextAlign('center');

    const html = h.getValue();
    expect(html).toMatch(
      /<td[^>]*style="text-align: right;"[^>]*><p style="text-align: center;">One/,
    );
    expect(html).toContain('<p>Two</p>');
  });

  it('drops what the message-HTML sanitiser drops', () => {
    const html = typeAtEnd(
      mount(
        '<p style="padding-left: 400px">Too wide</p>' +
          '<p style="padding-left: calc(1em + 10px)">Computed</p>' +
          '<p style="padding: 0 0 0 30px">Shorthand</p>' +
          '<p style="font-family: Verdana; color: red">Pasted</p>',
      ),
    );
    expect(html).not.toContain('style=');
  });

  it('keeps the declarations in the basic preset the comments use', () => {
    const html = typeAtEnd(mount('<p style="padding-left: 30px">Indented</p><p>End</p>', 'basic'));
    expect(html).toContain('<p style="padding-left: 30px;">Indented</p>');
  });

  it('hands the sanitiser markup it renders unchanged', () => {
    const html = typeAtEnd(
      mount(
        '<p style="text-align: justify; padding-left: 30px">Body</p>' +
          '<table><tbody><tr><td style="text-align: right">5</td></tr></tbody></table>',
      ),
    );
    const rendered = TestBed.inject(MessageHtmlSanitizer).sanitizeToString(html);
    expect(rendered).toContain('<p style="text-align: justify; padding-left: 30px">Body</p>');
    expect(rendered).toMatch(/<td[^>]*style="text-align: right"/);
  });
});

/**
 * The classes the editing surface holds. The rendered message keeps only the message-HTML class
 * vocabulary; the editing surface has to be held to the same one, or a stored utility class
 * (`fixed`, `inset-0`, `z-40`, …) reaches the admin stylesheet through the editor instead.
 */
describe('rich-text-engine — the class vocabulary of the editing surface', () => {
  let handles: RichTextEditorHandle[] = [];

  function mount(content: string, preset: RichTextPreset = 'full'): RichTextEditorHandle {
    const handle = createRichTextEditor({
      element: document.createElement('div'),
      preset,
      content,
      editable: true,
      onChange: noop,
      onSelectionChange: noop,
      onBlur: noop,
    });
    handles.push(handle);
    return handle;
  }

  afterEach(() => {
    handles.forEach((h) => h.destroy());
    handles = [];
  });

  it('keeps only the vocabulary on a table, a row, a header and a cell', () => {
    const h = mount(
      '<table class="fixed inset-0 z-40 no-border bg-surface-0 condensed"><tbody>' +
        '<tr class="opacity-0 underline"><th class="z-10 pos-index">#</th></tr>' +
        '<tr class="z-30"><td class="fixed pos-col">1</td></tr>' +
        '</tbody></table><p>End</p>',
    );

    for (const root of [h.editor.view.dom, parse(h.getValue())]) {
      expect(root.querySelector('table')?.getAttribute('class')).toBe('no-border condensed');
      const rows = root.querySelectorAll('tr');
      expect(rows[0].getAttribute('class')).toBe('underline');
      expect(rows[1].hasAttribute('class')).toBe(false);
      expect(root.querySelector('th')?.getAttribute('class')).toBe('pos-index');
      expect(root.querySelector('td')?.getAttribute('class')).toBe('pos-col');
    }
    expect(h.getValue()).not.toMatch(/fixed|inset-0|z-\d0|opacity-0|bg-surface-0/);
  });

  it('round-trips the classes the toolbar sets', () => {
    const h = mount('<table><tbody><tr><td><p>1</p></td></tr></tbody></table>');
    h.editor.commands.setTextSelection(4);
    h.editor.chain().updateAttributes('table', { class: 'no-border condensed' }).run();
    h.editor.chain().updateAttributes('tableRow', { class: 'underline' }).run();
    h.editor.chain().updateAttributes('tableCell', { class: 'pos-col' }).run();

    const html = h.getValue();
    expect(html).toMatch(/<table[^>]*class="no-border condensed"/);
    expect(html).toMatch(/<tr[^>]*class="underline"/);
    expect(html).toMatch(/<td[^>]*class="pos-col"/);

    // And again through a fresh editor, as the next load of the message would.
    const reloaded = mount(html);
    expect(reloaded.getValue()).toBe(html);
  });

  it('narrows a table class set by a command, not only a parsed one', () => {
    const h = mount('<table><tbody><tr><td><p>1</p></td></tr></tbody></table>');
    h.editor.commands.setTextSelection(4);
    h.editor.chain().updateAttributes('table', { class: 'fixed inset-0 no-border' }).run();

    expect(h.getValue()).toMatch(/<table[^>]*class="no-border"/);
    expect(h.getValue()).not.toContain('fixed');
  });

  for (const preset of ['full', 'basic', 'inline'] as const) {
    it(`drops a class outside the vocabulary on a link in the ${preset} preset`, () => {
      const anchors =
        '<a href="https://x.test" class="fixed inset-0 z-50 no-wrap">one</a> ' +
        '<a href="https://y.test" class="fixed inset-0">two</a>';
      const h = mount(preset === 'inline' ? anchors : `<p>${anchors}</p>`, preset);

      for (const root of [h.editor.view.dom, parse(h.getValue())]) {
        const links = root.querySelectorAll('a');
        expect(links).toHaveLength(2);
        expect(links[0].getAttribute('class')).toBe('no-wrap');
        expect(links[1].hasAttribute('class')).toBe(false);
      }
    });

    it(`gives a link with a target noopener and noreferrer in the ${preset} preset`, () => {
      const anchors =
        '<a href="https://x.test" target="_blank">one</a> ' +
        '<a href="https://y.test" target="chart" rel="opener">two</a> ' +
        '<a href="https://z.test" rel="nofollow">three</a>';
      const h = mount(preset === 'inline' ? anchors : `<p>${anchors}</p>`, preset);

      for (const root of [h.editor.view.dom, parse(h.getValue())]) {
        const links = root.querySelectorAll('a');
        expect(links[0].getAttribute('target')).toBe('_blank');
        expect(links[0].getAttribute('rel')).toBe('noopener noreferrer');
        expect(links[1].getAttribute('target')).toBe('chart');
        expect(links[1].getAttribute('rel')).toBe('noopener noreferrer');
        // A link that opens no other context keeps the relation it was stored with.
        expect(links[2].hasAttribute('target')).toBe(false);
        expect(links[2].getAttribute('rel')).toBe('nofollow');
      }
    });
  }

  it('gives a link the link dialog sets with a target noopener and noreferrer', () => {
    const h = mount('Chart', 'inline');
    h.editor
      .chain()
      .selectAll()
      .setLink({ href: 'https://x.test', target: '_blank' } as { href: string })
      .run();

    expect(h.getValue()).toBe(
      '<a target="_blank" rel="noopener noreferrer" href="https://x.test">Chart</a>',
    );
  });

  it('keeps the publication id, the title and the target of a stored anchor', () => {
    const h = mount(
      '<a publication="dk-harbour-pilot" href="https://x.test" target="_blank" title="Pilot">' +
        'Pilot</a>',
      'inline',
    );
    const link = parse(h.getValue()).querySelector('a') as HTMLElement;
    expect(attributesOf(link)).toEqual({
      publication: 'dk-harbour-pilot',
      href: 'https://x.test',
      target: '_blank',
      rel: 'noopener noreferrer',
      title: 'Pilot',
    });
  });

  it('hands the sanitiser links and tables it renders unchanged', () => {
    const html = mount(
      '<p><a href="https://x.test" target="_blank" class="fixed no-wrap">x</a></p>' +
        '<table class="z-50 no-border"><tbody><tr><td class="pos-col">1</td></tr></tbody></table>',
    ).getValue();
    const rendered = TestBed.inject(MessageHtmlSanitizer).sanitizeToString(html);
    const link = (root: string) => parse(root).querySelector('a') as HTMLElement;
    expect(attributesOf(link(rendered))).toEqual(attributesOf(link(html)));
    for (const selector of ['table', 'td']) {
      expect(parse(rendered).querySelector(selector)?.getAttribute('class')).toBe(
        parse(html).querySelector(selector)?.getAttribute('class'),
      );
    }
  });
});
