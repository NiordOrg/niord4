import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../../../../shared/testing/zone-testing';
import type {
  PublicationIssue,
  PublishCheck,
  PublishRequest,
} from '../../../services/api/publication-series-api.service';
import { PublishDialog } from './publish-dialog';

const HOUR = 60 * 60 * 1000;
const PERIOD_START = Date.now() - 30 * 24 * HOUR;
const PERIOD_END = Date.now() - 2 * HOUR;

/** The publishing desk's zone — west of the runner's, so the two never agree. */
const SESSION_ZONE = 'America/Halifax';

const ISSUE: PublicationIssue = {
  publicId: 'i-1',
  status: 'OPEN',
  descs: [{ lang: 'en', name: 'Week 1' }],
  intervalFrom: PERIOD_START,
  intervalTo: PERIOD_END,
};

@Component({
  imports: [PublishDialog],
  template: `
    <app-publish-dialog
      [(visible)]="open"
      [issue]="issue()"
      [viewInstant]="viewInstant()"
      [memberCount]="memberCount()"
      [opensSuccessor]="opensSuccessor()"
      [warnings]="warnings()"
      [informational]="informational()"
      [unacknowledged]="unacknowledged()"
      [errorKey]="errorKey()"
      [working]="working()"
      (submitted)="last.set($event)"
      (plannedCutoffChanged)="moved.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly issue = signal<PublicationIssue>(ISSUE);
  /**
   * Which instant the screen behind the dialog is being read at.
   *
   * The whole of what the dialog opens on. The series' own habit about where its
   * cut-off falls decides THIS value on the screen behind, so the dialog is
   * never handed it a second time.
   */
  readonly viewInstant = signal<'now' | 'planned'>('now');
  readonly memberCount = signal(0);
  /** Whether the release opens the next issue: a fact about the series, never a name. */
  readonly opensSuccessor = signal(false);
  readonly warnings = signal<readonly PublishCheck[]>([]);
  readonly informational = signal<readonly PublishCheck[]>([]);
  readonly unacknowledged = signal<readonly string[]>([]);
  readonly errorKey = signal<string | undefined>(undefined);
  /** Whether the release the screen started is still running. */
  readonly working = signal(false);
  readonly last = signal<PublishRequest | undefined>(undefined);
  /** The new planned cut-off the dialog asked the screen to write. */
  readonly moved = signal<number | undefined>(undefined);
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      // The desk this publish is being made from, half an hour west of the runner:
      // the cut-off is an instant, and the clock it is read on is the domain's.
      provideTestSessionZone(SESSION_ZONE),
      {
        provide: LanguageService,
        useValue: {
          activeLocale: signal('en'),
          selectedLanguage: signal('en'),
          // Two, in the configured order: the name rows are per-language, and a
          // single-language stub cannot tell "every language" from "the one".
          modelLanguages: [
            { value: 'en', flagPath: '/flags/en.png' },
            { value: 'da', flagPath: '/flags/da.png' },
          ],
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const CHECKLIST_PATH = '/rest/publication-issues/issue/i-1/publish-checklist';

const railRequests = () =>
  TestBed.inject(HttpTestingController).match((req) => req.url === CHECKLIST_PATH);

const q = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;

/**
 * A day cell in the open calendar, by the number printed on it.
 *
 * The leading/trailing cells of the neighbouring months carry the same numbers,
 * hence the `is-outside` guard.
 */
function calendarDay(text: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
    (b) => b.textContent?.trim() === text && !b.classList.contains('is-outside'),
  );
}

type Fixture = Awaited<ReturnType<typeof setup>>;

/** Opens the dialog. The cut-off radios are in the open: the one choice the release asks. */
async function show(fixture: Fixture) {
  fixture.componentInstance.open.set(true);
  await fixture.whenStable();
}

/** The same thing under the name the older tests use. */
async function open(fixture: Fixture) {
  await show(fixture);
}

/**
 * Picks the planned cut-off from a dialog that opened on the moment of the click.
 *
 * Which is the only way to reach that release from a screen read as of now — and
 * the only arrangement in which the dialog fetches a rail of its own for it.
 */
async function pickPlanned(fixture: Fixture) {
  await open(fixture);
  q<HTMLInputElement>('#publish-cutoff-planned')!.click();
  await fixture.whenStable();
}

/**
 * What the dialog resolved, rather than what the stub bundle printed.
 *
 * Every key renders as itself here and the parameters go with it, so a header
 * read off the DOM would say nothing about the name that was put into it.
 */
function dialog(fixture: Fixture): { issueName(): string } {
  return fixture.debugElement.children[0].componentInstance as unknown as { issueName(): string };
}

async function submit(fixture: Fixture) {
  (q<HTMLButtonElement>('.dialog-panel .btn-primary') as HTMLButtonElement).click();
  await fixture.whenStable();
}

describe('PublishDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The dialog opens on what pressing Publish will DO, not on a form.
   *
   * The cut-off is the part nobody expects: it ends the content period rather
   * than naming the click, and an admin who thinks it has already happened
   * publishes a period that closes somewhere else.
   */
  it('opens on a sentence naming the count and the stamp, and stating the successor', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(1);
    fixture.componentInstance.opensSuccessor.set(true);
    await show(fixture);

    const summary = q('[data-testid="publish-summary"]')!;
    expect(summary.textContent).toContain('admin.publications.publish.summaryNowNext');
    // And the one choice it asks is right there, not behind a fold.
    expect(q('#publish-cutoff-now')).not.toBeNull();
  });

  /**
   * A disabled button says the click landed; it does not say the work is running.
   *
   * A release freezes the membership and renders the document per language —
   * tens of seconds on a large issue, over which the dialog stands there looking
   * refused rather than busy.
   */
  it('spins the submit button while the release is running', async () => {
    const fixture = await setup();
    await show(fixture);

    expect(q('.dialog-panel .btn-primary app-icon')).toBeNull();

    fixture.componentInstance.working.set(true);
    await fixture.whenStable();

    const button = q<HTMLButtonElement>('.dialog-panel .btn-primary')!;
    expect(button.querySelector('app-icon')).not.toBeNull();
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.disabled).toBe(true);
    // The label stays, so the control never changes what it says mid-press.
    expect(button.textContent).toContain('admin.publications.publish.confirm');
  });

  /**
   * The header names WHICH issue is about to go out.
   *
   * "Publish issue" reads the same over every one of them, on the one surface
   * where the decision is irreversible and two tabs can be open at once.
   */
  it('names the issue in the header', async () => {
    const fixture = await setup();
    await show(fixture);

    expect(dialog(fixture).issueName()).toBe('Week 1');
  });

  /** An issue with no name of its own is named by the address it lives at. */
  it('falls back to the public id where the issue has no name', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set({ ...ISSUE, descs: [] });
    await show(fixture);

    expect(dialog(fixture).issueName()).toBe('i-1');
  });

  /** "1 message(s)" is the shape of a sentence nobody wrote for the number it got. */
  it('says one message in the singular', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(1);
    await show(fixture);

    expect(q('[data-testid="publish-summary"]')!.textContent).toContain(
      'admin.publications.publish.summaryNowOne',
    );
  });

  /** A series that opens no successor is not promised one. */
  it('says nothing about a next issue where none is opened', async () => {
    const fixture = await setup();
    await show(fixture);

    expect(q('[data-testid="publish-summary"]')!.textContent).toContain(
      'admin.publications.publish.summaryNow',
    );
    expect(q('[data-testid="publish-summary"]')!.textContent).not.toContain('Next');
  });

  /**
   * TWO CUT-OFFS, AND NO THIRD.
   *
   * The planned one and right now. Every further instant was a number that
   * appeared nowhere else — not on the screen behind this dialog, not in the
   * issue — so a release could be stamped at a moment nothing else could be
   * checked against.
   */
  it('offers exactly the planned cut-off and now, with no free instant', async () => {
    const fixture = await setup();
    await open(fixture);

    const radios = [
      ...document.querySelectorAll<HTMLInputElement>('[data-testid="publish-options"] .radio'),
    ];
    expect(radios.map((radio) => radio.id)).toEqual([
      'publish-cutoff-planned',
      'publish-cutoff-now',
    ]);
    // And no date box until somebody asks to MOVE the planned cut-off.
    expect(q('[data-testid="publish-cutoff-change"]')).toBeNull();
    expect(q('.dialog-panel app-date-time-field')).toBeNull();
  });

  /**
   * A SCREEN READ AS OF NOW OPENS ON NOW — even where the plan has passed.
   *
   * The instant the screen is read at is the whole answer: the series' habit
   * about where its cut-off falls is what put the screen there in the first
   * place, so weighing it a second time here would overrule the one reader who
   * had said anything — and offer a release nobody was looking at. "Now" is the
   * absence of a cut-off, and the payload says so by not carrying the field.
   */
  it('opens on the moment of the click when the screen is read as of now', async () => {
    const fixture = await setup();
    fixture.componentInstance.viewInstant.set('now');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-now')!.checked).toBe(true);
    await submit(fixture);

    expect(fixture.componentInstance.last()).toEqual({
      cutoff: undefined,
      acknowledgedWarnings: [],
    });
  });

  /** And a screen read at the planned cut-off closes the period there. */
  it('submits the planned cut-off when the screen is read at it', async () => {
    const fixture = await setup();
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.checked).toBe(true);
    await submit(fixture);

    expect(fixture.componentInstance.last()?.cutoff).toBe(PERIOD_END);
  });

  /**
   * A cut-off ends a period of CONTENT. One in the future would claim to contain
   * messages nobody has published, so the option is withheld — and the reason is
   * said, because an option that is simply dead leaves the reader to work out
   * whether the issue is wrong or they are.
   *
   * Read at the planned instant on purpose: a plan can move into the future
   * under an open dialog, and the choice must not stand over a disabled radio.
   */
  it('withholds the planned cut-off while it has not passed, and says why', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set({ ...ISSUE, intervalTo: Date.now() + 24 * HOUR });
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.disabled).toBe(true);
    expect(q<HTMLInputElement>('#publish-cutoff-now')!.checked).toBe(true);
    expect(q('[data-testid="publish-planned-unavailable"]')!.textContent).toContain(
      'admin.publications.publish.cutoffPlannedFuture',
    );
    await submit(fixture);

    expect(fixture.componentInstance.last()?.cutoff).toBeUndefined();
  });

  /** An issue that records no cut-off at all needs one given to it, not explained away. */
  it('withholds the planned cut-off on an issue that records none, and says why', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set({ ...ISSUE, intervalTo: undefined });
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.disabled).toBe(true);
    expect(q<HTMLInputElement>('#publish-cutoff-now')!.checked).toBe(true);
    expect(q('[data-testid="publish-planned-unavailable"]')!.textContent).toContain(
      'admin.publications.publish.cutoffPlannedNone',
    );
  });

  /**
   * The document is always rendered by the release, so nothing asks about it.
   *
   * The alternative promoted a hand-checked preview into the published document,
   * and it is gone: a release that renders sometimes and copies at other times
   * is two different acts behind one button.
   */
  it('offers no control over whether the document is rendered again', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q('#publish-regenerate')).toBeNull();
    await submit(fixture);

    expect(fixture.componentInstance.last()).not.toHaveProperty('regenerate');
  });

  it('submits the warning codes that were ticked, and only those', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
      {
        code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
      },
    ]);
    await open(fixture);

    q<HTMLInputElement>('#publish-ack-FEW_MEMBERS')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual(['FEW_MEMBERS']);
  });

  /**
   * The warnings nobody can tick are still said HERE.
   *
   * An interval that does not join the previous issue's describes the release
   * being made, and the release is made from this dialog. Listed only on the
   * screen behind it, they were what the admin had to remember rather than what
   * they could read — so they sit under the boxes, in the same band, marked
   * rather than offered.
   */
  it('lists the warnings that carry no tickbox beneath the ones that do', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    fixture.componentInstance.informational.set([
      { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
    ]);
    await show(fixture);

    const rows = [...document.querySelectorAll('[data-testid="publish-informational"]')];
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('admin.publications.checkDetail.INTERVAL_CHAINED');
    expect(rows[0].querySelector('input')).toBeNull();
    // And the band counts what is under it, ticked or not.
    expect(document.querySelector('.message-warn legend')!.textContent).toContain('(2)');
  });

  /** The band appears for those rows alone: there is something to read either way. */
  it('shows the warnings band when nothing in it can be ticked', async () => {
    const fixture = await setup();
    fixture.componentInstance.informational.set([
      { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
    ]);
    await show(fixture);

    expect(document.querySelector('.message-warn')).not.toBeNull();
    expect(document.querySelector('.message-warn input')).toBeNull();
  });

  /**
   * The small text under a warning is read in the reader's language.
   *
   * It used to be the server's English — "2 member(s) cancelled or expired after
   * the cut-off, still included" — under a Danish heading, because the numbers
   * and the words arrived welded together. The server now names the answer and
   * sends its values apart.
   */
  it('says what a warning was measured to be, in the reader language', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      {
        code: 'CANCELLED_BUT_DATE_ALIVE',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
        detail: '2 member(s) cancelled or expired after the cut-off, still included',
        detailCode: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count',
        detailParams: { count: 2 },
      },
    ]);
    await show(fixture);

    const detail = q('[data-testid="publish-check-detail"]')!;
    expect(detail.textContent).toContain(
      'admin.publications.checkDetail.CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count',
    );
    expect(detail.textContent).not.toContain('member(s)');
  });

  /** An answer this build has no words for still reads — as the server wrote it. */
  it('falls back to the sentence the server sent for an unworded answer', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      {
        code: 'FEW_MEMBERS',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
        detail: 'something the server measured',
        detailCode: 'FEW_MEMBERS.somethingNew',
      },
    ]);
    await show(fixture);

    const detail = q('[data-testid="publish-check-detail"]')!;
    expect(detail.textContent).toContain('something the server measured');
    expect(detail.textContent).not.toContain('admin.publications.checkDetail.FEW_MEMBERS');
  });

  /** A row this build has no sentence for is shown as its own code, never as a key path. */
  it('renders an unworded informational row as the code itself', async () => {
    const fixture = await setup();
    fixture.componentInstance.informational.set([
      { code: 'SOMETHING_NEW', severity: 'WARN', passed: false, acknowledgeable: false },
    ]);
    await show(fixture);

    const row = document.querySelector('[data-testid="publish-informational"]')!;
    expect(row.textContent).toContain('SOMETHING_NEW');
    expect(row.textContent).not.toContain('admin.publications.checkDetail.');
  });

  /**
   * The refusal is re-listed rather than replacing the form.
   *
   * The codes named here can differ from the checklist's: publish re-resolves, so
   * a warning can appear between the rail being fetched and the release being
   * attempted — which is the only reason this branch exists.
   */
  it('re-lists the warnings a refused publish is still waiting on', async () => {
    const fixture = await setup();
    await open(fixture);
    await submit(fixture);

    fixture.componentInstance.unacknowledged.set(['CANCELLED_BUT_DATE_ALIVE']);
    await fixture.whenStable();

    expect(q('.publish-unacknowledged')).not.toBeNull();
    const box = q<HTMLInputElement>('#publish-unack-CANCELLED_BUT_DATE_ALIVE');
    expect(box).not.toBeNull();

    box!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([
      'CANCELLED_BUT_DATE_ALIVE',
    ]);
  });

  /**
   * ONE vocabulary on the wire, and the SERVER says which word it is.
   *
   * The rail names its rows after the QUESTION it asked; the release gate
   * compares the resolver's own warning codes. Sending the rail's word back was
   * a release that reported itself acknowledged and was refused again over the
   * very row that had just been ticked — so the row now carries the gate's code
   * beside its own, and nothing here translates one into the other.
   */
  it('submits the rail row under the acknowledge code the row itself names', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      {
        code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
        acknowledgeCode: 'CANCELLED_BUT_DATE_ALIVE',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
      },
    ]);
    await open(fixture);

    q<HTMLInputElement>('#publish-ack-CANCELLED_BUT_DATE_ALIVE')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([
      'CANCELLED_BUT_DATE_ALIVE',
    ]);
  });

  /** A row whose two names coincide carries none, and answers with its own code. */
  it('falls back to the row’s own code where it names no acknowledge code', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    await open(fixture);

    q<HTMLInputElement>('#publish-ack-TYPE_DRIFT')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual(['TYPE_DRIFT']);
  });

  /**
   * A refusal belongs to the attempt that earned it, and to no later opening.
   *
   * Both refusal surfaces are held by the screen behind this dialog and survive
   * a cancel. Reopening therefore showed "acknowledge these to continue" over a
   * form that had just been reseeded, tickboxes that had just been cleared and a
   * release nobody had attempted yet — an admin's first sight of the dialog was
   * a demand about something they had not done.
   */
  it('drops the refusal notice when the dialog is closed and opened again', async () => {
    const fixture = await setup();
    await open(fixture);
    await submit(fixture);

    fixture.componentInstance.unacknowledged.set(['CANCELLED_BUT_DATE_ALIVE']);
    fixture.componentInstance.errorKey.set('admin.publications.error.WARNING_NOT_ACKNOWLEDGED');
    await fixture.whenStable();
    expect(q('.publish-unacknowledged')).not.toBeNull();

    // Cancelled, and opened again on the very same refusal the screen still holds.
    q<HTMLButtonElement>('.dialog-panel .btn-tertiary')!.click();
    await fixture.whenStable();
    await show(fixture);

    expect(q('.publish-unacknowledged')).toBeNull();
    expect(document.querySelector('.dialog-panel')!.textContent).not.toContain(
      'admin.publications.error.WARNING_NOT_ACKNOWLEDGED',
    );
  });

  /** And the rail row and the refusal are ONE row, not two. */
  it('renders a warning through the shared vocabulary, and an unknown one as itself', async () => {
    const fixture = await setup();
    await open(fixture);
    fixture.componentInstance.unacknowledged.set(['STALE_OVERRIDE', 'SOMETHING_NEW']);
    await fixture.whenStable();

    const text = document.querySelector('.dialog-panel')!.textContent ?? '';
    expect(text).toContain('admin.publications.warning.STALE_OVERRIDE');
    expect(text).toContain('SOMETHING_NEW');
    expect(text).not.toContain('admin.publications.warning.SOMETHING_NEW');
    expect(text).not.toContain('admin.publications.check.');
  });
});

/**
 * CHANGING THE CUT-OFF MOVES THE PLANNED ONE.
 *
 * Not a third instant chosen for this release alone: the date reached from here
 * travels through the issue's own edit path, which is what makes a cut-off
 * changed while deciding to publish and one changed in the edit panel the same
 * act with the same numbers. Anything else was a private instant that agreed
 * with nothing else on the screen.
 */
describe('PublishDialog, moving the planned cut-off', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** An issue whose period sits in a month the calendar can be walked into. */
  const DATED: PublicationIssue = {
    ...ISSUE,
    intervalFrom: Date.UTC(2026, 1, 1),
    intervalTo: Date.UTC(2026, 2, 5, 3, 45),
  };

  /** The instant picking the 2nd in the open calendar resolves to at that desk. */
  const MOVED = Date.UTC(2026, 2, 3, 3, 45);

  async function startChange(fixture: Fixture) {
    q<HTMLButtonElement>('[data-testid="publish-change-cutoff"]')!.click();
    await fixture.whenStable();
  }

  /** Walk the calendar to the 2nd of the month the field opens on. */
  async function pickSecond(fixture: Fixture) {
    q<HTMLButtonElement>(
      '[data-testid="publish-cutoff-change"] app-date-time-field button',
    )!.click();
    await fixture.whenStable();
    calendarDay('2')!.click();
    await fixture.whenStable();
  }

  /**
   * The dialog asks; the screen writes.
   *
   * Moving the cut-off is an edit of the issue, and the screen behind this
   * dialog is the one that already writes every other edit — with the revision
   * check and the refetch that go with it.
   */
  it('asks the screen to move the cut-off, and stays open while it lands', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await open(fixture);
    await startChange(fixture);
    await pickSecond(fixture);

    q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.moved()).toBe(MOVED);
    // Nothing is published by moving a cut-off, and the dialog does not go away.
    expect(fixture.componentInstance.last()).toBeUndefined();
    expect(fixture.componentInstance.open()).toBe(true);
    expect(q('[data-testid="publish-cutoff-change"]')).not.toBeNull();
  });

  /**
   * The cut-off is an instant, and the calendar it is read on belongs to the
   * domain the publication is written from.
   *
   * 2026-03-05T03:45Z is 23:45 on the 4th at that desk and 00:15 on the 5th in
   * the runner's own zone, so the grid opens on a different day in each — and a
   * day picked in the wrong one closes a different period of content.
   */
  it('resolves a moved cut-off day in the session domain’s zone', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await open(fixture);
    await startChange(fixture);
    await pickSecond(fixture);

    q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.click();
    await fixture.whenStable();

    // The picked day keeps the clock it was carrying: 23:45 at the desk.
    expect(wallClockIn(SESSION_ZONE, new Date(MOVED))).toBe('2026-03-02 23:45:00');
    expect(fixture.componentInstance.moved()).toBe(MOVED);
  });

  /**
   * The box closes when the ISSUE comes back carrying the new instant, and
   * everything the dialog says follows it.
   *
   * The write belongs to the screen, so its result arrives here as a fresh
   * issue — which is the only witness this dialog has that the edit went
   * through.
   */
  it('follows the issue back once the new cut-off has been stored', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await pickPlanned(fixture);
    railRequests()[0].flush({ rows: [], canPublish: true, blockingCodes: [], memberCount: 4 });
    await fixture.whenStable();

    await startChange(fixture);
    await pickSecond(fixture);
    q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.click();
    await fixture.whenStable();

    fixture.componentInstance.issue.set({ ...DATED, intervalTo: MOVED });
    await fixture.whenStable();

    expect(q('[data-testid="publish-cutoff-change"]')).toBeNull();
    // The rail, and so the count, is re-asked at the instant that is now planned.
    const asked = railRequests();
    expect(asked).toHaveLength(1);
    expect(asked[0].request.params.get('cutoff')).toBe(String(MOVED));

    asked[0].flush({ rows: [], canPublish: true, blockingCodes: [], memberCount: 2 });
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.cutoff).toBe(MOVED);
  });

  /**
   * A moved cut-off takes the ticks with it.
   *
   * The acknowledgements were given for the release that was on offer a moment
   * ago; a cut-off that moves resolves a different member list, so a warning
   * that comes up again is coming up over rows nobody has looked at. Carried
   * across, it would be an acknowledgement nobody gave.
   */
  it('drops the acknowledgements once the cut-off has moved', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await pickPlanned(fixture);
    railRequests()[0].flush({
      rows: [{ code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true }],
      canPublish: true,
      blockingCodes: [],
    });
    await fixture.whenStable();
    q<HTMLInputElement>('#publish-ack-TYPE_DRIFT')!.click();
    await fixture.whenStable();
    expect(q<HTMLInputElement>('#publish-ack-TYPE_DRIFT')!.checked).toBe(true);

    await startChange(fixture);
    await pickSecond(fixture);
    q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.click();
    await fixture.whenStable();
    fixture.componentInstance.issue.set({ ...DATED, intervalTo: MOVED });
    await fixture.whenStable();

    // The same warning at the new instant, and nothing ticked over it.
    railRequests()[0].flush({
      rows: [{ code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true }],
      canPublish: true,
      blockingCodes: [],
    });
    await fixture.whenStable();

    expect(q<HTMLInputElement>('#publish-ack-TYPE_DRIFT')!.checked).toBe(false);
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([]);
  });

  /**
   * A refusal leaves the cut-off where it was, so the box stays open above it.
   *
   * The alternative closed the box on the click and reported the failure over a
   * form that no longer showed what had been asked for.
   */
  it('keeps the box open and shows the refusal when the edit is refused', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await open(fixture);
    await startChange(fixture);
    await pickSecond(fixture);
    q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.click();
    await fixture.whenStable();

    fixture.componentInstance.errorKey.set('admin.publications.error.STALE_VERSION');
    await fixture.whenStable();

    expect(q('[data-testid="publish-cutoff-change"]')).not.toBeNull();
    expect(document.querySelector('.dialog-panel')!.textContent).toContain(
      'admin.publications.error.STALE_VERSION',
    );
  });

  /** An instant nobody moved is not an edit, and there is nothing to confirm. */
  it('offers nothing to confirm until the instant actually moves', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await open(fixture);
    await startChange(fixture);

    expect(q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.disabled).toBe(
      true,
    );
  });

  /**
   * A cut-off at or before the period's own start is refused beside the box.
   *
   * The release resolves over the half-open window (period start, cut-off], so
   * such a cut-off is not a narrow window but an impossible one — refused by the
   * edit, and said here rather than arriving as a coded failure afterwards.
   */
  it('refuses a cut-off that does not lie after the period start', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set({
      ...DATED,
      intervalFrom: Date.UTC(2026, 2, 4, 0, 0),
    });
    await open(fixture);
    await startChange(fixture);
    await pickSecond(fixture);

    expect(document.querySelector('.dialog-panel')!.textContent).toContain(
      'admin.publications.publish.cutoffNotAfterStart',
    );
    expect(q<HTMLButtonElement>('[data-testid="publish-cutoff-change-confirm"]')!.disabled).toBe(
      true,
    );
    expect(fixture.componentInstance.moved()).toBeUndefined();
  });

  /**
   * Publishing waits while the box is open.
   *
   * A release taken then would be stamped at the STORED instant while another
   * one sits unconfirmed in a box beside it, which is the one reading of this
   * dialog that cannot be defended afterwards.
   */
  it('holds the release while a new cut-off is being typed', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set(DATED);
    await open(fixture);
    await startChange(fixture);

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);
    await submit(fixture);
    expect(fixture.componentInstance.last()).toBeUndefined();

    q<HTMLButtonElement>('[data-testid="publish-cutoff-change"] .btn-tertiary')!.click();
    await fixture.whenStable();

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(false);
  });
});

/**
 * The rail follows the cut-off.
 *
 * Membership resolves AS OF the cut-off, so a release closed at the planned
 * instant is judged against a member list the page's rail — taken at "now" —
 * never looked at. Shown the page's rows, an admin ticks warnings about the
 * release they have just declined to make and is refused over the ones the
 * server actually raised.
 */
describe('PublishDialog, the rail for the offered cut-off', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('asks nothing while the cut-off is the moment of the click', async () => {
    const fixture = await setup();
    fixture.componentInstance.viewInstant.set('now');
    await open(fixture);

    expect(railRequests()).toHaveLength(0);
  });

  /**
   * And nothing either where the screen is ALREADY read at the planned cut-off.
   *
   * The rows handed in and the count beside them were taken at that very
   * instant, so a request for them is a round trip spent re-asking a question
   * the screen behind has already answered.
   */
  it('asks nothing for the planned cut-off on a screen read there', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(21);
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.checked).toBe(true);
    expect(railRequests()).toHaveLength(0);
  });

  it('asks for the planned instant’s rail, and ticks what IT names', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    await pickPlanned(fixture);

    const requests = railRequests();
    expect(requests).toHaveLength(1);
    expect(requests[0].request.params.get('cutoff')).toBe(String(PERIOD_END));
    expect(requests[0].request.params.get('allowFuture')).toBe('false');

    requests[0].flush({
      rows: [
        { code: 'TYPE_DRIFT', severity: 'WARN', passed: true, acknowledgeable: true },
        {
          code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
          acknowledgeCode: 'CANCELLED_BUT_DATE_ALIVE',
          severity: 'WARN',
          passed: false,
          acknowledgeable: true,
        },
      ],
      canPublish: true,
      blockingCodes: [],
    });
    await fixture.whenStable();

    // The page's row is gone -- it passed at this instant -- and the one this
    // cut-off raises is offered in its place.
    expect(q('#publish-ack-TYPE_DRIFT')).toBeNull();
    q<HTMLInputElement>('#publish-ack-CANCELLED_BUT_DATE_ALIVE')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([
      'CANCELLED_BUT_DATE_ALIVE',
    ]);
  });

  /** An instant that cannot be released at is not worth a request. */
  it('asks nothing while the planned cut-off has not passed', async () => {
    const fixture = await setup();
    fixture.componentInstance.issue.set({ ...ISSUE, intervalTo: Date.now() + 24 * HOUR });
    await pickPlanned(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.disabled).toBe(true);
    expect(railRequests()).toHaveLength(0);
  });

  /**
   * A failed check is SAID, not shown as an empty rail.
   *
   * "No warnings" and "we could not find out" must not read alike above a button
   * that publishes, so the page's own rows stand and the failure is named. The
   * release is still gated server-side either way.
   */
  it('keeps the page’s rows and says so when the rail could not be fetched', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    await pickPlanned(fixture);

    railRequests()[0].flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(document.querySelector('.dialog-panel')!.textContent).toContain(
      'admin.publications.publish.warningsForCutoffFailed',
    );
    expect(q('#publish-ack-TYPE_DRIFT')).not.toBeNull();
  });
});

/**
 * THE DIALOG OPENS ON THE RELEASE THE ADMIN WAS LOOKING AT.
 *
 * The screen behind it can be read at either of two instants, and the count and
 * the warnings are facts about ONE of them. A dialog that opened on "now"
 * regardless would offer a different release from the one on screen — and
 * quote the other instant's number while doing it.
 */
describe('PublishDialog, the instant it opens on', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** The dialog's own answers, which the stub bundle cannot show through the DOM. */
  function counted(fixture: Fixture): {
    headlineCount(): number | undefined;
    summaryKey(): string;
  } {
    return fixture.debugElement.children[0].componentInstance as unknown as {
      headlineCount(): number | undefined;
      summaryKey(): string;
    };
  }

  it('opens on the planned cut-off when the screen is being read there', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(2);
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-planned')!.checked).toBe(true);
    // Nothing is asked: the count on screen is that release's own.
    expect(railRequests()).toHaveLength(0);
    expect(counted(fixture).headlineCount()).toBe(2);
    await submit(fixture);

    expect(fixture.componentInstance.last()?.cutoff).toBe(PERIOD_END);
  });

  /** And a screen read as of now opens on the moment of the click. */
  it('opens on the moment of the click when the screen is read as of now', async () => {
    const fixture = await setup();
    fixture.componentInstance.viewInstant.set('now');
    await open(fixture);

    expect(q<HTMLInputElement>('#publish-cutoff-now')!.checked).toBe(true);
  });

  /** Nothing is asked for "now": the count on screen is the count of that release. */
  it('quotes the screen’s own count for a release at the moment of the click', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(193);
    await show(fixture);

    expect(counted(fixture).headlineCount()).toBe(193);
    expect(railRequests()).toHaveLength(0);
  });

  /**
   * The planned instant is counted by the rail fetched FOR it.
   *
   * The screen's count belongs to another release, so it is not shown in its
   * place while the real one is on its way — a number that is wrong is worse
   * than a number that has not arrived, on the one dialog that cannot be undone.
   */
  it('shows no count until the planned instant has been counted, then shows that one', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(193);
    await pickPlanned(fixture);

    expect(counted(fixture).headlineCount()).toBeUndefined();
    expect(counted(fixture).summaryKey()).toBe('admin.publications.publish.summaryPlannedCounting');

    railRequests()[0].flush({ rows: [], canPublish: true, blockingCodes: [], memberCount: 187 });
    await fixture.whenStable();

    expect(counted(fixture).headlineCount()).toBe(187);
    expect(counted(fixture).summaryKey()).toBe('admin.publications.publish.summaryPlanned');
  });

  /** The successor is still promised while the counting is going on. */
  it('keeps the successor in the sentence while the count is on its way', async () => {
    const fixture = await setup();
    fixture.componentInstance.opensSuccessor.set(true);
    await pickPlanned(fixture);

    expect(counted(fixture).summaryKey()).toBe(
      'admin.publications.publish.summaryPlannedCountingNext',
    );
  });

  /**
   * A rail that answers without a count is a server that does not send one.
   *
   * The dialog falls back to the count it was handed rather than leaving the
   * sentence counting forever.
   */
  it('falls back to the screen’s count when the rail carries none', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(193);
    await pickPlanned(fixture);

    railRequests()[0].flush({ rows: [], canPublish: true, blockingCodes: [] });
    await fixture.whenStable();

    expect(counted(fixture).headlineCount()).toBe(193);
  });

  /**
   * A rail that FAILED quotes no number either.
   *
   * The only number the dialog holds then is the screen's, which belongs to the
   * other instant — printed beside "the warnings could not be checked" it reads
   * as the one fact on the panel that survived, and it is the wrong one.
   */
  it('shows no count when the rail for the planned instant could not be fetched', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(193);
    await pickPlanned(fixture);

    railRequests()[0].flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(counted(fixture).headlineCount()).toBeUndefined();
    // And it says which of the two count-less states it is in: a count on its
    // way and a count nobody could work out ask different things of a reader.
    expect(counted(fixture).summaryKey()).toBe('admin.publications.publish.summaryPlannedUnknown');
  });

  /**
   * ON A SCREEN READ AT THE PLANNED CUT-OFF, "NOW" IS THE OTHER RELEASE.
   *
   * Everything handed into the dialog — the count, the warning rows — describes
   * the planned instant, so choosing to publish right now has to be asked about
   * like the instant the screen is not showing. Taking the inputs for the answer
   * quoted the planned count above the planned rail, for a release that freezes
   * a different list and is judged against different rows.
   */
  it('asks for the release-right-now rail when the screen is read at the planned cut-off', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(2);
    fixture.componentInstance.warnings.set([
      { code: 'TYPE_DRIFT', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    fixture.componentInstance.viewInstant.set('planned');
    await open(fixture);

    // The planned instant it opened on cost nothing: the screen answers for it.
    expect(railRequests()).toHaveLength(0);
    expect(counted(fixture).headlineCount()).toBe(2);

    q<HTMLInputElement>('#publish-cutoff-now')!.click();
    await fixture.whenStable();

    const asOfNow = railRequests();
    expect(asOfNow).toHaveLength(1);
    // Absent is "now" on the wire, exactly as it is on the release itself.
    expect(asOfNow[0].request.params.has('cutoff')).toBe(false);
    expect(counted(fixture).headlineCount()).toBeUndefined();
    expect(counted(fixture).summaryKey()).toBe('admin.publications.publish.summaryNowCounting');

    asOfNow[0].flush({
      rows: [
        {
          code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
          acknowledgeCode: 'CANCELLED_BUT_DATE_ALIVE',
          severity: 'WARN',
          passed: false,
          acknowledgeable: true,
        },
      ],
      canPublish: true,
      blockingCodes: [],
      memberCount: 3,
    });
    await fixture.whenStable();

    expect(counted(fixture).headlineCount()).toBe(3);
    expect(counted(fixture).summaryKey()).toBe('admin.publications.publish.summaryNow');
    // And the boxes are that release's, not the ones the screen was showing.
    expect(q('#publish-ack-TYPE_DRIFT')).toBeNull();
    expect(q('#publish-ack-CANCELLED_BUT_DATE_ALIVE')).not.toBeNull();
  });

  /**
   * And on a screen read as of now, "now" still costs nothing.
   *
   * The count and the rows on screen ARE that release's, so a request for them
   * would be a round trip spent re-asking a question already answered.
   */
  it('asks nothing for a release right now on a screen read as of now', async () => {
    const fixture = await setup();
    fixture.componentInstance.memberCount.set(193);
    fixture.componentInstance.viewInstant.set('now');
    await open(fixture);

    expect(railRequests()).toHaveLength(0);
    expect(counted(fixture).headlineCount()).toBe(193);
  });
});

/**
 * The sentence above the button, in every shape the dialog can put it in.
 *
 * Whole wordings rather than one assembled from fragments, so the key space is
 * sixteen strings and a missing one shows up as a dotted key path on the screen
 * that publishes. Walked here rather than trusted: the shape is chosen from
 * three facts at once, and the combination nobody thought about is the one that
 * reaches a reader.
 */
describe('the release summary wording', () => {
  const BUNDLES: readonly (readonly [string, Record<string, unknown>])[] = [['en', en]];

  /** Every key {@link PublishDialog.summaryKey} can produce, by its own rules. */
  const SUMMARY_KEYS = ['summaryNow', 'summaryPlanned'].flatMap((base) =>
    ['', 'Next'].flatMap((next) => [
      `${base}${next}`,
      `${base}${next}One`,
      `${base}Counting${next}`,
      `${base}Unknown${next}`,
    ]),
  );

  function summary(bundle: Record<string, unknown>, key: string): string | undefined {
    const publish = (bundle['admin'] as Record<string, Record<string, Record<string, string>>>)[
      'publications'
    ]['publish'];
    return publish[key];
  }

  it.each(BUNDLES)('has every summary shape in %s', (_lang, bundle) => {
    const missing = SUMMARY_KEYS.filter((key) => summary(bundle, key) === undefined);
    expect(missing).toEqual([]);
  });

  /**
   * The document is rendered by the release, once.
   *
   * The sentence said "generated again" while the dialog still offered to skip
   * the render and publish the newest preview instead. That choice is gone — a
   * release always renders — so "again" now describes a second pass over a
   * document that in the ordinary case does not exist yet.
   */
  it('promises one render rather than a repeat of one', () => {
    for (const key of SUMMARY_KEYS) {
      expect(summary(en, key)).not.toContain('generated again');
    }
  });

  /**
   * The successor is stated, never named.
   *
   * Its name can only be predicted from the issue's plan, and this is the dialog
   * where the cut-off it would be chained from is still being chosen — so a
   * period named here is named from an instant the admin may be about to
   * decline. An issue spanning several weeks made that visible: week 36 cut off
   * in week 38 opens week 39, and the prediction said week 37.
   */
  it('states that a successor opens without naming it', () => {
    for (const [, bundle] of BUNDLES) {
      for (const key of SUMMARY_KEYS) {
        expect(summary(bundle, key)).not.toContain('{{next}}');
      }
    }
  });

  /** The instant on offer is the issue's OWN, so nothing calls it "chosen". */
  it('carries no wording for a freely chosen instant', () => {
    for (const [, bundle] of BUNDLES) {
      const publish = (bundle['admin'] as Record<string, Record<string, Record<string, string>>>)[
        'publications'
      ]['publish'];
      expect(Object.keys(publish).filter((key) => key.startsWith('summaryChosen'))).toEqual([]);
      expect(publish['cutoffChosen']).toBeUndefined();
    }
  });
});
