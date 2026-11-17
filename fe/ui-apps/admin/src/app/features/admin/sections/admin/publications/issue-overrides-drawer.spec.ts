import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestLanguages } from '../../../../../shared/testing/language-testing';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { IssueOverridesDrawer, type IssueOverridesPatch } from './issue-overrides-drawer';

const BASE = '/rest';

const SERIES = {
  seriesId: 'weekly-ntm',
  status: 'ACTIVE',
  kind: 'SCHEDULED',
  cadence: 'WEEKLY',
  contentMode: 'GENERATED_FROM_QUERY',
  domainId: 'niord-nm',
  languages: ['da', 'en'],
  reportId: 'nm-tp-report',
  reportParams: { overskrift: 'Aktive P&T', volume: 133 },
  descs: [
    {
      lang: 'da',
      name: 'Aktive P&T',
      nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
      fileNamePattern: 'EfS-PT-Uge-${week}-${year}.pdf',
    },
    {
      lang: 'en',
      name: 'Active P&T',
      nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
      fileNamePattern: 'NtM-PT-Week-${week}-${year}.pdf',
    },
  ],
} as unknown as PublicationSeries;

/**
 * A series that writes the edition into the name.
 *
 * The one piece of numbering the cut-off does not derive, and the only one a
 * pattern can be written from that a reader can also type.
 */
const EDITIONED = {
  ...SERIES,
  descs: [
    {
      lang: 'da',
      name: 'Aktive P&T',
      nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year} (${edition})',
      fileNamePattern: 'EfS-PT-Uge-${week}-${year}.pdf',
    },
    {
      lang: 'en',
      name: 'Active P&T',
      nameSuggestionPattern: 'Active P&T Week ${week} - ${year} (${edition})',
      fileNamePattern: 'NtM-PT-Week-${week}-${year}.pdf',
    },
  ],
} as unknown as PublicationSeries;

/**
 * A yearly compilation: it prints a year and an edition, and no week.
 *
 * Its issues still derive a week from their cut-offs — every interval closes in
 * some ISO week — and that number names nothing about the document.
 */
const YEARLY = {
  ...SERIES,
  cadence: 'YEARLY',
  numberingScheme: 'YEAR_EDITION',
} as unknown as PublicationSeries;

/** An open issue that says exactly what its series says. */
const CLEAN = {
  publicId: 'i-38',
  seriesId: 'weekly-ntm',
  status: 'OPEN',
  version: 4,
  week: 38,
  year: 2026,
  edition: '1',
  intervalTo: Date.UTC(2026, 8, 16, 10, 0),
  descs: [
    { lang: 'da', name: 'Aktive P&T uge 38 - 2026' },
    { lang: 'en', name: 'Active P&T Week 38 - 2026' },
  ],
} as unknown as PublicationIssue;

/** The same issue, named as the series that writes the edition would name it. */
const EDITIONED_ISSUE = {
  ...CLEAN,
  descs: [
    { lang: 'da', name: 'Aktive P&T uge 38 - 2026 (1)' },
    { lang: 'en', name: 'Active P&T Week 38 - 2026 (1)' },
  ],
} as unknown as PublicationIssue;

/**
 * The same issue with three of the four groups tailored — the state the design
 * shows as "3 tailored".
 *
 * English is the tailored language because the default build lists English
 * first, and every per-language row on this screen renders in the configured
 * order: a fixture that tailored the second row would be asserting a position
 * rather than a behaviour.
 */
const TAILORED = {
  ...CLEAN,
  week: 36,
  weekTo: 37,
  weekLabel: '36+37',
  descs: [
    // Renumbered by the server when the label was set, and still following the
    // series — which is exactly the desc a reset must not quietly pin.
    { lang: 'da', name: 'Aktive P&T uge 36+37 - 2026' },
    {
      lang: 'en',
      name: 'Active P&T — double week',
      nameOverridden: true,
      fileName: 'NtM-PT-Double.pdf',
      fileNameOverridden: true,
    },
  ],
} as unknown as PublicationIssue;

const confirmDiscard = vi.fn(() => Promise.resolve(true));

/**
 * The drawer, open on one issue.
 *
 * The series is a tuple rest rather than a defaulted parameter because the
 * drawer's "still loading" degradation is an ABSENT series, and a default would
 * swallow the very `undefined` those tests are about.
 */
async function setup(issue: PublicationIssue, ...withSeries: [PublicationSeries | undefined] | []) {
  const series = withSeries.length === 1 ? withSeries[0] : SERIES;
  confirmDiscard.mockClear();
  TestBed.configureTestingModule({
    imports: [IssueOverridesDrawer],
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: BASE, languages: ['da', 'en'] } },
      provideTestLanguages(),
    ],
  });

  const fixture = TestBed.createComponent(IssueOverridesDrawer);
  fixture.componentRef.setInput('issue', issue);
  fixture.componentRef.setInput('series', series);
  fixture.componentRef.setInput('open', true);
  fixture.autoDetectChanges();
  await fixture.whenStable();

  // The report picker loads on construction; it is not what these assert.
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting.match(() => true).forEach((request) => request.flush([]));
  await fixture.whenStable();

  const saved: IssueOverridesPatch[] = [];
  fixture.componentInstance.save.subscribe((patch) => saved.push(patch));
  return { fixture, saved };
}

/** The drawer's pane, which the CDK renders outside the component's own host. */
function panel(): HTMLElement {
  const found = document.querySelector('.niord-drawer-panel');
  if (!found) {
    throw new Error('the drawer did not render');
  }
  return found as HTMLElement;
}

function field(id: string): HTMLInputElement {
  const found = panel().querySelector<HTMLInputElement>(`#${id}`);
  if (!found) {
    throw new Error(`no control ${id}`);
  }
  return found;
}

function type(input: HTMLInputElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

/** One control by its accessible name, which is how a language row is addressed. */
function byLabel(label: string): HTMLInputElement {
  const found = panel().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
  if (!found) {
    throw new Error(`no control labelled ${label}`);
  }
  return found;
}

function testId(id: string): HTMLElement | null {
  return panel().querySelector<HTMLElement>(`[data-testid="${id}"]`);
}

/** Whether the per-language mark stands beside one box. */
function marked(id: string): boolean {
  return !!field(id).parentElement?.querySelector('.status-badge');
}

/**
 * How many of the drawer's SECTIONS wear the mark — which is what the line
 * under the title counts. A section is marked in its own caption; the marks
 * beside the language rows are facts inside one section.
 */
function sectionMarks(): number {
  return panel().querySelectorAll('legend > .status-badge, label > .status-badge').length;
}

/** Every button in the drawer whose text is the given key's translation. */
function buttons(text: string): HTMLButtonElement[] {
  return [...panel().querySelectorAll('button')].filter(
    (button) => button.textContent?.trim() === text,
  ) as HTMLButtonElement[];
}

async function save(fixture: Awaited<ReturnType<typeof setup>>['fixture']): Promise<void> {
  const submit = buttons('admin.publications.overrides.save')[0];
  submit.click();
  await fixture.whenStable();
}

afterEach(() => {
  document.body.replaceChildren();
  TestBed.resetTestingModule();
});

describe('the tailoring drawer', () => {
  it('renders the four groups the issue can differ in, in the order they are read', async () => {
    await setup(CLEAN);
    const order = [...panel().querySelectorAll('[data-testid^="issue-overrides-"]')]
      .map((element) => element.getAttribute('data-testid'))
      .filter((id) => id !== 'issue-overrides-count' && id !== 'issue-overrides-reset-all');
    expect(order).toEqual([
      'issue-overrides-name',
      'issue-overrides-file-name',
      'issue-overrides-numbering',
      'issue-overrides-report',
      'issue-overrides-params',
    ]);
  });

  describe('the quiet line', () => {
    it('is absent on an issue that follows its series in every group', async () => {
      await setup(CLEAN);
      expect(testId('issue-overrides-count')).toBeNull();
    });

    it('appears with the count once something is tailored', async () => {
      await setup(TAILORED);
      const line = testId('issue-overrides-count');
      expect(line).not.toBeNull();
      // Name, file name and numbering — three of the four sections.
      expect(line?.textContent).toContain('admin.publications.overrides.countBadge');
      expect(line?.textContent).toContain('admin.publications.overrides.countNote');
    });

    /**
     * The mark follows the BOX, not the last save. A line that waited for a
     * refetch would count something other than what the reader can see.
     */
    it('appears the moment a box stops agreeing with the series', async () => {
      const { fixture } = await setup(CLEAN);
      expect(testId('issue-overrides-count')).toBeNull();
      type(field('issue-override-week'), '38+39');
      await fixture.whenStable();
      expect(testId('issue-overrides-count')).not.toBeNull();
      // ONE section. The name and the file name moved WITH the label rather than
      // away from the series, so neither of them is tailored by a week being
      // written differently.
      expect(sectionMarks()).toBe(1);
    });
  });

  /**
   * A box only for a label this series' documents print.
   *
   * The three numbers are derived from the cut-off whatever the publication is
   * numbered by, so drawing all three always would offer a yearly compilation a
   * week box whose text no document would ever carry.
   */
  describe('the boxes the series prints', () => {
    /** The stale label the yearly cases turn on — a week nothing prints. */
    const STALE_WEEK = { ...CLEAN, week: 36, weekTo: 37, weekLabel: '36+37' } as PublicationIssue;

    it('draws a year and an edition, and no week, for a yearly series', async () => {
      const { fixture, saved } = await setup(STALE_WEEK, YEARLY);
      expect(panel().querySelector('#issue-override-week')).toBeNull();
      expect(panel().querySelector('#issue-override-year')).not.toBeNull();
      expect(panel().querySelector('#issue-override-edition')).not.toBeNull();

      // A label with no box is not a tailoring of anything a reader can see.
      expect(testId('issue-overrides-count')).toBeNull();

      // And a save has no opinion about it: the year it was offered goes, the
      // week it never offered is left exactly as it stood.
      type(field('issue-override-year'), '2026/27');
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ yearLabel: '2026/27' }]);
    });

    it('draws all three for a weekly series', async () => {
      await setup(CLEAN);
      expect(panel().querySelector('#issue-override-week')).not.toBeNull();
      expect(panel().querySelector('#issue-override-year')).not.toBeNull();
      expect(panel().querySelector('#issue-override-edition')).not.toBeNull();
    });

    /** Nothing to compare against degrades to offering everything, as elsewhere. */
    it('draws all three while the series is still unknown', async () => {
      await setup(CLEAN, undefined);
      expect(panel().querySelector('#issue-override-week')).not.toBeNull();
      expect(panel().querySelector('#issue-override-year')).not.toBeNull();
      expect(panel().querySelector('#issue-override-edition')).not.toBeNull();
    });
  });

  describe('the numbering', () => {
    it('seeds the boxes from the printed numbering', async () => {
      await setup(TAILORED);
      expect(field('issue-override-week').value).toBe('36+37');
      expect(field('issue-override-year').value).toBe('2026');
      // Read off the issue, never invented — see the absent-edition case below.
      expect(field('issue-override-edition').value).toBe('1');
    });

    /**
     * A two-week issue states its span ONCE, in the week, which is free text.
     * The second week the cut-off derived is untouched and goes on reaching
     * every `${weekTo}` in a pattern; there is simply no second box to write a
     * different one into, and so no second value to disagree with the first.
     */
    it('offers no box for the second week', async () => {
      const { fixture, saved } = await setup(TAILORED);
      expect(panel().querySelector('#issue-override-week-to')).toBeNull();
      expect(panel().textContent).not.toContain('admin.publications.overrides.weekTo');

      // And nothing about a second week reaches the wire, whatever is typed.
      type(field('issue-override-week'), '36 & 37');
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36 & 37' }]);
    });

    /**
     * The whole reason the labels exist. Every box here is free text: `type` is
     * `text`, nothing validates a number, and a week written `36 & 37` saves.
     */
    it('takes free text and never asks for a number', async () => {
      const { fixture, saved } = await setup(CLEAN);
      const week = field('issue-override-week');
      expect(week.type).toBe('text');
      expect(week.getAttribute('inputmode')).toBeNull();
      expect(week.getAttribute('pattern')).toBeNull();

      type(week, '36 & 37');
      await fixture.whenStable();
      expect(panel().querySelector('[role="alert"]')).toBeNull();

      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36 & 37' }]);
    });

    /**
     * A label typed back to the derived number is no label at all — and the
     * empty string is what says so on the wire. Not `null`: absent and null are
     * one value by the time the server has read the body, so a null clear would
     * make every partial save wipe the fields it did not mention.
     */
    it('clears a label that has been typed back to the derived number', async () => {
      const { fixture, saved } = await setup(TAILORED);
      type(field('issue-override-week'), '36');
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '' }]);
    });

    it('refuses a blank edition in place rather than after the save', async () => {
      const { fixture } = await setup(CLEAN);
      type(field('issue-override-edition'), '   ');
      await fixture.whenStable();
      expect(panel().querySelector('[role="alert"]')?.textContent).toContain(
        'admin.publications.issue.editionRequired',
      );
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /**
     * An issue that never recorded an edition is not an issue with a mistake in
     * it. The box is empty, nothing is sent for it, and everything else in the
     * drawer still saves — the alternative locks a file name behind a number
     * nobody ever chose.
     */
    it('does not call an absent edition an error', async () => {
      const { fixture, saved } = await setup({ ...TAILORED, edition: undefined });
      expect(field('issue-override-edition').value).toBe('');
      expect(panel().textContent).not.toContain('admin.publications.issue.editionRequired');
      type(field('issue-override-file-name-da'), 'EfS-Saerudgave.pdf');
      await fixture.whenStable();
      await save(fixture);
      expect(saved[0].edition).toBeUndefined();
      expect(saved[0].fileNames).toEqual({ da: 'EfS-Saerudgave.pdf' });
    });
  });

  /**
   * The name is the series' pattern rendered through the numbering in the boxes
   * above it — until somebody types over it, per language.
   *
   * The hint under the boxes promises exactly this: rewrite the numbering and
   * the name follows. The pin the server keeps is what says a language has
   * stopped following; a name is always stored, so the text itself says nothing.
   */
  describe('the name', () => {
    it('holds what the series would call the issue, in every language', async () => {
      await setup(CLEAN);
      const da = field('issue-override-name-da');
      const en = field('issue-override-name-en');
      expect(da.value).toBe('Aktive P&T uge 38 - 2026');
      expect(en.value).toBe('Active P&T Week 38 - 2026');
      // Real text, so there is nothing left for a placeholder to say.
      expect(da.getAttribute('placeholder')).toBeNull();
      expect(en.getAttribute('placeholder')).toBeNull();
      // Holding the suggestion is not tailoring, and there is nothing to save.
      expect(testId('issue-overrides-count')).toBeNull();
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    it('follows the numbering while nobody has touched it', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 36+37 - 2026');
      expect(field('issue-override-name-en').value).toBe('Active P&T Week 36+37 - 2026');
      expect(marked('issue-override-name-da')).toBe(false);
      expect(marked('issue-override-name-en')).toBe(false);
      expect(sectionMarks()).toBe(1);
      // Followed, not pinned: the label is the only thing this save carries, and
      // the server derives the names from it.
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37' }]);
    });

    it('stops following the language that was typed, and only that one', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-name-da'), 'Aktive P&T - saerudgave');
      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(field('issue-override-name-da').value).toBe('Aktive P&T - saerudgave');
      expect(field('issue-override-name-en').value).toBe('Active P&T Week 36+37 - 2026');
      expect(marked('issue-override-name-da')).toBe(true);
      expect(marked('issue-override-name-en')).toBe(false);

      await save(fixture);
      expect(saved).toEqual([{ names: { da: 'Aktive P&T - saerudgave' }, weekLabel: '36+37' }]);
    });

    /** Typing the suggestion back is the same act as never having typed. */
    it('follows again once the box says what the series says', async () => {
      const { fixture } = await setup(CLEAN);
      const en = field('issue-override-name-en');
      type(en, 'Active P&T - double week');
      await fixture.whenStable();
      expect(marked('issue-override-name-en')).toBe(true);

      type(en, 'Active P&T Week 38 - 2026');
      await fixture.whenStable();
      expect(marked('issue-override-name-en')).toBe(false);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /**
     * A name is never absent — the column is never empty and a nameless issue
     * cannot be found in any list that shows it — so emptying the box can only
     * mean "let the series name it again", and the box says so at once.
     */
    it('snaps back to the suggestion when the box is emptied', async () => {
      const { fixture } = await setup(CLEAN);
      const da = field('issue-override-name-da');
      type(da, '');
      await fixture.whenStable();
      expect(da.value).toBe('Aktive P&T uge 38 - 2026');
      expect(marked('issue-override-name-da')).toBe(false);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /**
     * A name pinned to the very text the pattern renders today is still pinned:
     * the server stops re-deriving it, so the next time the numbering moves the
     * issue keeps the old name. The drawer says so — the field wears its mark and
     * the reset stands ready to take the pin back — while nothing is offered to
     * SAVE, because nothing has changed.
     */
    it('shows the pin on a stored name that agrees with the suggestion', async () => {
      const { fixture, saved } = await setup({
        ...CLEAN,
        descs: [
          { lang: 'da', name: 'Aktive P&T uge 38 - 2026', nameOverridden: true },
          { lang: 'en', name: 'Active P&T Week 38 - 2026' },
        ],
      } as unknown as PublicationIssue);
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 38 - 2026');
      expect(marked('issue-override-name-da')).toBe(true);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      expect((testId('issue-overrides-reset-all') as HTMLButtonElement).disabled).toBe(false);

      // The name's is the only control offered, and it is the pin it takes back.
      const follow = buttons('admin.publications.overrides.followSeries');
      expect(follow).toHaveLength(1);
      follow[0].click();
      await fixture.whenStable();
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 38 - 2026');
      expect(marked('issue-override-name-da')).toBe(false);
      await save(fixture);
      expect(saved).toEqual([{ names: { da: '' } }]);
    });

    it('leaves a pinned name where it is when the numbering moves', async () => {
      const { fixture, saved } = await setup(TAILORED);
      type(field('issue-override-week'), '36+37+38');
      await fixture.whenStable();
      expect(field('issue-override-name-en').value).toBe('Active P&T — double week');
      // And the language that never stopped following moves with the label.
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 36+37+38 - 2026');
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37+38' }]);
    });

    /**
     * A pattern written from the cut-off's month or day cannot be expanded here,
     * and a half-expanded name would be typed straight into the box by the next
     * keystroke. The box holds the name on file instead, and the mark rests on
     * the server's flag, which is the only answer left.
     */
    it('holds the stored name where the pattern cannot be expanded', async () => {
      const monthly = {
        ...SERIES,
        descs: [
          { lang: 'da', name: 'Aktive P&T', nameSuggestionPattern: 'EfS ${month}/${year}' },
          { lang: 'en', name: 'Active P&T', nameSuggestionPattern: 'NtM ${month}/${year}' },
        ],
      } as unknown as PublicationSeries;
      await setup(
        {
          ...CLEAN,
          descs: [
            { lang: 'da', name: 'EfS 09/2026', nameOverridden: true },
            { lang: 'en', name: 'NtM 09/2026' },
          ],
        } as unknown as PublicationIssue,
        monthly,
      );
      expect(field('issue-override-name-da').value).toBe('EfS 09/2026');
      expect(field('issue-override-name-en').value).toBe('NtM 09/2026');
      expect(marked('issue-override-name-da')).toBe(true);
      expect(marked('issue-override-name-en')).toBe(false);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /**
     * The suggestion MOVES, so what counts as "typed over" cannot be settled at
     * the keystroke that typed it: a name written by hand under one week label
     * is the series' own name once the label is rewritten to the one it was
     * written for. The mark goes out, and what is SENT has to say the same — a
     * save that pinned it here would pin a name nobody was told about.
     */
    it('sends nothing for a typed name the numbering has caught up with', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-name-en'), 'Active P&T Week 36+37 - 2026');
      await fixture.whenStable();
      expect(marked('issue-override-name-en')).toBe(true);

      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(marked('issue-override-name-en')).toBe(false);
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37' }]);
    });

    /**
     * The edition is numbering too, and a pattern may be written from it. It is
     * not derived from the cut-off — an issue that came out twice has a second
     * edition — so nothing about the name is tailored by it moving.
     */
    it('moves with the edition, which the pattern can be written from', async () => {
      const { fixture, saved } = await setup(EDITIONED_ISSUE, EDITIONED);
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 38 - 2026 (1)');

      type(field('issue-override-edition'), '2');
      await fixture.whenStable();
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 38 - 2026 (2)');
      expect(field('issue-override-name-en').value).toBe('Active P&T Week 38 - 2026 (2)');
      expect(marked('issue-override-name-da')).toBe(false);
      expect(marked('issue-override-name-en')).toBe(false);

      // The edition alone: the names followed it rather than being pinned to
      // where it took them.
      await save(fixture);
      expect(saved).toEqual([{ edition: '2' }]);
    });

    /**
     * A free-text edition is not a number, and the server expands `${edition}`
     * from the number — so a name written from it cannot be expanded at all
     * here. The box holds the name on file rather than one with a hole in it.
     */
    it('holds the stored name where the edition is not a number', async () => {
      const { fixture } = await setup(EDITIONED_ISSUE, EDITIONED);
      type(field('issue-override-edition'), '2b');
      await fixture.whenStable();
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 38 - 2026 (1)');
      expect(field('issue-override-name-en').value).toBe('Active P&T Week 38 - 2026 (1)');
      expect(marked('issue-override-name-da')).toBe(false);
      expect(marked('issue-override-name-en')).toBe(false);
    });
  });

  /**
   * The file name is text somebody can edit, not a prompt behind an empty box.
   *
   * The box carries the name the pattern would give this issue, so a name that
   * is nearly right takes one keystroke — and it follows the series for exactly
   * as long as it still says what the series would say.
   */
  describe('the file name', () => {
    it('holds what the series would name the file, in every language', async () => {
      await setup(CLEAN);
      const da = field('issue-override-file-name-da');
      const en = field('issue-override-file-name-en');
      expect(da.value).toBe('EfS-PT-Uge-38-2026.pdf');
      expect(en.value).toBe('NtM-PT-Week-38-2026.pdf');
      // Real text, so there is nothing left for a placeholder to say.
      expect(da.getAttribute('placeholder')).toBeNull();
      expect(en.getAttribute('placeholder')).toBeNull();
      // Holding the suggestion is not tailoring, and there is nothing to save.
      expect(testId('issue-overrides-count')).toBeNull();
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    it('follows the numbering while nobody has touched it', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-36+37-2026.pdf');
      expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-36+37-2026.pdf');
      // Followed, not pinned: the label is the only thing this save carries.
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37' }]);
    });

    /**
     * An upload writes the file name without pinning it, and so does a release.
     * Neither is a decision about what the file should be called, so reading the
     * stored text as one would freeze the box of every open issue carrying a
     * draft document — and mark it tailored for a name it is still following.
     */
    it('follows the numbering past a stored name that carries no pin', async () => {
      const { fixture, saved } = await setup({
        ...CLEAN,
        descs: [
          { lang: 'da', name: 'Aktive P&T uge 38 - 2026', fileName: 'EfS-udkast.pdf' },
          { lang: 'en', name: 'Active P&T Week 38 - 2026' },
        ],
      } as unknown as PublicationIssue);
      expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-38-2026.pdf');
      expect(marked('issue-override-file-name-da')).toBe(false);
      expect(testId('issue-overrides-count')).toBeNull();
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);

      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-36+37-2026.pdf');
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37' }]);
    });

    /**
     * A series naming no file-name pattern leaves nothing to expand, and the
     * document is then written under the name the issue already carries. An
     * empty box would say it has no name at all — and the one thing it must not
     * say is something other than what will be written.
     */
    it('holds the stored file name where the series names no pattern', async () => {
      const unnamedFiles = {
        ...SERIES,
        descs: [
          {
            lang: 'da',
            name: 'Aktive P&T',
            nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
          },
          {
            lang: 'en',
            name: 'Active P&T',
            nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
          },
        ],
      } as unknown as PublicationSeries;
      await setup(
        {
          ...CLEAN,
          descs: [
            { lang: 'da', name: 'Aktive P&T uge 38 - 2026', fileName: 'EfS-udkast.pdf' },
            { lang: 'en', name: 'Active P&T Week 38 - 2026' },
          ],
        } as unknown as PublicationIssue,
        unnamedFiles,
      );
      expect(field('issue-override-file-name-da').value).toBe('EfS-udkast.pdf');
      // No pattern to disagree with and no pin, so the name on file is a fact
      // about the issue rather than something it says differently.
      expect(marked('issue-override-file-name-da')).toBe(false);
      expect(testId('issue-overrides-count')).toBeNull();
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /**
     * The same reading the name is given: the suggestion MOVES, so a file name
     * written by hand under one week label is the series' own the moment the
     * label is rewritten to the one it was written for. The mark goes out, and
     * the save agrees rather than pinning a name nobody was told about.
     */
    it('sends nothing for a typed file name the numbering has caught up with', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-file-name-en'), 'NtM-PT-Week-36+37-2026.pdf');
      await fixture.whenStable();
      expect(marked('issue-override-file-name-en')).toBe(true);

      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(marked('issue-override-file-name-en')).toBe(false);
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '36+37' }]);
    });

    it('stops following the language that was typed, and only that one', async () => {
      const { fixture, saved } = await setup(CLEAN);
      type(field('issue-override-file-name-da'), 'EfS-Saerudgave.pdf');
      type(field('issue-override-week'), '36+37');
      await fixture.whenStable();
      expect(field('issue-override-file-name-da').value).toBe('EfS-Saerudgave.pdf');
      expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-36+37-2026.pdf');
      expect(marked('issue-override-file-name-da')).toBe(true);
      expect(marked('issue-override-file-name-en')).toBe(false);

      await save(fixture);
      expect(saved).toEqual([{ fileNames: { da: 'EfS-Saerudgave.pdf' }, weekLabel: '36+37' }]);
    });

    /**
     * A series may be saved with a file-name pattern that leaves out `.pdf`, and
     * the server adds it to every file name it writes. The box offers the name
     * the server will write, so a stored name it wrote is not read as tailored.
     */
    describe('under a pattern written without .pdf', () => {
      const NO_EXTENSION = {
        ...SERIES,
        descs: [
          {
            lang: 'da',
            name: 'Aktive P&T',
            nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
            fileNamePattern: 'EfS-PT-Uge-${week}-${year}',
          },
          {
            lang: 'en',
            name: 'Active P&T',
            nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
            fileNamePattern: 'NtM-PT-Week-${week}-${year}',
          },
        ],
      } as unknown as PublicationSeries;

      /** The issue with the names the server stored for it. */
      const STORED = {
        ...CLEAN,
        descs: [
          { lang: 'da', name: 'Aktive P&T uge 38 - 2026', fileName: 'EfS-PT-Uge-38-2026.pdf' },
          { lang: 'en', name: 'Active P&T Week 38 - 2026', fileName: 'NtM-PT-Week-38-2026.pdf' },
        ],
      } as unknown as PublicationIssue;

      it('suggests the name with .pdf, as the server writes it', async () => {
        await setup(STORED, NO_EXTENSION);
        expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-38-2026.pdf');
        expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-38-2026.pdf');
        expect(marked('issue-override-file-name-da')).toBe(false);
        expect(marked('issue-override-file-name-en')).toBe(false);
        expect(testId('issue-overrides-count')).toBeNull();
        expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      });

      it('reads the series name typed without .pdf as following the series', async () => {
        const { fixture } = await setup(STORED, NO_EXTENSION);
        // Typed as an officer types it, one keystroke at a time, so the box
        // holds an edit of its own before it reaches the series' name.
        type(field('issue-override-file-name-en'), 'NtM-PT-Week-38-202');
        await fixture.whenStable();
        type(field('issue-override-file-name-en'), 'NtM-PT-Week-38-2026');
        await fixture.whenStable();
        // The box keeps what was typed: rewriting it to the suggestion would put
        // the caret after `.pdf`, where the next keystroke lands.
        expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-38-2026');
        expect(marked('issue-override-file-name-en')).toBe(false);
        expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      });

      /**
       * Typing on past the series' name: the text the officer reached on the way
       * is not a reason to rewrite the box under the caret.
       */
      it('keeps typing past the series name without .pdf in the box', async () => {
        const { fixture, saved } = await setup(STORED, NO_EXTENSION);
        const en = field('issue-override-file-name-en');
        type(en, 'NtM-PT-Week-38-202');
        await fixture.whenStable();
        type(en, `${en.value}6`);
        await fixture.whenStable();
        type(en, `${en.value}b`);
        await fixture.whenStable();
        expect(en.value).toBe('NtM-PT-Week-38-2026b');
        expect(marked('issue-override-file-name-en')).toBe(true);
        await save(fixture);
        expect(saved).toEqual([{ fileNames: { en: 'NtM-PT-Week-38-2026b' } }]);
      });

      /**
       * Two languages the series itself names the same are a fact about the
       * series, not something this box can fix — typed without `.pdf` or with it.
       */
      it('names no clash for a box holding the series name typed without .pdf', async () => {
        const shared = {
          ...SERIES,
          descs: [
            {
              lang: 'da',
              name: 'Aktive P&T',
              nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
              fileNamePattern: 'PT-${week}-${year}',
            },
            {
              lang: 'en',
              name: 'Active P&T',
              nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
              fileNamePattern: 'PT-${week}-${year}',
            },
          ],
        } as unknown as PublicationSeries;
        const { fixture } = await setup(CLEAN, shared);
        type(field('issue-override-file-name-en'), 'PT-38-2026');
        await fixture.whenStable();
        expect(field('issue-override-file-name-en').value).toBe('PT-38-2026');
        expect(panel().textContent).not.toContain(
          'admin.publications.overrides.fileNameNotDistinct',
        );
        expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      });

      it('sees a clash between a name typed without .pdf and another language with it', async () => {
        const { fixture } = await setup(STORED, NO_EXTENSION);
        type(field('issue-override-file-name-en'), 'EfS-PT-Uge-38-2026');
        await fixture.whenStable();
        expect(panel().textContent).toContain('admin.publications.overrides.fileNameNotDistinct');
        expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      });
    });

    /** Typing the suggestion back is the same act as never having typed. */
    it('follows again once the box says what the series says', async () => {
      const { fixture } = await setup(CLEAN);
      const en = field('issue-override-file-name-en');
      type(en, 'NtM-PT-Something.pdf');
      await fixture.whenStable();
      expect(marked('issue-override-file-name-en')).toBe(true);

      type(en, 'NtM-PT-Week-38-2026.pdf');
      await fixture.whenStable();
      expect(marked('issue-override-file-name-en')).toBe(false);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
    });

    /** Emptying it says the same thing the suggestion says, in the same word. */
    it('hands the field back when the box is emptied', async () => {
      const { fixture, saved } = await setup(TAILORED);
      type(field('issue-override-file-name-en'), '');
      await fixture.whenStable();
      expect(field('issue-override-file-name-en').value).toBe('');
      await save(fixture);
      expect(saved[0].fileNames).toEqual({ en: '' });
    });

    /**
     * A file name pinned to the very text the pattern renders today is still
     * pinned: the release will skip the pattern and write what is stored, so the
     * next time the numbering moves the document keeps the old name. The drawer
     * says so — the field wears its mark and the reset stands ready to take the
     * pin back — while nothing is offered to SAVE, because nothing has changed.
     */
    it('shows the pin on a stored file name that agrees with the suggestion', async () => {
      await setup({
        ...CLEAN,
        descs: [
          {
            lang: 'da',
            name: 'Aktive P&T uge 38 - 2026',
            fileName: 'EfS-PT-Uge-38-2026.pdf',
            fileNameOverridden: true,
          },
          { lang: 'en', name: 'Active P&T Week 38 - 2026' },
        ],
      } as unknown as PublicationIssue);
      expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-38-2026.pdf');
      expect(marked('issue-override-file-name-da')).toBe(true);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      expect((testId('issue-overrides-reset-all') as HTMLButtonElement).disabled).toBe(false);
    });

    /**
     * And the reset takes it back. Without the mark there is no control to press
     * here at all, so the pin would outlive every edit made in this drawer and
     * rename the first document the numbering moved.
     */
    it('takes back a pin the suggestion has caught up with', async () => {
      const { fixture, saved } = await setup({
        ...CLEAN,
        descs: [
          {
            lang: 'da',
            name: 'Aktive P&T uge 38 - 2026',
            fileName: 'EfS-PT-Uge-38-2026.pdf',
            fileNameOverridden: true,
          },
          { lang: 'en', name: 'Active P&T Week 38 - 2026' },
        ],
      } as unknown as PublicationIssue);
      // The name group is unmarked, so the only control offered is the file
      // name's — which is the point: the pin is the one thing left to take back.
      const follow = buttons('admin.publications.overrides.followSeries');
      expect(follow).toHaveLength(1);
      follow[0].click();
      await fixture.whenStable();
      expect(field('issue-override-file-name-da').value).toBe('EfS-PT-Uge-38-2026.pdf');
      expect(marked('issue-override-file-name-da')).toBe(false);
      await save(fixture);
      expect(saved).toEqual([{ fileNames: { da: '' } }]);
    });
  });

  describe('the reset paths', () => {
    it('hands the numbering back to the cut-off', async () => {
      const { fixture, saved } = await setup(TAILORED);
      buttons('admin.publications.overrides.followCutoff')[0].click();
      await fixture.whenStable();
      expect(field('issue-override-week').value).toBe('36');
      await save(fixture);
      expect(saved).toEqual([{ weekLabel: '' }]);
    });

    /**
     * The box shows the suggestion again — a name is never absent — but what is
     * SENT is a clear. Writing the concrete name back would set the flag that
     * stops the name tracking the series at all, so the one control offering to
     * follow the series again would be the control that detaches it for good.
     */
    it('hands the names back to the series by clearing them', async () => {
      const { fixture, saved } = await setup(TAILORED);
      buttons('admin.publications.overrides.followSeries')[0].click();
      await fixture.whenStable();
      // Expanded with the LABEL, which is what the pattern prints.
      expect(field('issue-override-name-en').value).toBe('Active P&T Week 36+37 - 2026');
      await save(fixture);
      expect(saved[0].names).toEqual({ en: '' });
    });

    /**
     * The box shows the suggestion again — that is what "follow the series" was
     * asked for — but what is SENT is a clear. Writing the expansion in as a
     * hand-set name would pin a name that stops following the pattern the next
     * time the numbering moves, so the one control offering to follow the series
     * again would be the control that detaches the file name for good.
     */
    it('restores the suggested file name without pinning it', async () => {
      const { fixture, saved } = await setup(TAILORED);
      buttons('admin.publications.overrides.followSeries')[1].click();
      await fixture.whenStable();
      // Expanded with the LABEL, exactly as the name above it is.
      expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-36+37-2026.pdf');
      await save(fixture);
      expect(saved[0].fileNames).toEqual({ en: '' });
    });

    it('offers nothing to reset on an issue that is already following its series', async () => {
      await setup(CLEAN);
      expect((testId('issue-overrides-reset-all') as HTMLButtonElement).disabled).toBe(true);
    });

    /**
     * Every group at once — and the boxes then read what the series would say
     * through the numbers the CUT-OFF derived, not through the label that was in
     * the box a moment earlier.
     */
    it('resets every group at once from the footer', async () => {
      const { fixture, saved } = await setup(TAILORED);
      const reset = testId('issue-overrides-reset-all') as HTMLButtonElement;
      expect(reset.disabled).toBe(false);
      reset.click();
      await fixture.whenStable();
      expect(testId('issue-overrides-count')).toBeNull();
      // The DERIVED week — 36, the one the cut-off produced — and not the 36+37
      // that was in the box a moment earlier.
      expect(field('issue-override-name-da').value).toBe('Aktive P&T uge 36 - 2026');
      expect(field('issue-override-file-name-en').value).toBe('NtM-PT-Week-36-2026.pdf');
      await save(fixture);
      expect(saved[0].weekLabel).toBe('');
      expect(saved[0].fileNames).toEqual({ en: '' });
      // Only the pinned language is handed back. The one that had been following
      // all along has nothing to release, and a clear sent for it would be a
      // request to undo a decision nobody made.
      expect(saved[0].names).toEqual({ en: '' });
    });
  });

  describe('what a save sends', () => {
    it('sends nothing at all until something changes, and then only that', async () => {
      const { fixture, saved } = await setup(CLEAN);
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);

      type(field('issue-override-name-en'), 'Active P&T — double week');
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ names: { en: 'Active P&T — double week' } }]);
    });

    it('carries the report as a value, and an empty one where it goes back to the series', async () => {
      const { fixture, saved } = await setup({ ...CLEAN, reportId: 'nm-annex' });
      buttons('admin.publications.overrides.followSeries').at(-1)?.click();
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ reportId: '' }]);
    });

    /**
     * A blank box in the parameter table IS "follows the series": the key is
     * simply absent from the issue's map, and the render then reads the series'
     * value.
     */
    it('sends the whole parameter map, blank values dropped', async () => {
      const { fixture, saved } = await setup({
        ...CLEAN,
        reportParams: { overskrift: 'Aktive P&T uge 38', volume: 133 },
      } as unknown as PublicationIssue);
      type(byLabel('admin.publications.overrides.paramValueFor'), '');
      await fixture.whenStable();
      await save(fixture);
      expect(saved).toEqual([{ reportParams: { volume: '133' } }]);
    });

    /**
     * The issue's languages write into ONE folder, so two equal names are one
     * file: whichever document is written second is what BOTH languages then
     * serve. Refused where every box is on screen, not as a code after the save.
     */
    it('refuses two languages sharing one file name', async () => {
      const { fixture } = await setup(CLEAN);
      type(field('issue-override-file-name-en'), 'shared.pdf');
      type(field('issue-override-file-name-da'), 'shared.pdf');
      await fixture.whenStable();
      expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
      expect(panel().textContent).toContain('admin.publications.overrides.fileNameNotDistinct');
    });
  });

  /**
   * A backend that has never heard of any of this sends none of the new fields,
   * and every one of them reads as "follows the series" — which is the honest
   * answer, and the only one that does not mark the whole estate as tailored.
   */
  it('reads an issue from a server that carries none of the new fields', async () => {
    const bare = {
      publicId: 'i-38',
      status: 'OPEN',
      week: 38,
      year: 2026,
      descs: [{ lang: 'da', name: 'Aktive P&T uge 38 - 2026' }],
    } as unknown as PublicationIssue;
    await setup(bare);
    expect(testId('issue-overrides-count')).toBeNull();
    expect(field('issue-override-week').value).toBe('38');
    expect(buttons('admin.publications.overrides.save')[0].disabled).toBe(true);
  });
});

/** The report picker reads the editor-tier list, never the sysadmin inventory. */
describe('the report picker', () => {
  let urls: string[];

  beforeEach(() => {
    urls = [];
  });

  it('asks for the reports this desk may pick from', async () => {
    TestBed.configureTestingModule({
      imports: [IssueOverridesDrawer],
      providers: [
        provideTestTransloco(),
        provideNiordIcons(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DiscardConfirmService, useValue: { confirmDiscard } },
        { provide: APP_CONFIG, useValue: { apiBaseUrl: BASE, languages: ['da', 'en'] } },
        provideTestLanguages(),
      ],
    });
    const fixture = TestBed.createComponent(IssueOverridesDrawer);
    fixture.componentRef.setInput('issue', CLEAN);
    fixture.componentRef.setInput('series', SERIES);
    fixture.componentRef.setInput('open', true);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    TestBed.inject(HttpTestingController)
      .match(() => true)
      .forEach((request) => {
        urls.push(request.request.urlWithParams);
        request.flush([]);
      });
    expect(urls.some((url) => url.includes('/reports'))).toBe(true);
  });
});
