import { describe, expect, it } from 'vitest';

import { formatHtml } from './html-format';

describe('formatHtml', () => {
  it('indents block structure onto separate lines', () => {
    expect(formatHtml('<ul><li>one</li><li>two</li></ul>')).toBe(
      '<ul>\n  <li>one</li>\n  <li>two</li>\n</ul>',
    );
  });

  it("keeps a block's inline content and marks glued on one line (lossless on re-parse)", () => {
    expect(formatHtml('<p>Hello <strong>bold</strong> world</p>')).toBe(
      '<p>Hello <strong>bold</strong> world</p>',
    );
  });

  it('coalesces bare inline content (the inline/Publication preset) to a single line', () => {
    expect(formatHtml('Foo <strong>bar</strong> baz')).toBe('Foo <strong>bar</strong> baz');
  });

  it('preserves attributes on opening tags (publication ids, classes)', () => {
    expect(formatHtml('<p class="x"><span publication="7">P</span></p>')).toBe(
      '<p class="x"><span publication="7">P</span></p>',
    );
  });

  it('keeps escaped text at the top level escaped (the inline preset)', () => {
    expect(formatHtml('Press &lt;Enter&gt;')).toBe('Press &lt;Enter&gt;');
    expect(formatHtml('x &amp;lt; y')).toBe('x &amp;lt; y');
    expect(formatHtml('A &amp; B <strong>&lt;b&gt;</strong>')).toBe(
      'A &amp; B <strong>&lt;b&gt;</strong>',
    );
  });

  it('keeps escaped text beside a block escaped', () => {
    expect(formatHtml('<div>Press &lt;Enter&gt;<p>x</p></div>')).toBe(
      '<div>\n  Press &lt;Enter&gt;\n  <p>x</p>\n</div>',
    );
    expect(formatHtml('<ul><li>A &amp; B<p>x</p></li></ul>')).toBe(
      '<ul>\n  <li>\n    A &amp; B\n    <p>x</p>\n  </li>\n</ul>',
    );
  });

  it('is idempotent — formatting already-formatted HTML is a no-op', () => {
    const once = formatHtml('<table><tbody><tr><td>a</td><td>b</td></tr></tbody></table>');
    expect(formatHtml(once)).toBe(once);
  });
});
