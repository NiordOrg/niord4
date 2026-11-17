import { InjectionToken, Injectable, computed, inject, signal, type Signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Observable,
  catchError,
  concatMap,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
  tap,
  throwError,
  timer,
} from 'rxjs';

import {
  AmendRequest,
  AmendResult,
  IssueAuditEntry,
  IssueCriteria,
  IssueInFlight,
  IssueMember,
  IssueOverride,
  IssuePreview,
  MessageSinceRelease,
  PreviewMiss,
  PublicationIssue,
  PublicationIssueDesc,
  PublicationSeries,
  PublicationSeriesApiService,
  PublishChecklist,
  PublishRequest,
  PublishResult,
  SourceIssue,
  SourceIssueRef,
} from '../../../services/api/publication-series-api.service';
import {
  ISSUE_BUSY,
  ISSUE_CITED,
  STALE_VERSION,
  issueCitationsOf,
  unacknowledgedWarningsOf,
  type CitingMessage,
} from './publication-errors';

/**
 * How often a client that did not start the work asks whether it has finished.
 *
 * Five seconds, because the work it is waiting on takes tens of seconds and the
 * question is one cheap read: slower and a release looks stuck for longer than
 * it ran, faster and a page left open all afternoon asks hundreds of times to
 * be told nothing changed. Only asked while the server says something IS
 * running — an idle issue is never polled at all.
 */
export const DEFAULT_IN_FLIGHT_POLL_MS = 5_000;

/**
 * The interval that poll runs at, so a spec can drive it under fake timers.
 *
 * A token rather than a constant read directly, because the alternative in a
 * test is advancing a real five seconds per assertion.
 */
export const ISSUE_IN_FLIGHT_POLL_MS = new InjectionToken<number>('issue in-flight poll ms', {
  providedIn: 'root',
  factory: () => DEFAULT_IN_FLIGHT_POLL_MS,
});

/**
 * Which period the screen wants the name of, and what it comes after.
 *
 * Asked only where a release actually opens a successor. On a series whose next
 * issue is created by hand there is no period to name, and a sentence promising
 * one would describe work the publish is not going to do.
 */
export interface SuccessorProbe {
  readonly seriesId: string;
  readonly afterPublicId: string;
}

const NO_SUCCESSOR: readonly PublicationIssueDesc[] = [];

/**
 * Which instant the screen is reading the issue at.
 *
 * Two, and only two, because they are the two releases an admin can actually
 * make: one right now, and one closed at the cut-off the issue was planned to
 * close at. Any other instant is chosen inside the release dialog, where it is
 * a decision rather than a way of looking.
 */
export type IssueViewInstant = 'now' | 'planned';

/**
 * Which write is running, for a screen that has to point at the one place the
 * work is happening.
 *
 * The two releases are named because they are the two that take long enough to
 * need saying — each renders the whole document again, per language — and
 * because they are shown in different places: a publish is the release control,
 * an amend is the document list it is about to replace. Everything else is
 * `'write'`: those return in one round trip, and which of them it was changes
 * nothing a reader would see.
 */
type IssueAction = 'publish' | 'amend' | 'write';

/**
 * A period the screen is asking about instead of the one on file.
 *
 * What the edit panel proposes while somebody is typing in it. Both ends are
 * optional because a date box can be empty: an absent start falls back to the
 * issue's own, and an absent end reads as now.
 */
export interface PreviewPeriod {
  readonly from?: number;
  readonly to?: number;
}

/**
 * Why a proposed period cannot be read, when it cannot.
 *
 * Both are ordinary states of a form somebody is halfway through, and neither
 * is a failure to report: a period that starts after it ends selects nothing,
 * and one that has not begun selects nothing YET. What they have in common is
 * that no resolution can answer them — the server takes `from ≤ at` and its
 * own now as the upper bound of what exists — so the screen holds the answer it
 * has and says which of the two it is looking at.
 */
export type PeriodProblem = 'START_AFTER_CUTOFF' | 'START_IN_FUTURE';

/**
 * One run of members that came from the same place, in the order they were taken.
 *
 * A compilation's list is the source issues' own lists laid end to end, and that
 * is the only thing that makes it readable: a thousand rows in one table say
 * nothing about which week printed what. The group with no `source` is the
 * manual one — a message somebody added by hand was printed by no source issue,
 * so it belongs after all of them rather than under any.
 *
 * `members` may be empty, and that is a statement rather than a gap: the source
 * issue was covered and printed nothing.
 */
export interface MemberGroup {
  /** What identifies the group in a list — the source's id, or the manual key. */
  readonly key: string;
  /** The source issue whose frozen rows these are; absent on the manual group. */
  readonly source?: SourceIssueRef;
  readonly members: readonly IssueMember[];
}

/**
 * The key of the one group that belongs to no source issue.
 *
 * Namespaced apart from the source keys below rather than sharing their space: a
 * public id is opaque, and a source issue that happened to be called `manual`
 * would otherwise have its rows swept into the hand-added run.
 */
const MANUAL_GROUP = 'manual';

/** What a source issue's run is keyed by — never a bare public id, see above. */
function sourceGroupKey(publicId: string): string {
  return `source:${publicId}`;
}

/** No groups, as one value, so a non-compiled screen never recomputes an empty list. */
const NO_GROUPS: readonly MemberGroup[] = [];

interface IssueDetailState {
  readonly issue?: PublicationIssue;
  readonly members: readonly IssueMember[];
  /**
   * The members published after the planned cut-off, as the answer named them.
   *
   * The same rows in both views — the server takes them from the as-of-now
   * resolution whatever instant the rest was resolved at — so the section that
   * lists them says the same thing either way and only its sub-line changes.
   */
  readonly late: readonly IssueMember[];
  /** The instant those rows were counted as late against, as the server took it. */
  readonly lateAfter?: number;
  readonly audit: readonly IssueAuditEntry[];
  readonly overrides: readonly IssueOverride[];
  readonly checklist?: PublishChecklist;
  /**
   * Every source-series period this issue's period covers, as the survey saw it.
   *
   * Present — possibly empty — on every issue of the compiled regime, and on no
   * other regime, where the question does not apply. While the issue is open it
   * is the live survey and carries all three statuses; once it is released it is
   * the same list rebuilt from the frozen header, every row `PUBLISHED` and in
   * the cut-off order the union was taken in. An empty LIST is a third answer
   * and must not read like the absence — a compilation whose period covers no
   * source issue at all.
   */
  readonly sources?: readonly SourceIssue[];
  /** A capped sample of the candidates the resolver saw and did not take. */
  readonly omissions: readonly PreviewMiss[];
  /** How many it dropped in all, which the sample above may not account for. */
  readonly omissionCount: number;
  /**
   * The messages published into a released issue's period since its release.
   *
   * Empty on every issue that is not published, and wherever the server sent
   * none.
   */
  readonly publishedSinceRelease: readonly MessageSinceRelease[];
  /**
   * The release or amend the SERVER says is running on this issue.
   *
   * Absent while the issue is idle. It is the same fact for every reader, which
   * is the whole of why it is held: the work outlives the request that started
   * it, so a reloaded page, a second tab and another admin must all see it, and
   * a browser-only flag shows it to exactly one of them.
   */
  readonly inFlight?: IssueInFlight;
  /** The instant the server resolved this answer at. */
  readonly viewedAt?: number;
  /** The period start the server resolved this answer over. */
  readonly viewedFrom?: number;
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: IssueDetailState = {
  members: [],
  late: [],
  audit: [],
  overrides: [],
  omissions: [],
  omissionCount: 0,
  publishedSinceRelease: [],
  loading: true,
  error: false,
};

/**
 * No late rows, as one value.
 *
 * Shared rather than written fresh, so a computed that answers "none" twice
 * answers with the same array both times and nothing downstream recomputes.
 */
const NO_LATE_MEMBERS: readonly IssueMember[] = [];

/** Nothing loaded and nothing being loaded — the state before an id is set. */
const IDLE: IssueDetailState = {
  members: [],
  late: [],
  audit: [],
  overrides: [],
  omissions: [],
  omissionCount: 0,
  publishedSinceRelease: [],
  loading: false,
  error: false,
};

/**
 * One issue: what is in it, whether it can be released, and what has happened to it.
 *
 * ONE request, and a screen that never shows half of one. The parts answer a
 * single question between them — *should this go out, and what would go out* —
 * and a screen that half-renders is worse than one that fails, because the half
 * that rendered looks complete. The release rail in particular must never appear
 * beside a member list that failed to load: an admin would be deciding to
 * publish a document while looking at an empty table.
 *
 * A refused RE-READ is a different case, and gets a different answer: the
 * screen keeps the whole answer it already had and raises the banner over it.
 * That is still one complete resolution, and it is not the one it will act on —
 * the banner says so, and the alternative was worse. See the read below.
 *
 * One request rather than several. Asked for in turn, the parts paid a round trip
 * each; asked for side by side, three of them each ran their own resolve of the
 * same issue. One request costs one round trip.
 *
 * It also makes the parts one answer instead of several. Members, rail and
 * omissions are decided from a single resolution, so they can no longer be taken
 * at instants milliseconds apart — a rail counting 214 beside a list of 213 is
 * not a state this screen can reach.
 *
 * ONE request AT ONE INSTANT. An open issue whose planned cut-off has passed can
 * be read either as it stands now or as it stood at that cut-off, and the two
 * select different messages. Changing which is a fresh read of everything rather
 * than a filter over one answer, because the rail and the omissions are
 * resolved server-side at the instant that is asked for — a screen that filtered
 * the member list alone would show one instant's list beside another instant's
 * verdict. A series that closes at the end of its period therefore costs a
 * second read on the way in: the planned cut-off is a fact about the issue, so
 * the first answer is what names it.
 *
 * The checklist arrives rather than being derived. `canPublish` is the server's
 * answer and re-deriving it here from the rows would let the button and the
 * backend disagree about the same issue. It is absent for a reader who may
 * curate an issue but not administer one, which renders as no rail at all —
 * whether a release may be made is not their decision, and refusing them the
 * screen over it would hide an issue from somebody allowed to curate it.
 */
@Injectable()
export class IssueDetailFacade {
  private readonly api = inject(PublicationSeriesApiService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = toSignal(this.transloco.langChanges$, { requireSync: true });
  private readonly pollMs = inject(ISSUE_IN_FLIGHT_POLL_MS);

  private readonly publicId = signal<string | undefined>(undefined);
  private readonly refreshTick = signal(0);

  /**
   * The mutation in flight, so the rail cannot be double-fired.
   *
   * Named rather than a bare flag, because two of these take tens of seconds —
   * a publish and an amend each render a document per language server-side —
   * and the screen has to say WHERE the work is happening, not merely that
   * something is. The request lives here rather than in the dialog that started
   * it, so closing the dialog cancels nothing and the page keeps the indicator.
   * Every other write is one value: which one it was changes nothing on screen
   * while it runs.
   */
  private readonly action = signal<IssueAction | undefined>(undefined);

  /** The last mutation that failed, for the screen to name rather than swallow. */
  private readonly actionError = signal<string | undefined>(undefined);

  /**
   * The server's own sentence for that refusal.
   *
   * Kept beside the code because some refusals carry the part no translation can:
   * an all-or-nothing curation names every uid it could not resolve, and a
   * curator who only sees "one of these does not exist" has to find out which by
   * bisecting their own selection.
   */
  private readonly actionMessage = signal<string | undefined>(undefined);

  /**
   * The warning codes the server refused the last publish over.
   *
   * Held apart from {@link actionError} because it is not an error message but a
   * list of decisions still owed: the dialog stays open and re-lists exactly
   * these, which is the only way an admin can answer a refusal that arrived after
   * the checklist they ticked.
   */
  private readonly unacknowledged = signal<readonly string[]>([]);

  /**
   * The messages a refused removal named, held apart from the sentence.
   *
   * Like the unacknowledged warnings above, this is not an error message but the
   * substance of one: "some message still cites this issue" is not something an
   * admin can act on, and the ids are.
   */
  private readonly citations = signal<
    { readonly messages: readonly CitingMessage[]; readonly count: number } | undefined
  >(undefined);

  /**
   * The renders on offer for this issue: the server's, plus one made just now.
   *
   * Seeded from every workbench answer, so a reload, a refresh after a release
   * and the refetch a render itself triggers all show what the server is
   * actually holding — a screen that only remembered the renders made in this
   * sitting hid, after every reload, documents that were sitting there. Written
   * directly by a render as well, so the document appears the moment it exists
   * rather than one round trip later.
   */
  private readonly previews = signal<readonly IssuePreview[]>([]);
  private readonly previewing = signal(false);
  private readonly previewError = signal<unknown>(undefined);

  // ------------------------------------------------------- which instant

  /**
   * The series this issue belongs to, as the screen that provides this facade
   * holds it.
   *
   * The facade reads ONE issue, and two of the questions asked of it are facts
   * about the series above: where its cut-off falls, and how it selects its
   * content. Fetching them here would be a second source that can disagree with
   * the list beside it — so the screen hands its OWN signal over, once, and the
   * answers below are read from it rather than copied out of it. A copy kept in
   * step by an effect is briefly wrong on every load, which is exactly when the
   * first render happens.
   *
   * `undefined` until a screen attaches one, which is what a facade created
   * without a series reads.
   */
  private readonly attachedSeries = signal<Signal<PublicationSeries | undefined> | undefined>(
    undefined,
  );

  private readonly series = computed(() => this.attachedSeries()?.());

  /** What the series says about where its cut-off falls. */
  private readonly cutoffPreference = computed(() => this.series()?.cutoffDefault);

  /** How the series selects its content. */
  private readonly seriesRelation = computed(() => this.series()?.timeRelation);

  /**
   * The issue's planned cut-off, remembered from the last answer.
   *
   * Held beside the state rather than read out of it, and that is what keeps
   * the read from chasing its own tail: the request is keyed on this instant,
   * and a value that vanished while the request it keys was in flight would
   * re-key the request back to "now" and start again.
   *
   * Only an OPEN issue has one. A frozen issue is the record of what went out,
   * so there is no second instant to read it at and nothing for the default
   * below to prefer.
   */
  private readonly plannedCutoff = signal<number | undefined>(undefined);

  /** The reader's own choice, once they have made one. */
  private readonly chosenView = signal<IssueViewInstant | undefined>(undefined);

  /**
   * A period being proposed rather than the one on file.
   *
   * Set while the edit panel is open, so every number on the screen answers for
   * the period as it is being typed rather than for the saved one. Without it
   * the member list beside the two date boxes described a period nobody was
   * looking at any more, and the only way to find out what the edit would
   * actually select was to save it.
   *
   * The period is the whole of the question while it is set: the reader's choice
   * of instant is not consulted, because the boxes in front of them name one.
   */
  private readonly previewPeriod = signal<PreviewPeriod | undefined>(undefined);

  /**
   * Which instant the screen is reading at.
   *
   * The reader's choice where they made one, and otherwise the series' own
   * habit: a publication that closes at the end of its content period is read
   * at that instant once it has passed, because that is the release its admin
   * is about to make. Everything else is read as of now, which is what a weekly
   * notice's release actually freezes.
   */
  readonly viewInstant = computed<IssueViewInstant>(() => {
    const planned = this.plannedCutoff();
    if (planned === undefined) {
      return 'now';
    }
    const chosen = this.chosenView();
    if (chosen !== undefined) {
      return chosen;
    }
    return this.cutoffPreference() === 'PERIOD_END' && planned <= Date.now() ? 'planned' : 'now';
  });

  /**
   * Whether the cut-off being typed is one no read can be taken at yet.
   *
   * True for a cut-off that has not arrived and for an empty box, which name the
   * same thing: a period whose end lies at or beyond this moment. Both the
   * instant the read asks for and what "late" means below hang off it, and they
   * have to hang off ONE answer — two readings of the clock taken a moment apart
   * could otherwise disagree about the very period the screen is describing.
   */
  private readonly previewCutoffAhead = computed(() => {
    const preview = this.previewPeriod();
    const to = preview?.to;
    return preview !== undefined && (to === undefined || to > Date.now());
  });

  /**
   * The instant the read asks for — absent is "now", as it is on the wire.
   *
   * A proposed period closes at its own end, CLAMPED to now: a period that ends
   * in the future contains nothing beyond this moment, so asking about that
   * moment and asking about the end are the same question — and only one of the
   * two is an instant the server will accept. The clamp is expressed as an
   * absent instant rather than as the client's clock reading, so every future
   * end keys the same read instead of one per keystroke.
   *
   * Every signal is read before the branch, so none goes untracked on the pass
   * that takes the first one.
   */
  private readonly readAt = computed(() => {
    const preview = this.previewPeriod();
    const ahead = this.previewCutoffAhead();
    const viewed = this.viewInstant();
    const planned = this.plannedCutoff();
    if (preview) {
      return ahead ? undefined : preview.to;
    }
    return viewed === 'planned' ? planned : undefined;
  });

  /** The period start the read asks for — absent is the issue's own. */
  private readonly readFrom = computed(() => this.previewPeriod()?.from);

  /**
   * Whether the period being proposed is one no read can answer, and why.
   *
   * A start typed after the end, and a period that has not begun, are both
   * REFUSED by the server — it takes `from ≤ at`, and the latest instant that
   * exists is its own now. Asked anyway, the refusal arrives as a failed read of
   * the whole screen, which is how picking a later start date in the calendar
   * (the natural first move when carrying a period forward) took the two date
   * boxes off the screen behind an error banner, with no way back to the box
   * that caused it and the edit lost on the way out.
   *
   * So the question is not asked. The screen keeps the answer it has, says which
   * of the two states the boxes are in, and starts reading again the moment they
   * describe a period that can be read.
   */
  readonly periodProblem = computed<PeriodProblem | undefined>(() => {
    const preview = this.previewPeriod();
    const from = preview?.from;
    if (preview === undefined || from === undefined) {
      return undefined;
    }
    // The typed pair first, so an inverted period is named as one rather than as
    // whatever its start happens to be relative to now.
    if (preview.to !== undefined && from >= preview.to) {
      return 'START_AFTER_CUTOFF';
    }
    return from >= (this.readAt() ?? Date.now()) ? 'START_IN_FUTURE' : undefined;
  });

  /**
   * What identifies the read: which issue, over which period, how many refreshes
   * ago.
   *
   * A STRING rather than an object, because the read is keyed on a value that
   * the read itself produces — the planned cut-off comes out of the answer — and
   * a fresh object literal per recomputation would re-fire the request on every
   * response whether or not anything about it had changed.
   *
   * The verdict above rides in the key rather than being recomputed inside the
   * stream: both decisions read a clock, and two readings taken a moment apart
   * could otherwise disagree about whether the request that is about to go out
   * is the one the screen just decided not to send.
   */
  private readonly requestKey = computed(() =>
    JSON.stringify({
      publicId: this.publicId(),
      lang: this.language(),
      tick: this.refreshTick(),
      at: this.readAt() ?? null,
      from: this.readFrom() ?? null,
      unreadable: this.periodProblem() !== undefined,
    }),
  );

  /**
   * The last answer this screen actually rendered.
   *
   * Held so a read can carry it: a refresh of an issue already on screen keeps
   * showing that issue while the next answer is in flight, and keeps it if the
   * answer never comes. The alternative — blanking to nothing on every re-read —
   * unmounts the edit panel under whoever is typing in it, which is a live
   * preview that cannot survive its own second keystroke.
   *
   * Plain field rather than a signal: it is read and written inside one stream
   * and nothing renders from it, so a signal would only invite a second reader.
   */
  private lastAnswer: IssueDetailState = INITIAL;

  private readonly state = toSignal(
    toObservable(this.requestKey).pipe(
      distinctUntilChanged(),
      switchMap((key) => {
        const { publicId, at, from, unreadable } = JSON.parse(key) as {
          publicId?: string;
          at: number | null;
          from: number | null;
          unreadable: boolean;
        };
        if (!publicId) {
          this.lastAnswer = IDLE;
          return of(IDLE);
        }
        // What this read starts from, and falls back to. Only an answer about
        // THIS issue can be carried: the panel is reused when one issue hands
        // over to another, and the predecessor's members under the successor's
        // address would be a screen that looks loaded and is wrong.
        const held = this.lastAnswer.issue?.publicId === publicId ? this.lastAnswer : INITIAL;
        if (unreadable) {
          // Nothing is asked and nothing is lost: the numbers stand as they were,
          // and the screen says why they are not moving.
          return of({ ...held, loading: false, error: false });
        }
        // The language titles the member rows and nothing else — membership,
        // history, standing decisions and the rail are the same in every one.
        return this.api
          .issueWorkbench(
            publicId,
            this.transloco.getActiveLang(),
            at ?? undefined,
            from ?? undefined,
          )
          .pipe(
            // The stored renders are not part of the screen's state object: they
            // are also written by a render of their own, and a value with two
            // authors cannot be a projection of one of them. Taken from every
            // answer, so the server has the last word on what is on offer -- a
            // frozen issue answers with none, which is exactly the moment the rows
            // from the sitting before must stop being offered.
            tap((workbench) => {
              this.previews.set(workbench.previews ?? []);
              // Kept outside the state for the reason given where it is declared:
              // it keys the read, and a key that empties while its own request is
              // in flight re-keys the request.
              this.plannedCutoff.set(
                workbench.issue?.status === 'OPEN' ? workbench.issue.intervalTo : undefined,
              );
            }),
            map(
              (workbench): IssueDetailState => ({
                issue: workbench.issue,
                members: workbench.members,
                late: workbench.afterPlannedCutoff ?? [],
                // `null` is the server saying there is no such instant, which
                // comes with an empty list — it and an older server that sends
                // nothing at all reach the screen the same way, and the screen
                // falls back to the issue's own planned cut-off.
                lateAfter: workbench.lateAfter ?? undefined,
                // Carried exactly as it arrived, absence included: absent IS the
                // statement that nothing is running, and it is what stops the
                // poll below.
                inFlight: workbench.inFlight,
                viewedAt: workbench.viewedAt,
                // A server that does not echo the start resolved over the
                // issue's own, which is what it had to work with. An in-force
                // publication answers with none at all, and falls through to the
                // same place: an issue with no stored start either.
                viewedFrom: workbench.viewedFrom ?? workbench.issue?.intervalFrom,
                audit: workbench.audit,
                overrides: workbench.overrides,
                // Carried through exactly as it arrived, absence included: it is
                // the workbench's own statement of which regime answered, and a
                // `[]` substituted for a missing field would turn "this issue
                // compiles nothing" into "this compilation found nothing".
                sources: workbench.sources,
                // A RAIL BELONGS TO AN ISSUE THAT CAN STILL BE RELEASED. It is a
                // pre-release gate, and read against a frozen issue it reports what
                // the criteria select TODAY rather than what went out -- measured on
                // a weekly issue that released 20 messages and whose gate counted 26,
                // because six more had been published since. It also drew two red
                // failures on a successful release, one of them failing precisely
                // BECAUSE the issue is published. Held here as well as on the side
                // that computes it, so a gate can never be drawn beside a record.
                checklist: workbench.issue?.status === 'OPEN' ? workbench.checklist : undefined,
                omissions: workbench.omissions?.misses ?? [],
                // The rows are a capped sample; the count is the real answer.
                omissionCount: workbench.omissions?.missCount ?? 0,
                // Only a published issue can have missed anything: the list is
                // what an amend of the release would take in. Held to that here
                // as well, so a stray list can never put the banner on an open
                // or withdrawn issue.
                publishedSinceRelease:
                  workbench.issue?.status === 'PUBLISHED'
                    ? (workbench.publishedSinceRelease ?? [])
                    : [],
                loading: false,
                error: false,
              }),
            ),
            tap((answer) => (this.lastAnswer = answer)),
            // A REFUSED RE-READ IS NOT AN EMPTY ISSUE. Emptying the screen was
            // the same all-or-nothing rule applied to the wrong thing: what it
            // exists to prevent is HALF an answer, and the answer already on
            // screen is a whole one taken a moment ago. Dropping it took the
            // edit panel, its date boxes and its Cancel button down with it, so
            // the only way out of a refusal was the back button — which
            // discards the edit, and does it without asking. The banner above
            // says the numbers are not current; the panel stays where it is.
            catchError(() => of({ ...held, loading: false, error: true })),
            startWith({ ...held, loading: true, error: false }),
          );
      }),
    ),
    { initialValue: INITIAL },
  );

  readonly issue = computed(() => this.state().issue);

  /**
   * The revision this screen is holding, which every write echoes back.
   *
   * Read from the loaded issue rather than kept in a signal of its own, so there
   * is exactly one answer to "which revision is on screen" — a second copy is a
   * copy that can be left behind by a refetch nobody remembered to update it
   * from. `undefined` before the first response, which is the caller declining
   * the check rather than claiming revision zero.
   */
  private readonly heldVersion = computed(() => this.state().issue?.version);

  /**
   * Every member the answer carried, at the instant it was read.
   *
   * The headline count, and never a filtered list: in the as-of-now view the
   * late rows below are part of what a release right now would freeze, and a
   * count taken after they were lifted out would be short of the release it
   * describes.
   */
  readonly members = computed(() => this.state().members);

  /**
   * The members published after the planned cut-off.
   *
   * The same rows whichever instant the screen is read at; only what they mean
   * changes. Empty is the ordinary answer — an issue whose cut-off has not
   * passed, one with no planned cut-off, and every frozen issue.
   *
   * Empty too while a cut-off that has not arrived is being typed, and that one
   * is answered HERE rather than asked. Nothing lies after an instant that has
   * not happened, so the honest count is nought — but the read taken for such a
   * period is the as-of-now one (there being no future instant to resolve at),
   * and the answer to it names the rows that are late against the cut-off ON
   * FILE. Left as they came, the screen would list them against the saved
   * cut-off while a different one sits in the box beside them.
   */
  readonly lateMembers = computed(() => {
    const late = this.state().late;
    return this.previewCutoffAhead() ? NO_LATE_MEMBERS : late;
  });

  /**
   * The instant those rows are late against.
   *
   * The screen names it rather than assuming the stored planned cut-off,
   * because the two part company exactly where it matters: a period being
   * typed is asked about at the cut-off in the box, and the rows come back
   * counted from there. `undefined` where nothing named one, and the reader
   * falls back to the issue's own planned cut-off.
   *
   * A cut-off still ahead is the instant in the box, for the reason above: the
   * empty list belongs to what is being typed, not to what is on file.
   */
  readonly lateAfter = computed(() => {
    const answered = this.state().lateAfter;
    const typed = this.previewPeriod()?.to;
    return this.previewCutoffAhead() ? typed : answered;
  });

  /**
   * Whether there is a second reading to offer at all.
   *
   * Nothing but these rows separates the two views: with none of them, "as of
   * now" and "as of the planned cut-off" select the same messages, and a switch
   * between two identical answers is a control that does nothing.
   *
   * Withheld outright while a period is being proposed. The two readings are
   * about the period ON FILE, and the boxes in the edit panel name a different
   * one — a switch offered there would be asking the reader to choose between
   * two answers to a question they have already replaced.
   */
  readonly switchAvailable = computed(
    () => this.previewPeriod() === undefined && this.lateMembers().length > 0,
  );

  /** The instant the answer on screen was resolved at, as the server took it. */
  readonly viewedAt = computed(() => this.state().viewedAt);

  /** The period start the answer on screen was resolved over, as the server took it. */
  readonly viewedFrom = computed(() => this.state().viewedFrom);

  /**
   * The members the main table lists: everything except the late rows.
   *
   * They are not dropped, they are lifted — the section above the table holds
   * them, with the sentence that says what they mean at this instant. Listing
   * them twice would put the same message in two places on one screen and make
   * the table's own length disagree with the count in the heading.
   */
  readonly tableMembers = computed(() => {
    const late = this.lateMembers();
    if (late.length === 0) {
      return this.members();
    }
    const uids = new Set(late.map((member) => member.messageUid));
    return this.members().filter((member) => !uids.has(member.messageUid));
  });

  /**
   * Every source-series period this issue's period covers, in cut-off order.
   *
   * `undefined` on every regime but a compilation, where the question does not
   * apply. On a compilation it is the live survey while the issue is open — a
   * week that has gone out, one still open, a stretch nobody published — and the
   * same list rebuilt from the frozen header once it is released, where every
   * row is `PUBLISHED` because only a published issue ever contributed. What
   * reads it is drawn from the PRESENCE of an answer rather than from the
   * regime, because an empty list is a third and different answer: a compilation
   * whose period covers no source issue at all.
   */
  readonly sources = computed(() => this.state().sources);

  /**
   * Whether this issue's members came from another series' issues.
   *
   * Two witnesses, for the reason the imported flag has two: the series says
   * what the regime IS, and the frozen provenance says what this issue's members
   * WERE taken from. A published compilation whose series was re-pointed
   * afterwards still holds compiled rows, and reading only the series would
   * redraw its record as an ordinary member list.
   */
  readonly compiled = computed(
    () =>
      this.seriesRelation() === 'COMPILED_FROM_SOURCE' ||
      this.issue()?.membershipProvenance === 'COMPILED',
  );

  /**
   * What the compilation covered, each source issue holding the rows it printed.
   *
   * The groups are the compilation's RECORD of what it covered, and the survey
   * is what holds that record — the rows alone cannot. A source week that
   * printed nothing contributes no row, so a grouping built from the rows drops
   * it silently, and a year that quietly lists fifty-one of its fifty-two weeks
   * is a document claiming a week was never covered. A week that printed nothing
   * is still a week the year covers, so it stays a heading with nothing under
   * it. Only a `PUBLISHED` source is one: an open week and an uncovered stretch
   * contributed nothing and are not part of what this issue holds — what they
   * are is the release rail's question, and the sources card's.
   *
   * A derivation over {@link members} rather than a second list: the flat list
   * stays what selection, select-all and every count are taken from, so a
   * grouped table can never disagree with the box above it about how many rows
   * there are. Order is the survey's, which IS the frozen cut-off order the
   * union was built in; nothing is sorted here and a repeated uid stays with the
   * source that owned it. A row naming a source the survey does not hold gets a
   * group of its own after them — the two answers come from different reads, and
   * a member with nowhere to go must not vanish from the table it is counted in.
   * Without a survey the runs are cut from the rows alone, which is every fact
   * there is to cut them from.
   *
   * Empty on every other regime: the flat table is what those issues render, and
   * a group built over rows that name no source would be one group holding
   * everything.
   */
  readonly groupedMembers = computed<readonly MemberGroup[]>(() => {
    if (!this.compiled()) {
      return NO_GROUPS;
    }
    const groups = new Map<string, { source?: SourceIssueRef; members: IssueMember[] }>();
    for (const source of this.sources() ?? []) {
      const publicId = source.publicId;
      if (source.status !== 'PUBLISHED' || publicId === undefined) {
        continue;
      }
      groups.set(sourceGroupKey(publicId), {
        source: {
          publicId,
          seriesId: source.seriesId,
          week: source.week,
          weekTo: source.weekTo,
          year: source.year,
          cutoff: source.cutoff,
          intervalFrom: source.intervalFrom,
          name: source.name,
        },
        members: [],
      });
    }
    for (const member of this.members()) {
      const source = member.sourceIssue;
      const key = source ? sourceGroupKey(source.publicId) : MANUAL_GROUP;
      const group = groups.get(key);
      if (group) {
        group.members.push(member);
      } else {
        groups.set(key, { source, members: [member] });
      }
    }
    // The hand-added rows last, whatever order they arrived in: they were
    // printed by no source issue, so they follow every issue that printed one.
    const manual = groups.get(MANUAL_GROUP);
    groups.delete(MANUAL_GROUP);
    const ordered = [...groups].map(([key, group]) => ({ ...group, key }));
    return manual ? [...ordered, { ...manual, key: MANUAL_GROUP }] : ordered;
  });

  /**
   * The series this issue belongs to, handed over by the screen that provides
   * this facade.
   *
   * The signal itself, not a value off it: everything read from it is read when
   * it is asked for, so a series that arrives after the first load moves the
   * answers with it and nothing has to be pushed again.
   */
  attachSeries(series: Signal<PublicationSeries | undefined>): void {
    this.attachedSeries.set(series);
  }

  /**
   * Read the issue at the other instant.
   *
   * A re-read, not a filter: members, the rail and the omissions are all
   * resolved at whichever instant is asked for, so the screen cannot end up
   * showing one view's list beside another view's verdict.
   */
  setViewInstant(view: IssueViewInstant): void {
    this.chosenView.set(view);
  }

  /**
   * Read the issue over a period being proposed, or stop doing so.
   *
   * A re-read like the switch above, and for the same reason: members, the rail
   * and the omissions come from one resolution, so a period is asked about
   * rather than filtered for. Nothing is written — the issue on file keeps the
   * period it has until somebody saves.
   */
  setPreviewPeriod(period: PreviewPeriod | undefined): void {
    this.previewPeriod.set(period);
  }

  readonly audit = computed(() => this.state().audit);
  /** The curation decisions in force, as the server holds them. */
  readonly overrides = computed(() => this.state().overrides);
  readonly checklist = computed(() => this.state().checklist);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /**
   * The release or amend the server says is running, whoever started it.
   *
   * The issue's own fact rather than this browser's, which is what makes it
   * survive a reload and reach a second reader: the work takes tens of seconds
   * and the request outlives the dialog it was pressed from, so a page that
   * knew only about presses made in its own tab showed an idle issue while the
   * document was being written and offered to write it again.
   */
  readonly serverInFlight = computed(() => this.state().inFlight);

  /**
   * True while any write is in flight — what every control gates itself on.
   *
   * Two sources, and the server's is not optional: this facade's own action
   * covers what was pressed HERE, and the issue's own record covers what is
   * running anywhere. Every guard that refuses a second action while one runs
   * reads this, so both readings refuse alike.
   */
  readonly working = computed(
    () => this.action() !== undefined || this.serverInFlight() !== undefined,
  );

  /**
   * True while the release itself is running, wherever the reader is standing.
   *
   * Read by the release control as well as by the dialog, because the request
   * outlives the dialog: closing it leaves the publish running, and a page that
   * showed nothing would read as one where the press never landed. Answered
   * from the server's record too, so a refreshed page and a second reader see
   * the same spinner as the tab that pressed the button.
   */
  readonly publishing = computed(
    () => this.action() === 'publish' || this.serverInFlight()?.action === 'PUBLISH',
  );

  /** True while the amend is running, for the documents it is replacing. */
  readonly amending = computed(
    () => this.action() === 'amend' || this.serverInFlight()?.action === 'AMEND',
  );

  /**
   * Whether this client has to ASK whether the work has finished.
   *
   * Only where the work is somebody else's: a client that started the action
   * holds the request, and its own response is what tells it the work is over —
   * polling beside it would be a second answer to a question already being
   * answered, and a re-read landing mid-publish would redraw the screen from a
   * half-finished transaction.
   */
  private readonly awaitingServerWork = computed(
    () => this.serverInFlight() !== undefined && this.action() === undefined,
  );

  constructor() {
    // A RE-READ ON A TIMER, not a subscription to anything: the fact lives on
    // the issue, and the only way to learn it has cleared is to ask again. The
    // read is the ordinary workbench read, so nothing is held that the screen
    // is not showing. It runs only while the server says work is running, and
    // stops on the first answer that carries none — and with the injector, so a
    // screen left for another issue stops asking about this one.
    toObservable(this.awaitingServerWork)
      .pipe(
        switchMap((awaiting) => (awaiting ? timer(this.pollMs, this.pollMs) : EMPTY)),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.refreshTick.update((tick) => tick + 1));
  }

  readonly lastActionError = computed(() => this.actionError());
  /** The refusal's own text, where a dialog wants to show the ids it names. */
  readonly lastActionMessage = computed(() => this.actionMessage());

  /** Warnings the last publish attempt was refused over. Empty until one is. */
  readonly unacknowledgedWarnings = computed(() => this.unacknowledged());

  /**
   * The list, but only while the refusal it belongs to is the one on screen.
   *
   * Tied to the code rather than cleared by hand at every write, because there
   * are five of those and the sixth is the one that would leave a list of
   * citations standing under an unrelated refusal — a publish that failed for
   * its own reason, reading as though messages were citing an issue it never
   * asked about.
   */
  private readonly activeCitations = computed(() =>
    this.actionError() === ISSUE_CITED ? this.citations() : undefined,
  );

  /** The messages a refused removal named — a sample, capped server-side. */
  readonly citingMessages = computed(() => this.activeCitations()?.messages ?? []);

  /** How many cite it in all. Read this rather than the list's own length. */
  readonly citingCount = computed(() => this.activeCitations()?.count ?? 0);

  /** True when the server named fewer messages than it counted. */
  readonly citationsBounded = computed(() => this.citingCount() > this.citingMessages().length);

  // -------------------------------------------------------- what was left out

  /**
   * The omitted rows the server sent — a sample, not the whole set.
   *
   * From the same resolution as the members beside them, so the two panels can
   * never describe different answers to one question: a message is in the list
   * or it is in this one, judged at a single instant. Empty on an issue whose
   * membership is frozen, where the record is what went out and re-deciding it
   * against today's corpus would answer about a resolution that never happened.
   */
  readonly omissions = computed(() => this.state().omissions);

  /** How many candidates were dropped in all. Zero hides the panel. */
  readonly omissionCount = computed(() => this.state().omissionCount);

  /** True when the server sent fewer rows than it counted. */
  readonly omissionsBounded = computed(() => this.omissionCount() > this.omissions().length);

  // ---------------------------------------------- what a release went without

  /**
   * The messages published into this released issue's period after the release.
   *
   * A message whose publish date lies in a period released before it was
   * published is in no issue of the series, and until this list nothing said
   * so. Empty on every issue that is not published, so the warning that lists
   * them is drawn only where an amend can take them in.
   */
  readonly publishedSinceRelease = computed(() => this.state().publishedSinceRelease);

  // ------------------------------------------------ the period that comes next

  private readonly successorProbe = signal<SuccessorProbe | undefined>(undefined);

  /**
   * What the period after this one would be called.
   *
   * Its OWN fetch, outside the all-or-nothing chain, for the same reason the
   * omissions probe is: it decorates a sentence and is never worth failing the
   * workbench over. A refusal answers with no names, and every sentence that
   * would have used one falls back to wording that names no period rather than
   * to an empty gap where a name should be.
   *
   * The draft endpoint writes nothing, which is what makes it safe to ask from a
   * screen somebody is only reading.
   */
  private readonly successorState = toSignal(
    toObservable(computed(() => JSON.stringify(this.successorProbe() ?? null))).pipe(
      distinctUntilChanged(),
      switchMap((key) => {
        const probe = JSON.parse(key) as SuccessorProbe | null;
        if (!probe) {
          return of(NO_SUCCESSOR);
        }
        return this.api
          .issueDraft(probe.seriesId, {
            afterPublicId: probe.afterPublicId,
            lang: this.transloco.getActiveLang(),
          })
          .pipe(
            map((draft) => draft.descs ?? NO_SUCCESSOR),
            catchError(() => of(NO_SUCCESSOR)),
            startWith(NO_SUCCESSOR),
          );
      }),
    ),
    { initialValue: NO_SUCCESSOR },
  );

  /** What the next period is called, per language. Empty where nothing was asked. */
  readonly successorDescs = computed(() => this.successorState());

  /** Ask what the next period is called, or stop asking. Idempotent by content. */
  probeSuccessor(probe: SuccessorProbe | undefined): void {
    this.successorProbe.set(probe);
  }

  readonly previewRows = computed(() => this.previews());
  readonly previewWorking = computed(() => this.previewing());
  /** The raw refusal, so the screen can name REPORT_NOT_CONFIGURED as itself. */
  readonly lastPreviewError = computed(() => this.previewError());

  /** OPEN is the only status whose membership can still be curated. */
  readonly curatable = computed(() => this.issue()?.status === 'OPEN');

  /** Warnings an admin may accept; a BLOCKER is never in here. */
  readonly acknowledgeableWarnings = computed(() =>
    (this.checklist()?.rows ?? []).filter((r) => !r.passed && r.acknowledgeable),
  );

  /**
   * The warnings that carry no tickbox — said, but not asked about.
   *
   * A failing WARN the gate does not ask to be acknowledged still describes the
   * release about to be made: an interval that does not join the previous
   * issue's, a manual decision that no longer applies. Left out of the dialog
   * entirely, the release decision was taken in front of a shorter list than the
   * one the screen behind it showed — and the admin had to remember what the
   * rail said.
   */
  readonly informationalWarnings = computed(() =>
    (this.checklist()?.rows ?? []).filter(
      (r) => !r.passed && r.severity === 'WARN' && !r.acknowledgeable,
    ),
  );

  /**
   * The rows that actually stop a release, by the server's own severity.
   *
   * Not "everything failing that cannot be ticked": exactly one row is
   * acknowledgeable, so that reading filed every other failing WARN — an
   * unchained interval, an override that no longer bites — under blockers, next
   * to a button the server was perfectly willing to honour.
   */
  readonly blockers = computed(() =>
    (this.checklist()?.rows ?? []).filter((r) => !r.passed && r.severity === 'BLOCK'),
  );

  load(publicId: string): void {
    this.publicId.set(publicId);
    // The instant belongs to ONE issue as much as the previews below do: a
    // planned cut-off carried across would key the first read of the next issue
    // to a moment out of the previous one's period, and a view chosen for one
    // issue is not a preference about the next.
    this.plannedCutoff.set(undefined);
    this.chosenView.set(undefined);
    // A proposed period belongs to the issue it was typed against, and carrying
    // one over would read the next issue over a window out of the previous one.
    this.previewPeriod.set(undefined);
    // Previews belong to ONE issue. Carrying them across would offer a link to
    // last issue's document from the screen of the next one.
    this.previews.set([]);
    this.previewError.set(undefined);
    this.unacknowledged.set([]);
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);
    this.citations.set(undefined);
  }

  refresh(): void {
    this.clearError();
    this.refreshTick.update((tick) => tick + 1);
  }

  /**
   * Forget the last refusal without refetching.
   *
   * What a dialog does on the way IN: a refusal from the previous attempt would
   * otherwise be sitting there, describing a decision the admin has not taken yet.
   */
  clearError(): void {
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);
    this.unacknowledged.set([]);
    this.citations.set(undefined);
  }

  /**
   * Release the issue.
   *
   * Every mutation here refetches rather than patching the local state. A
   * publish stamps the cut-off, freezes the members, writes an audit entry and
   * may open a successor — four things this screen shows — so reconstructing the
   * result client-side would be re-implementing the transaction.
   */
  /**
   * @param onPublished runs only when the release actually happened, so the
   *   dialog closes and the toast fires on the transaction rather than on the
   *   click. A refusal leaves both alone and the dialog holds what was typed.
   *   The result carries the two facts the screen cannot see from the refetch
   *   alone: the instant membership was frozen, and the successor the release
   *   opened.
   */
  publish(request: PublishRequest, onPublished?: (result: PublishResult) => void): void {
    const publicId = this.publicId();
    if (!publicId || this.working()) {
      return;
    }
    this.action.set('publish');
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);
    this.unacknowledged.set([]);

    this.api.publishIssue(publicId, { ...request, version: this.heldVersion() }).subscribe({
      next: (result) => {
        this.action.set(undefined);
        this.refreshTick.update((tick) => tick + 1);
        onPublished?.(result);
      },
      error: (response: { error?: { code?: string; message?: string }; status?: number }) => {
        this.action.set(undefined);
        this.actionError.set(
          response?.error?.code ?? (response?.status === 403 ? 'FORBIDDEN' : 'UNKNOWN'),
        );
        this.actionMessage.set(response?.error?.message);
        // A 409 over warnings is not a failure to record and forget: it is the
        // server naming what it is still waiting for, and the dialog re-lists it.
        this.unacknowledged.set(unacknowledgedWarningsOf(response));
        this.rereadIfBehind(response);
      },
    });
  }

  /** @param onRetired runs only when the withdrawal actually happened — see publish(). */
  retire(reason: string, onRetired?: () => void): void {
    this.mutate(
      (publicId) => this.api.retireIssue(publicId, reason, this.heldVersion()),
      onRetired,
    );
  }

  /**
   * Render the current member list without releasing anything.
   *
   * Refetches afterwards on purpose: rendering touches the issue itself, so the
   * copy this screen holds — its stamp, and the version every write is submitted
   * under — would otherwise be one step behind the server's.
   *
   * Rendered at the instant the screen is showing: the planned cut-off in the
   * planned view, this moment in the as-of-now view — the same cut-off the
   * publish dialog offers first. Left to the server, the render followed the
   * series' default instant, so a reader on the other view checked a document
   * that held a different list from the one in front of them.
   */
  generatePreview(): void {
    const publicId = this.publicId();
    if (!publicId || this.previewing()) {
      return;
    }
    this.previewing.set(true);
    this.previewError.set(undefined);

    const at = this.viewInstant() === 'planned' ? this.plannedCutoff() : Date.now();
    this.api.previewIssue(publicId, at).subscribe({
      next: (rows) => {
        this.previewing.set(false);
        this.previews.set(rows);
        this.refreshTick.update((tick) => tick + 1);
      },
      error: (response: unknown) => {
        this.previewing.set(false);
        this.previewError.set(response);
      },
    });
  }

  /**
   * Fetch one language's rendered preview, with the caller's own credentials.
   *
   * The bytes come back rather than an address, because the address is not
   * followable: the endpoint answers only to a request carrying the bearer, so
   * a link to it downloads a 401 page. The caller decides what to do with the
   * document — this facade does no DOM work.
   *
   * Reported through the same `previewError` the render uses: from the reader's
   * side both failures are "the preview did not appear", and the refusal codes
   * (`NO_PREVIEW`, `ISSUE_NOT_OPEN`) are named by the same mapping either way.
   */
  fetchPreview(lang: string, onFile: (file: Blob) => void): void {
    const publicId = this.publicId();
    if (!publicId || this.previewing()) {
      return;
    }
    this.previewing.set(true);
    this.previewError.set(undefined);

    this.api.previewFile(publicId, lang).subscribe({
      next: (file) => {
        this.previewing.set(false);
        onFile(file);
      },
      error: (response: { error?: unknown }) => {
        this.previewing.set(false);
        this.recordPreviewFailure(response);
      },
    });
  }

  /**
   * Where one superseded document is streamed from, or `undefined` before an
   * issue is loaded.
   *
   * An ADDRESS rather than a fetch, unlike the preview above, because the
   * endpoint is shaped to be opened: it checks the admin role in code so that a
   * one-time ticket satisfies it, and a ticket is what lets a plain navigation —
   * which carries no bearer — reach it at all. Pulling the bytes through
   * `HttpClient` here would work and would throw that away, along with the
   * browser's own PDF viewer.
   *
   * The archive is addressed by the audit entry that wrote it. Every generation
   * is kept, so a language alone names as many files as the issue has been
   * amended, and only the entry tells them apart.
   */
  archiveFileUrl(auditEntryId: number, lang: string): string | undefined {
    const publicId = this.publicId();
    return publicId === undefined
      ? undefined
      : this.api.archiveFileUrl(publicId, auditEntryId, lang);
  }

  /**
   * Store a failed document fetch so the screen can name the rule it broke.
   *
   * A request that asked for bytes gets its ERROR body as bytes too, so the
   * coded refusal the server sent arrives as an unreadable blob and every
   * refusal on this path would degrade to "the preview could not be generated" —
   * including `NO_PREVIEW`, which has an obvious next step. Decoded back into the
   * envelope the rest of the section reads.
   */
  private recordPreviewFailure(response: { error?: unknown }): void {
    const body = response?.error;
    if (!(body instanceof Blob)) {
      this.previewError.set(response);
      return;
    }
    void body.text().then((text) => {
      try {
        this.previewError.set({ error: JSON.parse(text) as unknown });
      } catch {
        this.previewError.set(response);
      }
    });
  }

  reactivate(reason?: string): void {
    this.mutate((publicId) => this.api.reactivateIssue(publicId, reason, this.heldVersion()));
  }

  /**
   * Remove the issue and everything recorded against it.
   *
   * The one mutation here that does NOT refetch, because there is nothing left
   * to read: the issue, its members, its standing decisions and its own trail
   * are gone, and a refresh would answer with the 404 that produces — a failed
   * screen where the caller is about to leave for the series.
   *
   * A refusal is kept exactly as the other mutations keep theirs, plus the
   * messages a citation refusal named: they are what the reader has to act on,
   * and no sentence can carry them.
   *
   * @param onDeleted runs only when the removal actually happened, so the caller
   *   leaves the screen on the transaction rather than on the click.
   */
  deleteIssue(onDeleted?: () => void): void {
    const publicId = this.publicId();
    if (!publicId || this.working()) {
      return;
    }
    this.action.set('write');
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);
    this.citations.set(undefined);

    // The language goes with the request because the answer that matters here is
    // the refusal: a removal blocked by citations names the messages citing the
    // issue, and those titles are read on this screen.
    this.api
      .deleteIssue(publicId, this.heldVersion(), undefined, this.transloco.getActiveLang())
      .subscribe({
        next: () => {
          this.action.set(undefined);
          onDeleted?.();
        },
        error: (response: { error?: { code?: string; message?: string }; status?: number }) => {
          this.action.set(undefined);
          this.actionError.set(
            response?.error?.code ?? (response?.status === 403 ? 'FORBIDDEN' : 'UNKNOWN'),
          );
          this.actionMessage.set(response?.error?.message);
          this.citations.set(issueCitationsOf(response));
          this.rereadIfBehind(response);
        },
      });
  }

  /**
   * Put a message into the issue, addressed by whatever the admin typed.
   *
   * Two calls, sequenced, because the two halves answer different questions: the
   * messages endpoint knows what `NM-123-24` is, and only the curation endpoint
   * can decide whether it may go in. An id that names nothing never reaches the
   * second call — it is reported with the same code the server would have used,
   * so the dialog says the same thing either way.
   */
  includeByMessageId(messageId: string, reason: string, onDone?: () => void): void {
    this.mutate(
      (publicId) =>
        this.api.resolveMessage(messageId).pipe(
          concatMap((message) =>
            message?.id
              ? this.api.includeMembers(publicId, [message.id], reason, this.heldVersion())
              : throwError(() => ({
                  error: {
                    code: 'OVERRIDE_MESSAGE_NOT_FOUND',
                    message: messageId,
                  },
                })),
          ),
        ),
      onDone,
    );
  }

  /** One decision over a list: all of them go out, or none does. */
  excludeMembers(messageUids: readonly string[], reason: string, onDone?: () => void): void {
    this.mutate(
      (publicId) => this.api.excludeMembers(publicId, messageUids, reason, this.heldVersion()),
      onDone,
    );
  }

  /** Withdraw a curation decision, leaving the query to decide again. */
  clearOverride(messageUid: string, reason: string, onDone?: () => void): void {
    this.mutate(
      (publicId) => this.api.clearOverride(publicId, messageUid, reason, this.heldVersion()),
      onDone,
    );
  }

  /**
   * Take the issue's content decision again and replace its document in place.
   *
   * Handled like a publish rather than like an edit: it re-resolves and re-renders
   * behind the same warning gate, so a 409 over unacknowledged warnings has to
   * reach the dialog as a list of decisions still owed rather than as a failure.
   *
   * @param onAmended runs only when the amend actually happened.
   */
  amend(request: AmendRequest, onAmended?: (result: AmendResult) => void): void {
    const publicId = this.publicId();
    if (!publicId || this.working()) {
      return;
    }
    this.action.set('amend');
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);
    this.unacknowledged.set([]);

    this.api.amendIssue(publicId, { ...request, version: this.heldVersion() }).subscribe({
      next: (result) => {
        this.action.set(undefined);
        this.refreshTick.update((tick) => tick + 1);
        onAmended?.(result);
      },
      error: (response: { error?: { code?: string; message?: string }; status?: number }) => {
        this.action.set(undefined);
        this.actionError.set(
          response?.error?.code ?? (response?.status === 403 ? 'FORBIDDEN' : 'UNKNOWN'),
        );
        this.actionMessage.set(response?.error?.message);
        this.unacknowledged.set(unacknowledgedWarningsOf(response));
        this.rereadIfBehind(response);
      },
    });
  }

  /**
   * Open a successor edition, and hand its id back so the screen can follow it.
   *
   * The successor is a different issue at a different address, so the callback
   * carries its public id rather than the caller assuming the refetch will show
   * it: this issue is still the one on screen, and still the current one publicly,
   * until the new edition is published.
   */
  newEdition(onCreated?: (publicId: string) => void): void {
    const publicId = this.publicId();
    if (!publicId || this.working()) {
      return;
    }
    this.action.set('write');
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);

    // The PREDECESSOR's revision: it is the row this action reads and caps, and
    // the successor does not exist yet to have one.
    this.api.newEdition(publicId, undefined, this.heldVersion()).subscribe({
      next: (edition) => {
        this.action.set(undefined);
        this.refreshTick.update((tick) => tick + 1);
        onCreated?.(edition.publicId);
      },
      error: (response: { error?: { code?: string; message?: string }; status?: number }) => {
        this.action.set(undefined);
        this.actionError.set(
          response?.error?.code ?? (response?.status === 403 ? 'FORBIDDEN' : 'UNKNOWN'),
        );
        this.actionMessage.set(response?.error?.message);
        this.rereadIfBehind(response);
      },
    });
  }

  /**
   * Edit the issue: its names, its numbering, its file names, its interval, and
   * the report it renders with.
   *
   * Refetches like every other mutation, and here it is not optional: moving the
   * interval re-derives week/year and re-renders every name the series suggested,
   * and an edition can reach a file-name pattern — so the response is the only
   * thing that knows what the issue is now called.
   *
   * Absent means "leave it alone" and the EMPTY STRING means "hand it back to
   * the series" — never null, which the server cannot tell apart from absent
   * once the body is read, and which would make this partial edit wipe the
   * fields it does not mention.
   */
  /**
   * @param onSaved runs only when the write actually landed, so a drawer closes
   *   on the transaction rather than on the click — a refusal leaves it open
   *   with what was typed still in it. Same arrangement every dialog here uses.
   */
  edit(
    edit: {
      names?: Readonly<Record<string, string>>;
      edition?: string;
      weekLabel?: string;
      yearLabel?: string;
      fileNames?: Readonly<Record<string, string>>;
      reportId?: string;
      reportParams?: Readonly<Record<string, unknown>>;
      intervalFrom?: number;
      intervalTo?: number;
      criteriaOverride?: IssueCriteria;
      clearCriteriaOverride?: boolean;
    },
    onSaved?: () => void,
  ): void {
    this.mutate(
      (publicId) => this.api.updateIssue(publicId, { ...edit, version: this.heldVersion() }),
      onSaved,
    );
  }

  /**
   * Replace one language's document.
   *
   * A mutation like any other, so it refetches: an upload archives what it
   * replaces, writes an audit entry, and flips the sticky flag that decides
   * whether the next publish regenerates over it. Three things this screen
   * shows, none of them reconstructable from the request.
   */
  uploadFile(lang: string, file: File): void {
    this.mutate((publicId) => this.api.uploadIssueFile(publicId, lang, file, this.heldVersion()));
  }

  clearFile(lang: string): void {
    this.mutate((publicId) => this.api.clearIssueFile(publicId, lang, this.heldVersion()));
  }

  setLink(lang: string, link: string): void {
    this.mutate((publicId) => this.api.setIssueLink(publicId, lang, link, this.heldVersion()));
  }

  /**
   * Re-read the issue when the server says the screen is behind it.
   *
   * Two refusals qualify, and neither is answered by pressing the button again.
   * A stale revision says the row moved underneath the admin, and the re-read
   * is what shows them what moved and what makes the next attempt carry a
   * revision the server will accept. `ISSUE_BUSY` says work the screen did not
   * know about is already running, and the re-read is what fetches the fact
   * behind the refusal — without it the page goes on looking idle beside a
   * sentence saying it is not. Every other refusal leaves the screen alone:
   * re-reading after a rule refusal would replace the state the admin is
   * looking at with the same state they were already looking at.
   */
  private rereadIfBehind(response: { error?: { code?: string } }): void {
    const code = response?.error?.code;
    if (code === STALE_VERSION || code === ISSUE_BUSY) {
      this.refreshTick.update((tick) => tick + 1);
    }
  }

  private mutate(call: (publicId: string) => Observable<unknown>, onDone?: () => void): void {
    const publicId = this.publicId();
    if (!publicId || this.working()) {
      return;
    }
    this.action.set('write');
    this.actionError.set(undefined);
    this.actionMessage.set(undefined);

    call(publicId).subscribe({
      next: () => {
        this.action.set(undefined);
        this.refreshTick.update((tick) => tick + 1);
        onDone?.();
      },
      error: (err: { error?: { code?: string; message?: string }; status?: number }) => {
        this.action.set(undefined);
        // The backend's own code where it sent one: it distinguishes a state
        // conflict an admin can resolve from a permission they simply lack, and
        // "something went wrong" hides which.
        this.actionError.set(err?.error?.code ?? (err?.status === 403 ? 'FORBIDDEN' : 'UNKNOWN'));
        this.actionMessage.set(err?.error?.message);
        this.rereadIfBehind(err);
      },
    });
  }
}
