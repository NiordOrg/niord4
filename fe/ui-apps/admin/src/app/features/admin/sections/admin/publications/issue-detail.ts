import { httpResource } from '@angular/common/http';
import {
  DOCUMENT,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { distinctUntilChanged, map, of, switchMap, timer } from 'rxjs';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { ApiUrlResolver } from '../../../../../core/config/absolute-api-url';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Card } from '../../../../../shared/components/card/card';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { ExpectedDate } from '../../../../../shared/components/expected-date/expected-date';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { RelativeTimePipe } from '../../../../../shared/pipes/relative-time.pipe';
import type { MessageStatus } from '../../../../../shared/models/message-status.model';
import { messageStatusToStateClass } from '../../../../../shared/pipes/status-class.pipe';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { withEpochDates } from '../../../../../shared/utils/epoch-dates';
import {
  PublicationSeriesApiService,
  type AmendRequest,
  type IssueArchiveFile,
  type IssueAuditEntry,
  type IssueCriteria,
  type IssueCriterion,
  type IssueMember,
  type IssueOverride,
  type IssuePreview,
  type PreviewMiss,
  type PublicationIssue,
  type PublicationSeries,
  type PublishCheck,
  type PublishRequest,
  type PublishResult,
  type SourceIssue,
  type SourceIssueRef,
} from '../../../services/api/publication-series-api.service';
import { PublicationAmendDialog } from './amend-dialog';
import { PublicationIncludeDialog, type IncludeRequest } from './include-dialog';
import {
  IssueDetailFacade,
  type IssueViewInstant,
  type PreviewPeriod,
  type SuccessorProbe,
} from './issue-detail.facade';
import { laterEdition } from './issue-number';
import { IssueOverridesDrawer, type IssueOverridesPatch } from './issue-overrides-drawer';
import {
  anyOverridden,
  issueValues,
  overrideMarks,
  paramText,
  seriesDefaults,
} from './issue-overrides';
import { MemberChangeNote } from './member-change-note';
import { MessageRef } from './message-ref';
import {
  boundSourceKey,
  cutoffBoundSource,
  isMarkedBound,
  publishedApartFromCutoff,
} from './publication-bounds';
import { ISSUE_NOT_OPEN, publicationErrorCode, publicationErrorKey } from './publication-errors';
import { PublicationForeignDomain } from './publication-foreign-domain';
import {
  ACTOR_KEY_PREFIX,
  CHECK_CODES,
  CHECK_DETAIL_CODES,
  auditActionKey,
  auditActorKey,
  checkDetailKey as checkRowDetailKey,
  checkDetailText,
  checkKey as checkRowKey,
  checklistVerdict,
  computedIssueStatusClass,
  computedIssueStatusKey,
  criteriaMissKey,
  issueBadgeClass,
  issueStatusKey,
  MEMBER_STATUSES,
  memberStatusKey,
  ownerTransfer,
  publishedChecklist,
  type PublishedCheck,
} from './publication-status';
import { PublishDialog } from './publish-dialog';
import { PublicationReasonDialog } from './reason-dialog';
import { SeriesCriteriaEditor } from './series-criteria-editor';
import { ownerVerdict, seriesWritable } from './series-rules';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/** Which curation question the one reason dialog is currently asking. */
type CurationAction = 'EXCLUDE' | 'CLEAR';

/** How long a typed period must be left alone before the screen is re-read. */
const PERIOD_DEBOUNCE_MS = 300;

/** What an issue that records no edition is: the first one. */
const DEFAULT_EDITION = '1';

/** The longest an edition may be, matching the column that stores it. */
const EDITION_MAX_LENGTH = 64;

/**
 * The checklist vocabulary, re-published from where it is defined.
 *
 * The rail and the release dialog name the same rows, so the codes live beside
 * the other stored vocabularies rather than on one of the two screens. Kept
 * reachable from here because a spec walks it against both bundles: a code added
 * without wording is a red test rather than a key path on the release rail.
 */
export { CHECK_CODES, CHECK_DETAIL_CODES };

/**
 * The membership reasons nobody decided by hand.
 *
 * A row that is in because the query put it there says so quietly — the column
 * stays, so every member still shows why, but it does not compete with the
 * handful somebody actually decided.
 */
const AUTOMATIC_REASONS: ReadonlySet<string> = new Set([
  'IN_INTERVAL',
  'IN_FORCE_AT_CUTOFF',
  // A compiled row is in because a source issue printed it, which is the loudest
  // fact on the row and is already said by the heading it sits under. Said again
  // at full weight on every line it would make a whole compilation read as a
  // list of decisions somebody took by hand.
  'FROM_SOURCE_ISSUE',
  'IMPORTED',
]);

/** The two notice types whose short id carries a letter in the message lists. */
const TYPE_SUFFIXES: Readonly<Record<string, string>> = {
  TEMPORARY_NOTICE: '(T)',
  PRELIMINARY_NOTICE: '(P)',
};

/** The one history actor whose label is also the whole action. */
const IMPORT_ACTOR_KEY = `${ACTOR_KEY_PREFIX}IMPORT`;

/**
 * What identifies one history line to the panel's own per-line state.
 *
 * The same pair the list tracks by, and for the same reason: an entry written by
 * a server that does not send its id still has to be told apart from the one
 * above it, and the instant it was recorded is the only other thing it carries.
 */
function auditEntryKey(entry: { readonly id?: number; readonly created?: number }): string {
  return `${entry.id ?? ''}:${entry.created ?? ''}`;
}

/**
 * One issue, end to end: what is in it, whether it can go out, and its history.
 *
 * A workbench rather than a stack of panels. The members are the work and take
 * the width; the release rail sits beside them at a fixed 340px and answers the
 * one question the screen exists for — can this go out, and what is stopping it.
 * Below the wide breakpoint the rail comes FIRST, because on a narrow screen the
 * verdict is what a reader has come for and a member list they must scroll past
 * to reach it buries the answer.
 */
@Component({
  selector: 'app-issue-detail',
  imports: [
    LanguageFlag,
    NgTemplateOutlet,
    RouterLink,
    Card,
    Confirm,
    Icon,
    Menu,
    MenuTrigger,
    ProgressBar,
    ErrorBanner,
    ExpectedDate,
    RelativeTimePipe,
    TranslocoPipe,
    DateTimeField,
    FormField,
    SeriesCriteriaEditor,
    MemberChangeNote,
    MessageRef,
    CopyLinkButton,
    PublishDialog,
    PublicationAmendDialog,
    PublicationIncludeDialog,
    PublicationReasonDialog,
    PublicationForeignDomain,
    IssueOverridesDrawer,
  ],
  providers: [IssueDetailFacade, ConfirmService],
  templateUrl: './issue-detail.html',
  // `relative` is for the progress bar: pinned to the top edge as an overlay,
  // so a refetch that finishes in a blink does not push the page down and back.
  host: { class: 'relative flex min-w-0 flex-1 flex-col' },
})
export class IssueDetail {
  readonly publicId = input.required<string>();

  /**
   * The series this issue belongs to, where the caller already has it.
   *
   * Passed down rather than fetched: the screen above this one loads the series
   * to render its archive, and a second request would be a second source that can
   * disagree with the one the list is drawn from. Several decisions read it —
   * where the cut-off falls by default, whether there is a report to preview,
   * whether a release opens a successor — and all degrade to a safe answer while
   * it is still loading.
   */
  readonly series = input<PublicationSeries | undefined>(undefined);

  /**
   * How long a typed period is left alone before the screen is re-read.
   *
   * Zero re-reads immediately, which is what a test wants and no screen does. A
   * debounce is a fact about typing, so it belongs to the component holding the
   * boxes rather than to the read underneath it — and a spec that has to wait
   * 300 ms per keystroke ends up asserting the timer instead of the behaviour.
   */
  readonly previewDebounceMs = input(PERIOD_DEBOUNCE_MS);

  /** Raised when the reader is done with this issue and wants the series back. */
  readonly closed = output<void>();

  /**
   * Another issue to open in this panel — the successor a new edition created.
   *
   * Raised rather than navigated to from here: which issue the panel shows is the
   * series screen's state, and a component that reached past its own inputs to
   * change it would leave the URL and the panel disagreeing.
   */
  readonly openIssue = output<string>();

  protected readonly facade = inject(IssueDetailFacade);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly discardConfirm = inject(DiscardConfirmService);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly notifications = inject(NotificationService);
  private readonly document = inject(DOCUMENT);
  private readonly domains = inject(DomainFacade);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly apiUrls = inject(ApiUrlResolver);
  // Injected for the two addresses this screen reads for itself, both of them
  // names of records the facade does not hold: what the series a compilation
  // names is called, and what the successor a release just opened is called.
  // Everything else it shows comes through the facade, which owns the issue's
  // own reads.
  private readonly api = inject(PublicationSeriesApiService);

  constructor() {
    // Reloaded whenever the id changes, not once at construction. The panel is
    // reused when one issue hands over to another — which is exactly what a new
    // edition does — and a load that ran only at construction would leave the
    // successor's address on screen above the predecessor's contents.
    effect(() => {
      const publicId = this.readableIssueId();
      untracked(() => {
        this.selected.set(new Set());
        this.publishOutcome.set(undefined);
        // Re-folded with the issue, like every other per-issue piece of state
        // reset here: opening the section is a judgement about ONE issue's late
        // messages, and carried over it would open a list of the next issue's
        // that nobody on this screen asked to see. The trail folds back for the
        // same reason.
        this.showLate.set(false);
        this.showHistory.set(false);
        // And the compiled runs fold back with them: an opened week is a
        // judgement about ONE compilation's list, and carried over it would
        // leave a run of the next issue's rows unfolded that nobody asked for.
        this.expandedGroups.set(new Set());
        if (publicId) {
          this.facade.load(publicId);
        }
      });
    });

    // The period the numbers answer for, handed to the read that produces them.
    //
    // Debounced, because it comes from two date boxes somebody is typing in and
    // every intermediate value would otherwise be a request. Clearing is NOT
    // debounced: closing the panel puts the screen back on the period on file,
    // and a delay there would leave the saved issue described by a window that
    // no longer exists anywhere.
    toObservable(this.editedPeriod)
      .pipe(
        // Presence is part of the value. Compared on the two ends alone, a
        // closed panel and one whose boxes are both empty read as the same
        // period, so the `undefined` that Cancel pushes would be dropped and the
        // screen would stay on a period nobody could see any more.
        distinctUntilChanged(
          (a, b) =>
            (a === undefined) === (b === undefined) && a?.from === b?.from && a?.to === b?.to,
        ),
        switchMap((period) => {
          const ms = this.previewDebounceMs();
          return period === undefined || ms <= 0 ? of(period) : timer(ms).pipe(map(() => period));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((period) => this.facade.setPreviewPeriod(period));

    // The period a release would open next: whether one is opened at all is a
    // fact about the SERIES, which this screen is handed and the facade is not,
    // so the question is composed here and asked nowhere when the answer would
    // describe work publish will not do.
    effect(() => {
      const probe = this.successorProbe();
      untracked(() => this.facade.probeSuccessor(probe));
    });

    // The series itself, handed to the facade for the two questions it asks of
    // it: where the cut-off falls, which decides the instant this screen opens
    // at, and how the series selects its content, which decides whether the
    // screen draws a member list or a compiled one. Handed over as the SIGNAL,
    // once — a series that arrives after the first read then moves both answers
    // with it, where a value copied across would be a second copy to keep in
    // step and briefly wrong exactly when the first render happens.
    this.facade.attachSeries(this.series);
  }

  // ------------------------------------------------------------ a compilation

  /**
   * Whether this issue's members were taken from another series' issues.
   *
   * The one branch the whole screen turns on: a compilation has no criteria to
   * show, no candidates it left out, and no second instant to be read at —
   * every one of those panels answers a question that was never asked of it —
   * and its member list is a list of lists rather than a list.
   */
  protected readonly compiled = computed(() => this.facade.compiled());

  /**
   * Whether the issue is still open, as the panels that ask about the future do.
   *
   * A released issue is a record and an open one is a plan, and the two take
   * different halves of the same answer: what is still open or missing is a
   * question only an open issue has, and a frozen one answers it by having
   * already gone out. Read off the issue's own status — the one fact that says
   * which of the two this is — rather than from a second field that could
   * disagree with it.
   */
  protected readonly issueOpen = computed(() => this.facade.issue()?.status === 'OPEN');

  /**
   * Which series this issue compiled, by its id.
   *
   * The frozen header first: the series can be re-pointed after a release, and
   * the record of what this issue took its rows from must not move with it. An
   * open issue has no header yet and reads the series' own setting, which is
   * exactly what its next release would freeze.
   */
  protected readonly sourceSeriesId = computed(
    () => this.facade.issue()?.snapshotSourceSeriesId ?? this.series()?.sourceSeriesId,
  );

  /**
   * What that series is called, read once for the band over the list.
   *
   * The id is what the record holds and what a link is built from; it is not
   * what a reader knows the publication by, and a band reading "compiled from 51
   * issues of weekly-ntm" names a row in a database rather than a publication.
   * Nothing already on this screen carries the name — the source ISSUES name
   * themselves, their series does not — so it is asked for, in the lean shape
   * (an id and its names) and only for a compilation: no regime, no request.
   * Nor for an issue another desk owns — the refusal is the whole screen, the
   * band it would feed is not drawn, and a refused screen reads nothing.
   *
   * Nothing is held: the read lives as long as the screen that shows it, and the
   * next visit asks again rather than printing a name that has since changed.
   */
  private readonly sourceSeriesResource = httpResource<
    Pick<PublicationSeries, 'seriesId' | 'descs'>
  >(
    () => {
      const readable = this.compiled() && this.foreignOwnerDomainId() === undefined;
      const seriesId = readable ? this.sourceSeriesId() : undefined;
      return seriesId ? this.api.leanSeriesRequest(seriesId) : undefined;
    },
    // The endpoints answer ISO 8601 instants and the models read epoch
    // milliseconds. A resource built from a request descriptor normalises the
    // response itself, the way every observable on the API service does.
    { parse: (raw) => withEpochDates(raw as Pick<PublicationSeries, 'seriesId' | 'descs'>) },
  );

  /**
   * The name the band uses, falling back to the id.
   *
   * The id while the read is in flight and wherever it fails or names nothing:
   * a band is a sentence about the list under it, and a sentence with a hole in
   * it is worse than one naming the series the way the record does. `value()`
   * throws in the error state, which is why the read goes through `hasValue()`.
   */
  protected readonly sourceSeriesName = computed(() => {
    const seriesId = this.sourceSeriesId();
    if (!this.sourceSeriesResource.hasValue()) {
      return seriesId;
    }
    return this.pickName(this.sourceSeriesResource.value().descs) ?? seriesId;
  });

  /**
   * How many source issues went into this one.
   *
   * The survey's published rows where the answer carries one — that is what the
   * grouped table heads, week by week, and what a reader would count — and the
   * groups on screen otherwise, so a server that sends no survey still gets a
   * band that agrees with the table under it. Counted on the same two facts the
   * grouping keys on, a published row that names an issue, so the sentence and
   * the headings under it can never report different numbers.
   */
  protected readonly compiledSourceCount = computed(() => {
    const sources = this.facade.sources();
    if (sources !== undefined) {
      return sources.filter(
        (source) => source.status === 'PUBLISHED' && source.publicId !== undefined,
      ).length;
    }
    return this.facade.groupedMembers().filter((group) => group.source !== undefined).length;
  });

  /**
   * What the band says over a compiled list: where the rows came from.
   *
   * The count and the source in one sentence, because neither is the answer on
   * its own — "51 issues" says nothing about which publication, and the series
   * alone says nothing about how much of it is in here. The wording without a
   * named source is not a fallback for a missing translation but for a
   * compilation whose source nobody has set yet, which is a real state of a
   * series somebody is still configuring.
   */
  protected readonly compiledBandKey = computed(() => {
    const one = this.compiledSourceCount() === 1;
    if (this.sourceSeriesId() === undefined) {
      return one
        ? 'admin.publications.issue.compiledFromUnnamedOne'
        : 'admin.publications.issue.compiledFromUnnamed';
    }
    return one
      ? 'admin.publications.issue.compiledFromOne'
      : 'admin.publications.issue.compiledFrom';
  });

  protected readonly compiledBandParams = computed(() => ({
    count: this.compiledSourceCount(),
    series: this.sourceSeriesName() ?? '',
  }));

  /**
   * How wide a heading row spans — every column the member table draws.
   *
   * Counted rather than written out, because the table grows a selection column
   * and an actions column together while the issue can still be curated, and a
   * span that is one out leaves a heading ending in the middle of the table.
   */
  protected readonly memberColumns = computed(() => (this.facade.curatable() ? 6 : 4));

  /**
   * Which source runs are unfolded, by group key. Empty: every one of them is
   * folded, which is how the list opens.
   *
   * Held as the set of what is OPEN rather than of what is closed, so nothing
   * has to know the groups before they are drawn — a year compiles fifty-one
   * weeks, and a set of the closed ones would have to be filled from the list
   * before it could mean "all closed" at all.
   *
   * Folding is a way of drawing the list and never a second list: the rows are
   * members whether their run is open or not, so selection, select-all and every
   * count are taken from the flat list exactly as before, and each heading keeps
   * saying how many rows it is holding back.
   *
   * Re-folded for each issue this panel is pointed at — see the load effect.
   */
  protected readonly expandedGroups = signal<ReadonlySet<string>>(new Set());

  protected groupExpanded(key: string): boolean {
    return this.expandedGroups().has(key);
  }

  protected toggleGroup(key: string): void {
    this.expandedGroups.update((open) => {
      const next = new Set(open);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  }

  /** What a source group is headed by: the issue's name, or its id where it has none. */
  protected sourceName(source: SourceIssueRef | SourceIssue): string {
    return source.name ?? source.publicId ?? '';
  }

  /**
   * Where a source issue's own screen is, or `undefined` where it cannot be
   * addressed.
   *
   * A source issue that has since been deleted arrives as an id with nothing
   * else, and an issue whose series is not named cannot be reached at all — both
   * are rendered as plain text rather than as a link to a screen that would 404.
   */
  protected sourceRoute(source: SourceIssueRef | SourceIssue): readonly string[] | undefined {
    const seriesId = source.seriesId ?? this.sourceSeriesId();
    return source.publicId && seriesId ? ['/admin/publications', seriesId] : undefined;
  }

  protected sourceQueryParams(source: SourceIssueRef | SourceIssue): Record<string, string> {
    return { issue: source.publicId ?? '' };
  }

  /**
   * The week a source issue covered, in the form that fits what it carries.
   *
   * `undefined` for a period nobody numbered — an uncovered stretch has no
   * issue and therefore no week — and a single week where both ends name the
   * same one, because "weeks 12–12" is a range nobody wrote.
   */
  protected sourceWeekKey(source: SourceIssueRef | SourceIssue): string | undefined {
    const week = source.week;
    if (week === undefined) {
      return undefined;
    }
    return source.weekTo !== undefined && source.weekTo !== week
      ? 'admin.publications.issue.sourceWeeks'
      : 'admin.publications.issue.sourceWeek';
  }

  protected sourceWeekParams(source: SourceIssueRef | SourceIssue): Record<string, unknown> {
    return { week: source.week, weekTo: source.weekTo };
  }

  /**
   * A source issue's cut-off, read on the clock of the desk that owns the
   * publication.
   *
   * The compilation's periods are the source series' periods, and those were
   * decided in the owning desk's zone — a cut-off of 31 December 23:59 shown on
   * another desk's clock lands in the wrong year, which is the one thing a
   * yearly roll-up must never say. A desk with no zone falls through to the
   * session's, which is what every other instant on this screen uses.
   */
  protected sourceCutoff(value: number | undefined): string {
    return value === undefined
      ? '—'
      : this.localeFormat.formatDate(value, { timeZone: this.ownerZone() });
  }

  /**
   * How a source issue is dated beside its name: the content period its notices
   * were published in, which is what a reader of the year looks a week up by. A
   * source with no period start — an in-force edition is a single instant — is
   * dated by its cut-off alone.
   */
  protected sourcePeriodKey(source: SourceIssueRef | SourceIssue): string {
    return source.intervalFrom === undefined
      ? 'admin.publications.issue.sourceCutoff'
      : 'admin.publications.issue.sourcePeriod';
  }

  protected sourcePeriodParams(source: SourceIssueRef | SourceIssue): Record<string, unknown> {
    return source.intervalFrom === undefined
      ? { time: this.sourceCutoff(source.cutoff) }
      : { from: this.sourceCutoff(source.intervalFrom), to: this.sourceCutoff(source.cutoff) };
  }

  /** The zone the desk that owns this publication reads its dates in. */
  private readonly ownerZone = computed(() => {
    const domainId = this.series()?.domainId;
    return domainId ? this.domains.resolveById(domainId)?.timeZone : undefined;
  });

  /**
   * What a source-series period is, named from the vocabulary the strip uses.
   *
   * Two of the three answers name an issue and the third names its absence,
   * which is exactly the merged vocabulary the dashboard already renders — so
   * "missing" reads here as it reads there rather than in a second wording of
   * the same idea.
   */
  protected sourceStatusKey(source: SourceIssue): string {
    return computedIssueStatusKey(source.status);
  }

  protected sourceStatusClass(source: SourceIssue): string {
    return computedIssueStatusClass(source.status);
  }

  /** The stretch an uncovered period spans, since it has no name to be called by. */
  protected sourceGap(source: SourceIssue): string {
    return `${this.sourceCutoff(source.intervalFrom)} – ${this.sourceCutoff(source.cutoff)}`;
  }

  // -------------------------------------------------------- which instant

  /**
   * Read the issue at the other instant.
   *
   * The selection goes with it. It is held by uid and the two views do not list
   * the same rows, so a row picked in one and no longer on screen in the other
   * would still be in a bulk decision taken there — an exclusion of a message
   * the curator can no longer see.
   */
  protected setView(view: IssueViewInstant): void {
    if (this.facade.viewInstant() === view) {
      return;
    }
    this.selected.set(new Set());
    this.facade.setViewInstant(view);
  }

  /**
   * Whether the late-messages section is expanded. Folded: it is a second list.
   *
   * The heading above it already says how many arrived after the planned cut-off
   * and what that means at the instant being read, which is the whole of the
   * answer for a reader who is not about to act on it. The rows themselves are
   * the follow-up question, and a second table opening by itself above the
   * members pushed the list this screen exists for off the first screenful.
   *
   * Re-folded for each issue this panel is pointed at — see the load effect.
   */
  protected readonly showLate = signal(false);

  protected toggleLate(): void {
    this.showLate.update((open) => !open);
  }

  /**
   * Whether the trail is expanded. Folded, for the same reason the late section is.
   *
   * It is the longest thing in the rail and the one nobody opens the screen to
   * read: it answers who did this and when, which is asked after something looks
   * wrong rather than before. Open by default it pushed the two cards that carry
   * an answer down past the fold. The count on the line is what makes folding it
   * safe — a reader sees that there is a trail, and how long, without opening it.
   *
   * Re-folded for each issue this panel is pointed at — see the load effect.
   */
  protected readonly showHistory = signal(false);

  protected toggleHistory(): void {
    this.showHistory.update((open) => !open);
  }

  /**
   * The instant the late rows are late against, as the answer took it.
   *
   * Named rather than assumed, because the two part company exactly where it
   * matters: a period being typed is asked about at the cut-off in the box, and
   * the rows come back counted from there — a heading quoting the saved value
   * over rows counted from the typed one is two answers under one sentence.
   * Falls back to the issue's own planned cut-off, which is what a server that
   * does not name the instant measured against.
   */
  protected readonly lateAfter = computed(
    () => this.facade.lateAfter() ?? this.facade.issue()?.intervalTo,
  );

  /**
   * What the late rows MEAN at the instant being read.
   *
   * The same messages in both views, and opposite answers about them: read now
   * they are what a release right now would carry, and read at the planned
   * cut-off they are what it would leave out. The rows alone cannot say which,
   * which is the whole reason this line exists.
   */
  protected readonly lateMeaningKey = computed(() =>
    this.facade.viewInstant() === 'now'
      ? 'admin.publications.issue.lateIncludedNow'
      : 'admin.publications.issue.lateNotIncludedPlanned',
  );

  /**
   * Whose issue this is, from the desk being worked at.
   *
   * An issue has no owner of its own — it inherits the series', which is where
   * the accountability is recorded — so the answer is read off the series this
   * panel was already handed rather than from a second lookup that could
   * disagree with it. Another desk's issue is refused in place of the whole
   * workbench, through the same panel the series and one-off screens show.
   */
  protected readonly ownerState = computed(() =>
    ownerVerdict(this.series(), this.domains.sessionDomain()?.domainId),
  );

  /** The owning desk to refuse in favour of, and nothing while there is none. */
  protected readonly foreignOwnerDomainId = computed(() =>
    this.ownerState() === 'foreign' ? this.series()?.domainId : undefined,
  );

  /**
   * The issue to read, and nothing where it is not this desk's to read.
   *
   * The workbench is the one call this screen makes, and it answers members,
   * history, curation decisions and the release rail about the issue — none of
   * which a refused screen shows. Derived rather than folded into the effect so
   * a series arriving after the id does not re-issue a read that already ran.
   */
  private readonly readableIssueId = computed(() =>
    this.ownerState() === 'foreign' ? undefined : this.publicId(),
  );

  /**
   * Whether anything on this screen may be changed from the domain in use.
   *
   * The same owner verdict read for writes. Every write below folds this in;
   * nothing that only READS does, which is why the facts, the member list, the
   * standing exclusions, the history and the copy-link all stay exactly as they
   * are.
   */
  protected readonly writable = computed(() =>
    seriesWritable(this.series(), this.domains.sessionDomain()?.domainId),
  );

  /**
   * What the desk that owns this publication is called.
   *
   * `undefined` where the series belongs to no desk, which is a real
   * arrangement rather than missing data: such a series is administered from
   * everywhere, and a sentence naming one desk would be wrong about it. Falls
   * back to the id for a desk that has since been deleted — `niord-annex` names
   * it to anybody reading this screen.
   */
  protected readonly ownerDomainName = computed(() => {
    const domainId = this.series()?.domainId;
    return domainId ? this.domainName(domainId) : undefined;
  });

  protected readonly issueName = computed(() => {
    const issue = this.facade.issue();
    if (!issue) {
      return '';
    }
    return this.pickName(issue.descs) ?? issue.publicId;
  });

  /** What the series is called, for the way back and the context line. */
  protected readonly seriesName = computed(() => {
    const series = this.series();
    return series ? (this.pickName(series.descs) ?? series.seriesId) : '';
  });

  private pickName(
    descs: readonly { lang: string; name: string }[] | undefined,
  ): string | undefined {
    const rows = descs ?? [];
    const lang = this.language.selectedLanguage();
    return (rows.find((d) => d.lang === lang) ?? rows[0])?.name;
  }

  /** The lifecycle tint the badge beside the name wears. */
  protected badgeClass(issue: PublicationIssue): string {
    return issueBadgeClass(issue.status);
  }

  /**
   * How the release line names the release — with a person, or without one.
   *
   * A release run by a job, and one carried in from the previous system, have no
   * person to name. Printed through the sentence that has a slot for one they
   * read "published by —", where the dash stands exactly where a name would: a
   * reader takes that for a name that went missing rather than for a release
   * nobody signed.
   */
  protected publishedLineKey(issue: PublicationIssue): string {
    return issue.publishedBy
      ? 'admin.publications.issue.publishedLine'
      : 'admin.publications.issue.publishedLineNoUser';
  }

  /**
   * The count sentence, in the form that fits the number it carries.
   *
   * Two keys and a choice, rather than one key carrying a bracketed plural that
   * is wrong for whichever number it meets.
   */
  protected memberCountKey(count: number): string {
    return count === 1
      ? 'admin.publications.issue.memberCountOne'
      : 'admin.publications.issue.memberCount';
  }

  /**
   * Whether publishing this issue opens the next one by itself.
   *
   * Two facts, and both are about the SERIES: a publication with no calendar has
   * no next period to open, and a series whose next issue is created by hand
   * opens nothing on a release. The default is automatic, which is what an
   * absent value means on the wire.
   */
  protected readonly autoNextIssue = computed(() => {
    const series = this.series();
    if (!series || series.cadence === 'NONE') {
      return false;
    }
    return (series.nextIssueCreation ?? 'AUTO_ON_PUBLISH') === 'AUTO_ON_PUBLISH';
  });

  /**
   * What the period after this one is called, where a release would open it.
   *
   * `undefined` is a real answer and the sentences that use it have wording for
   * it: a series that creates its next issue by hand opens nothing, and a probe
   * that failed knows no name. Neither is worth an empty gap in a sentence.
   */
  private readonly successorProbe = computed<SuccessorProbe | undefined>(() => {
    const issue = this.facade.issue();
    const seriesId = issue?.seriesId ?? this.series()?.seriesId;
    if (!issue || !seriesId || issue.status !== 'OPEN' || !this.autoNextIssue()) {
      return undefined;
    }
    return { seriesId, afterPublicId: issue.publicId };
  });

  protected readonly successorName = computed(() => this.pickName(this.facade.successorDescs()));

  /** The release-mode label, or the release line once the issue has gone out. */
  protected readonly releaseModeKey = computed(() => {
    const mode = this.series()?.releaseMode ?? 'MANUAL_GATE';
    return `admin.publications.issue.releaseMode.${mode}`;
  });

  /**
   * Whether the release dialog may be opened.
   *
   * `canPublish` is the server's answer and is not re-derived here. The warnings
   * are NOT part of this gate any more: they are decisions taken inside the
   * dialog, against a checklist the server re-runs at publish time, and gating
   * the button on them would hide the release behind tickboxes that may no
   * longer be the ones that matter.
   */
  protected readonly publishable = computed(() => {
    const working = this.facade.working();
    const writable = this.writable();
    return this.facade.checklist()?.canPublish === true && !working && writable;
  });

  protected readonly publishDialogOpen = signal(false);

  /**
   * How many messages the release would freeze.
   *
   * The live list while the issue is open, because the stored column is only
   * written when membership is frozen and reads `0` — not null — until then. An
   * issue holding 193 resolved messages therefore opened the dialog that
   * publishes it saying "0 messages", beside the list of 193 it was resolved
   * from. The column still answers for a frozen issue, where what went out is
   * the record and today's resolution is a different question.
   */
  protected readonly publishMemberCount = computed(() => {
    const issue = this.facade.issue();
    const live = this.facade.members().length;
    return issue?.status === 'OPEN' ? live : (issue?.memberCount ?? live);
  });

  /**
   * The refusal to show inside the publish dialog.
   *
   * Suppressed while the dialog is listing unacknowledged warnings: those ARE
   * the refusal, spelled out row by row, and repeating them as a sentence above
   * would say the same thing twice in two different vocabularies.
   */
  protected readonly publishErrorKey = computed(() => {
    const code = this.facade.lastActionError();
    if (!code || this.facade.unacknowledgedWarnings().length > 0) {
      return undefined;
    }
    return publicationErrorKey({ error: { code } }, 'admin.publications.issue.actionFailed');
  });

  protected openPublish(): void {
    if (!this.publishable()) {
      return;
    }
    // A refusal from the previous attempt would otherwise be sitting inside the
    // dialog, describing a release nobody has asked for yet — the same thing
    // the amend dialog does on the way in, and for the same reason.
    this.facade.clearError();
    this.publishDialogOpen.set(true);
  }

  /**
   * What the release just did, for the banner above the now-published issue.
   *
   * Held rather than re-derived, because the successor is the one fact the
   * refetch cannot show: it is a different issue at a different address, and the
   * screen is looking at its predecessor. The ADDRESS is all that is held — what
   * the successor is called is read from the successor itself, below.
   *
   * No time is held. The result's `stampedAt` is the CUT-OFF, which after a
   * release at a chosen past cut-off is not when the issue was published, and
   * the banner printed it as "Published {time}". The header line above it
   * prints the issue's own publication time once the refetch lands.
   */
  protected readonly publishOutcome = signal<{ readonly successorId?: string } | undefined>(
    undefined,
  );

  /**
   * The successor the release actually opened, read from the server.
   *
   * Not the probe's answer. The probe predicts the next period from the issue's
   * plan BEFORE the release, and an issue spanning several periods is published
   * at a cut-off the prediction never saw — a week 36 issue cut off in week 38
   * was announced as opening week 37 while the server had opened week 39. A name
   * is either certain or it is not said, so it is read from the row the publish
   * created, by the address the publish reported.
   *
   * Nothing is held: the read lives as long as the banner that shows it, and it
   * is asked once per release rather than kept for the next one.
   */
  private readonly successorResource = httpResource<Pick<PublicationIssue, 'publicId' | 'descs'>>(
    () => {
      const successorId = this.publishOutcome()?.successorId;
      return successorId ? this.api.leanIssueRequest(successorId) : undefined;
    },
    // Normalised here for the same reason as the read above it.
    { parse: (raw) => withEpochDates(raw as Pick<PublicationIssue, 'publicId' | 'descs'>) },
  );

  /**
   * What the banner may call the successor — and `undefined` until it knows.
   *
   * No fallback to the id: an address is not a name, and a sentence naming
   * "a1b2c3d4-…" as the next issue says less than one that names no period at
   * all. Both sentences exist in the bundles, so the wording follows the answer
   * rather than the answer being forced into one wording. `value()` throws in
   * the error state, which is why the read goes through `hasValue()`.
   */
  protected readonly openedSuccessorName = computed(() => {
    if (!this.successorResource.hasValue()) {
      return undefined;
    }
    return this.pickName(this.successorResource.value().descs);
  });

  /**
   * Move the planned cut-off from inside the release dialog.
   *
   * The SAME write the edit panel makes, on purpose: a cut-off changed while
   * deciding to publish and one changed while editing the period have to be the
   * same act, or the two surfaces would produce different numbers for the same
   * issue. The dialog stays open and follows the issue back — the refetch is
   * what tells it the change went through, and a refusal reaches it through the
   * error the rest of this screen already maps.
   */
  protected changePlannedCutoff(intervalTo: number): void {
    if (this.facade.working() || !this.editable()) {
      return;
    }
    this.facade.edit({ intervalTo });
  }

  protected submitPublish(request: PublishRequest): void {
    this.facade.publish(request, (result: PublishResult) => {
      this.publishDialogOpen.set(false);
      this.publishOutcome.set({ successorId: result.successorId });
      this.notifications.success('admin.publications.toast.published');
    });
  }

  /** Follow the successor a release opened. */
  protected openSuccessor(): void {
    const successorId = this.publishOutcome()?.successorId;
    if (successorId) {
      this.openIssue.emit(successorId);
    }
  }

  // ----------------------------------------------------------------- curation

  /**
   * The rows a bulk decision would apply to.
   *
   * Held by uid rather than by index: the list refetches after every mutation and
   * an index-keyed selection would silently point at different messages once one
   * of them left.
   */
  private readonly selected = signal<ReadonlySet<string>>(new Set());

  protected readonly selectedCount = computed(() => this.selected().size);

  protected isSelected(messageUid: string): boolean {
    return this.selected().has(messageUid);
  }

  protected toggleSelected(messageUid: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (!next.delete(messageUid)) {
        next.add(messageUid);
      }
      return next;
    });
  }

  /**
   * True only when every row is selected — the header box mirrors the list, not
   * the intent.
   *
   * The list it mirrors is the TABLE's, which is not every member: the messages
   * published after the planned cut-off are lifted into their own section, and a
   * box that could never be ticked because of rows nobody can see is a control
   * that looks broken.
   */
  protected readonly allSelected = computed(() => {
    const members = this.facade.tableMembers();
    const selected = this.selected();
    return members.length > 0 && members.every((member) => selected.has(member.messageUid));
  });

  protected toggleSelectAll(): void {
    const all = this.allSelected();
    this.selected.set(
      all ? new Set() : new Set(this.facade.tableMembers().map((m) => m.messageUid)),
    );
  }

  /**
   * Which curation question the shared reason dialog is asking, and about what.
   *
   * One dialog rather than three, because the question — "why?" — and its bounds
   * are identical; what differs is the subject and the copy, and both are inputs.
   */
  protected readonly curationDialogOpen = signal(false);
  private readonly curationAction = signal<CurationAction>('EXCLUDE');
  private readonly curationTargets = signal<readonly string[]>([]);

  protected readonly curationHeaderKey = computed(() =>
    this.curationAction() === 'EXCLUDE'
      ? 'admin.publications.curation.excludeTitle'
      : 'admin.publications.curation.clearTitle',
  );

  protected readonly curationEffectKey = computed(() =>
    this.curationAction() === 'EXCLUDE'
      ? 'admin.publications.curation.excludeEffect'
      : 'admin.publications.curation.clearEffect',
  );

  protected readonly curationConfirmKey = computed(() =>
    this.curationAction() === 'EXCLUDE'
      ? 'admin.publications.curation.exclude'
      : 'admin.publications.curation.clear',
  );

  /**
   * The refusal to show for the last mutation, wherever it surfaces.
   *
   * Mapped through the same table the rest of the section uses, so a rule the
   * server names has one wording in the curation dialogs, in the include dialog
   * and on the panel behind them — rather than a sentence in one place and a
   * bare identifier in another.
   */
  protected readonly actionErrorKey = computed(() => {
    const code = this.facade.lastActionError();
    return code === undefined
      ? undefined
      : publicationErrorKey({ error: { code } }, 'admin.publications.issue.actionFailed');
  });

  /** Excluding one row, from the row itself. */
  protected openExclude(member: IssueMember): void {
    this.openCuration('EXCLUDE', [member.messageUid]);
  }

  /** Excluding the selection, as ONE decision over a list. */
  protected openExcludeSelected(): void {
    const targets = [...this.selected()];
    if (targets.length === 0) {
      return;
    }
    this.openCuration('EXCLUDE', targets);
  }

  /** Withdrawing the decision a row stands on, so the query decides again. */
  protected openClearOverride(messageUid: string): void {
    this.openCuration('CLEAR', [messageUid]);
  }

  private openCuration(action: CurationAction, targets: readonly string[]): void {
    if (this.facade.working() || !this.writable()) {
      return;
    }
    this.facade.clearError();
    this.curationAction.set(action);
    this.curationTargets.set(targets);
    this.curationDialogOpen.set(true);
  }

  /**
   * Submit the curation decision, closing on the response rather than the click.
   *
   * A refusal — an unresolvable uid, an issue that stopped being open under the
   * admin — is shown inside the dialog with the reason still typed, which is the
   * same arrangement withdrawal uses.
   */
  protected submitCuration(reason: string): void {
    const targets = this.curationTargets();
    const done = () => {
      this.curationDialogOpen.set(false);
      this.selected.set(new Set());
    };
    if (this.curationAction() === 'EXCLUDE') {
      this.facade.excludeMembers(targets, reason, done);
    } else {
      this.facade.clearOverride(targets[0], reason, done);
    }
  }

  protected readonly includeDialogOpen = signal(false);

  protected openInclude(): void {
    if (this.facade.working() || !this.writable()) {
      return;
    }
    this.facade.clearError();
    this.includeDialogOpen.set(true);
  }

  protected submitInclude(request: IncludeRequest): void {
    this.facade.includeByMessageId(request.messageId, request.reason, () =>
      this.includeDialogOpen.set(false),
    );
  }

  /**
   * Whether a row is in the issue because somebody decided it should be.
   *
   * Read off the source rather than off the reason code: the code says what kind
   * of membership it is, and only the source says whether a decision stands
   * behind it that could be withdrawn. An exclusion never appears here — an
   * excluded message is not a member — so the include is the only override a
   * member row can carry.
   */
  protected isOverride(member: IssueMember): boolean {
    return member.source === 'OVERRIDE_INCLUDE';
  }

  /**
   * The reason label for a row, and how loudly to say it.
   *
   * Every member always shows why it is in — the column never disappears — but a
   * row the query selected says so quietly. Full weight is reserved for the rows
   * somebody decided by hand and the ones whose message has moved since, which
   * are the two kinds a reader has to look at.
   */
  protected reasonLoud(member: IssueMember): boolean {
    const code = member.reasonCode;
    return (
      (code !== undefined && !AUTOMATIC_REASONS.has(code)) ||
      this.isOverride(member) ||
      (member.drift?.length ?? 0) > 0
    );
  }

  /**
   * The one reason every row shares, for the band above the table.
   *
   * `undefined` the moment two rows differ: a heading that names one reason over
   * a list with several is a heading that is wrong about most of it.
   */
  protected readonly uniformReasonKey = computed(() => {
    const members = this.facade.members();
    if (members.length === 0) {
      return undefined;
    }
    const first = members[0].reasonCode;
    return first !== undefined && members.every((m) => m.reasonCode === first)
      ? `admin.publications.reason.${first}`
      : undefined;
  });

  /**
   * What the band says about the list as a whole.
   *
   * While the issue is open that is the one reason every row shares, lifted out
   * of the column so it is said once instead of on every line. Once it has been
   * published the reasons still belong to the rows, and the fact worth a band is
   * that the list stopped moving: a published member list is the record of what
   * went out, not a query anybody is still resolving.
   */
  protected bandSuffixKey(issue: PublicationIssue): string | undefined {
    return issue.status === 'PUBLISHED'
      ? 'admin.publications.issue.frozenAtPublish'
      : this.uniformReasonKey();
  }

  /** The `(P)`/`(T)` the message lists print after a notice's short id. */
  protected typeSuffix(member: IssueMember): string {
    return (member.frozenType && TYPE_SUFFIXES[member.frozenType]) ?? '';
  }

  /**
   * How a member row is named where a label needs the whole name as one string.
   *
   * The same order the rows themselves use — short id, then title — and never
   * the uid: "Select 3f9a…-…" tells a screen-reader user nothing about which
   * message the box is about.
   */
  protected memberName(member: IssueMember): string {
    return (
      member.frozenShortId ??
      member.title ??
      this.transloco.translate('admin.publications.messageRef.untitled')
    );
  }

  /**
   * The second line under a named row: what the message is called.
   *
   * Only where the short id is what names the row. A message nobody has numbered
   * yet is named by its title, and a row that then repeats the title underneath
   * says the same thing twice and reads as two different facts.
   */
  protected subtitle(
    messageId: string | null | undefined,
    title: string | null | undefined,
  ): string | undefined {
    return messageId ? (title ?? undefined) : undefined;
  }

  /**
   * Who decided this row, and when — `undefined` where nobody did.
   *
   * Read from the curation object where the server sends one and left alone
   * otherwise: the stored note is the older half of the same answer, rendered
   * separately, and inventing an author for it would put a name on a decision
   * nobody recorded.
   */
  protected curationLine(member: IssueMember): string | undefined {
    const curation = member.curation;
    if (!curation) {
      return undefined;
    }
    const parts = [
      curation.author,
      curation.at ? this.dateTime(curation.at) : undefined,
      curation.reason,
    ];
    const line = parts.filter((part) => !!part && part !== '—').join(' · ');
    return line === '' ? undefined : line;
  }

  /**
   * The exclusions that still stand, so a wrong one can be undone.
   *
   * An excluded message is not a member and so appears in no member list, which
   * would leave a curator who excluded the wrong one with nothing to undo. Taken
   * from the standing decisions the server holds rather than reconstructed from
   * the history: the history records an exclusion AND its later withdrawal, and
   * reading that as a state puts a decision nobody holds back on the screen.
   *
   * Rendered in the SAME table as the members, struck through, because "what is
   * in this issue" and "what was taken out of it" are one list to a reader
   * checking a document.
   *
   * Includes are left out because they already have a row: a message somebody
   * forced in IS a member, and its why-line sits on that row.
   */
  protected readonly exclusions = computed<readonly IssueOverride[]>(() =>
    this.facade.curatable()
      ? this.facade.overrides().filter((override) => override.kind === 'EXCLUDE')
      : [],
  );

  protected exclusionLine(exclusion: IssueOverride): string {
    const parts = [
      exclusion.author,
      exclusion.at ? this.dateTime(exclusion.at) : undefined,
      exclusion.reason,
    ];
    return parts.filter((part) => !!part && part !== '—').join(' · ');
  }

  // ------------------------------------------------- what the criteria left out

  /** Whether the omissions section is expanded. Closed: it answers a second question. */
  protected readonly showOmissions = signal(false);

  protected toggleOmissions(): void {
    this.showOmissions.update((open) => !open);
  }

  /** The reason a message was dropped, as the key that says it in words. */
  protected missKey(miss: PreviewMiss): string {
    return criteriaMissKey(miss.code);
  }

  // ------------------------------------------------------------------ preview

  /** A preview is a rendered report, so a series without one has nothing to render. */
  protected readonly canPreview = computed(() => !!this.series()?.reportId);

  /**
   * The preview's refusal, in the section's words.
   *
   * ISSUE_NOT_OPEN keeps the preview's own sentence here: the shared one is
   * neutral, because every other write that meets it is not a preview.
   */
  protected readonly previewErrorKey = computed(() => {
    const response = this.facade.lastPreviewError();
    if (response === undefined) {
      return undefined;
    }
    return publicationErrorCode(response) === ISSUE_NOT_OPEN
      ? 'admin.publications.preview.notOpen'
      : publicationErrorKey(response, 'admin.publications.preview.failed');
  });

  protected generatePreview(): void {
    if (!this.canPreview() || !this.writable()) {
      return;
    }
    this.facade.generatePreview();
  }

  /**
   * Whether the card offers a render at all.
   *
   * All three have to hold: a render freezes nothing but still writes a file
   * against the issue, so it belongs to a reader who may change it; a frozen
   * issue takes no preview; and a series with no report has nothing to render.
   * A reader who may only look is shown the renders that exist without a control
   * the server would refuse.
   */
  protected readonly canGeneratePreview = computed(
    () => this.facade.curatable() && this.canPreview() && this.writable(),
  );

  /**
   * Whether the preview strip is drawn at all.
   *
   * It is drawn for what it can say: the control, the renders that exist, or the
   * line explaining why there is no control. With none of the three it would be
   * a rule across the card under nothing.
   */
  protected readonly showPreviewSection = computed(
    () =>
      this.facade.curatable() &&
      (this.facade.previewRows().length > 0 ||
        // Only a series that renders its document has anything to preview: one
        // with a report, or one that generates from a query and merely lacks the
        // report so far (which is what the note below says). An uploaded or
        // linked series would otherwise be told, on every open issue, that it
        // has no report — which is not a shortcoming of such a series.
        (this.writable() &&
          (!!this.series()?.reportId || this.series()?.contentMode === 'GENERATED_FROM_QUERY'))),
  );

  /**
   * Open a rendered preview, by fetching it rather than by linking to it.
   *
   * The address the render reports cannot be followed: the endpoint answers only
   * to a request carrying the caller's bearer, and it is reported root-relative
   * while the API lives on its own origin — so a link to it asked the web host
   * for a path it does not serve, and would have got a 401 page if it had. The
   * document is fetched with the same credentials the rest of this screen uses
   * and handed to the browser from memory, the way the catalogue export is.
   */
  protected openPreview(row: IssuePreview): void {
    const publicId = this.facade.issue()?.publicId ?? this.publicId();
    this.facade.fetchPreview(row.lang, (file) =>
      this.saveFile(file, `${publicId}-${row.lang}.pdf`),
    );
  }

  private saveFile(file: Blob, name: string): void {
    const url = URL.createObjectURL(file);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    // Released once the click has been dispatched; the browser has taken its own
    // reference by then, and holding it would keep the document in memory for
    // the life of the page.
    URL.revokeObjectURL(url);
  }

  /**
   * The address of THIS ISSUE, for handing to somebody else.
   *
   * Not the preview's: that one is gated on the reader's own admin bearer, so
   * pasting it into a message hands over an address that 401s for everybody it
   * reaches. The issue's deep link opens this screen for anybody who may see it,
   * which is what "copy link" means everywhere else in the app.
   */
  protected readonly issueLink = computed(() => {
    const issue = this.facade.issue();
    const seriesId = issue?.seriesId ?? this.series()?.seriesId;
    if (!issue || !seriesId) {
      return undefined;
    }
    const path = `/admin/publications/${encodeURIComponent(seriesId)}`;
    return `${this.document.location.origin}${path}?issue=${encodeURIComponent(issue.publicId)}`;
  });

  protected renderedAt(value: number): string {
    return this.localeFormat.formatDateTime(value);
  }

  // ------------------------------------------------------------- the checklist

  /**
   * When the rail on screen was taken.
   *
   * Re-stamped whenever the checklist itself changes, which is what makes the
   * relative time honest: a rail read five minutes ago describes a member list
   * that may have moved, and on the screen whose next button publishes that is
   * worth saying.
   */
  protected readonly checkedAt = linkedSignal({
    source: () => this.facade.checklist(),
    computation: () => Date.now(),
  });

  /**
   * The rows a reader can act on, in the order they matter.
   *
   * Rows the server marked as not applicable are dropped outright: a question
   * that was never asked has no answer, and a rail padded with rules that do not
   * apply to this series is one nobody reads to the end.
   */
  private readonly applicableRows = computed<readonly PublishCheck[]>(() =>
    (this.facade.checklist()?.rows ?? []).filter((row) => row.applicable !== false),
  );

  /**
   * What the rail adds up to — the sentence above it, never the gate.
   *
   * The key travels with the counts because the sentence changes shape with
   * them: "1 warning(s)" is the shape of a string nobody wrote for the number it
   * ended up carrying, and the choice belongs where the number is known.
   */
  protected readonly verdict = computed(() => {
    const verdict = checklistVerdict(this.applicableRows());
    const suffix =
      verdict.state === 'warnings'
        ? verdict.warnings === 1
          ? 'warningsOne'
          : 'warningsMany'
        : verdict.state;
    return { ...verdict, key: `admin.publications.checklist.verdict.${suffix}` };
  });

  /** Blockers first, then warnings: the order in which they have to be dealt with. */
  protected readonly attentionRows = computed(() => {
    const failing = this.applicableRows().filter((row) => !row.passed);
    return [
      ...failing.filter((row) => row.severity === 'BLOCK'),
      ...failing.filter((row) => row.severity !== 'BLOCK'),
    ];
  });

  protected readonly passedRows = computed(() => this.applicableRows().filter((row) => row.passed));

  /** Closed: a check that passed is a question already answered. */
  protected readonly showPassed = signal(false);

  protected togglePassed(): void {
    this.showPassed.update((open) => !open);
  }

  /** The label key for a rail row, or `undefined` for a code with no wording. */
  protected checkKey(code: string): string | undefined {
    return checkRowKey(code);
  }

  /** The sentence a failing row means, or `undefined` where this build has none. */
  protected checkDetailKey(code: string): string | undefined {
    return checkRowDetailKey(code);
  }

  /** The server's own text, which is where a row names the languages or the ids. */
  protected checkParams(row: PublishCheck): Record<string, string> {
    return { detail: row.detail ?? '' };
  }

  /**
   * What the row was measured to be — the numbers under the sentence.
   *
   * The sentence above says what a failing check MEANS; this says what it found:
   * "214 of 1000", "must be after 1 September 12:00". The server sends the values
   * apart from its own English wording so this line can be read in the language
   * the rest of the rail is in, and instants go through the screen's one clock
   * rather than arriving pre-formatted in the series' zone. A status or a
   * selection regime among the values is named with the label the rest of the
   * screen uses, so no stored code is left sitting in a translated sentence.
   */
  protected checkDetail(row: PublishCheck) {
    return checkDetailText(
      row,
      (value) => this.localeFormat.formatDateTime(value),
      (key) => this.transloco.translate(key),
    );
  }

  // ------------------------------------------------- withdraw and reinstate

  protected readonly retireDialogOpen = signal(false);

  protected openRetire(): void {
    if (this.facade.working() || !this.writable()) {
      return;
    }
    this.retireDialogOpen.set(true);
  }

  /**
   * Withdraw, with the reason the history will keep.
   *
   * The dialog closes on the response, not on the click: a refusal — a reason
   * the server would not take, a state that changed under the admin — is shown
   * inside the dialog with what was typed still there, rather than in the panel
   * behind a dialog that has already gone.
   */
  protected submitRetire(reason: string): void {
    this.facade.retire(reason, () => this.retireDialogOpen.set(false));
  }

  /**
   * Reinstate, behind a plain confirm.
   *
   * No reason is asked for, and that is not an oversight: reinstating restores
   * the state the issue was already published in — it re-resolves, re-freezes
   * and re-caps nothing — so there is no new fact for a reason to explain.
   */
  protected reactivate(): void {
    if (this.facade.working() || !this.writable()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.reactivate.title'),
      message: this.transloco.translate('admin.publications.reactivate.message'),
      accept: () => this.facade.reactivate(),
    });
  }

  // ------------------------------------------------- amend and new edition

  /**
   * Whether this issue was carried in from the archive rather than released here.
   *
   * Two witnesses, because they answer at different times: the legacy id is
   * stamped by the importer on the issue itself, and the membership provenance
   * says the member snapshot was frozen from the archive rather than resolved.
   * The server refuses an amend on either, and the action is hidden rather than
   * offered and refused — an imported issue's document is what people cited, and
   * re-deciding it is not a correction but a rewrite of the record.
   */
  protected readonly imported = computed(() => {
    const issue = this.facade.issue();
    return !!issue?.legacyPublicationId || issue?.membershipProvenance === 'IMPORTED';
  });

  protected readonly amendable = computed(() => {
    const issue = this.facade.issue();
    const imported = this.imported();
    const working = this.facade.working();
    const writable = this.writable();
    return issue?.status === 'PUBLISHED' && !imported && !working && writable;
  });

  /**
   * Whether a successor edition can be opened here.
   *
   * A one-off holds exactly one issue by definition, so there is no second
   * edition to open; everything else that has published can be superseded, and
   * the server refuses an interval that would overlap what is already there.
   *
   * Withheld as well on a series that opens the next issue by itself: the
   * successor already exists, and offering to create a second one is offering a
   * period that overlaps the one publishing just opened.
   */
  protected readonly canOpenNewEdition = computed(() => {
    const issue = this.facade.issue();
    const oneOff = this.series()?.kind === 'ONE_OFF';
    const working = this.facade.working();
    const writable = this.writable();
    const automatic = this.autoNextIssue();
    return issue?.status === 'PUBLISHED' && !oneOff && !automatic && !working && writable;
  });

  protected readonly amendDialogOpen = signal(false);

  /**
   * The refusal to show inside the amend dialog.
   *
   * Suppressed while the dialog is listing unacknowledged warnings, for the same
   * reason the publish dialog suppresses it: those ARE the refusal, row by row.
   */
  protected readonly amendErrorKey = computed(() => {
    const code = this.facade.lastActionError();
    if (!code || this.facade.unacknowledgedWarnings().length > 0) {
      return undefined;
    }
    return publicationErrorKey({ error: { code } }, 'admin.publications.issue.actionFailed');
  });

  protected openAmend(): void {
    if (!this.amendable()) {
      return;
    }
    this.facade.clearError();
    this.amendDialogOpen.set(true);
  }

  /**
   * Amend, and say how many messages the replacement document carries.
   *
   * The count is the one fact an admin cannot see from the screen behind the
   * dialog: the whole point of an amend is that the membership was re-resolved,
   * and "it worked" leaves them to go and count.
   */
  protected submitAmend(request: AmendRequest): void {
    this.facade.amend(request, (result) => {
      this.amendDialogOpen.set(false);
      this.notifications.success('admin.publications.toast.amended', {
        count: result.memberCount,
      });
    });
  }

  /**
   * Open a successor edition, behind a confirm that says what stays current.
   *
   * The surprise this dialog exists to prevent: the new edition is OPEN and
   * invisible, and the edition it supersedes stays the public one until the new
   * one is published. An admin who expects the flip to happen here would leave
   * the site showing the old edition and not know why.
   */
  protected newEdition(): void {
    if (!this.canOpenNewEdition()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.newEdition.title'),
      message: this.transloco.translate('admin.publications.newEdition.message'),
      accept: () =>
        this.facade.newEdition((publicId) => {
          this.notifications.success('admin.publications.toast.newEdition');
          this.openIssue.emit(publicId);
        }),
    });
  }

  // `translateSignal` keeps the menu labels reactive to a language change: the
  // menu is a computed, and a one-shot `translate()` call inside it is a value the
  // computed cannot track — so the first language's words stay cached for the life
  // of the page.
  private readonly amendLabel = translateSignal('admin.publications.amend.action');
  private readonly newEditionLabel = translateSignal('admin.publications.newEdition.action');
  private readonly reactivateLabel = translateSignal('admin.publications.issue.reactivate');
  private readonly deleteLabel = translateSignal('admin.publications.issue.delete');

  /**
   * The lifecycle actions that did not earn a button of their own.
   *
   * Built from the same predicates the buttons use, so an action that is not
   * available is absent rather than present and refused. An empty list hides the
   * trigger: a menu button that opens onto nothing is worse than no button.
   */
  protected readonly menuItems = computed<MenuItem[]>(() => {
    const items: MenuItem[] = [];
    if (this.amendable()) {
      items.push({
        label: this.amendLabel(),
        icon: 'lucidePencil',
        command: () => this.openAmend(),
      });
    }
    if (this.canOpenNewEdition()) {
      items.push({
        label: this.newEditionLabel(),
        icon: 'lucidePlus',
        command: () => this.newEdition(),
      });
    }
    if (this.facade.issue()?.status === 'RETIRED') {
      items.push({
        label: this.reactivateLabel(),
        icon: 'lucideUndo2',
        disabled: this.facade.working() || !this.writable(),
        command: () => this.reactivate(),
      });
    }
    // Last, and behind a divider: it is the one row here that takes something
    // away for good, and the separated group is what stops it being reached by
    // the same flick as the row above it.
    if (this.deletable()) {
      if (items.length > 0) {
        items.push({ separator: true });
      }
      items.push({
        label: this.deleteLabel(),
        icon: 'lucideTrash2',
        styleClass: 'is-destructive',
        disabled: this.facade.working(),
        command: () => this.deleteIssue(),
      });
    }
    return items;
  });

  // ------------------------------------------------------------ removing it

  /**
   * Whether removing the issue outright is on the table.
   *
   * Two statuses and no others. An OPEN issue has never left the building, so
   * nothing can be pointing at it; a RETIRED one has been taken off the public
   * list deliberately, and the server still refuses while any message cites it.
   * A PUBLISHED issue is never removable at all — withdrawing it first is the
   * step that makes the question askable — and the action is withheld rather
   * than offered and refused.
   */
  protected readonly deletable = computed(() => {
    const status = this.facade.issue()?.status;
    return (status === 'OPEN' || status === 'RETIRED') && this.writable();
  });

  /**
   * Remove the issue, behind the house destructive confirm.
   *
   * Leaves for the series on success rather than refetching: the issue this
   * screen is about no longer exists, and the list behind it re-reads when the
   * panel closes — where the period now shows as one nothing was released for.
   */
  protected deleteIssue(): void {
    if (this.facade.working() || !this.deletable()) {
      return;
    }
    this.facade.clearError();
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.publications.issue.deleteTitle'),
      // One sentence for an open and a withdrawn issue alike: the removal is
      // refused while anything still cites it, and a message can cite an issue
      // before anything has been released under its name.
      message: this.transloco.translate('admin.publications.issue.deleteMessage', {
        name: this.issueName(),
      }),
      // Named rather than the shared "Yes": the confirm for something
      // irreversible should say what pressing it does, and the menu row's own
      // label carries an ellipsis because IT opens this.
      acceptLabel: this.transloco.translate('admin.publications.issue.deleteTitle'),
      accept: () =>
        this.facade.deleteIssue(() => {
          this.notifications.success('admin.publications.toast.issueDeleted');
          this.closed.emit();
        }),
    });
  }

  // ---------------------------------------------------------------- editing

  /**
   * The edit panel, closed by default.
   *
   * Editing an issue's name or interval is rare and consequential -- moving the
   * interval re-resolves the member list -- so it is a deliberate act rather
   * than a form sitting open on a screen people come to in order to read.
   */
  protected readonly editing = signal(false);

  /**
   * The period every number on the screen answers for, while one is being typed.
   *
   * `undefined` closes the question: with the panel shut the issue is read the
   * way it is stored, at whichever instant the reader chose. With it open the
   * two boxes ARE the period — including a box left empty, which is a period
   * with an open end rather than one that falls back to the saved value, because
   * the reader can see that it is empty.
   */
  private readonly editedPeriod = computed<PreviewPeriod | undefined>(() => {
    if (!this.editing()) {
      return undefined;
    }
    const interval = this.intervalModel();
    return {
      from: interval.from?.getTime(),
      to: interval.to?.getTime(),
    };
  });

  /**
   * What the panel says when the boxes describe a period nothing can be read
   * over — and nothing, which is the ordinary case.
   *
   * The numbers below the panel stop moving in both states, and a screen that
   * went quiet without saying why reads as one that has stopped working. Each
   * sentence names its own state and says that the list is the one from before
   * the edit, which is exactly what is on screen.
   */
  protected readonly periodNoteKey = computed(() => {
    switch (this.facade.periodProblem()) {
      case 'START_AFTER_CUTOFF':
        return 'admin.publications.issue.periodStartAfterCutoff';
      case 'START_IN_FUTURE':
        return 'admin.publications.issue.periodNotStarted';
      default:
        return undefined;
    }
  });

  /**
   * Whether the period in the boxes is one that must not be saved.
   *
   * A period that starts after it ends selects nothing and is a typo every time.
   * A period that has not begun is NOT one: planning next week's issue is the
   * ordinary reason to open this panel, and refusing to save it would be
   * refusing the work the panel exists for — it simply cannot be previewed yet,
   * which the note above says.
   */
  protected readonly periodUnsavable = computed(
    () => this.facade.periodProblem() === 'START_AFTER_CUTOFF',
  );

  /**
   * The period start the criteria probe runs from — the edited one while the
   * panel is open, and the issue's own before anything has been typed.
   */
  protected readonly criteriaIntervalFrom = computed(
    () => this.editedPeriod()?.from ?? this.facade.issue()?.intervalFrom,
  );

  /**
   * The instant the criteria probe runs to.
   *
   * The SAME instant the member list beside it was resolved at, clamped to now
   * for the same reason: nothing exists beyond this moment, so a period ending
   * later selects exactly what one ending now does — and the two panels
   * answering at instants that merely mean the same thing is how a count beside
   * a list of a different length gets onto one screen.
   *
   * The clock is read HERE rather than once when the screen was built. A panel
   * opened an hour into a sitting was otherwise clamped to an instant an hour
   * old, while the read beside it clamped to the real now — so a message
   * published in between was in the list and not in the count. It recomputes only
   * when the boxes move, so the probe key still does not drift on its own.
   */
  protected readonly criteriaCutoff = computed(() => {
    const now = Date.now();
    // An emptied end box is an open period, which closes now — not a fallback to
    // the value that was in the box, which is the one thing the reader can see
    // is no longer there. Exactly what the read beside it does with the same box.
    return Math.min(this.editedPeriod()?.to ?? now, now);
  });

  /**
   * The edition, while the panel is open.
   *
   * Free text, and offered on EVERY series rather than only on the ones whose
   * names are built from it. An edition says "this is the second time this
   * period went out", which is a fact about an issue and not about a numbering
   * scheme — the archive holds a weekly issue that came out twice, and until now
   * there was nowhere in the product to see or say so.
   *
   * Seeded to `1` where the issue carries none: an issue always has a first
   * edition, and a blank box invites somebody to leave it blank, which the
   * server refuses.
   */
  private readonly editionDraft = signal('');

  /** What the edition was when the panel opened, so a save can send only changes. */
  private readonly seededEdition = signal('');

  /** The bound the column enforces, said in the box rather than after the save. */
  protected readonly editionMaxLength = EDITION_MAX_LENGTH;

  protected editionInput(): string {
    return this.editionDraft();
  }

  protected onEditionInput(value: string): void {
    this.editionDraft.set(value);
  }

  /**
   * An emptied box, which is not a way to clear the field.
   *
   * The server refuses a blank edition, so the panel refuses it first and says
   * why — a Save that goes out only to come back as a coded refusal teaches
   * nothing the box could have said in place.
   */
  protected readonly editionInvalid = computed(
    () => this.editing() && this.editionDraft().trim() === '',
  );

  /**
   * The edition to mark the header with, on any series.
   *
   * Through the same rule the timeline strip uses, so "the first edition shows
   * nothing" cannot come out differently in the two places. Numeric 1 and blank
   * both answer nothing; anything else is a mark worth carrying.
   */
  protected readonly headerEdition = computed(() => laterEdition(this.facade.issue()?.edition));

  /**
   * The criteria this issue would select by, while the panel is open.
   *
   * `undefined` means untouched. Distinct from a value of `undefined` INSIDE the
   * draft, which means "no criteria", so the draft is held in a one-field wrapper
   * rather than as a bare optional — otherwise "not edited" and "cleared" are the
   * same signal value and the second is unsendable.
   */
  private readonly criteriaDraft = signal<{ value: IssueCriteria | undefined } | undefined>(
    undefined,
  );

  /** Whether the criteria section is expanded. Closed by default: most issues inherit. */
  protected readonly editingCriteria = signal(false);

  /** What the issue selects right now — its own document, or the series'. */
  protected readonly effectiveCriteria = computed<IssueCriteria | undefined>(() => {
    const draft = this.criteriaDraft();
    if (draft) {
      return draft.value;
    }
    const issue = this.facade.issue();
    return issue?.criteriaOverride ?? this.seriesCriteria();
  });

  /**
   * What the issue selects, one chip per criterion.
   *
   * The values are the stable keys the document is written in, which is what a
   * criteria document contains: resolving them to names would be a fan-out of
   * lookups for a strip whose job is to say at a glance whether this issue
   * selects what its series selects.
   *
   * Each chip's label is assembled here rather than interpolated in the
   * template. Interpolations written across lines are joined with the whitespace
   * between them, which put a space in front of the colon — a label that read as
   * one whose value had gone missing.
   */
  protected readonly criteriaChips = computed(() => {
    // Read so a language switch re-labels the strip: the words are looked up at
    // the moment this runs, and the lookup is not itself a signal.
    this.language.selectedLanguage();
    return (this.effectiveCriteria()?.criteria ?? []).map((criterion) => ({
      label: this.chipLabel(criterion),
    }));
  });

  /** One criterion as the single string its chip prints. */
  private chipLabel(criterion: IssueCriterion): string {
    const kind = this.transloco.translate(`admin.publications.criteria.kind.${criterion.kind}`);
    const relation = criterion.operator === 'NOT_IN' ? ' ≠' : '';
    return `${kind}${relation}: ${criterion.values.join(', ')}`;
  }

  /** Open the edit panel straight onto the criteria, from the facts strip. */
  protected openCriteriaEditor(): void {
    if (!this.editable()) {
      return;
    }
    if (!this.editing()) {
      this.openEditing();
    }
    this.editingCriteria.set(true);
  }

  /**
   * True once the panel is showing something other than what the series says.
   *
   * An EMPTIED editor is not one of those cases, and that is the whole point.
   * Removing the last criterion row leaves no document, and "no document" is
   * what inheriting looks like — there is no such thing as a tailored issue that
   * selects by nothing. Reading it as a tailored empty document made the badge
   * say "tailored", and made the save send an override with no value, which the
   * wire drops and the server therefore stores nothing about.
   */
  protected readonly criteriaOverridden = computed(() => {
    const draft = this.criteriaDraft();
    const series = this.seriesCriteria();
    const stored = this.facade.issue()?.criteriaOverridden === true;
    if (draft) {
      return draft.value !== undefined && JSON.stringify(draft.value) !== JSON.stringify(series);
    }
    return stored;
  });

  /**
   * The series' own criteria, for the "back to the series" comparison.
   *
   * Read off the issue's own payload rather than fetched: an override is only
   * meaningful against the series it deviates from, and a second request would
   * be a second source that can disagree with the `criteriaOverridden` flag the
   * server already computed.
   */
  private readonly seriesCriteria = computed(() => this.facade.issue()?.seriesCriteria);

  protected onCriteriaChange(criteria: IssueCriteria | undefined): void {
    this.criteriaDraft.set({ value: criteria });
  }

  /** Hand the issue back to its series. */
  protected resetCriteria(): void {
    this.criteriaDraft.set({ value: this.seriesCriteria() });
  }

  /**
   * The interval, as a Signal Form so the shared date-time pickers can drive it.
   *
   * The native `<input type="datetime-local">` renders its popup in the BROWSER
   * locale rather than the app language, which is why the shared pickers exist.
   */
  private readonly intervalModel = signal<{ from: Date | null; to: Date | null }>({
    from: null,
    to: null,
  });
  protected readonly intervalForm = form(this.intervalModel);

  /**
   * What the interval was when the panel opened, so a save can send only changes.
   *
   * A signal rather than a field: {@link editDirty} compares against it, and a
   * plain field would leave that comparison stuck on the first issue the panel
   * was ever opened for.
   */
  private readonly seededInterval = signal<{ from: number | undefined; to: number | undefined }>({
    from: undefined,
    to: undefined,
  });

  /** An issue's name and interval are settled once it is published — or from another domain. */
  protected readonly editable = computed(() => {
    const open = this.facade.issue()?.status === 'OPEN';
    return open && this.writable();
  });

  /**
   * Whether the open panel holds work that closing would throw away.
   *
   * Compared against what was loaded rather than tracked as interaction: typing a
   * name back to what it already was is not a change, and prompting over it
   * teaches people to dismiss the prompt without reading it.
   */
  protected readonly editDirty = computed(() => {
    if (!this.editing()) {
      return false;
    }
    if (this.editionDraft().trim() !== this.seededEdition()) {
      return true;
    }
    const interval = this.intervalModel();
    const seeded = this.seededInterval();
    if ((interval.from?.getTime() ?? undefined) !== seeded.from) {
      return true;
    }
    if ((interval.to?.getTime() ?? undefined) !== seeded.to) {
      return true;
    }
    const draft = this.criteriaDraft();
    if (!draft) {
      return false;
    }
    const stored = this.facade.issue()?.criteriaOverride ?? this.seriesCriteria();
    return JSON.stringify(draft.value) !== JSON.stringify(stored);
  });

  /** Open on request, close through the unsaved-changes gate. */
  protected toggleEditing(): void {
    if (this.editing()) {
      void this.requestCloseEditing();
    } else {
      this.openEditing();
    }
  }

  private openEditing(): void {
    this.resetDrafts();
    this.editing.set(true);
  }

  /**
   * Close the panel, asking first when that would discard something.
   *
   * The same surface every other discard prompt in the app uses. Cancelling
   * leaves the panel exactly as it was — nothing is reseeded on the way out of a
   * prompt the reader answered "no" to.
   */
  private async requestCloseEditing(): Promise<void> {
    if (this.editDirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.closeEditing();
  }

  private closeEditing(): void {
    this.editing.set(false);
    this.resetDrafts();
  }

  private resetDrafts(): void {
    this.criteriaDraft.set(undefined);
    this.editingCriteria.set(false);

    const issue = this.facade.issue();
    // `1` where the issue carries none: an issue always has a first edition, and
    // an empty box invites an empty value the server refuses.
    const edition = issue?.edition?.trim() || DEFAULT_EDITION;
    this.seededEdition.set(edition);
    this.editionDraft.set(edition);
    this.seededInterval.set({ from: issue?.intervalFrom, to: issue?.intervalTo });
    this.intervalModel.set({
      from: issue?.intervalFrom ? new Date(issue.intervalFrom) : null,
      to: issue?.intervalTo ? new Date(issue.intervalTo) : null,
    });
  }

  /**
   * Submit only what actually changed.
   *
   * An absent field means "leave it alone" on the wire, so sending every field
   * back would round-trip values nobody edited — and eventually a stale one,
   * because the panel opened before somebody else's change landed.
   */
  protected saveEdit(): void {
    // A period that starts after it ends is refused here as well as by the
    // disabled button: the button is the only path today, and a guard that lives
    // only in the template is one keyboard shortcut away from not existing.
    if (!this.writable() || this.periodUnsavable() || this.editionInvalid()) {
      return;
    }

    const edit: {
      edition?: string;
      intervalFrom?: number;
      intervalTo?: number;
      criteriaOverride?: IssueCriteria;
      clearCriteriaOverride?: boolean;
    } = {};

    // Only when it moved. An issue that records no edition is showing the
    // default, and sending that back would write a `1` nobody typed onto every
    // issue anybody happened to rename.
    const edition = this.editionDraft().trim();
    if (edition !== this.seededEdition()) {
      edit.edition = edition;
    }

    const interval = this.intervalModel();
    const seeded = this.seededInterval();
    if (interval.from && interval.from.getTime() !== seeded.from) {
      edit.intervalFrom = interval.from.getTime();
    }
    if (interval.to && interval.to.getTime() !== seeded.to) {
      edit.intervalTo = interval.to.getTime();
    }

    // A criteria draft that came back to the series is a CLEAR, not a document
    // equal to the series'. Both end in the same stored state, but only the flag
    // can say "go back to inheriting" — sending the document would be sending a
    // copy of the series' criteria, which is a different intention and one the
    // server would then have to guess at.
    //
    // An emptied editor takes the same branch, and must: an override field with
    // no value does not survive JSON, so the request body came out empty, the
    // server changed nothing and the screen reported success.
    const criteria = this.criteriaDraft();
    if (criteria) {
      if (criteria.value !== undefined && this.criteriaOverridden()) {
        edit.criteriaOverride = criteria.value;
      } else {
        edit.clearCriteriaOverride = true;
      }
    }

    // Closed directly rather than through the gate: a save is not a discard, and
    // there is nothing left to lose once what was typed has been sent.
    if (Object.keys(edit).length === 0) {
      this.closeEditing();
      return;
    }
    this.facade.edit(edit);
    this.closeEditing();
  }

  // ------------------------------------------------------- tailoring the issue

  /**
   * What this edition says differently from its series.
   *
   * Read off the STORED issue rather than off the drawer: the screen is what a
   * reader sees when the drawer is shut, and while it is open the drawer marks
   * its own boxes from what is being typed. The two use the same rule, so a
   * field marked in one is marked in the other.
   *
   * Degrades to nothing while the series is still loading — there is nothing to
   * compare against yet, and marking every field as tailored for a second would
   * be worse than marking none.
   */
  protected readonly overrides = computed(() => {
    const issue = this.facade.issue();
    const series = this.series();
    if (!issue || !series) {
      return undefined;
    }
    const languages = this.language.modelLanguages
      .map((lang) => lang.value)
      .filter((lang) => (series.languages?.length ?? 0) === 0 || series.languages.includes(lang));
    return overrideMarks(issueValues(issue), seriesDefaults(series), languages);
  });

  /** Whether the header wears the tailoring mark at all. */
  protected readonly tailored = computed(() => {
    const marks = this.overrides();
    return marks !== undefined && anyOverridden(marks);
  });

  /**
   * How many of the facts the Document card states are tailored — the number in
   * the sentence beneath them.
   *
   * The card's own count rather than the issue's whole one: the card lists the
   * report parameters this edition answers differently from its series, so a
   * sentence counting anything else would name a number the rows above it do
   * not add up to.
   */
  protected readonly tailoredFactCount = computed(() => this.overrides()?.paramKeys.length ?? 0);

  protected readonly tailoredSentenceKey = computed(() =>
    this.tailoredFactCount() === 1
      ? 'admin.publications.overrides.tailoredFactsOne'
      : 'admin.publications.overrides.tailoredFacts',
  );

  /**
   * Whether this issue may be tailored at all.
   *
   * OPEN and writable, and nothing else. A published issue's file names are the
   * addresses its citations resolve to and its numbering is what went out on the
   * cover, so changing either is not a correction but a rewrite of the record —
   * the action is withheld rather than offered and refused.
   */
  protected readonly canTailor = computed(
    () => this.facade.issue()?.status === 'OPEN' && this.writable(),
  );

  protected readonly overridesOpen = signal(false);

  /**
   * Whether the drawer's component exists.
   *
   * Held apart from `overridesOpen` so nothing is kept while the drawer is shut:
   * the mount goes up when it opens and comes down on `hidden`, once the pane
   * has finished sliding out — which is also what makes the next open start from
   * the issue as it stands rather than from what was typed last time.
   */
  protected readonly overridesMounted = signal(false);

  protected openOverrides(): void {
    if (!this.canTailor()) {
      return;
    }
    // A refusal from the previous attempt would otherwise be sitting inside the
    // drawer, describing a save nobody has asked for yet.
    this.facade.clearError();
    this.overridesMounted.set(true);
    this.overridesOpen.set(true);
  }

  protected saveOverrides(patch: IssueOverridesPatch): void {
    if (!this.canTailor()) {
      return;
    }
    this.facade.edit(patch, () => {
      this.overridesOpen.set(false);
      this.notifications.success('admin.publications.toast.overridesSaved');
    });
  }

  /**
   * The facts the Document card states: one row per report parameter this issue
   * answers differently from its series — which is where the PDF's heading
   * lives, because a heading IS a parameter.
   *
   * Assembled here rather than in the template because the list's LENGTH depends
   * on how many parameters were tailored, which a template writing them out one
   * by one would be wrong about.
   *
   * Every row in it is tailored — that is what puts it in the list — so the card
   * marks them all rather than asking per row.
   */
  protected readonly documentFacts = computed(() => {
    const issue = this.facade.issue();
    if (!issue) {
      return [];
    }
    const marks = this.overrides();
    const params = paramText(issue.reportParams);
    return (marks?.paramKeys ?? []).map((param) => ({
      key: `param:${param}`,
      labelKey: 'admin.publications.overrides.fact.param',
      param,
      value: params[param] ?? '—',
    }));
  });

  // ------------------------------------------------------------------ document

  /**
   * One row per configured language, whether or not the issue has a desc for it.
   *
   * Driven off the configured language list rather than off the descs, because a
   * missing desc is exactly the case the panel exists to make visible: a language
   * with no row has no document, and a panel that only listed existing descs
   * would render an issue that is half-published as if it were complete.
   *
   * The address is resolved against the backend's own origin before it reaches
   * the row. The server reports it root-relative, which is what it is on the
   * host that serves it — and the app is not always served from that host, so a
   * link followed or copied verbatim reaches the wrong one.
   */
  protected readonly documentRows = computed(() => {
    const descs = this.facade.issue()?.descs ?? [];
    return this.language.modelLanguages.map((lang) => {
      const desc = descs.find((d) => d.lang === lang.value);
      return {
        lang: lang.value,
        flagPath: lang.flagPath,
        name: desc?.name,
        fileName: desc?.fileName,
        href: desc?.href === undefined ? undefined : this.apiUrls.absoluteApiUrl(desc.href),
        link: desc?.link,
        uploaded: desc?.fileSource === 'UPLOADED',
        sticky: desc?.fileSourceSticky === true,
        // A desc that does not exist cannot receive a file: the server refuses,
        // because the series is not configured for that language.
        editable: !!desc,
      };
    });
  });

  /**
   * The sentence the document card shows instead of its rows, while the files
   * are being written.
   *
   * Two actions write them and each takes tens of seconds, so which one is
   * running decides the wording: an amend replaces documents that already exist
   * and a publish writes the first ones. `undefined` is the ordinary state and
   * the rows stand.
   *
   * The publish half is gated on the status because that is the only shape the
   * sentence is true of: a release writes documents for an issue that has none
   * yet, and the card belongs to the issue on screen rather than to the release.
   */
  protected readonly documentsPendingKey = computed<string | undefined>(() => {
    if (this.facade.amending()) {
      return 'admin.publications.issue.documentsRegenerating';
    }
    if (this.facade.publishing() && this.facade.issue()?.status === 'OPEN') {
      return 'admin.publications.issue.documentsGenerating';
    }
    return undefined;
  });

  /**
   * Whether a document will appear at all without somebody uploading one.
   *
   * A query-backed series renders its own file at release; every other content
   * mode is waiting for a person. The two say different things about an empty
   * row, and reading them alike promises a file that is never coming.
   */
  protected readonly generatesDocument = computed(
    () => (this.series()?.contentMode ?? 'GENERATED_FROM_QUERY') === 'GENERATED_FROM_QUERY',
  );

  /**
   * A series whose document is uploaded by hand puts that card first, open.
   *
   * On such a series the upload IS the work, and a card that has to be opened
   * before the only action on it appears is a card that hides its own purpose.
   */
  protected readonly documentFirst = computed(() => this.series()?.contentMode === 'UPLOADED_FILE');

  /**
   * Whether the replace controls are showing.
   *
   * Read-first: the ordinary visit is somebody checking which file is attached,
   * and an upload box under every language turns that into a form. Opens by
   * itself where uploading is the whole job.
   */
  protected readonly showDocumentControls = linkedSignal({
    source: () => this.documentFirst(),
    computation: (first: boolean) => first,
  });

  protected toggleDocumentControls(): void {
    this.showDocumentControls.update((open) => !open);
  }

  /**
   * Whether any language's file will survive the next publish untouched.
   *
   * Worth saying out loud once rather than per row: a sticky file is the reason
   * a republished issue can come out carrying last week's document, and that is
   * a surprise best had here rather than after pressing publish.
   */
  protected readonly anySticky = computed(() => this.documentRows().some((row) => row.sticky));

  /** The link box's working value per language, until it is submitted. */
  private readonly linkDrafts = signal<Readonly<Record<string, string>>>({});

  /**
   * The link box's value — never the repository address of a generated file.
   *
   * The box sets an EXTERNAL link, and a repository path typed back into it
   * would store the app's own file location as if somebody else were hosting the
   * document. The resolved address lives on the row above and is copied from
   * there.
   */
  protected linkDraft(lang: string): string {
    const drafts = this.linkDrafts();
    if (lang in drafts) {
      return drafts[lang];
    }
    const stored = this.documentRows().find((r) => r.lang === lang)?.link ?? '';
    return stored.includes('/repo/') ? '' : stored;
  }

  protected onLinkInput(lang: string, value: string): void {
    this.linkDrafts.update((drafts) => ({ ...drafts, [lang]: value }));
  }

  protected saveLink(lang: string): void {
    if (!this.writable()) {
      return;
    }
    this.facade.setLink(lang, this.linkDraft(lang));
    // Dropped so the refetched value takes over again; keeping the draft would
    // leave the box showing what was typed even if the server normalised it.
    this.linkDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[lang];
      return next;
    });
  }

  /**
   * The file name this language's chooser was refused over, until it is retried.
   *
   * Held per language rather than as one message, because the panel shows every
   * language at once and a single line could not say which row it belonged to.
   */
  private readonly fileNameConflicts = signal<Readonly<Record<string, string>>>({});

  protected fileNameConflict(lang: string): string | undefined {
    return this.fileNameConflicts()[lang];
  }

  /**
   * Upload a language's document, refusing a name another language already holds.
   *
   * The issue's languages share one folder, so two languages carrying the same
   * file name is one file: whichever is written second is the document BOTH
   * languages then serve. The server refuses it — and writes the bytes before it
   * does, so by the time the refusal arrives the other language's document has
   * already been overwritten. Checked here first, where every language's current
   * file name is already on screen and nothing has been sent yet.
   */
  protected onFileChosen(lang: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file && this.writable()) {
      // Case-insensitively: the repository folder is served from a case-folding
      // filesystem on at least one deployment, so two names differing only in
      // case are the same file there and distinct here.
      const chosen = file.name.toLowerCase();
      const clash = this.documentRows().some(
        (row) => row.lang !== lang && row.fileName?.toLowerCase() === chosen,
      );
      if (clash) {
        this.fileNameConflicts.update((names) => ({ ...names, [lang]: file.name }));
      } else {
        this.dismissFileNameConflict(lang);
        this.facade.uploadFile(lang, file);
      }
    }
    // Cleared so choosing the same file twice fires again: after a failed upload
    // the obvious next action is to retry with the identical file, and a file
    // input that still holds it raises no change event.
    input.value = '';
  }

  private dismissFileNameConflict(lang: string): void {
    this.fileNameConflicts.update((names) => {
      const next = { ...names };
      delete next[lang];
      return next;
    });
  }

  /**
   * Clear a language's uploaded file, behind a confirm.
   *
   * Not a toggle: clearing removes a document somebody uploaded by hand. On a
   * series that generates its document the next release renders over the slot;
   * on one whose document is uploaded the release generates nothing and is
   * refused until another file is uploaded, so the confirm says that
   * instead. There is no undo — the bytes are gone from this screen's reach
   * the moment the request lands.
   */
  protected clearFile(lang: string): void {
    if (this.facade.working() || !this.writable()) {
      return;
    }
    const messageKey = this.generatesDocument()
      ? 'admin.publications.issue.clearFileMessage'
      : 'admin.publications.issue.clearUploadedFileMessage';
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.publications.issue.clearFileTitle'),
      message: this.transloco.translate(messageKey, {
        lang: lang.toUpperCase(),
      }),
      accept: () => this.facade.clearFile(lang),
    });
  }

  /**
   * The two ends of the content period, rendered apart.
   *
   * Apart rather than as one string because each end carries its own
   * provenance: a period can open on a stamped bound and close on a nominal one,
   * and a single "from – to" leaves nowhere to say which of the two was actually
   * observed.
   */
  protected intervalFrom(issue: PublicationIssue): string {
    return this.dateTime(issue.intervalFrom);
  }

  protected intervalTo(issue: PublicationIssue): string {
    return this.dateTime(issue.intervalTo);
  }

  /**
   * Whether an instant is a projection rather than a record.
   *
   * A date after now that nobody has stamped is what WILL be written if somebody
   * presses the button, and rendered plainly it is indistinguishable from a date
   * that was actually recorded.
   */
  protected expected(value: number | undefined): boolean {
    return value !== undefined && value > Date.now();
  }

  /** The end of the content period, marked while the issue is still open. */
  protected expectedIntervalTo(issue: PublicationIssue): boolean {
    return issue.status === 'OPEN' && this.expected(issue.intervalTo);
  }

  /**
   * Whether the public window is a projection at BOTH ends.
   *
   * A window nobody has opened yet is expected whole, and marking each end
   * separately reads as two projections that happen to sit beside each other
   * rather than as the one period it is. The moment the window has opened its
   * start is a fact, and only the end is still waiting on somebody.
   */
  protected expectedPublicWindow(issue: PublicationIssue): boolean {
    return this.expected(issue.publicFrom) && this.expected(issue.publicTo);
  }

  /**
   * Where the content period's bounds came from, said once per distinct answer.
   *
   * The provenance is a sentence ABOUT the period, not part of it: standing
   * between the two dates it broke a two-date range across three lines and read
   * as a value of its own. Below the period it is the same sentence the cut-off
   * beside it already carries. Empty for a period whose bounds were both
   * witnessed, which is the ordinary case and the one a caveat would devalue.
   *
   * An end that is still expected contributes nothing: it has not happened, so
   * it has no provenance yet — the clock beside it has already said so, and a
   * word about where a date that does not exist came from is a claim about
   * nothing.
   */
  protected intervalSourceKeys(issue: PublicationIssue): readonly string[] {
    const sources = [
      issue.intervalFromSource,
      this.expectedIntervalTo(issue) ? undefined : issue.intervalToSource,
    ];
    const keys = sources
      .filter((source) => isMarkedBound(source))
      .map((source) => boundSourceKey(source));
    return keys.filter((key, index) => keys.indexOf(key) === index);
  }

  /**
   * The instant this issue would close at, as things stand.
   *
   * The period's own end where it has one, which is what a reader plans around;
   * the recorded stamp otherwise. It is a projection either way until somebody
   * publishes, which is what the marker beside it says.
   */
  protected expectedCutoff(issue: PublicationIssue): number | undefined {
    return issue.intervalTo ?? issue.cutoffStampedAt;
  }

  /**
   * What to call the instant beside it: a plan, or a record.
   *
   * On an open issue the date is where the period is EXPECTED to close, and it
   * moves whenever somebody edits the interval or publishes at another moment —
   * so it is named as the plan it is. Once the issue is frozen the same field
   * holds the instant that was actually stamped, and calling that "planned"
   * would cast doubt on the one number here that is a fact.
   */
  protected cutoffLabelKey(issue: PublicationIssue): string {
    return issue.status === 'OPEN'
      ? 'admin.publications.issue.plannedCutoff'
      : 'admin.publications.field.cutoff';
  }

  /**
   * Where the cut-off came from — `undefined` when there is nothing to say.
   *
   * A reconstructed bound was read back out of the archive rather than recorded
   * at a release, and somebody deciding whether to trust the period needs to see
   * which. Said in words beside the date rather than behind a glyph: the
   * distinction is the whole reason the line exists, and it was reaching nobody
   * who did not hover.
   */
  protected cutoffSource(issue: PublicationIssue): string | undefined {
    return issue.cutoffStampedAt == null ? undefined : cutoffBoundSource(issue);
  }

  protected cutoffSourceKey(issue: PublicationIssue): string | undefined {
    const source = this.cutoffSource(issue);
    return source !== undefined && isMarkedBound(source) ? boundSourceKey(source) : undefined;
  }

  /**
   * Whether the release moment is worth showing beside the cut-off.
   *
   * Publishing stamps the cut-off and then does the work, so the two normally
   * differ by seconds and showing both would be one moment printed twice. They
   * separate when a release was deliberately run later than the instant
   * membership was frozen — and that gap is the whole distinction between
   * "content up to here" and "went out then".
   */
  protected publishedApart(issue: PublicationIssue): boolean {
    return publishedApartFromCutoff(issue);
  }

  protected publicFrom(issue: PublicationIssue): string {
    return issue.publicFrom ? this.localeFormat.formatDate(issue.publicFrom) : '—';
  }

  protected dateTime(value: number | undefined): string {
    return value ? this.localeFormat.formatDateTime(value) : '—';
  }

  protected date(value: number | undefined): string {
    return value ? this.localeFormat.formatDate(value) : '—';
  }

  /**
   * The label key for an actor, or `undefined` when the entry names a person.
   *
   * A person is shown by their own name and never through a key; the machine
   * kinds have no name to show and are labelled as what they are.
   */
  protected actorKey(entry: IssueAuditEntry): string | undefined {
    return auditActorKey(entry);
  }

  /**
   * The label key for a history line, or `undefined` for an action this build
   * has no wording for — which the panel then prints as the action itself.
   */
  protected actionKey(entry: IssueAuditEntry): string | undefined {
    return auditActionKey(entry.action);
  }

  /**
   * Whether this line records the issue arriving from the previous system.
   *
   * The action and the actor are one fact there — an imported issue was imported
   * BY the import — and printed as two they read as two things having happened
   * to it. Every other line keeps both halves, because on those the actor is a
   * genuinely separate answer to "who".
   */
  protected importedEntry(entry: IssueAuditEntry): boolean {
    return auditActorKey(entry) === IMPORT_ACTOR_KEY;
  }

  /**
   * What the line carries as hover text: its own id, and an import's identifier.
   *
   * An import records the identifier the issue had in the system it came out of
   * where every other line records a reason somebody typed. Nobody reads a uuid
   * off a history panel — the one person who needs it is matching it against a
   * record elsewhere — so it travels here rather than as a third visible line
   * under the date.
   */
  protected entryTitle(entry: IssueAuditEntry): string | null {
    const parts = [
      this.importedEntry(entry) ? entry.reason : undefined,
      entry.id === undefined ? undefined : String(entry.id),
    ].filter((part) => !!part);
    return parts.length === 0 ? null : parts.join(' · ');
  }

  /**
   * The two desks a transfer moved the publication between, named.
   *
   * `undefined` on every other line, and on a transfer whose detail did not
   * carry both ends — the action's own label still stands, and half a sentence
   * about a move would describe a move to nowhere.
   *
   * The ids are resolved through the domain catalogue and fall back to
   * themselves: a domain that has since been deleted still has to be named, and
   * `niord-annex` names it to anybody who would be reading this trail.
   */
  protected ownerMove(entry: IssueAuditEntry): { from: string; to: string } | undefined {
    const move = ownerTransfer(entry);
    return move ? { from: this.domainName(move.from), to: this.domainName(move.to) } : undefined;
  }

  private domainName(domainId: string): string {
    return this.domains.resolveById(domainId)?.name || domainId;
  }

  /**
   * The documents a history line superseded, newest-first order untouched.
   *
   * An empty list for every other line, so the template asks one question
   * instead of guarding on two optional levels.
   */
  protected archivedFiles(entry: IssueAuditEntry): readonly IssueArchiveFile[] {
    return entry.archive?.files ?? [];
  }

  /**
   * The checklist a history line was released against, or an empty list.
   *
   * The record rather than the rail. The rail answers "can this go out", and on
   * a published issue it is not drawn at all — so without this, what the release
   * was actually checked against, and which warnings somebody ticked to get past,
   * survive nowhere a reader can reach.
   */
  protected releaseChecks(entry: IssueAuditEntry): readonly PublishedCheck[] {
    return publishedChecklist(entry);
  }

  /**
   * Which history lines have their preserved checklist open, held per line.
   *
   * Per line rather than one flag for the panel: a line's record is about that
   * line, and one shared flag would open every one of them together.
   */
  private readonly openReleaseChecks = signal<ReadonlySet<string>>(new Set<string>());

  protected releaseChecksOpen(entry: IssueAuditEntry): boolean {
    return this.openReleaseChecks().has(auditEntryKey(entry));
  }

  protected toggleReleaseChecks(entry: IssueAuditEntry): void {
    const key = auditEntryKey(entry);
    this.openReleaseChecks.update((open) => {
      const next = new Set(open);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  }

  /**
   * Whether a preserved row carries an acknowledgement worth naming.
   *
   * Only a failing WARN does. A row that passed was never waved through, and a
   * BLOCK cannot be — saying "not acknowledged" beside either would report a
   * decision nobody was ever offered.
   */
  protected acknowledgeable(row: PublishedCheck): boolean {
    return !row.passed && row.severity === 'WARN';
  }

  protected acknowledgedKey(row: PublishedCheck): string {
    return row.acknowledged
      ? 'admin.publications.issue.acknowledged'
      : 'admin.publications.issue.notAcknowledged';
  }

  /**
   * Open one superseded document in its own tab, behind a one-time ticket.
   *
   * OPENED, not fetched, unlike the preview. The archive endpoint checks the
   * admin role in code rather than by annotation precisely so a ticket can
   * satisfy it — and a ticket is what lets a top-level navigation, which carries
   * no bearer token, reach the document at all. The reward is the browser's own
   * viewer instead of a blob the page has to hand back to it.
   *
   * The ticket lives for a minute and is single-use, so the address in the new
   * tab is not something anybody can pass on. That is the point: a superseded
   * edition is a record, not a publication, and it stays reachable only through
   * a request somebody made here.
   *
   * No role check of its own. This whole screen is behind the admin area guard,
   * and a second gate here would be a copy that can disagree with the one that
   * decides whether the reader is looking at the page.
   */
  protected openArchivedFile(entry: IssueAuditEntry, file: IssueArchiveFile): void {
    const auditEntryId = entry.archive?.auditEntryId ?? entry.id;
    if (auditEntryId === undefined) {
      return;
    }
    const url = this.facade.archiveFileUrl(auditEntryId, file.lang);
    if (url) {
      void this.downloads.openTicketed(url, 'admin');
    }
  }

  /**
   * The label keys for the two stored statuses this screen renders.
   *
   * A member's frozen status is a MESSAGE status — what the message was when the
   * issue closed — so it is read against that vocabulary rather than the issue's.
   */
  protected statusKey(issue: PublicationIssue): string {
    return issueStatusKey(issue.status);
  }

  /**
   * The status a row shows: the one the message has NOW.
   *
   * On a frozen list that is the live block every row carries, and a message
   * that is gone reads as deleted rather than as an empty badge. On a live list
   * the row's own values ARE today's, so they answer directly. The frozen status
   * is never shown: it is "published" for every member by construction, and the
   * reader's question is what the message is today — the note under the badge
   * says when that changed and that the message is still in the issue.
   */
  protected memberStatus(member: IssueMember): string | undefined {
    const live = member.current;
    if (!live) {
      return member.frozenStatus;
    }
    return live.exists ? live.status : 'DELETED';
  }

  protected memberStatusKey(member: IssueMember): string {
    return memberStatusKey(this.memberStatus(member));
  }

  /**
   * The tint that status wears — the message palette, read through the one
   * mapping the message list and the editor read.
   *
   * A status outside the message vocabulary gets no class at all: the badge then
   * falls back to its own neutral treatment, which says nothing, where a tint
   * would say something specific about a value nobody identified.
   */
  protected memberStatusClass(member: IssueMember): string {
    // The same list the label is read from, so a status can never be named on
    // screen and left untinted beside it.
    const status = this.memberStatus(member);
    const known = MEMBER_STATUSES.includes(status ?? '');
    return known ? messageStatusToStateClass(status as MessageStatus) : '';
  }

  /**
   * Leave the issue, asking first when the edit panel holds unsaved work.
   *
   * The panel is a form like any other: navigating away from it silently is the
   * same loss as closing a drawer with the ✕, and it goes through the same
   * prompt.
   */
  protected async close(): Promise<void> {
    if (this.editDirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.closed.emit();
  }
}
