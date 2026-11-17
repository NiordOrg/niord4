import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  IssueTimeline,
  IssueTimelineRow,
} from '../../../services/api/publication-series-api.service';
import type { SeriesNumbering } from './issue-number';
import { IssueTimelineStrip, gapSpanText } from './issue-timeline';

const openIssue = vi.fn<(publicId: string) => void>();
const openSeries = vi.fn<() => void>();
const timeline = signal<IssueTimeline | undefined>(undefined);
const numbering = signal<SeriesNumbering | undefined>(undefined);

@Component({
  imports: [IssueTimelineStrip],
  template: `<app-issue-timeline
    [timeline]="timeline()"
    [numbering]="numbering()"
    seriesId="weekly-ntm"
    (openIssue)="openIssue($event)"
    (openSeries)="openSeries()"
  />`,
})
class Host {
  protected readonly timeline = timeline;
  protected readonly numbering = numbering;
  protected readonly openIssue = openIssue;
  protected readonly openSeries = openSeries;
}

function strip(rows: readonly Partial<IssueTimelineRow>[], gapsEnabled = true): IssueTimeline {
  return {
    publicationSeriesId: 'weekly-ntm',
    rows: rows as readonly IssueTimelineRow[],
    gapDetection: {
      enabled: gapsEnabled,
      reasonCode: gapsEnabled ? 'CADENCED_SERIES' : 'SERIES_NOT_ACTIVE',
      reason: '',
    },
  };
}

/** Weekly unless a test says otherwise: the strip's reason for existing. */
function setup(
  value: IssueTimeline | undefined,
  series: SeriesNumbering | undefined = { cadence: 'WEEKLY', numberingScheme: 'ISO_WEEK_YEAR' },
) {
  vi.clearAllMocks();
  timeline.set(value);
  numbering.set(series);
  TestBed.configureTestingModule({
    // The cells are links now: they need a router to serialize their href from.
    providers: [provideRouter([]), provideTestTransloco(), provideNiordIcons()],
  });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.publications.timeline.cell': '{{label}} — {{status}}, {{count}} messages',
      'admin.publications.timeline.cellNoCount': '{{label}} — {{status}}',
      'admin.publications.issueStatus.PUBLISHED': 'Published',
      'admin.publications.row.MISSING': 'Missing',
      'admin.publications.row.UPCOMING': 'Upcoming',
      'admin.publications.detail.gaps.found': '{{count}} period(s) with no issue.',
    },
    'en',
  );

  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, element: fixture.nativeElement as HTMLElement };
}

function cells(element: HTMLElement): HTMLElement[] {
  return [...element.querySelectorAll<HTMLElement>('.pub-cell')];
}

describe('IssueTimelineStrip', () => {
  /** Nothing to draw is nothing at all: no empty box on a card that has no strip. */
  it('renders nothing at all while there is no strip', () => {
    const { element } = setup(undefined);

    expect(cells(element)).toEqual([]);
    expect(element.textContent?.trim()).toBe('');
  });

  /**
   * A cell per PERIOD, tinted by what happened in it.
   *
   * The synthesized cells are the point: a series that stopped producing is
   * visible from the dashboard and from nowhere else short of opening it.
   */
  it('draws a cell per period, tinted by what the period holds', () => {
    const { element } = setup(
      strip([
        { week: 35, computedStatus: 'OPEN', publicId: 'p-35' },
        { week: 34, computedStatus: 'UPCOMING' },
        { week: 33, computedStatus: 'MISSING' },
        { week: 32, computedStatus: 'PUBLISHED', publicId: 'p-32', memberCount: 214 },
      ]),
    );

    const drawn = cells(element);
    expect(drawn.map((c) => c.textContent?.trim())).toEqual(['35', '34', '33', '32']);
    // Every cell wears the shared shape, and its state class on top of it: the
    // shape is what the legend explains, and the state class is what tints it.
    expect(drawn.every((cell) => cell.classList.contains('pub-cell'))).toBe(true);
    expect(drawn[0].className).toContain('is-issue-open');
    expect(drawn[1].className).toContain('is-gap-UPCOMING');
    expect(drawn[2].className).toContain('is-gap-MISSING');
    expect(drawn[3].className).toContain('is-issue-published');
  });

  /**
   * The cell shows a week number, so everything that makes it readable has to be
   * on its label — which period, what happened, and how many messages went out.
   */
  it('names the period, its state and its size on every cell', () => {
    const { element } = setup(
      strip([
        {
          week: 32,
          year: 2026,
          label: 'Uge 32',
          computedStatus: 'PUBLISHED',
          publicId: 'p-32',
          memberCount: 214,
        },
      ]),
    );

    expect(cells(element)[0].getAttribute('aria-label')).toBe('Uge 32 — Published, 214 messages');
  });

  /** A publication that resolves nothing has no count, and must not claim zero. */
  it('leaves the count out where the publication has no membership', () => {
    const { element } = setup(
      strip([{ week: 32, label: 'Uge 32', computedStatus: 'PUBLISHED', publicId: 'p-32' }]),
    );

    expect(cells(element)[0].getAttribute('aria-label')).toBe('Uge 32 — Published');
  });

  it('opens the issue behind a real cell', () => {
    const { element, fixture } = setup(
      strip([{ week: 32, computedStatus: 'PUBLISHED', publicId: 'p-32' }]),
    );

    cells(element)[0].click();
    fixture.detectChanges();

    expect(openIssue).toHaveBeenCalledWith('p-32');
    expect(openSeries).not.toHaveBeenCalled();
  });

  /**
   * A cell leads to an address — the series, with that issue open — so it is a
   * link: it says where it goes and can be opened in a second tab. A missing
   * period addresses the series alone, which is where it can be recovered.
   */
  it('addresses the series and the issue from the cells that open one', () => {
    const { element } = setup(
      strip([
        { week: 33, computedStatus: 'MISSING' },
        { week: 32, computedStatus: 'PUBLISHED', publicId: 'p-32' },
      ]),
    );

    const [missing, published] = cells(element);
    expect(missing.getAttribute('href')).toBe('/weekly-ntm');
    expect(published.getAttribute('href')).toBe('/weekly-ntm?issue=p-32');
  });

  /**
   * A missing period has no issue to open, so its cell opens the SERIES — the
   * one screen where the period can be recovered. Left inert it would be the
   * most important cell on the strip and the only one nothing happens on.
   */
  it('sends a missing period to the series, where it can be recovered', () => {
    const { element, fixture } = setup(strip([{ week: 33, computedStatus: 'MISSING' }]));

    cells(element)[0].click();
    fixture.detectChanges();

    expect(openSeries).toHaveBeenCalledTimes(1);
    expect(openIssue).not.toHaveBeenCalled();
  });

  /**
   * A period still being worked toward is not a problem and has nothing behind
   * it, so it is not a control — a control that does nothing reads as broken.
   */
  it('leaves an upcoming period as a named, inert cell', () => {
    const { element } = setup(strip([{ week: 34, computedStatus: 'UPCOMING' }]));

    const cell = cells(element)[0];
    expect(cell.tagName).toBe('SPAN');
    expect(cell.getAttribute('role')).toBe('img');
    expect(cell.getAttribute('aria-label')).toContain('Upcoming');
  });

  it('warns with the number of periods that have no issue', () => {
    const { element } = setup(
      strip([
        { week: 33, computedStatus: 'MISSING' },
        { week: 32, computedStatus: 'MISSING' },
        { week: 31, computedStatus: 'PUBLISHED', publicId: 'p-31' },
      ]),
    );

    expect(element.textContent).toContain('2 period(s) with no issue.');
  });

  /**
   * A strip nobody examined must not read as a strip with nothing wrong.
   *
   * Every imported series is DRAFT, which closes the gate, so this is the common
   * case rather than an unusual one — and the reason it was not examined belongs
   * on the series' own screen, which can say what it is.
   */
  it('says nothing about gaps where nobody looked for them', () => {
    const { element } = setup(
      strip([{ week: 32, computedStatus: 'PUBLISHED', publicId: 'p-32' }], false),
    );

    expect(element.textContent).not.toContain('period(s) with no issue');
  });

  /**
   * A cadence-less series' cells stand for issues rather than for periods, and
   * have no number to be called by — so the cell falls back to the issue's name.
   *
   * The row still carries a week, because every issue's numbers are derived from
   * its cut-off whatever the publication is numbered by. Reading it would put a
   * bare "17" on a cell whose publication has never been called by a week.
   */
  it('falls back to the row’s own name where the series numbers nothing', () => {
    const { element } = setup(
      strip([
        { label: 'NCAGS 11', week: 17, year: 2026, computedStatus: 'PUBLISHED', publicId: 'p-1' },
      ]),
      { cadence: 'NONE' },
    );

    expect(cells(element)[0].textContent?.trim()).toBe('NCAGS 11');
  });

  /**
   * The holiday issue closes two periods at once and is called by both weeks.
   *
   * "52+1" is one issue covering both, which is why the pair is written with a
   * plus rather than as a range.
   */
  it('writes a multi-week issue as both of its weeks', () => {
    const { element } = setup(
      strip([
        { week: 52, weekTo: 1, label: 'Uge 52+1', computedStatus: 'PUBLISHED', publicId: 'p-52' },
      ]),
    );

    expect(cells(element)[0].textContent?.trim()).toBe('52+1');
  });

  /**
   * An annual is called by its year, and the week it happens to close in says
   * nothing about it — the numbers are derived from the cut-off for every issue,
   * so the strip has to be told which of them the publication is numbered by.
   */
  it('names an annual by its year, never by the week it closed in', () => {
    const { element } = setup(
      strip([
        {
          week: 1,
          year: 2026,
          label: 'Fyrliste 2026',
          computedStatus: 'PUBLISHED',
          publicId: 'p-2026',
        },
      ]),
      { cadence: 'YEARLY', numberingScheme: 'YEAR_EDITION' },
    );

    expect(cells(element)[0].textContent?.trim()).toBe('2026');
  });

  /** A year with a second edition in it needs the edition to tell them apart. */
  it('adds the edition where a year holds more than one', () => {
    const { element } = setup(
      strip([
        { week: 30, year: 2026, edition: '2', computedStatus: 'PUBLISHED', publicId: 'p-2026-2' },
      ]),
      { numberingScheme: 'YEAR_EDITION' },
    );

    expect(cells(element)[0].textContent?.trim()).toBe('2026 (2)');
  });

  /**
   * An imported weekly issue carries the same numbers a native one does.
   *
   * They are derived from the recovered cut-off by the same arithmetic, so a
   * cell has nothing to read them differently by — and a missing week is a
   * series that is not numbered by weeks, never a sign that an issue was
   * imported.
   */
  it('draws an imported weekly issue by its week like any other', () => {
    const { element } = setup(
      strip([
        {
          week: 27,
          year: 2026,
          label: 'EfS 27/2026',
          computedStatus: 'PUBLISHED',
          publicId: 'p-27',
          memberCount: 118,
        },
      ]),
    );

    const cell = cells(element)[0];
    expect(cell.textContent?.trim()).toBe('27');
    expect(cell.getAttribute('aria-label')).toBe('EfS 27/2026 — Published, 118 messages');
  });
});

/**
 * The stretch of periods that produced nothing, named by its ends.
 *
 * The count is the strip's own warning; this is what a card says instead —
 * WHICH periods, which is what decides whether the silence matters.
 */
describe('gapSpanText', () => {
  const YEARLY: SeriesNumbering = { cadence: 'YEARLY', numberingScheme: 'YEAR_EDITION' };

  it('names nothing where there is no strip at all', () => {
    expect(gapSpanText(undefined, YEARLY)).toBeUndefined();
  });

  it('names nothing where every period produced an issue', () => {
    expect(
      gapSpanText(
        strip([
          { year: 2026, computedStatus: 'PUBLISHED', publicId: 'p-26' },
          { year: 2025, computedStatus: 'PUBLISHED', publicId: 'p-25' },
        ]),
        YEARLY,
      ),
    ).toBeUndefined();
  });

  /**
   * Gaps nobody looked for are not gaps. Every imported series is DRAFT, which
   * closes the gate, so this is the common case rather than an unusual one.
   */
  it('says nothing about gaps where nobody looked for them', () => {
    expect(
      gapSpanText(strip([{ year: 2025, computedStatus: 'MISSING' }], false), YEARLY),
    ).toBeUndefined();
  });

  /** Newest first on the wire, so the span reads from the far end back. */
  it('writes an unbroken run as its two ends', () => {
    expect(
      gapSpanText(
        strip([
          { year: 2025, computedStatus: 'MISSING' },
          { year: 2024, computedStatus: 'MISSING' },
          { year: 2023, computedStatus: 'MISSING' },
          { year: 2018, computedStatus: 'PUBLISHED', publicId: 'p-18' },
        ]),
        YEARLY,
      ),
    ).toBe('2023–2025');
  });

  /** One period is one period, not a range from itself to itself. */
  it('writes a single missing period as itself', () => {
    expect(
      gapSpanText(
        strip([
          { year: 2025, computedStatus: 'MISSING' },
          { year: 2024, computedStatus: 'PUBLISHED', publicId: 'p-24' },
        ]),
        YEARLY,
      ),
    ).toBe('2025');
  });

  /**
   * A span written across a period that DID produce an issue claims something
   * untrue about it. The strip is where a scattered pattern can be read, so the
   * card says nothing rather than say it wrongly.
   */
  it('refuses to span a run that something came out in the middle of', () => {
    expect(
      gapSpanText(
        strip([
          { year: 2025, computedStatus: 'MISSING' },
          { year: 2024, computedStatus: 'PUBLISHED', publicId: 'p-24' },
          { year: 2023, computedStatus: 'MISSING' },
        ]),
        YEARLY,
      ),
    ).toBeUndefined();
  });

  /** An upcoming period ahead of the run is not part of it, and does not break it. */
  it('starts the run below the periods still being worked toward', () => {
    expect(
      gapSpanText(
        strip([
          { year: 2027, computedStatus: 'UPCOMING' },
          { year: 2026, computedStatus: 'MISSING' },
          { year: 2025, computedStatus: 'MISSING' },
        ]),
        YEARLY,
      ),
    ).toBe('2025–2026');
  });

  /** A series numbered by nothing has only its periods' names to be called by. */
  it('falls back to the period’s own name where the series numbers nothing', () => {
    expect(
      gapSpanText(
        strip([
          { label: 'Winter 2026', computedStatus: 'MISSING' },
          { label: 'Winter 2025', computedStatus: 'MISSING' },
        ]),
        { cadence: 'NONE' },
      ),
    ).toBe('Winter 2025–Winter 2026');
  });

  /** A period with neither a number nor a name cannot be named, so nothing is claimed. */
  it('names nothing where a period has no number and no name', () => {
    expect(gapSpanText(strip([{ computedStatus: 'MISSING' }]), YEARLY)).toBeUndefined();
  });
});
