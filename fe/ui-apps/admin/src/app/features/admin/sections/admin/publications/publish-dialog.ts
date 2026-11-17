import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { Icon } from '../../../../../shared/icons/icon';
import {
  PublicationSeriesApiService,
  type PublicationIssue,
  type PublishCheck,
  type PublishRequest,
} from '../../../services/api/publication-series-api.service';
import { warningKey } from './publication-errors';
import { checkDetailKey, checkDetailText } from './publication-status';

/** Which of the two cut-offs the release will carry. */
type CutoffMode = 'PLANNED' | 'NOW';

/** The rail for one instant: the rows, or the fact that nothing was asked. */
interface CutoffRail {
  readonly rows?: readonly PublishCheck[];
  /**
   * How many messages the resolution behind those rows selected.
   *
   * Absent on a server that does not send it, where the caller falls back to
   * the count the screen already had rather than showing none at all.
   */
  readonly memberCount?: number;
  readonly loading: boolean;
  readonly failed: boolean;
}

const NO_RAIL: CutoffRail = { loading: false, failed: false };

/**
 * The release decision, asked once and in full.
 *
 * TWO CUT-OFFS, AND NO THIRD. An open issue has a period start and a planned
 * cut-off, and the only alternative worth offering is closing it right now.
 * Every further instant was a number nobody could relate to the ones on the
 * screen behind this dialog: the member count, the warnings and the late list
 * all move with the moment they are read at, and a release stamped at a moment
 * that appears nowhere else produced a count that could be checked against
 * nothing.
 *
 * **Which cut-off.** The cut-off is a fact about the CONTENT, not about the
 * click: a weekly notice closes when it goes out, but a yearly in-force list
 * closes at the boundary its public window names, however late the document was
 * actually produced. So the planned cut-off is offered as it stands, and "now"
 * beside it. A planned cut-off that has not passed is not offered at all —
 * a period cannot end in the future without claiming to contain messages nobody
 * has published — and the reason is said rather than left to be guessed at.
 *
 * **Changing it is not a third option: it MOVES the planned cut-off.** The date
 * reached from here travels through the issue's own edit path, which is what
 * makes a cut-off changed here and one changed in the edit panel the same act
 * with the same numbers — the screen behind re-reads at it, and this dialog's
 * own count, warnings and label follow the issue back.
 *
 * **Not what the issue is called.** A release publishes the issue as it stands.
 * What an issue says differently from its series — its name, its printed
 * numbering, its file name, its report — is one decision and lives in the
 * tailoring drawer on the open issue; a name typed at the last moment beside
 * a document already rendered was a second way to say it, and one that could
 * disagree with the file.
 *
 * **Which warnings are accepted.** The acknowledgeable rows come from the
 * checklist, but the checklist was fetched BEFORE this attempt and the server
 * re-resolves at publish time — so a warning can appear in between. That is why
 * submitting is not gated on ticking them all: the server is the authority, and
 * when it refuses it names exactly what it is still waiting for, which this
 * dialog then re-lists without losing anything that was typed.
 *
 * And the rail follows the cut-off. Membership is resolved AS OF it, so a rail
 * taken at the other one describes a release the admin has just declined to
 * make: it can warn about members published since and stay silent about the ones
 * that were open at it. Whichever of the two instants the screen behind this
 * dialog is NOT being read at therefore gets its own request, and the boxes on
 * screen — and the number above them — are the ones the release will actually be
 * judged against. That includes "now": on a screen read at the planned cut-off,
 * publishing right now is the different release.
 */
@Component({
  selector: 'app-publish-dialog',
  imports: [Dialog, DateTimeField, FormField, Icon, TranslocoPipe],
  templateUrl: './publish-dialog.html',
  host: { class: 'contents' },
})
export class PublishDialog {
  readonly visible = model(false);

  readonly issue = input.required<PublicationIssue>();

  /**
   * Which instant the screen behind this dialog is being read at.
   *
   * The dialog opens on the release the admin was just looking at: somebody
   * reading the issue as it stood at its planned cut-off, and then pressing
   * publish, means to close it there — and a dialog that opened on "now" would
   * quietly offer a different release from the one on the screen behind it.
   *
   * The whole of the answer, and deliberately: the series' own habit about where
   * its cut-off falls is what decides this value where the reader has not said
   * otherwise, so it has already been taken into account by the time it arrives
   * here. Weighed a second time it would outvote the reader who had.
   */
  readonly viewInstant = input<'now' | 'planned'>('now');

  /**
   * How many messages the screen behind this dialog is showing.
   *
   * Passed in rather than read off the issue: the caller has the resolved member
   * list on screen, and an issue's stored count is the count of the last
   * resolution rather than of the one about to be frozen. It answers for
   * {@link viewInstant} and for nothing else — the other instant resolves a
   * different member list, and its count comes back with its own rail.
   */
  readonly memberCount = input(0);

  /**
   * Whether this release opens the next issue at all. Not what it is called.
   *
   * The fact is a property of the SERIES — a cadence opens its own next issue —
   * so it is certain before the release and belongs in the sentence. The NAME is
   * not: it can only be predicted from the issue's plan, and this dialog is
   * where the cut-off the successor is chained from is still being chosen, so a
   * period named here is named from an instant the admin may be about to
   * decline. The one sentence that cannot be taken back promises what it knows.
   */
  readonly opensSuccessor = input(false);

  /**
   * The page's checklist rows, taken at {@link viewInstant}.
   *
   * What the dialog opens on. A release the dialog describes at the OTHER
   * instant asks for its own rail instead, and these stand only until it
   * answers.
   */
  readonly warnings = input<readonly PublishCheck[]>([]);

  /**
   * The failing warnings the gate does not ask about, said but not asked.
   *
   * An interval that does not join the previous issue's, or an override that no
   * longer bites, is a fact about the release being made, and the release is
   * made from this dialog:
   * listed only on the screen behind it, they were what the admin had to
   * remember rather than what they could read.
   */
  readonly informational = input<readonly PublishCheck[]>([]);

  /** What the server refused the last attempt over. Empty until it does. */
  readonly unacknowledged = input<readonly string[]>([]);

  readonly working = input(false);

  /** A refused attempt, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  readonly submitted = output<PublishRequest>();

  /**
   * A new planned cut-off for the issue, to be written by whoever owns it.
   *
   * Raised rather than sent from here: moving the cut-off is an edit of the
   * issue, and the screen that holds this dialog is the one that already writes
   * every other edit — with the revision check and the refetch that go with it.
   * A dialog that wrote it itself would be a second author of the same field.
   */
  readonly plannedCutoffChanged = output<number>();

  private readonly api = inject(PublicationSeriesApiService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /**
   * What the issue is called, so the header names what is about to go out.
   *
   * The same rule the page's own heading uses: the name in the language being
   * read, the first one otherwise, and the public id for an issue that has no
   * name at all — a header saying only "Publish issue" is one that would read
   * the same over any of them.
   */
  protected readonly issueName = computed(() => {
    const issue = this.issue();
    const descs = issue.descs ?? [];
    const lang = this.language.selectedLanguage();
    return (descs.find((desc) => desc.lang === lang) ?? descs[0])?.name ?? issue.publicId;
  });

  private readonly acknowledged = signal<ReadonlySet<string>>(new Set());

  /**
   * The clock reading the "now" option quotes, taken when the dialog opens.
   *
   * A cut-off stamped "now" is stamped at the moment the button is pressed, and
   * quoting a value that ticked while the dialog sat open would be quoting a
   * different instant every render for no gain.
   */
  private readonly openedAt = signal(Date.now());

  /**
   * Whether the form describes THIS opening yet.
   *
   * Between the dialog becoming visible and the seed landing, the cut-off answer
   * on file is the one the previous opening left — and anything reading it in
   * that gap describes a release nobody has proposed. It only shows where a
   * request hangs off the answer, which is why the rail waits for it.
   */
  private readonly seeded = signal(false);

  /**
   * The refusal that was already on file when this opening began.
   *
   * Both refusal surfaces — the warnings still to be acknowledged, and the
   * sentence beside them — are inputs held by the screen behind, and that state
   * outlives a cancelled dialog. So reopening showed a notice about an attempt
   * from a previous opening, over a form that had just been reseeded and above
   * tickboxes that had just been cleared: everything on screen said "answer
   * this" about a release nobody had asked for yet.
   *
   * What was standing at the moment of the open is therefore remembered and not
   * shown. A refusal that arrives AFTER it is a different answer and shows —
   * which is also why this holds the value rather than a flag saying an attempt
   * was made: a cut-off edit refused from inside this dialog is not a publish,
   * and its sentence still has to reach the reader who asked for it.
   */
  private readonly refusalAtOpen = signal<readonly string[]>([]);
  private readonly errorKeyAtOpen = signal<string | undefined>(undefined);

  constructor() {
    // Seeded when the dialog OPENS, not per change: the default is an opening
    // position, and re-applying it while somebody is answering would undo the
    // choice they came here to make.
    effect(() => {
      const open = this.visible();
      untracked(() => {
        if (open) {
          this.seed();
        }
        this.seeded.set(open);
      });
    });

    // The box closes when the issue comes back carrying the instant it asked
    // for. The write belongs to the screen behind this dialog and its result
    // arrives as a fresh issue, so that is the only witness here that it went
    // through — a refusal leaves the cut-off where it was, and the box stays
    // open above the refusal that named why.
    effect(() => {
      const stored = this.issue().intervalTo;
      untracked(() => {
        const requested = this.requestedCutoff();
        if (requested !== undefined && requested === stored) {
          this.requestedCutoff.set(undefined);
          this.changingCutoff.set(false);
          // The ticks went with the release that was on offer a moment ago. A
          // moved cut-off resolves a different member list, so a warning that
          // still comes up is coming up over rows nobody has looked at — and an
          // acknowledgement carried across would be one nobody gave.
          this.acknowledged.set(new Set());
        }
      });
    });
  }

  // ------------------------------------------------------------ the cut-off

  /** The instant the issue is planned to close at — option one, where it can be taken. */
  protected readonly plannedCutoff = computed(() => this.issue().intervalTo);

  /**
   * Whether that instant can close a period yet.
   *
   * A cut-off ends a period of CONTENT, so one that has not happened would claim
   * to contain messages nobody has published. Refused here as well as by the
   * server — not because the server needs help, but because an option that can
   * be picked and then reported back as impossible is a slower way of saying no.
   */
  protected readonly plannedAvailable = computed(() => {
    const planned = this.plannedCutoff();
    return planned !== undefined && planned <= Date.now();
  });

  /**
   * Why option one cannot be taken, or `undefined` when it can.
   *
   * Two different situations and they are told apart, because the answer to each
   * is different: an issue that records no cut-off needs one given to it, and
   * one whose cut-off is still ahead needs either the wait or a release now.
   */
  protected readonly plannedUnavailableKey = computed(() => {
    const planned = this.plannedCutoff();
    const available = this.plannedAvailable();
    if (planned === undefined) {
      return 'admin.publications.publish.cutoffPlannedNone';
    }
    return available ? undefined : 'admin.publications.publish.cutoffPlannedFuture';
  });

  private readonly chosenMode = signal<CutoffMode>('NOW');

  /**
   * The cut-off actually in force.
   *
   * Option one cannot be in force while it is refused: the planned instant can
   * move into the future under an open dialog — that is exactly what changing it
   * from here can do — and a choice left standing over a disabled radio would
   * publish at an instant the form is showing as unavailable.
   */
  protected readonly mode = computed<CutoffMode>(() => {
    const chosen = this.chosenMode();
    const available = this.plannedAvailable();
    return chosen === 'PLANNED' && available ? 'PLANNED' : 'NOW';
  });

  protected setMode(mode: CutoffMode): void {
    this.chosenMode.set(mode);
  }

  private seed(): void {
    // The instant the screen is being read at, and nothing else: somebody
    // reading the issue at its planned cut-off and then pressing publish means
    // to close it there, and somebody who switched the screen to "as of now"
    // means the opposite — including on a series that ordinarily closes at the
    // end of its period, whose habit is already what put the screen where it is.
    // Consulting that habit a second time here overrode the only reader who had
    // said anything.
    const viewed = this.viewInstant() === 'planned';
    this.chosenMode.set(this.plannedAvailable() && viewed ? 'PLANNED' : 'NOW');
    this.changingCutoff.set(false);
    this.requestedCutoff.set(undefined);
    this.acknowledged.set(new Set());
    this.refusalAtOpen.set(this.unacknowledged());
    this.errorKeyAtOpen.set(this.errorKey());
    this.openedAt.set(Date.now());
  }

  /** The planned instant as the option prints it, or a dash where there is none. */
  protected readonly plannedAt = computed(() => {
    const planned = this.plannedCutoff();
    return planned === undefined ? '—' : this.localeFormat.formatDateTime(planned);
  });

  /** The clock reading option two would stamp. */
  protected readonly nowAt = computed(() => this.localeFormat.formatDateTime(this.openedAt()));

  // ------------------------------------------------ moving the planned cut-off

  /** Whether the one box that moves the planned cut-off is showing. */
  protected readonly changingCutoff = signal(false);

  /**
   * The instant being typed, as a Signal Form so the shared pickers can drive it.
   *
   * The native `<input type="datetime-local">` renders its popup in the BROWSER
   * locale rather than the app language, which is why the shared pickers exist.
   */
  private readonly cutoffModel = signal<{ cutoff: Date | null }>({ cutoff: null });
  protected readonly cutoffForm = form(this.cutoffModel);

  protected readonly typedCutoff = computed(() => this.cutoffModel().cutoff);

  /** The instant an edit asked for, until the issue comes back carrying it. */
  private readonly requestedCutoff = signal<number | undefined>(undefined);

  protected startCutoffChange(): void {
    const planned = this.plannedCutoff();
    this.cutoffModel.set({ cutoff: planned === undefined ? null : new Date(planned) });
    this.changingCutoff.set(true);
  }

  protected cancelCutoffChange(): void {
    this.requestedCutoff.set(undefined);
    this.changingCutoff.set(false);
  }

  /**
   * A cut-off that does not lie after the content period's own start.
   *
   * The release resolves over the half-open window (period start, cut-off], so a
   * cut-off at or before the start is not a narrow window but an impossible one.
   * The edit that would store it is refused, and refusing it here means the
   * refusal arrives beside the box rather than as a coded failure underneath.
   */
  protected readonly typedNotAfterStart = computed(() => {
    const typed = this.typedCutoff();
    const start = this.issue().intervalFrom;
    return typed !== null && start !== undefined && typed.getTime() <= start;
  });

  // Every read happens unconditionally: a short-circuited `||` would leave the
  // later signals untracked on the pass that took the first branch.
  protected readonly cutoffChangeDisabled = computed(() => {
    const typed = this.typedCutoff();
    const beforeStart = this.typedNotAfterStart();
    const planned = this.plannedCutoff();
    const working = this.working();
    return working || typed === null || beforeStart || typed.getTime() === planned;
  });

  protected confirmCutoffChange(): void {
    const typed = this.typedCutoff();
    if (this.cutoffChangeDisabled() || typed === null) {
      return;
    }
    this.requestedCutoff.set(typed.getTime());
    this.plannedCutoffChanged.emit(typed.getTime());
  }

  /**
   * What pressing Publish will actually do, in one sentence.
   *
   * Whole wordings rather than one assembled from fragments: which instant the
   * content closes at, whether a successor opens and how many messages go out
   * are different facts about the same act, and a sentence stitched together
   * from translated pieces reads like one in every language but the one it was
   * written in. The count decides the shape as much as the other two do —
   * "1 message(s)" is nobody's sentence.
   */
  protected readonly summaryKey = computed(() => {
    const planned = this.mode() === 'PLANNED';
    const next = this.opensSuccessor();
    // Read before the branch: without a number the two count-less wordings are
    // told apart by this alone, and a `count` that stays `undefined` across a
    // failure arriving would otherwise leave the sentence saying "counting".
    const failed = this.railFailed();
    const count = this.headlineCount();
    const base = planned
      ? 'admin.publications.publish.summaryPlanned'
      : 'admin.publications.publish.summaryNow';
    // A sentence with no number in it while the number is not known. The count
    // belongs to ONE instant, and quoting the screen's while the server is
    // working out this instant's states a figure this release will not produce.
    // Which of the two reasons it is stays visible: a count still on its way and
    // a count that could not be worked out ask different things of the reader.
    if (count === undefined) {
      return `${base}${failed ? 'Unknown' : 'Counting'}${next ? 'Next' : ''}`;
    }
    return `${base}${next ? 'Next' : ''}${count === 1 ? 'One' : ''}`;
  });

  /** The instant the sentence quotes: the planned one, or this dialog's own clock. */
  private readonly summaryInstant = computed(() => {
    const planned = this.plannedCutoff();
    return this.mode() === 'PLANNED' && planned !== undefined ? planned : this.openedAt();
  });

  protected readonly summaryParams = computed(() => ({
    count: this.headlineCount() ?? 0,
    at: this.localeFormat.formatWeekdayDateTime(this.summaryInstant()),
  }));

  /**
   * How many messages the release being described would carry.
   *
   * The count is a fact about an INSTANT: a release closed at the planned
   * cut-off freezes the members alive at that moment, which is not the list a
   * screen read as of now resolved. So an instant this dialog asked about is
   * counted by the rail fetched for it, and `undefined` — not the other
   * instant's number — is the honest answer until that answer is in.
   *
   * A rail that FAILED is `undefined` for the same reason rather than falling
   * back: the number beside a "could not be checked" notice would be the other
   * instant's, which is exactly the figure this whole arrangement exists to keep
   * off the screen.
   *
   * A rail that answered without a count is a server that does not send one; it
   * falls back to the caller's, which is the count this dialog showed before the
   * field existed.
   */
  protected readonly headlineCount = computed<number | undefined>(() => {
    const live = this.memberCount();
    const asked = this.railCutoff() !== undefined;
    const rail = this.railState();
    if (!asked) {
      return live;
    }
    if (rail.loading || rail.failed) {
      return undefined;
    }
    return rail.memberCount ?? live;
  });

  // Every read happens unconditionally: a short-circuited `||` would leave the
  // later signals untracked on the pass that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const changing = this.changingCutoff();
    // Publishing while the cut-off box is open would release at the STORED
    // instant while another one is on screen in a box beside it, which is the
    // one reading of this dialog that cannot be defended afterwards.
    return this.working() || changing;
  });

  /**
   * The instant to ask the server about, or `undefined` where the page's own
   * rail already answers for it.
   *
   * Whichever of the two the screen behind the dialog is NOT being read at.
   * Everything handed in — the count, the rows — describes the viewed instant,
   * and taking that for the answer to the other one quoted a number and a rail
   * belonging to the release the admin had just switched away from. `cutoff`
   * absent is "now" on the wire, which is the same thing the release itself
   * says.
   *
   * Every signal is read before the branch, so none goes untracked on the pass
   * that takes the first one.
   */
  private readonly railCutoff = computed<{ publicId: string; cutoff?: number } | undefined>(() => {
    const open = this.visible();
    const publicId = this.issue().publicId;
    const planned = this.plannedCutoff();
    const plannedMode = this.mode() === 'PLANNED';
    const readAtPlanned = this.viewInstant() === 'planned';
    const seeded = this.seeded();
    if (!open || !seeded) {
      return undefined;
    }
    if (plannedMode) {
      // Nothing to ask when the screen is already read there: the rows and the
      // count handed in were taken at this very instant.
      return readAtPlanned || planned === undefined ? undefined : { publicId, cutoff: planned };
    }
    return readAtPlanned ? { publicId } : undefined;
  });

  /**
   * The rail for the instant on offer, re-asked as it moves and never otherwise.
   *
   * A failure is kept as a failure rather than as an empty rail: no warnings and
   * "we could not find out" must not read alike on a screen whose next button
   * publishes. The dialog falls back to the page's own rows and says the check
   * did not answer, and the release itself is still gated server-side.
   */
  private readonly railState = toSignal(
    toObservable(this.railCutoff).pipe(
      distinctUntilChanged((a, b) => a?.publicId === b?.publicId && a?.cutoff === b?.cutoff),
      switchMap((request) => {
        if (!request) {
          return of(NO_RAIL);
        }
        return this.api.publishChecklist(request.publicId, false, request.cutoff).pipe(
          map(
            (checklist): CutoffRail => ({
              rows: checklist.rows.filter((row) => !row.passed && row.acknowledgeable),
              // The count the rail was computed over: same resolution, same
              // instant, so the sentence above the boxes and the boxes
              // themselves describe one release.
              memberCount: checklist.memberCount,
              loading: false,
              failed: false,
            }),
          ),
          catchError(() => of<CutoffRail>({ loading: false, failed: true })),
          startWith<CutoffRail>({ loading: true, failed: false }),
        );
      }),
    ),
    { initialValue: NO_RAIL },
  );

  /** True while the rail for the offered instant is still being fetched. */
  protected readonly railLoading = computed(() => this.railState().loading);

  /** True when that rail could not be fetched at all. */
  protected readonly railFailed = computed(() => this.railState().failed);

  /**
   * The rows the boxes are drawn from: this release's own, where they exist.
   *
   * The page's rail describes a release at the instant the screen is being read
   * at, which is a different release with a different member list — so the
   * moment the dialog names the other one, the rows IT produced are the only
   * honest thing to tick.
   */
  private readonly activeWarnings = computed(() => this.railState().rows ?? this.warnings());

  /**
   * The warnings, in the vocabulary the release gate compares.
   *
   * Read off the row rather than translated here. The rail names its rows after
   * the QUESTION it asked and the gate compares the resolver's own vocabulary;
   * the server states the correspondence, and a client that kept its own table
   * of it sent a ticked box back under a word the gate did not recognise. A row
   * whose two names coincide carries no `acknowledgeCode`, and its own code is
   * the right answer.
   */
  protected readonly warningRows = computed(() =>
    this.activeWarnings().map((row) => {
      const code = row.acknowledgeCode ?? row.code;
      return { code, labelKey: warningKey(code), detail: this.detailOf(row) };
    }),
  );

  /**
   * The rows that are only said, in the sentence that says what each one means.
   *
   * The row's own label names the QUESTION it answered, which read as a failure
   * says only that something about the interval or the overrides is wrong. The
   * sentence is `undefined` for a code this build has no sentence for, and the
   * code itself then stands rather than an unresolved key path.
   */
  protected readonly informationalRows = computed(() =>
    this.informational().map((row) => ({
      code: row.code,
      detailKey: checkDetailKey(row.code),
      detail: this.detailOf(row),
    })),
  );

  /**
   * What a row was measured to be, in the reader's language where possible.
   *
   * The server states the answer as a code plus its values rather than only as
   * an English sentence, so the small text under a warning is read in the same
   * language as the warning itself. Instants are epoch milliseconds on the wire
   * and go through this dialog's own clock — the one that renders the cut-off a
   * few lines below — instead of arriving pre-formatted in another zone. A
   * status or a selection regime among the values is named with the label the
   * rest of the screen gives it, rather than as the stored code.
   */
  private detailOf(row: PublishCheck) {
    return checkDetailText(
      row,
      (value) => this.localeFormat.formatDateTime(value),
      (key) => this.transloco.translate(key),
    );
  }

  /** What the band is counting: everything under it, ticked or not. */
  protected readonly warningCount = computed(
    () => this.warningRows().length + this.informationalRows().length,
  );

  /**
   * The warnings this opening's own attempt was refused over.
   *
   * Empty until one is made — see {@link refusalAtOpen} for why a list left over
   * from a previous opening is not this opening's answer.
   */
  private readonly liveRefusal = computed(() => {
    const codes = this.unacknowledged();
    return codes === this.refusalAtOpen() ? [] : codes;
  });

  /** Whether the refusal notice has anything to say about THIS opening. */
  protected readonly refused = computed(() => this.liveRefusal().length > 0);

  /** The refusal sentence, once it is about something that happened here. */
  protected readonly liveErrorKey = computed(() => {
    const key = this.errorKey();
    return key === this.errorKeyAtOpen() ? undefined : key;
  });

  protected readonly unacknowledgedRows = computed(() =>
    // A refusal names the resolver's codes already, so nothing is mapped here —
    // which is what keeps the tick state of a row listed by BOTH the rail and
    // the refusal as one answer.
    this.liveRefusal().map((code) => ({ code, labelKey: warningKey(code) })),
  );

  protected isAcknowledged(code: string): boolean {
    return this.acknowledged().has(code);
  }

  protected toggleAcknowledged(code: string): void {
    this.acknowledged.update((current) => {
      const next = new Set(current);
      if (!next.delete(code)) {
        next.add(code);
      }
      return next;
    });
  }

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    const planned = this.plannedCutoff();
    this.submitted.emit({
      // Absent rather than null: "now" is the absence of a chosen instant, and
      // the wire says so by not carrying the field at all.
      cutoff: this.mode() === 'PLANNED' ? planned : undefined,
      acknowledgedWarnings: [...this.acknowledged()],
    });
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
