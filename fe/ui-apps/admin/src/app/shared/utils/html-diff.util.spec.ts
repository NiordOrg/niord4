import { describe, expect, it } from 'vitest';

import { htmlDiff } from './html-diff.util';

describe('htmlDiff', () => {
  it('returns identical input unchanged', () => {
    expect(htmlDiff('<p>hello world</p>', '<p>hello world</p>')).toBe('<p>hello world</p>');
  });

  it('wraps an inserted word in <ins>', () => {
    // The inserted run carries its trailing whitespace token, e.g. `<ins>brave </ins>`.
    expect(htmlDiff('<p>hello world</p>', '<p>hello brave world</p>')).toMatch(
      /<ins>brave\s*<\/ins>/,
    );
  });

  it('wraps a deleted word in <del>', () => {
    const out = htmlDiff('<p>hello brave world</p>', '<p>hello world</p>');
    expect(out).toMatch(/<del>brave\s*<\/del>/);
    expect(out).not.toContain('<ins>');
  });

  it('renders a replacement as a deletion followed by an insertion', () => {
    const out = htmlDiff('<p>the cat sat</p>', '<p>the dog sat</p>');
    expect(out).toContain('<del>cat</del>');
    expect(out).toContain('<ins>dog</ins>');
    expect(out.indexOf('<del>cat</del>')).toBeLessThan(out.indexOf('<ins>dog</ins>'));
  });

  it('keeps unchanged tags outside the ins/del wrappers', () => {
    // The <strong> tag is structural — it must not be wrapped even though its text is added.
    const out = htmlDiff('<p>a b</p>', '<p>a <strong>x</strong> b</p>');
    expect(out).toContain('<strong>');
    expect(out).not.toContain('<ins><strong>');
  });

  it('diffs against empty content (everything inserted / everything deleted)', () => {
    expect(htmlDiff('', 'new')).toContain('<ins>new</ins>');
    expect(htmlDiff('old', '')).toContain('<del>old</del>');
  });
});
