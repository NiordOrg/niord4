import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../../../../shared/testing/zone-testing';
import {
  PublicationIssueDraftDialog,
  type IssueDraftDecision,
  type IssueDraftSeed,
} from './issue-draft-dialog';

const FROM = 1_766_000_000_000;
const TO = 1_766_604_800_000;
const MOVED_TO = 1_767_209_600_000;

/** The desk's zone — west of the runner's, so the two never agree on a day. */
const SESSION_ZONE = 'America/Halifax';

const DRAFT = {
  descs: [
    { lang: 'en', name: 'Week 27' },
    { lang: 'da', name: 'Uge 27' },
  ],
  intervalFrom: FROM,
  intervalTo: TO,
  intervalFromSource: 'RECOVERED',
  intervalToSource: 'NOMINAL',
  wouldMatchCount: 11,
  chainedFromPublicId: 'nm-w26-2026',
  chainedFromName: 'Week 26',
  warnings: [{ code: 'INTERVAL_NOT_CHAINED', message: 'server prose nobody renders' }],
};

// The active language leads every draft request: the answer names the edition
// this one is chained from, and a name is only readable in a language.
const draftUrl = (query: string) =>
  `/rest/publication-series/series/weekly-ntm/issue-draft?lang=en&${query}`;

@Component({
  imports: [PublicationIssueDraftDialog],
  template: `
    <app-publication-issue-draft-dialog
      [(visible)]="open"
      seriesId="weekly-ntm"
      [seed]="seed()"
      [errorKey]="errorKey()"
      [debounceMs]="0"
      (submitted)="last.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly seed = signal<IssueDraftSeed | undefined>(undefined);
  readonly errorKey = signal<string | undefined>(undefined);
  readonly last = signal<IssueDraftDecision | undefined>(undefined);
}

/** The two bound signals the shared pickers write into. */
interface Internals {
  readonly boundsForm: {
    from: () => { value: WritableSignal<Date | null> };
    to: () => { value: WritableSignal<Date | null> };
  };
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      // The desk the period is being opened from, half an hour west of the runner:
      // the bounds are instants, and the days they fall on are the domain's.
      provideTestSessionZone(SESSION_ZONE),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal('en'), selectedLanguage: signal('en') },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, httpTesting: TestBed.inject(HttpTestingController) };
}

type Fixture = Awaited<ReturnType<typeof setup>>;

async function openWith(fixture: Fixture, seed: IssueDraftSeed) {
  fixture.fixture.componentInstance.seed.set(seed);
  fixture.fixture.componentInstance.open.set(true);
  await fixture.fixture.whenStable();
}

const panel = () => document.querySelector('.dialog-panel');
const text = () => panel()?.textContent ?? '';

/** One language's name box — the names are edited here, not printed. */
const nameBox = (lang: string) =>
  document.querySelector<HTMLInputElement>(`#issue-draft-name-${lang}`);

async function typeName(fixture: Fixture, lang: string, value: string) {
  const field = nameBox(lang)!;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.fixture.whenStable();
}

/**
 * A day cell in the open calendar, by the number printed on it.
 *
 * The leading/trailing cells of the neighbouring months carry the same numbers,
 * hence the `is-outside` guard.
 */
function calendarDay(label: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
    (b) => b.textContent?.trim() === label && !b.classList.contains('is-outside'),
  );
}

function internals(fixture: Fixture): Internals {
  return fixture.fixture.debugElement.query(By.directive(PublicationIssueDraftDialog))
    .componentInstance as unknown as Internals;
}

describe('PublicationIssueDraftDialog', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The gap row hands back its OWN bounds.
   *
   * Not a chain from the preceding issue: the bounds are what the reader is
   * looking at, and asking about a different period than the one on screen is
   * how the answer and the row stop agreeing.
   */
  it('asks for the draft with the period the row already knows', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    expect(nameBox('en')!.value).toBe('Week 27');
    expect(nameBox('da')!.value).toBe('Uge 27');
    // The chain is named, and the id that addresses it stays off the screen.
    expect(panel()?.querySelector('[data-testid="issue-draft-chained-from"]')?.textContent).toBe(
      'Week 26',
    );
    expect(text()).not.toContain('nm-w26-2026');
    // The reconstructed bound is MARKED, which is the whole reason the period is
    // reviewed rather than committed by one click.
    expect(
      panel()?.querySelector('[title="admin.publications.boundSource.RECOVERED"]'),
    ).not.toBeNull();
  });

  /**
   * A chain with no name is not shown at all.
   *
   * The draft still carries the id that addresses the issue it follows on from,
   * and that id is a handle rather than a name: printed under "Chained from" it
   * tells the reader nothing about which issue their new period comes after. So
   * the row goes, rather than falling back to the id.
   */
  it('drops the chaining row when the draft names nothing to chain from', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting
      .expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`))
      .flush({ ...DRAFT, chainedFromName: undefined });
    await fixture.fixture.whenStable();

    expect(panel()?.querySelector('[data-testid="issue-draft-chained-from"]')).toBeNull();
    expect(text()).not.toContain('admin.publications.issueDraft.chainedFrom');
    expect(text()).not.toContain('nm-w26-2026');
  });

  /**
   * The edition the server proposes, shown and not editable.
   *
   * It is minted with the issue, so there is nothing to decide here — but a
   * number that reaches a file name has to be visible before anybody presses
   * Create, on the one draft that carries a number worth reading.
   */
  it('shows the proposed edition, read-only, where the draft carries a later one', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush({ ...DRAFT, edition: '2' });
    await fixture.fixture.whenStable();

    const shown = panel()?.querySelector('[data-testid="issue-draft-edition"]');
    expect(shown?.textContent?.trim()).toBe('2');
    expect(text()).toContain('admin.publications.issue.edition');
  });

  /**
   * A first edition says nothing, here as everywhere else.
   *
   * A fresh period's document is the first of that period, so the server
   * proposes 1 for every one of them. Printing that on every create dialog
   * spends a row on the answer nobody needed, and it would also be the one
   * surface saying "Edition 1" while the header and the archive strip say
   * nothing — the rule has to read the same on all three.
   */
  it('says nothing about the first edition of a fresh period', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush({ ...DRAFT, edition: '1' });
    await fixture.fixture.whenStable();

    expect(panel()?.querySelector('[data-testid="issue-draft-edition"]')).toBeNull();
  });

  /** A series that mints no edition says nothing about one either. */
  it('says nothing about an edition the draft does not carry', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush(DRAFT);
    await fixture.fixture.whenStable();

    expect(panel()?.querySelector('[data-testid="issue-draft-edition"]')).toBeNull();
  });

  /** No bounds and no chain: the server derives the next period itself. */
  it('asks with no period when the next issue is opened by hand', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush(DRAFT);
    await fixture.fixture.whenStable();

    expect(nameBox('en')!.value).toBe('Week 27');
  });

  /**
   * Null is not zero.
   *
   * Zero says the period is empty; null says nothing counted it, and a screen
   * that showed them alike would report an unmeasured week as an empty one.
   */
  it('separates an uncounted period from an empty one', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush({ ...DRAFT, wouldMatchCount: null });
    await fixture.fixture.whenStable();

    expect(text()).toContain('admin.publications.issueDraft.wouldMatchUnknown');
  });

  it('renders a warning through the mapping, and an unknown one as itself', async () => {
    const fixture = await setup();
    await openWith(fixture, { recovered: false });

    fixture.httpTesting
      .expectOne('/rest/publication-series/series/weekly-ntm/issue-draft?lang=en')
      .flush({
        ...DRAFT,
        warnings: [{ code: 'NO_ANCHOR' }, { code: 'SOMETHING_NEW' }],
      });
    await fixture.fixture.whenStable();

    expect(text()).toContain('admin.publications.issueDraft.warning.NO_ANCHOR');
    expect(text()).toContain('SOMETHING_NEW');
    expect(text()).not.toContain('admin.publications.issueDraft.warning.SOMETHING_NEW');
    // The server's own English sentence is never rendered.
    expect(text()).not.toContain('server prose nobody renders');
  });

  /**
   * Moving a bound re-asks, so the name and the count keep describing the
   * period actually on screen.
   */
  it('re-asks the draft when a bound is moved', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    internals(fixture).boundsForm.to().value.set(new Date(MOVED_TO));
    await fixture.fixture.whenStable();

    fixture.httpTesting
      .expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${MOVED_TO}`))
      .flush({ ...DRAFT, intervalTo: MOVED_TO, descs: [{ lang: 'en', name: 'Week 27+28' }] });
    await fixture.fixture.whenStable();

    expect(nameBox('en')!.value).toBe('Week 27+28');
  });

  /**
   * A reviewed bound is an INSTANT, moved on the domain's calendar.
   *
   * 2025-12-17T03:45Z is 23:45 on the 16th at the desk and 00:15 on the 17th in
   * the runner's own zone: the grid opens on a different day in each, so a day
   * picked in the wrong one asks the server about a different period than the
   * one the reader is looking at — and then creates it.
   */
  it('moves a bound on the desk’s calendar, not the browser’s', async () => {
    const from = Date.UTC(2025, 11, 17, 3, 45);
    const to = Date.UTC(2025, 11, 24, 3, 45);
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: from, intervalTo: to, recovered: true });

    fixture.httpTesting
      .expectOne(draftUrl(`intervalFrom=${from}&intervalTo=${to}`))
      .flush({ ...DRAFT, intervalFrom: from, intervalTo: to });
    await fixture.fixture.whenStable();

    // The From field is the first of the two; its date trigger the first button.
    const field = document.querySelectorAll('.dialog-panel app-date-time-field')[0];
    field.querySelectorAll('button')[0].click();
    await fixture.fixture.whenStable();
    calendarDay('5')!.click();
    await fixture.fixture.whenStable();

    // The 5th at the desk, still carrying the clock the bound came with.
    const moved = Date.UTC(2025, 11, 6, 3, 45);
    expect(wallClockIn(SESSION_ZONE, new Date(moved))).toBe('2025-12-05 23:45:00');

    fixture.httpTesting
      .expectOne(draftUrl(`intervalFrom=${moved}&intervalTo=${to}`))
      .flush({ ...DRAFT, intervalFrom: moved, intervalTo: to });
    await fixture.fixture.whenStable();

    (panel()?.querySelector('.btn-primary') as HTMLButtonElement).click();
    await fixture.fixture.whenStable();

    expect(fixture.fixture.componentInstance.last()).toEqual({
      intervalFrom: moved,
      intervalTo: to,
      recovered: true,
    });
  });

  /**
   * Seeding the fields from the draft must not ask for the draft again.
   *
   * The request reads the bounds whether or not it uses them, so a fresh object
   * describing the same question would loop: ask, seed, ask, seed.
   */
  it('does not re-ask for the draft it just seeded the fields from', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    fixture.httpTesting.expectNone(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`));
  });

  /** A refusal is answered inside the dialog, with the create held back. */
  it('states a refused draft and refuses to create on it', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: TO, intervalTo: FROM, recovered: true });

    fixture.httpTesting
      .expectOne(draftUrl(`intervalFrom=${TO}&intervalTo=${FROM}`))
      .flush({ code: 'INTERVAL_INVERTED' }, { status: 400, statusText: 'Bad Request' });
    await fixture.fixture.whenStable();

    expect(text()).toContain('admin.publications.error.intervalInverted');
    const create = panel()?.querySelector('.btn-primary') as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    create.click();
    await fixture.fixture.whenStable();
    expect(fixture.fixture.componentInstance.last()).toBeUndefined();
  });

  it('emits the reviewed period and the recovery marker', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    (panel()?.querySelector('.btn-primary') as HTMLButtonElement).click();
    await fixture.fixture.whenStable();

    expect(fixture.fixture.componentInstance.last()).toEqual({
      intervalFrom: FROM,
      intervalTo: TO,
      recovered: true,
    });
  });

  /** A refused create is stated here rather than behind the closing dialog. */
  it('shows a refused create without losing the reviewed period', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    fixture.fixture.componentInstance.errorKey.set('admin.publications.error.seriesNotActive');
    await fixture.fixture.whenStable();

    expect(text()).toContain('admin.publications.error.seriesNotActive');
    expect(nameBox('en')!.value).toBe('Week 27');
  });

  /**
   * The name, reviewed with the period.
   *
   * The suggestion follows the cut-off's ISO week, so it moves whenever a bound
   * does — which is precisely why a box somebody has typed in must not be
   * rewritten underneath them, and why the untouched ones must still follow.
   */
  it('keeps a typed name while the untouched ones follow the moved period', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    await typeName(fixture, 'da', 'Uge 27 (jul)');

    internals(fixture).boundsForm.to().value.set(new Date(MOVED_TO));
    await fixture.fixture.whenStable();

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${MOVED_TO}`)).flush({
      ...DRAFT,
      intervalTo: MOVED_TO,
      descs: [
        { lang: 'en', name: 'Week 27+28' },
        { lang: 'da', name: 'Uge 27+28' },
      ],
    });
    await fixture.fixture.whenStable();

    expect(nameBox('en')!.value).toBe('Week 27+28');
    expect(nameBox('da')!.value).toBe('Uge 27 (jul)');
  });

  /** The suggestion, kept, is not a name anybody chose — so nothing travels. */
  it('emits no names when both suggestions are kept', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    (panel()?.querySelector('.btn-primary') as HTMLButtonElement).click();
    await fixture.fixture.whenStable();

    expect(fixture.fixture.componentInstance.last()).not.toHaveProperty('names');
  });

  /**
   * Only what was retyped travels.
   *
   * A name on the wire is recorded as chosen by hand, so a suggestion sent back
   * would create an issue whose name has stopped tracking its own period before
   * anybody decided that it should.
   */
  it('emits only the language whose name was typed', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    await typeName(fixture, 'en', '  Week 27 (Christmas)  ');
    (panel()?.querySelector('.btn-primary') as HTMLButtonElement).click();
    await fixture.fixture.whenStable();

    expect(fixture.fixture.componentInstance.last()).toEqual({
      intervalFrom: FROM,
      intervalTo: TO,
      recovered: true,
      names: { en: 'Week 27 (Christmas)' },
    });
  });

  /** An issue is never nameless in a language its series carries. */
  it('refuses to create on a blank name', async () => {
    const fixture = await setup();
    await openWith(fixture, { intervalFrom: FROM, intervalTo: TO, recovered: true });

    fixture.httpTesting.expectOne(draftUrl(`intervalFrom=${FROM}&intervalTo=${TO}`)).flush(DRAFT);
    await fixture.fixture.whenStable();

    await typeName(fixture, 'da', '   ');

    expect(text()).toContain('admin.publications.issueDraft.nameRequired');
    const create = panel()?.querySelector('.btn-primary') as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    create.click();
    await fixture.fixture.whenStable();
    expect(fixture.fixture.componentInstance.last()).toBeUndefined();
  });
});
