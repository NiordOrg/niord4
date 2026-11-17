import { describe, expect, it } from 'vitest';

import { formatByteSize } from './format-byte-size';

/**
 * Properties of the byte sizes in the file uploader and the attachments table, over generated sizes
 * from a fixed seed and every unit edge (error hunt, phase 2): the text reads back within the
 * rounding of its one decimal, a larger file never shows a smaller size, and no unit shows 1024 or
 * more (phase 1's B1-14, "1024.0 KB", was fixed on this branch).
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
  return { next };
}

const FACTOR: Record<string, number> = { B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3 };

function readBack(text: string): { value: number; unit: string; bytes: number } {
  const match = /^(\d+(?:\.\d)?) (B|KB|MB|GB)$/.exec(text);
  if (!match) throw new Error(`"${text}" is not a size`);
  const value = Number(match[1]);
  return { value, unit: match[2], bytes: value * FACTOR[match[2]] };
}

function sizes(): number[] {
  const out: number[] = [];
  for (let unit = 0; unit <= 4; unit++) {
    const base = 1024 ** unit;
    for (const delta of [-2, -1, 0, 1, 2])
      out.push(Math.max(0, base + delta), Math.max(0, Math.floor(base * 1023.95) + delta));
  }
  const rng = seeded(20261115);
  for (let i = 0; i < 20000; i++) out.push(Math.floor(rng.next() ** 4 * 2 * 1024 ** 4));
  return [...new Set(out)].sort((a, b) => a - b);
}

describe('formatByteSize, properties over generated sizes', () => {
  it('reads back within the rounding of its decimal, never 1024 or more of a unit below GB, and never smaller for a larger file', () => {
    let previous = -1;
    for (const bytes of sizes()) {
      const text = formatByteSize(bytes);
      const { value, unit, bytes: shown } = readBack(text);
      if (unit === 'B') {
        expect(shown, text).toBe(bytes);
      } else {
        expect(Math.abs(shown - bytes), `${bytes} → ${text}`).toBeLessThanOrEqual(
          0.05 * FACTOR[unit] + 1e-6,
        );
      }
      if (unit !== 'GB') expect(value, `${bytes} → ${text}`).toBeLessThan(1024);
      if (unit !== 'B') expect(value, `${bytes} → ${text}`).toBeGreaterThanOrEqual(1);
      expect(shown, `${bytes} → ${text}`).toBeGreaterThanOrEqual(previous);
      previous = shown;
    }
  });
});
