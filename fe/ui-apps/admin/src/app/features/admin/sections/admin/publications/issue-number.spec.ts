import { describe, expect, it } from 'vitest';

import { issueNumberText } from './issue-number';

/**
 * The question is asked of the series and the issue TOGETHER.
 *
 * Every issue carries a week, a year and sometimes an edition, all derived from
 * the same cut-off — so an issue on its own cannot say which of them names it,
 * and the shapes below are the whole of what separates one publication's cells
 * from another's.
 */
describe('issueNumberText', () => {
  const weekly = { cadence: 'WEEKLY' } as const;
  const annual = { numberingScheme: 'YEAR_EDITION' } as const;

  it('names a weekly period by its week', () => {
    expect(issueNumberText({ week: 27, year: 2026 }, weekly)).toBe('27');
  });

  /**
   * The holiday issue closes two periods at once and is called by both weeks.
   * One issue covering both, not a range — which is why it is not a dash.
   */
  it('names a multi-week period by both weeks', () => {
    expect(issueNumberText({ week: 52, weekTo: 1, year: 2026 }, weekly)).toBe('52+1');
    expect(issueNumberText({ week: 36, weekTo: 37, year: 2026 }, weekly)).toBe('36+37');
  });

  /**
   * An issue of three weeks or more has only its first and its last week to
   * print, and "36+38" reads as weeks 36 and 38: it is written as the range.
   * Across a year's end the earlier year's length counts — 2025 has 52 ISO
   * weeks and 2026 has 53.
   */
  it('names a period of three weeks or more as a range', () => {
    expect(issueNumberText({ week: 36, weekTo: 38, year: 2026 }, weekly)).toBe('36-38');
    expect(issueNumberText({ week: 35, weekTo: 38, year: 2026 }, weekly)).toBe('35-38');
    expect(issueNumberText({ week: 52, weekTo: 2, year: 2026 }, weekly)).toBe('52-2');
    expect(issueNumberText({ week: 52, weekTo: 1, year: 2027 }, weekly)).toBe('52-1');
    expect(issueNumberText({ week: 53, weekTo: 1, year: 2027 }, weekly)).toBe('53+1');
    expect(issueNumberText({ week: 53, weekTo: 2, year: 2027 }, weekly)).toBe('53-2');
  });

  it('names an annual by its year, ignoring the week it closed in', () => {
    expect(issueNumberText({ week: 1, year: 2026 }, annual)).toBe('2026');
  });

  /** The first edition of a year adds nothing, and invites a search for a second. */
  it('leaves the first edition off', () => {
    expect(issueNumberText({ year: 2026, edition: '1' }, annual)).toBe('2026');
    expect(issueNumberText({ year: 2026, edition: '   ' }, annual)).toBe('2026');
  });

  it('adds the edition where a year holds more than one', () => {
    expect(issueNumberText({ year: 2026, edition: '2' }, annual)).toBe('2026 (2)');
  });

  /** An edition written as words was typed to tell two issues apart. Show it. */
  it('keeps an edition it cannot compare to the first', () => {
    expect(issueNumberText({ year: 2026, edition: 'B' }, annual)).toBe('2026 (B)');
  });

  /**
   * The scheme is the answer to this question; the cadence answers a different
   * one and only fills a gap where a series has no scheme. `NONE` on a weekly
   * series was chosen deliberately and must not be overruled by the cadence.
   */
  it('lets the numbering scheme overrule the cadence', () => {
    expect(issueNumberText({ week: 27, year: 2026 }, { cadence: 'WEEKLY' })).toBe('27');
    expect(
      issueNumberText({ week: 27, year: 2026 }, { cadence: 'WEEKLY', numberingScheme: 'NONE' }),
    ).toBeUndefined();
    expect(
      issueNumberText(
        { week: 27, year: 2026 },
        { cadence: 'WEEKLY', numberingScheme: 'YEAR_EDITION' },
      ),
    ).toBe('2026');
  });

  /** Nothing to say, rather than a number nobody asked for. */
  it('answers with nothing where the series is numbered by neither', () => {
    expect(issueNumberText({ week: 27, year: 2026 }, { cadence: 'MONTHLY' })).toBeUndefined();
    expect(issueNumberText({ week: 27, year: 2026 }, { cadence: 'NONE' })).toBeUndefined();
    expect(issueNumberText({ week: 27, year: 2026 }, undefined)).toBeUndefined();
    expect(issueNumberText(undefined, weekly)).toBeUndefined();
  });

  /**
   * A missing number is a missing number, never a claim about the issue.
   *
   * An imported issue carries the same week a native one does — derived from the
   * recovered cut-off by the same arithmetic — so nothing may read the absence
   * of one as the mark of an import.
   */
  it('answers with nothing where the number the series wants is absent', () => {
    expect(issueNumberText({ year: 2026 }, weekly)).toBeUndefined();
    expect(issueNumberText({ week: 27 }, annual)).toBeUndefined();
  });

  /** Week zero is a number like any other, and `0` is not "no week". */
  it('treats a zero as a number rather than as an absence', () => {
    expect(issueNumberText({ week: 0 }, weekly)).toBe('0');
    expect(issueNumberText({ year: 0 }, annual)).toBe('0');
  });
});
