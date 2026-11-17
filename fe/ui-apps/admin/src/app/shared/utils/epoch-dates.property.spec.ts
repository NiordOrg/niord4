import { describe, expect, it } from 'vitest';

import { withEpochDates } from './epoch-dates';

/**
 * Properties of the publications payloads' date normalisation, over generated instants, offsets
 * and payloads from a fixed seed (error hunt, phase 2): an ISO date-time with an offset in a declared
 * date field becomes the instant it names, at any offset and any length of fraction the pattern
 * accepts; anything else, and anything in an opaque container, is left exactly as it came; the input
 * is never changed and a container with nothing to convert comes back as the same object.
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

const pad = (n: number, width = 2): string => String(n).padStart(width, '0');

/**
 * The instant written as the backend writes it, at `offsetMinutes` east of UTC, with `fraction`
 * digits of the second (the milliseconds, then `extra` digits below a millisecond); UTC is written
 * `Z` at an odd number of digits.
 */
function iso(epoch: number, offsetMinutes: number, fraction: number, extra: string): string {
  const local = new Date(epoch + offsetMinutes * 60000);
  const ms = pad(local.getUTCMilliseconds(), 3);
  const digits = (ms + extra).slice(0, fraction);
  const offset =
    offsetMinutes === 0 && fraction % 2 === 1
      ? 'Z'
      : `${offsetMinutes < 0 ? '-' : '+'}${pad(Math.floor(Math.abs(offsetMinutes) / 60))}:${pad(Math.abs(offsetMinutes) % 60)}`;
  return (
    `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}` +
    `T${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}:${pad(local.getUTCSeconds())}` +
    (fraction > 0 ? `.${digits}` : '') +
    offset
  );
}

function instant(rng: Rng): number {
  return rng.int(Date.UTC(1990, 0, 1), Date.UTC(2060, 0, 1));
}

function offset(rng: Rng): number {
  return rng.pick([
    -12 * 60,
    -9 * 60 - 30,
    -3 * 60 - 30,
    -3 * 60,
    -2 * 60,
    0,
    60,
    2 * 60,
    5 * 60 + 45,
    14 * 60,
  ]);
}

describe('withEpochDates, properties over generated payloads', () => {
  it('turns a declared date with an offset into the instant it names, at any offset, with 1 to 3 fraction digits or none', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 20000; i++) {
      const epoch = instant(rng);
      const fraction = rng.pick([0, 1, 2, 3]);
      const text = iso(epoch, offset(rng), fraction, '');
      const expected = Math.floor(epoch / 10 ** (3 - fraction)) * 10 ** (3 - fraction);
      expect(withEpochDates({ cutoff: text }).cutoff, text).toBe(
        fraction === 0 ? Math.floor(epoch / 1000) * 1000 : expected,
      );
    }
  });

  it('turns a date with 4 to 9 fraction digits into its instant to the millisecond', () => {
    // The pattern accepts any length of fraction "so a server that widens its formatter does not
    // silently stop being understood"; the instant must then not be left a string either.
    const rng = seeded(20261116);
    const failures: string[] = [];
    for (let i = 0; i < 5000; i++) {
      const epoch = instant(rng);
      const fraction = rng.int(4, 9);
      const text = iso(epoch, offset(rng), fraction, String(rng.int(100000, 999999)));
      const converted: unknown = withEpochDates({ publishedAt: text }).publishedAt;
      if (converted !== epoch) failures.push(`${text} -> ${String(converted)}`);
    }
    expect(failures.slice(0, 5)).toEqual([]);
  });

  it('leaves every other value, and everything inside an opaque container, exactly as it came', () => {
    const rng = seeded(20261117);
    for (let i = 0; i < 5000; i++) {
      const date = iso(instant(rng), offset(rng), 3, '');
      const wallClock = date.replace(/(Z|[+-]\d{2}:\d{2})$/, '');
      const payload = {
        name: date,
        status: 'OPEN',
        cutoff: rng.pick([
          wallClock,
          `${date} `,
          '2026-13-01T00:00:00.000+01:00',
          42,
          null,
          undefined,
          '',
        ]),
        reportParams: { publishedAt: date, cutoff: date },
        criteria: [{ cutoff: date }],
        names: { da: date },
        rows: [{ id: 1, label: date }],
      };
      const frozen = JSON.stringify(payload);
      const out = withEpochDates(payload);
      expect(out, frozen).toBe(payload);
      expect(JSON.stringify(payload)).toBe(frozen);
    }
  });

  it('converts declared dates at any depth without touching the input, sharing what has nothing to convert', () => {
    const rng = seeded(20261118);
    for (let i = 0; i < 3000; i++) {
      const epoch = instant(rng);
      const untouched = { id: rng.int(1, 9), label: 'x' };
      const payload = {
        series: {
          issues: [
            { publicId: 'a', intervalFrom: iso(epoch, offset(rng), 3, ''), meta: untouched },
          ],
        },
        other: untouched,
      };
      const frozen = JSON.stringify(payload);
      const out = withEpochDates(payload);
      expect(out.series.issues[0].intervalFrom).toBe(epoch);
      expect(out.other).toBe(untouched);
      expect(out.series.issues[0].meta).toBe(untouched);
      expect(JSON.stringify(payload)).toBe(frozen);
    }
  });
});
