import { describe, expect, it } from 'vitest';

import { formatHtml } from './html-format';

/**
 * Properties of the rich-text source view's Format action, over generated message HTML from a fixed
 * seed: formatting changes only the whitespace between blocks, so the
 * formatted source, parsed again, holds the same elements in the same order with the same text; and
 * formatting it again changes nothing. That holds for text escaped in the source (`&lt;`, `&amp;`)
 * also where it sits directly beside a block element rather than in a paragraph of its own, which
 * the Format once wrote back unescaped, so that it read as markup (fe/docs/backlog.md, Editor: "The
 * rich-text source view's Format writes escaped text beside a block back unescaped").
 */

/** A small seeded generator (mulberry32): the same seed gives the same inputs on every run. */
function seeded(seed: number) {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  return { next, int, pick };
}

type Rng = ReturnType<typeof seeded>;

/** Words, as message text holds them; `escaped` adds the characters HTML must escape. */
function words(rng: Rng, escaped: boolean): string {
  const plain = [
    'Wreck',
    'buoy',
    "55° 30.0'N",
    'Ø',
    'depth 5 m',
    '(see chart)',
    '–',
    '"Ny"',
    '1/2',
  ];
  const special = ['&lt;b&gt;', '&amp;lt;', 'A &amp; B', '&lt;Enter&gt;', '&lt;br&gt;'];
  return Array.from({ length: rng.int(1, 4) }, () =>
    rng.pick(escaped && rng.next() < 0.3 ? special : plain),
  ).join(' ');
}

function inline(rng: Rng, escaped: boolean, depth = 0): string {
  const roll = rng.int(0, depth > 1 ? 0 : 5);
  if (roll === 0) return words(rng, escaped);
  if (roll === 1) return `<strong>${inline(rng, escaped, depth + 1)}</strong>`;
  if (roll === 2) return `<em>${inline(rng, escaped, depth + 1)}</em>`;
  if (roll === 3)
    return `<a href="https://example.org/a?b=1&amp;c=2" target="_blank">${words(rng, escaped)}</a>`;
  if (roll === 4) return `<span class="position">${words(rng, escaped)}</span>`;
  return `${words(rng, escaped)}<br>${words(rng, escaped)}`;
}

function inlineRun(rng: Rng, escaped: boolean): string {
  return Array.from({ length: rng.int(1, 3) }, () => inline(rng, escaped)).join(' ');
}

/**
 * A block. With `loose` a container may hold text directly beside its blocks, as hand-written and
 * older stored HTML does; without it every text sits in a leaf block, as the editor writes it.
 */
function block(rng: Rng, escaped: boolean, loose: boolean, depth = 0): string {
  const roll = rng.int(0, depth > 2 ? 1 : 5);
  if (roll === 0) return `<p>${inlineRun(rng, escaped)}</p>`;
  if (roll === 1) {
    const level = rng.int(1, 4);
    return `<h${level}>${inlineRun(rng, escaped)}</h${level}>`;
  }
  const children = Array.from({ length: rng.int(1, 3) }, () =>
    block(rng, escaped, loose, depth + 1),
  );
  const looseText = loose && rng.next() < 0.5 ? inlineRun(rng, escaped) : '';
  if (roll === 2) return `<ul>${children.map((c) => `<li>${looseText}${c}</li>`).join('')}</ul>`;
  if (roll === 3) return `<ol><li>${children.join('')}</li></ol>`;
  if (roll === 4) {
    return `<table><tbody><tr>${children.map((c) => `<td>${c}</td>`).join('')}</tr></tbody></table>`;
  }
  return `<div>${looseText}${children.join('')}</div>`;
}

function fragment(rng: Rng, escaped: boolean, loose: boolean): string {
  return Array.from({ length: rng.int(1, 4) }, () => block(rng, escaped, loose)).join('');
}

/**
 * The tree as the editor reads it: element names in document order, and the words of its text, run
 * by run (the whitespace between blocks, which the Format adds and the editor drops, is not text).
 */
function shape(html: string): { elements: string; text: string } {
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  const elements = [...body.querySelectorAll('*')].map((e) => e.tagName.toLowerCase()).join(',');
  const runs: string[] = [];
  const walker = body.ownerDocument.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const run = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
    if (run) runs.push(run);
  }
  return { elements, text: runs.join(' | ') };
}

/** The generator's block elements, as the formatter puts them on lines of their own. */
const GENERATED_BLOCKS = new Set(['p', 'h1', 'h2', 'h3', 'h4', 'div', 'ul', 'ol', 'li', 'table']);

/** Whether a text node holding `<`, `>` or `&` sits directly beside a block element. */
function hasEscapedTextBesideBlock(html: string): boolean {
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  const walker = body.ownerDocument.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const siblings = [...(node.parentElement?.children ?? [])];
    if (
      /[<>&]/.test(node.textContent ?? '') &&
      siblings.some((e) => GENERATED_BLOCKS.has(e.tagName.toLowerCase()))
    ) {
      return true;
    }
  }
  return false;
}

describe('formatHtml, properties over generated message HTML', () => {
  it('keeps the elements, their order and their text, and formats a formatted source to itself', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 200; i++) {
      const loose = rng.next() < 0.5;
      // Escaped characters only where every text sits in a leaf block; the next property has the rest.
      const html = fragment(rng, !loose, loose);
      const formatted = formatHtml(html);
      expect(shape(formatted), html).toEqual(shape(html));
      expect(formatHtml(formatted), html).toBe(formatted);
    }
  }, 60_000);

  it('keeps escaped text beside a block as text, and formats a formatted source to itself', () => {
    // The smallest cases: the text as written, `Press <Enter>` and `x &lt; y`, read back as text.
    expect(formatHtml('<div>Press &lt;Enter&gt;<p>x</p></div>')).toBe(
      '<div>\n  Press &lt;Enter&gt;\n  <p>x</p>\n</div>',
    );
    expect(shape(formatHtml('<div>Press &lt;Enter&gt;<p>x</p></div>')).text).toBe(
      'Press <Enter> | x',
    );
    expect(formatHtml('x &amp;lt; y')).toBe('x &amp;lt; y');
    expect(shape(formatHtml('x &amp;lt; y')).text).toBe('x &lt; y');

    // Over generated loose HTML with escaped text: the same elements and text, and stable.
    const rng = seeded(20261116);
    let besideBlock = 0;
    for (let i = 0; i < 100; i++) {
      const html = fragment(rng, true, true);
      const formatted = formatHtml(html);
      expect(shape(formatted), html).toEqual(shape(html));
      expect(formatHtml(formatted), html).toBe(formatted);
      if (hasEscapedTextBesideBlock(html)) besideBlock++;
    }
    // The case under test is not rare in the generated documents.
    expect(besideBlock).toBeGreaterThan(10);
  }, 60_000);
});
