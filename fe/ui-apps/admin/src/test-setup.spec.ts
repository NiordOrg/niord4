import { describe, expect, it } from 'vitest';

/**
 * Guards the jsdom Range rect stub installed in `test-setup.ts`.
 *
 * Without it, any spec mounting a TipTap editor can throw an unhandled
 * `TypeError: target.getClientRects is not a function` out of ProseMirror's `singleRect` — which
 * fails the whole run (vitest exits non-zero on unhandled errors) while every individual test
 * still reports as passing. That combination is very hard to diagnose from a CI log, and the
 * throw is intermittent, so it would not reliably come back on the next run to be caught.
 */
describe('jsdom Range rect stub', () => {
  it('gives Range the two rect methods ProseMirror calls on it', () => {
    const range = document.createRange();
    range.selectNodeContents(document.body);

    // singleRect() calls getClientRects() first and falls back to getBoundingClientRect(),
    // so BOTH have to exist — stubbing only the first just moves the crash one line down.
    expect(typeof range.getClientRects).toBe('function');
    expect(typeof range.getBoundingClientRect).toBe('function');
  });

  it('reports no layout, exactly as jsdom already does for Elements', () => {
    const range = document.createRange();
    range.selectNodeContents(document.body);

    // ProseMirror reads a zero-area rect as "position unknown" and degrades gracefully; matching
    // Element behaviour keeps text-node positions on the same path as element-backed ones.
    expect(range.getClientRects()).toHaveLength(0);
    expect(range.getBoundingClientRect()).toMatchObject({ top: 0, bottom: 0, left: 0, right: 0 });
  });
});
