import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, distinctUntilChanged, map, of, scan, startWith, switchMap } from 'rxjs';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { nowTick } from '../../../../../shared/utils/now-tick';

import {
  GapDetection,
  IssueAuditEntry,
  PublicationIssueRow,
  PublicationSeries,
  PublicationSeriesApiService,
  PublishChecklist,
} from '../../../services/api/publication-series-api.service';
import type { IssueDraftDecision } from './issue-draft-dialog';
import {
  NOT_IN_DOMAIN,
  STALE_VERSION,
  publicationErrorCode,
  publicationErrorKey,
  publicationErrorMessage,
  seriesNotFound,
} from './publication-errors';
import { ownerVerdict, seriesWritable } from './series-rules';

interface SeriesState {
  /** Which series this state is about, whether or not the answer has arrived. */
  readonly forId?: string;
  /** The answer itself. Undefined for as long as the read is in flight. */
  readonly series?: PublicationSeries;
  /**
   * The last answer about {@link forId}, carried through the read replacing it.
   *
   * Kept apart from `series` deliberately: the archive is keyed on the series
   * that ARRIVED, which is what holds the two reads in sequence, and folding the
   * carried-over answer into that key would fire them together.
   */
  readonly carried?: PublicationSeries;
  readonly loading: boolean;
  readonly error: boolean;
  /** The read failed because the series is not there: a 404 with SERIES_NOT_FOUND. */
  readonly notFound?: boolean;
}

interface ArchiveState {
  readonly issues: readonly PublicationIssueRow[];
  /** Real issues only. The rows above may include periods that have none. */
  readonly issueCount: number;
  readonly gapDetection?: GapDetection;
  /** Undefined when detection did not run — which is NOT the same as zero. */
  readonly gapCount?: number;
  /**
   * The refresh these rows answered — how the screen tells fresh from carried over.
   *
   * The rows survive an issue opened on top of them and survive a re-read, which
   * is what keeps the card and the table from blanking; the cost is that "there
   * are rows" no longer means "these are the rows as of now". Anything that acts
   * on them rather than merely showing them compares this with the current tick
   * first.
   */
  readonly tick: number;
  readonly error: boolean;
}

const NO_SERIES: SeriesState = { loading: true, error: false };

/** `-1`: no read has answered, which no refresh tick can ever be. */
const NO_ARCHIVE: ArchiveState = { issues: [], issueCount: 0, tick: -1, error: false };

/** The series' own trail, and whether it has been read. */
interface HistoryState {
  readonly entries: readonly IssueAuditEntry[];
  readonly loading: boolean;
  readonly error: boolean;
}

const NO_HISTORY: HistoryState = { entries: [], loading: false, error: false };

/**
 * One series and its issues.
 *
 * On the archive screen neither answer is useful alone — a series with no issue
 * list cannot show its archive, and an issue list with no series cannot say what
 * cadence those issues follow — so both failures are one error state rather than
 * a page that half-renders.
 *
 * Chained, not fired together. The issue list is by far the more expensive of
 * the two (it walks every issue of the series and its per-language rows), and
 * the series decides whether it is worth asking for: a series that failed has
 * nothing to render an archive against. The chain is kept by keying the archive
 * on the series that ARRIVED rather than on the id it was asked for.
 *
 * The two are separate reads because the screen has two views. Opening an issue
 * replaces the archive with a workbench that reads none of it, and the archive
 * is the heaviest request the section makes — so it is gated on the archive
 * being what is on screen, and the series, which the workbench does need, is
 * not.
 *
 * The same gate settles the owner. A publication another desk owns is refused in
 * place of the page, and the answer that settles that is the series itself — so
 * the archive is refused inside the chain, on the payload it is keyed on, rather
 * than by a flag the screen sets once it has seen the same answer.
 */
@Injectable()
export class SeriesDetailFacade {
  private readonly api = inject(PublicationSeriesApiService);
  private readonly domains = inject(DomainFacade);
  private readonly language = inject(LanguageService);

  private readonly seriesId = signal<string | undefined>(undefined);
  private readonly refreshTick = signal(0);

  /** The wall clock, as a dependency. See {@link publicNowIssue}. */
  private readonly now = nowTick();

  /**
   * Whether the archive is the view on screen. False while an issue is open.
   *
   * TRUE until told otherwise: a facade nobody has said anything to is one whose
   * screen is showing the archive, which is where every visit starts unless a
   * link named an issue.
   */
  private readonly archiveShown = signal(true);

  /** Told by the screen, which owns which of its two views is rendered. */
  setArchiveShown(shown: boolean): void {
    this.archiveShown.set(shown);
  }

  private readonly seriesState = toSignal(
    toObservable(computed(() => ({ seriesId: this.seriesId(), tick: this.refreshTick() }))).pipe(
      switchMap(({ seriesId }) =>
        !seriesId
          ? of<SeriesState>({ loading: false, error: false })
          : this.api.getSeries(seriesId).pipe(
              map(
                (series): SeriesState => ({
                  forId: seriesId,
                  series,
                  loading: false,
                  error: false,
                }),
              ),
              catchError((response: unknown) =>
                of<SeriesState>({
                  forId: seriesId,
                  loading: false,
                  error: true,
                  notFound: seriesNotFound(response),
                }),
              ),
              startWith<SeriesState>({ forId: seriesId, loading: true, error: false }),
            ),
      ),
      // What the screen keeps while the next read of the SAME series runs.
      //
      // Written here rather than derived from the signal, so it does not depend
      // on anybody having read the signal in between: a series answered while
      // nothing was watching is still the series the header draws.
      //
      // Only ever the same series' answer, and only while the read is still on
      // its way. Being pointed at a DIFFERENT one starts from nothing, because a
      // header naming the publication that was on screen a moment ago is worse
      // than one that is briefly absent. A read that FAILED drops it too: the
      // banner says the series could not be read, and a header beside it still
      // stating name, cadence and owner would be asserting facts nobody has
      // just confirmed.
      scan(
        (previous, next): SeriesState => ({
          ...next,
          carried: next.error
            ? undefined
            : (next.series ??
              (previous.carried?.seriesId === next.forId ? previous.carried : undefined)),
        }),
        NO_SERIES,
      ),
    ),
    { initialValue: NO_SERIES },
  );

  private readonly archiveState = toSignal(
    toObservable(
      computed(() => ({
        // The id of the series that ARRIVED, never the one that was asked for.
        // That is what keeps the pair sequential: this read cannot be issued
        // before the series has answered, and it is skipped entirely when the
        // series failed, because there is nothing to render an archive against.
        seriesId: this.seriesState().series?.seriesId,
        // The owner rides in on the very answer this read is keyed on, so the
        // refusal is decided HERE rather than by the screen a flush later: a
        // flag set from outside arrives after this chain has already put the
        // heaviest request in the section on the wire, and cancelling it is not
        // the same as never asking.
        foreign:
          ownerVerdict(this.seriesState().series, this.domains.sessionDomain()?.domainId) ===
          'foreign',
        tick: this.refreshTick(),
        shown: this.archiveShown(),
      })),
    ).pipe(
      switchMap(({ seriesId, shown, foreign, tick }) =>
        // EMPTY rather than an empty answer, in ALL three cases, because each is
        // a moment the rows have to survive: opening an issue, where the card
        // above the archive and the "can a new issue be opened" rule are still
        // read from them, and the instant a refresh clears the series before its
        // re-read answers. A value here would blank the table on each. A series
        // another desk owns never renders an archive at all — the screen is
        // refused in place of the page.
        !seriesId || !shown || foreign
          ? EMPTY
          : this.api.issuesOfSeries(seriesId).pipe(
              map(
                (list): ArchiveState => ({
                  issues: list.data,
                  issueCount: list.total,
                  gapDetection: list.gapDetection,
                  gapCount: list.gapCount,
                  // Stamped with the refresh that ASKED, so a reader can tell
                  // these rows from the ones a re-read is on its way to replace.
                  tick,
                  error: false,
                }),
              ),
              catchError(() => of<ArchiveState>({ issues: [], issueCount: 0, tick, error: true })),
            ),
      ),
    ),
    { initialValue: NO_ARCHIVE },
  );

  /**
   * The series the screen draws: the one that arrived, or the one it still has.
   *
   * Everything in the header hangs off this — the name, the badge, the cadence
   * line, the settings button, the actions menu — and this screen re-reads
   * itself after every action taken on it. Emptying it for the length of that
   * request unmounted the whole header on a screen nobody had navigated away
   * from, which reads as a page that failed rather than as one that is busy.
   */
  readonly series = computed(() => {
    const state = this.seriesState();
    return state.series ?? state.carried;
  });
  readonly issues = computed(() => this.archiveState().issues);

  /**
   * Whether the series' CATEGORY is one the public site lists at all.
   *
   * The public listing needs the category's own publish flag as well as a
   * published issue inside an open window; without it this screen could name an
   * issue as public that the site would never serve — a series filed under an
   * internal category has no public listing whatever its issues say.
   *
   * Read only once a series that HAS a category has arrived: the catalogue
   * answers exactly one question here, and a screen showing a series with no
   * category has nothing to ask. Tri-state, and `undefined` is "not answered
   * yet" rather than "not public" — a slow or refused read must not silently
   * demote a live issue.
   */
  private readonly categoryPublishState = toSignal(
    toObservable(computed(() => this.series()?.categoryId)).pipe(
      distinctUntilChanged(),
      switchMap((categoryId) =>
        categoryId === undefined || categoryId === ''
          ? of<boolean | undefined>(undefined)
          : this.api.publicationCategories().pipe(
              map((rows) => rows.find((row) => row.categoryId === categoryId)?.publish),
              catchError(() => of<boolean | undefined>(undefined)),
            ),
      ),
    ),
    { initialValue: undefined as boolean | undefined },
  );

  readonly loading = computed(() => {
    // All four read up front: a `&&` chain would leave the archive's own state
    // untracked whenever the series answered first, and the strip would then
    // never notice the second read finishing.
    const series = this.seriesState();
    const shown = this.archiveShown();
    const archive = this.archiveState();
    const tick = this.refreshTick();
    // The archive counts only once there is a series to key it on: before that,
    // and when the series read failed, no archive request exists to wait for and
    // its untouched initial state would report one forever.
    //
    // Read off the tick rather than a flag of its own, because the rows are now
    // kept through the re-read that replaces them: the state carries the refresh
    // it answered, so a stale set of rows still says a request is outstanding
    // instead of being shown as though it had settled.
    return series.loading || (shown && series.series !== undefined && archive.tick !== tick);
  });

  /**
   * Whether the screen is still waiting for its FIRST pair of answers.
   *
   * Not the same question as {@link loading}, and the difference is what the
   * panels key on. Loading is true on every re-read, and the rows deliberately
   * survive one — blanking the table after each action would make a screen that
   * re-reads itself constantly look like one that keeps failing. But before the
   * first answer there are no rows to keep, and the panels drawn from an empty
   * archive state say things that are not true yet: an empty current-issue card,
   * and "no issue matches the filter" about a list nobody has fetched.
   *
   * `tick === -1` is what "never answered" looks like on the archive, because no
   * refresh can ever produce it. The series answers when it has a value, carried
   * or fresh — a failed read is not waiting for anything, and the banner takes
   * the screen anyway.
   *
   * Every signal is read before the branch, so none goes untracked on the pass
   * that takes the first one.
   */
  readonly awaitingFirstRead = computed(() => {
    const series = this.seriesState();
    const shown = this.archiveShown();
    const archive = this.archiveState();
    if (series.error) {
      return false;
    }
    const seriesAnswered = series.series !== undefined || series.carried !== undefined;
    // The archive is only read while it is the view on screen, so on any other
    // view there is no first archive read to be waiting for.
    return !seriesAnswered || (shown && archive.tick === -1);
  });

  readonly error = computed(() => {
    // Read up front, for the same reason the loading flag is.
    const seriesFailed = this.seriesState().error;
    const shown = this.archiveShown();
    const archiveFailed = this.archiveState().error;
    // All or nothing on the archive screen, where neither half is readable
    // without the other — but an issue renders on a series whose archive was
    // never asked for, so the archive's failure only counts where it is shown.
    return seriesFailed || (shown && archiveFailed);
  });

  /**
   * Whether the series read failed because the series is not there (L-174).
   *
   * The server's coded 404 SERIES_NOT_FOUND: a link to a series that was
   * deleted, or an id that never existed. The screen says so and offers no
   * Retry, because the same read can only meet the same 404. Only ever true
   * while {@link error} is.
   */
  readonly notFound = computed(() => this.seriesState().notFound === true);

  /** How many issues actually exist, which is not how many rows are rendered. */
  readonly issueCount = computed(() => this.archiveState().issueCount);

  /**
   * Whether the gaps in this list were looked for, and why not.
   *
   * Undefined only before the first response. Once loaded it is always present,
   * so the page can state the answer rather than infer it from an absence.
   */
  readonly gapDetection = computed(() => this.archiveState().gapDetection);

  /**
   * Missing periods found — undefined when nothing looked for them.
   *
   * The distinction is the whole point, and it is why this is not defaulted to
   * 0 anywhere on the way through. Every imported series is DRAFT, which closes
   * the gate, so a 0 here would give the entire archive a clean bill of health
   * on the strength of a check that never ran.
   */
  readonly gapCount = computed(() => this.archiveState().gapCount);

  /**
   * The issue the public site would be serving right now, if any.
   *
   * The rule is the backend public adapter's, deliberately: PUBLISHED, with a
   * publicFrom that has passed and a publicTo that has not. Two definitions of
   * "current" in one system is how an operator ends up trusting a card that
   * disagrees with the site.
   *
   * It is NOT `publicTo == null`. That is I-18's open-ended-issue concept, and
   * on the imported estate it answers a different question badly: almost every
   * legacy issue carries an explicit week-long window, so the test finds nothing
   * at all for weekly-ntm and weekly-ntm-p-t — the two busiest series in the
   * archive — while an issue is plainly live in both.
   *
   * Newest publicFrom wins if the windows ever overlap. Overlap is exactly what
   * I-18 forbids, so this is a tie-break for a state that should not exist
   * rather than a rule; showing the newest beats showing whichever the list
   * happened to hold first.
   *
   * "Right now" is read from a moving clock rather than from `Date.now()`. A
   * bare read is not a dependency, so the card froze at whatever the answer was
   * when the page opened — and this is a screen an operator leaves open across
   * the release it is watching for.
   *
   * The end bound is inclusive, matching the adapter's `publicTo >= :from`
   * exactly. The imported archive carries verbatim legacy windows whose end
   * lands on the instant a successor begins, and an exclusive test drops those
   * issues from this card while the public site is still serving them.
   *
   * And it is gated on the CATEGORY, which the adapter applies to both halves of
   * its union: a series filed under a category the site does not publish has no
   * public listing at all, whatever its issues say. `false` is the only answer
   * that closes the card — an unanswered read leaves the rule as it was rather
   * than reporting a live issue as nothing.
   */
  readonly publicNowIssue = computed(() => {
    if (this.categoryPublishState() === false) {
      return undefined;
    }
    const now = this.now();
    return this.issues()
      .filter(
        (i) =>
          i.status === 'PUBLISHED' &&
          i.publicFrom != null &&
          i.publicFrom <= now &&
          (i.publicTo == null || i.publicTo >= now),
      )
      .reduce<PublicationIssueRow | undefined>(
        (newest, i) => (!newest || (i.publicFrom ?? 0) > (newest.publicFrom ?? 0) ? i : newest),
        undefined,
      );
  });

  /**
   * The issue the series is working on right now, if one is open.
   *
   * At most one is expected, and the first is taken where a series somehow has
   * two: the card above the archive answers "what am I working on", and a
   * screen that renders nothing because the answer was ambiguous answers
   * nothing at all.
   */
  readonly currentIssue = computed(() => this.issues().find((issue) => issue.status === 'OPEN'));

  /**
   * What the release rail already says about the issue being worked on.
   *
   * The card above the archive states how far that issue is from going out, and
   * the honest answer to that is the checklist the release itself is judged by —
   * anything derived from the row alone would be a second opinion the workbench
   * can contradict.
   *
   * Deliberately OUTSIDE the chain the page is drawn from. The series and its
   * archive are all-or-nothing because neither is readable without the other;
   * this is a closing clause on one line, and an admin who may read a series but
   * not its release gate should get the screen rather than an error. A failure —
   * a refusal, a timeout, a server that has no such issue any more — leaves the
   * line off and says nothing.
   *
   * Asked once per open issue, and again after anything that refetches the page:
   * the workbench is where a warning gets dealt with, and coming back to a card
   * still announcing the warning that was just fixed is the card being wrong.
   *
   * And asked only while the card that says it is on screen. The workbench reads
   * the same gate for the issue it opens, so asking here as well would pay for
   * the release resolution twice on every issue anybody opens.
   *
   * And only about rows the current refresh answered. Coming back from the
   * workbench refetches the archive, and the rows still on screen at that moment
   * are the ones the workbench has just made out of date — the issue it names as
   * open may be the one that was published there, whose successor the re-read is
   * about to bring back. Asking on those rows buys the slowest read the screen
   * makes, twice, about the wrong issue first.
   */
  private readonly checklistState = toSignal(
    toObservable(
      computed(() => {
        // All read up front so the key follows any of them: the answer is
        // nothing while an issue is open, nothing while the rows it would be
        // read from are being replaced, and coming back has to re-ask.
        const shown = this.archiveShown();
        const tick = this.refreshTick();
        const current = this.archiveState().tick === tick;
        const publicId = this.currentIssue()?.publicId;
        return { publicId: shown && current ? publicId : undefined, tick };
      }),
    ).pipe(
      distinctUntilChanged((a, b) => a.publicId === b.publicId && a.tick === b.tick),
      switchMap(({ publicId }) =>
        publicId === undefined
          ? of<PublishChecklist | undefined>(undefined)
          : this.api
              .publishChecklist(publicId)
              .pipe(catchError(() => of<PublishChecklist | undefined>(undefined))),
      ),
    ),
    { initialValue: undefined as PublishChecklist | undefined },
  );

  readonly currentChecklist = computed(() => this.checklistState());

  /**
   * Whether this admin, in the domain they are currently in, may change anything.
   *
   * The domain is the one the client already names on every request, so what this
   * answers is exactly what the server will decide about the same write — the
   * point being to stop offering the action rather than to guess at the outcome.
   * The archive, the timeline and every reading surface stay as they are: the
   * rule is about writes only. Whether the series is shown at all is the same
   * owner verdict read one notch stricter, and the screen asks it directly.
   */
  readonly writable = computed(() =>
    seriesWritable(this.series(), this.domains.sessionDomain()?.domainId),
  );

  /**
   * Whether the series is in a state that can receive a new issue at all.
   *
   * ACTIVE and nothing else. A DRAFT series has not been validated — that is
   * what activation is — and a RETIRED one is finished; an issue opened on
   * either is an issue nothing will ever publish. The two are kept apart from
   * {@link canCreateIssue} so the screen can explain the refusal instead of
   * quietly having no button.
   */
  readonly seriesAcceptsIssues = computed(() => this.series()?.status === 'ACTIVE');

  /**
   * Whether opening a new issue by hand makes sense right now.
   *
   * Suppressed while an issue is already OPEN, and that half is an affordance
   * rather than a rule — the server accepts a second one, and an
   * IN_FORCE_AT_CUTOFF series has overlapping issues by design. What it prevents
   * is the accident: a cadenced series opens its successor itself when the
   * current issue publishes, so a button standing next to that open issue
   * invites a duplicate of one the series was about to create.
   *
   * The status half IS a rule, and one the endpoint does not enforce.
   *
   * The domain half is a rule the endpoint DOES enforce, and folding it in here
   * is what keeps the button from being one the server was always going to
   * refuse — the note beside it says which domain to switch to.
   */
  readonly canCreateIssue = computed(
    () =>
      this.seriesAcceptsIssues() &&
      this.writable() &&
      !this.issues().some((issue) => issue.status === 'OPEN'),
  );

  /**
   * A series that has released nothing.
   *
   * Counted on the real issues rather than on the rows: a series whose only row
   * is the UPCOMING period has published nothing, and calling that a populated
   * archive would replace the "nothing released yet" copy with a table of one
   * row describing a period that does not exist.
   */
  readonly isEmpty = computed(
    () => !this.loading() && !this.error() && !!this.series() && this.issueCount() === 0,
  );

  /** Set while a create is in flight, so a double click cannot make two issues. */
  private readonly creating = signal(false);
  readonly working = computed(() => this.creating());

  private readonly createError = signal<string | undefined>(undefined);

  /** A refused create, as the key that names it. Cleared on the next attempt. */
  readonly createErrorKey = computed(() => this.createError());

  /**
   * Create the issue an admin reviewed in the draft dialog.
   *
   * The bounds arrive reviewed rather than derived here: the dialog asked the
   * server what the period would be, showed it with the provenance of each
   * bound, and this is what came back approved.
   *
   * Both bounds go out in the ONE create. The end used to be applied afterwards,
   * through a second write, which left the issue standing for a moment with a
   * period nobody had approved — and standing there for good whenever that
   * second write was the one that failed.
   *
   * Refetches on success: the new issue must replace the pseudo-row it was
   * created from, and only the server knows what the list looks like now.
   */
  createIssue(decision: IssueDraftDecision, onDone?: () => void): void {
    const seriesId = this.seriesId();
    if (!seriesId || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.createError.set(undefined);

    this.api
      .createIssue({
        seriesId,
        intervalFrom: decision.intervalFrom,
        intervalTo: decision.intervalTo,
        recovered: decision.recovered,
        // Absent unless the admin renamed the issue in the dialog: the server's
        // own suggestion stands otherwise, and sending it back would record it
        // as a name somebody chose.
        names: decision.names,
      })
      .subscribe({
        next: () => {
          this.creating.set(false);
          this.refreshTick.update((tick) => tick + 1);
          onDone?.();
        },
        error: (response: unknown) => {
          this.creating.set(false);
          this.createError.set(
            publicationErrorKey(response, 'admin.publications.issueDraft.createFailed'),
          );
        },
      });
  }

  // ------------------------------------------------- what happened to the series

  private readonly historyWanted = signal(false);

  /**
   * Ask for the series' own trail — once somebody has opened the panel that shows it.
   *
   * Idempotent, and deliberately not called on load. This screen's request
   * budget is three, and the trail answers a question almost nobody arriving
   * here is asking; a fourth request on every visit to every series buys an
   * answer that stays folded away.
   */
  loadHistory(): void {
    this.historyWanted.set(true);
  }

  /**
   * The entries recorded against the SERIES rather than against one of its issues.
   *
   * Keyed on the language as well as the series, because the server translates
   * what it sends per request — a reader who switches language with the panel
   * open would otherwise keep the trail in the language they left.
   *
   * And keyed on the refresh, because the trail is written BY the things this
   * screen does — removing an issue, retiring the series, handing it to another
   * desk all add a line to it. Left off the key, a panel that was open when one
   * of those ran would keep showing the trail as it stood just before the entry
   * it exists to show was written.
   *
   * The tick is read only on the branch that asks. A refresh with the panel
   * folded away changes nothing here, so it starts no request.
   *
   * Being asked for OUTLIVES the panel being open: reopening the fold on the
   * same series shows what was already read instead of paying for it again, and
   * the key above keeps that answer current. It is dropped when the screen moves
   * to another series, which is the only move that makes it wrong rather than
   * merely unwatched.
   *
   * A failure here is not the page's failure: the series and its archive render
   * perfectly well without it, and only this panel says it could not be read.
   */
  private readonly historyState = toSignal(
    toObservable(
      computed(() =>
        this.historyWanted()
          ? {
              seriesId: this.seriesId(),
              lang: this.language.selectedLanguage(),
              tick: this.refreshTick(),
            }
          : undefined,
      ),
    ).pipe(
      switchMap((request) =>
        !request?.seriesId
          ? of(NO_HISTORY)
          : this.api.seriesHistory(request.seriesId, request.lang).pipe(
              map((entries): HistoryState => ({ entries, loading: false, error: false })),
              catchError(() => of<HistoryState>({ entries: [], loading: false, error: true })),
              startWith<HistoryState>({ entries: [], loading: true, error: false }),
            ),
      ),
    ),
    { initialValue: NO_HISTORY },
  );

  readonly history = computed(() => this.historyState().entries);
  readonly historyLoading = computed(() => this.historyState().loading);
  readonly historyError = computed(() => this.historyState().error);

  // -------------------------------------------------------- moving it to a desk

  private readonly transferring = signal(false);
  readonly transferWorking = computed(() => this.transferring());

  private readonly transferError = signal<string | undefined>(undefined);
  readonly transferErrorKey = computed(() => this.transferError());

  /**
   * The server's own sentence, for the refusal whose detail no key can carry.
   *
   * A domain refusal names WHICH of the two the caller is not an admin in —
   * the desk they are sitting at, or the one they are moving the series to —
   * and that is the whole of what they have to act on.
   */
  private readonly transferDetail = signal<string | undefined>(undefined);
  readonly transferErrorDetail = computed(() => this.transferDetail());

  /**
   * Move the series to another desk.
   *
   * Everything about it moves at once: which domain lists it, which admins may
   * write it, and the zone its future cut-offs are read in. Nothing already
   * stamped changes — an issue that went out closed at the instant it closed.
   *
   * The revision this screen read goes out with it, and a refusal over it
   * refetches: a transfer changes who may write the series at all, so being
   * refused because somebody moved it underneath is worth seeing rather than
   * overwriting.
   */
  transferOwner(domainId: string, reason: string, onDone?: () => void): void {
    const seriesId = this.seriesId();
    if (!seriesId || this.transferring()) {
      return;
    }
    this.transferring.set(true);
    this.transferError.set(undefined);
    this.transferDetail.set(undefined);

    this.api
      .transferOwner(seriesId, { domainId, reason, version: this.series()?.version })
      .subscribe({
        next: () => {
          this.transferring.set(false);
          this.refreshTick.update((tick) => tick + 1);
          onDone?.();
        },
        error: (response: unknown) => {
          this.transferring.set(false);
          const code = publicationErrorCode(response);
          this.transferError.set(
            code === NOT_IN_DOMAIN
              ? 'admin.publications.transfer.notInDomain'
              : publicationErrorKey(response, 'admin.publications.transfer.failed'),
          );
          this.transferDetail.set(
            code === NOT_IN_DOMAIN ? publicationErrorMessage(response) : undefined,
          );
          if (code === STALE_VERSION) {
            this.refreshTick.update((tick) => tick + 1);
          }
        },
      });
  }

  /**
   * Point the screen at a series.
   *
   * Moving to a DIFFERENT series forgets that the folded trail was asked for:
   * the answer belongs to the series it was read for, and carrying the request
   * over would fetch a panel nobody has opened on the new one.
   */
  load(seriesId: string): void {
    if (this.seriesId() !== seriesId) {
      this.historyWanted.set(false);
    }
    this.seriesId.set(seriesId);
  }

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
