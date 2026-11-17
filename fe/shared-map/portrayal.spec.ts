import { describe, expect, it } from 'vitest';

import definition from './portrayal/portrayal.json';

// The portrayal definition is read by both applications' maps and by the backend's map-image
// renderer, so a malformed edit breaks three drawings at once. These checks hold the file to the
// shape all of its readers assume. Typing the import below is the first check: a key renamed or a
// value of the wrong kind fails the compilation before a test runs.

interface Pen {
  readonly width: number;
  readonly dash: readonly number[];
}

interface SizeSet {
  readonly unit: string;
  readonly symbols: Readonly<Record<string, readonly number[]>>;
  readonly line: Pen;
  readonly tagSpacing: number;
  readonly buffer: Pen;
}

interface Definition {
  readonly catalogue: string;
  readonly colours: Readonly<Record<string, string>>;
  readonly symbols: Readonly<
    Record<string, { readonly file: string; readonly anchor: readonly number[] }>
  >;
  readonly lineStyle: { readonly cap: string; readonly join: string; readonly miterLimit: number };
  readonly sizes: {
    readonly screen: SizeSet;
    readonly print: SizeSet & { readonly referenceImageSize: number };
  };
  readonly styles: Readonly<
    Record<
      string,
      {
        readonly stroke: string;
        readonly point: string;
        readonly tag: string;
        readonly approx: string;
      }
    >
  >;
  readonly buffer: { readonly fill: string; readonly stroke: string };
  readonly select: readonly Readonly<Record<string, string | undefined>>[];
}

const DEFINITION: Definition = definition;
const SIZE_SETS = Object.entries(DEFINITION.sizes) as [string, SizeSet][];
const STYLES = Object.entries(DEFINITION.styles);
const SELECTION_KEYS = ['mainType', 'messageType'];

/** The two colour notations every reader of the definition parses: `#RRGGBB` and `rgba(r, g, b, a)`. */
function parsesAsColour(value: string): boolean {
  if (/^#[0-9a-fA-F]{6}$/.test(value)) return true;
  const rgba = /^rgba\((\d{1,3}), (\d{1,3}), (\d{1,3}), (0|1|0?\.\d+)\)$/.exec(value);
  return rgba !== null && rgba.slice(1, 4).every((channel) => Number(channel) <= 255);
}

describe('portrayal definition — selection', () => {
  it('has rows, and every row names an existing style', () => {
    expect(DEFINITION.select.length).toBeGreaterThan(0);
    for (const row of DEFINITION.select) {
      expect(Object.keys(DEFINITION.styles)).toContain(row['style']);
    }
  });

  it('matches only on the keys the style module reads, each a non-empty string', () => {
    for (const row of DEFINITION.select) {
      for (const [key, value] of Object.entries(row)) {
        if (key === 'style') continue;
        expect(SELECTION_KEYS).toContain(key);
        expect(typeof value === 'string' && value.length > 0).toBe(true);
      }
    }
  });

  it('ends with a row that names no key, so every feature gets a style', () => {
    const last = DEFINITION.select[DEFINITION.select.length - 1];
    expect(Object.keys(last)).toEqual(['style']);
  });
});

describe('portrayal definition — styles and symbols', () => {
  it('names an existing colour and existing symbols in every style', () => {
    for (const [, style] of STYLES) {
      expect(Object.keys(DEFINITION.colours)).toContain(style.stroke);
      for (const symbolId of [style.point, style.tag, style.approx]) {
        expect(Object.keys(DEFINITION.symbols)).toContain(symbolId);
      }
    }
  });

  it('names each symbol file by its bare file name, an SVG', () => {
    for (const symbol of Object.values(DEFINITION.symbols)) {
      expect(symbol.file).toMatch(/^[a-z0-9-]+\.svg$/);
    }
  });

  it('places every anchor inside its symbol, as two fractions of the box', () => {
    for (const symbol of Object.values(DEFINITION.symbols)) {
      expect(symbol.anchor).toHaveLength(2);
      for (const fraction of symbol.anchor) {
        expect(fraction).toBeGreaterThanOrEqual(0);
        expect(fraction).toBeLessThanOrEqual(1);
      }
    }
  });

  it('writes every colour in a notation both the canvas and the backend parse', () => {
    for (const [name, value] of Object.entries(DEFINITION.colours)) {
      expect(parsesAsColour(value), `${name}: ${value}`).toBe(true);
    }
  });

  it('draws the affected-area overlay in existing colours', () => {
    expect(Object.keys(DEFINITION.colours)).toContain(DEFINITION.buffer.fill);
    expect(Object.keys(DEFINITION.colours)).toContain(DEFINITION.buffer.stroke);
  });

  it('gives the pen a cap, a join and a miter limit a canvas accepts', () => {
    expect(['butt', 'round', 'square']).toContain(DEFINITION.lineStyle.cap);
    expect(['bevel', 'round', 'miter']).toContain(DEFINITION.lineStyle.join);
    expect(DEFINITION.lineStyle.miterLimit).toBeGreaterThan(0);
  });
});

describe('portrayal definition — sizes', () => {
  it('has the screen set in CSS pixels and the print set in image pixels', () => {
    expect(DEFINITION.sizes.screen.unit).toBe('css-px');
    expect(DEFINITION.sizes.print.unit).toBe('image-px');
    expect(DEFINITION.sizes.print.referenceImageSize).toBeGreaterThan(0);
  });

  it('sizes every symbol in every size set, as a positive width and height', () => {
    for (const [setName, set] of SIZE_SETS) {
      for (const symbolId of Object.keys(DEFINITION.symbols)) {
        const size = set.symbols[symbolId];
        expect(size, `${setName} ${symbolId}`).toHaveLength(2);
        for (const px of size) expect(px).toBeGreaterThan(0);
      }
    }
  });

  it('sizes every screen symbol in whole pixels', () => {
    for (const [symbolId, size] of Object.entries(DEFINITION.sizes.screen.symbols)) {
      for (const px of size) expect(Number.isInteger(px), `${symbolId}: ${px}`).toBe(true);
    }
  });

  it("draws every style's screen point symbol square and at an even size", () => {
    // The halo rings and the count badge derive from half the point symbol, and an even size is
    // what keeps a halo's centre on the same sub-pixel phase as the symbol's ring.
    for (const [styleId, style] of STYLES) {
      const [width, height] = DEFINITION.sizes.screen.symbols[style.point];
      expect(width, styleId).toBe(height);
      expect(width % 2, styleId).toBe(0);
    }
  });

  it('gives every pen a positive width and a dash of one dash and one gap', () => {
    for (const [setName, set] of SIZE_SETS) {
      for (const [penName, line] of [
        ['line', set.line],
        ['buffer', set.buffer],
      ] as const) {
        expect(line.width, `${setName} ${penName}`).toBeGreaterThan(0);
        expect(line.dash, `${setName} ${penName}`).toHaveLength(2);
        for (const length of line.dash) expect(length).toBeGreaterThan(0);
      }
      expect(set.tagSpacing, setName).toBeGreaterThan(0);
    }
  });
});
