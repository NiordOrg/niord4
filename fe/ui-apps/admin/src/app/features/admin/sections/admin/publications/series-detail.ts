import {
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
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { map } from 'rxjs';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Card } from '../../../../../shared/components/card/card';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { ExpectedDate } from '../../../../../shared/components/expected-date/expected-date';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Pager } from '../../../../../shared/components/pager/pager';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { nowTick } from '../../../../../shared/utils/now-tick';
import {
  RowLinkAnchorDirective,
  RowLinkDirective,
} from '../../../../../shared/directives/row-link.directive';
import { Icon } from '../../../../../shared/icons/icon';
import {
  PublicationSeriesApiService,
  type IssueAuditEntry,
  type PublicationIssueRow,
  type PublicationSeries,
  type SeriesStatus,
} from '../../../services/api/publication-series-api.service';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { createClientPager } from '../../../utils/client-pager';
import { BoundSourceMarker } from './bound-source-marker';
import { IssueDetail } from './issue-detail';
import {
  PublicationIssueDraftDialog,
  type IssueDraftDecision,
  type IssueDraftSeed,
} from './issue-draft-dialog';
import { PublicationReasonDialog } from './reason-dialog';
import {
  DEFAULT_ISSUE_SORT,
  issueAriaSort,
  issueSortIcon,
  nextIssueSort,
  sortIssueRows,
  type IssueSort,
  type IssueSortColumn,
} from './issue-sort';
import { cutoffBoundSource, publishedApartFromCutoff } from './publication-bounds';
import {
  SERIES_INVALID,
  STALE_VERSION,
  publicationErrorCode,
  publicationErrorKey,
  publicationFieldErrors,
  seriesRuleTexts,
} from './publication-errors';
import { localizedNames, matchesFilter } from './publication-filter';
import { PublicationForeignDomain } from './publication-foreign-domain';
import { PublicationListFilter } from './publication-list-filter';
import { publicationVisibility, type PublicationVisibility } from './publication-visibility';
import {
  auditActionKey,
  auditActorKey,
  checkSummaryKey,
  checklistVerdict,
  computedIssueStatusClass,
  issueStatusKey as issueStatusLabelKey,
  seriesBadgeClass,
  seriesStatusKey as seriesStatusLabelKey,
} from './publication-status';
import { SeriesDetailFacade } from './series-detail.facade';
import { ownerVerdict } from './series-rules';
import { PublicationTransferTargets } from './transfer-owner';
import { PublicationTransferDialog, type TransferOwnerDecision } from './transfer-owner-dialog';

/**
 * The numbering schemes that give an issue a number worth a column of its own.
 *
 * A week-and-year or month-and-year name already carries its number in the name,
 * so a second column repeats it; a series that numbers nothing has nothing to
 * put there. Only the two edition schemes produce a number the name does not
 * say, and it is the handle people quote for those.
 */
const EDITION_SCHEMES: ReadonlySet<string> = new Set(['YEAR_EDITION', 'EDITION_SEQUENCE']);

/**
 * Whether a trail entry's detail is a bag of named facts rather than a sentence.
 *
 * The detail is typed as an open bag because the wire carries both shapes, so a
 * reader pulling a named key out of it has to establish which one it is holding
 * first.
 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * A stable handle for a row, real or synthesized.
 *
 * The same key the table tracks by: a synthesized row has no id, and keying
 * several of them on one absent value would collapse them into a single entry.
 */
function rowKey(issue: PublicationIssueRow): string {
  return issue.publicId ?? `period:${issue.sortKey ?? ''}`;
}

/**
 * How close the open issue is to going out, as the card's closing clause.
 *
 * One warning is worth naming — a card that says "1 warning" and nothing else
 * sends the reader into the workbench to find out which. Several are not: the
 * first one stands for the group and the rail behind it holds the rest.
 */
interface ChecklistSummary {
  readonly state: 'ready' | 'warnings' | 'blocked';
  /** Warnings, or the count of what stops the release. Zero when ready. */
  readonly count: number;
  /** The short clause for the first warning, where this build has one for the code. */
  readonly detailKey?: string;
  readonly detailParams?: Record<string, string>;
  /** What to say instead — the server's own text, or the bare code. */
  readonly detailText: string;
}

/**
 * One series: what it is, what it is working on, and every issue released under it.
 *
 * Three levels, top to bottom: the header says which series and what the public
 * site is serving from it; the card under it is the issue somebody is working on
 * right now; the archive is everything that came before. Issues arrive newest
 * first from the backend and stay that way — the question asked at the top is
 * "what went out most recently", and the archive is the tail of that answer.
 */
@Component({
  selector: 'app-series-detail',
  imports: [
    Icon,
    Card,
    Confirm,
    ExpectedDate,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    IssueDetail,
    Menu,
    MenuTrigger,
    Pager,
    BoundSourceMarker,
    PublicationForeignDomain,
    PublicationIssueDraftDialog,
    PublicationListFilter,
    PublicationReasonDialog,
    PublicationTransferDialog,
    RowLinkDirective,
    RowLinkAnchorDirective,
    TranslocoPipe,
  ],
  providers: [SeriesDetailFacade, ConfirmService],
  templateUrl: './series-detail.html',
  // `relative` is for the progress bar: pinned to the top edge as an overlay,
  // so a fetch that finishes in a blink does not push the page down and back.
  host: { class: 'relative flex min-w-0 flex-1 flex-col' },
})
export class SeriesDetail {
  readonly seriesId = input.required<string>();

  /**
   * The row the reader clicked, so the header is drawn before the fetch answers.
   *
   * Whoever opened this screen already had the name and the status in hand, and
   * the request behind the series takes long enough for a header that waits on
   * it to read as a page that failed to load. Never the source of anything the
   * fetch answers: it is replaced the moment the series itself arrives, and a
   * screen reached by a link gets none — nobody clicked a row.
   */
  readonly initial = input<PublicationSeries | undefined>(undefined);

  /**
   * Bumped by the host when the settings drawer has written this series.
   *
   * The drawer lives on the page rather than here, and this screen fetches the
   * series itself — so without being told, a save from inside the drawer leaves
   * the header, the cadence and the settings showing what they were before it.
   * A count rather than a flag, because two saves in a row are two events.
   */
  readonly refreshTick = input(0);

  /** Back to the list. The list owns which series is selected, so it decides. */
  readonly back = output<void>();

  /**
   * Open the settings drawer for this series — the host owns the editor.
   *
   * The series travels WITH the request. The host does not fetch the list on
   * this branch, so it has nothing to look the row up in, and the series this
   * screen fetched is the fresher of the two answers anyway.
   */
  readonly settings = output<PublicationSeries>();

  /**
   * Start a new series from this one's settings, in the same drawer.
   *
   * Not `copy`: that is a native DOM event name, and an output that shadows one
   * is a listener the host cannot tell apart from the browser's own.
   */
  readonly copySeries = output<void>();

  protected readonly facade = inject(SeriesDetailFacade);
  private readonly api = inject(PublicationSeriesApiService);
  private readonly transferTargets = inject(PublicationTransferTargets);
  private readonly domains = inject(DomainFacade);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /**
   * What the address bar names, read live rather than once on entry.
   *
   * This screen is not rebuilt for every address it can be reached at. A link
   * from a compiled issue to one of the weeks it was compiled from lands on a
   * different series and a different issue under the same route, and the page
   * is reused with the new params — so a value read from the snapshot would
   * keep showing the issue the reader just left. Back and Forward move the
   * address the same way.
   */
  private readonly routedIssueId = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('issue') ?? undefined)),
    { requireSync: true },
  );
  private readonly routedIssueFilter = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('issueFilter') ?? '')),
    { requireSync: true },
  );

  /**
   * The open issue, mirrored into `?issue=` rather than a route segment.
   *
   * The shared admin route builder takes ONE param segment, which the series
   * already uses. A query param deep-links and honours Back exactly the same
   * way without changing infrastructure every other section depends on.
   *
   * Linked to the address rather than seeded from it: the address is the record
   * and this is the screen's eager copy. Opening or closing an issue sets it
   * straight away so the panel does not wait for the navigation to settle, and
   * the address then echoes the same value back; a navigation that arrives from
   * anywhere else — a link, Back — resets it to what the address says.
   */
  protected readonly selectedIssueId = linkedSignal(() => this.routedIssueId());

  protected openIssue(publicId: string | undefined): void {
    if (!publicId) {
      // A synthesized row stands for a period, not an entity, so there is
      // nothing to open. Bringing one into existence is what the row's own
      // "create" button does — see createFromGap below.
      return;
    }
    this.selectedIssueId.set(publicId);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { issue: publicId } });
  }

  /** What the draft dialog is reviewing. `undefined` while it is closed. */
  protected readonly draftSeed = signal<IssueDraftSeed | undefined>(undefined);
  protected readonly draftDialogOpen = signal(false);

  /**
   * Whether a synthesized row offers to bring the period it stands for into being.
   *
   * A MISSING period always does: nothing was released for it and nothing ever
   * will unless somebody says so.
   *
   * An UPCOMING period does too, but only while the series has no issue open.
   * The period being worked toward is normally opened by the series itself when
   * the current issue publishes — so a button beside an OPEN issue would invite a
   * duplicate of one that already exists. With nothing open there is no duplicate
   * to invite and the row IS the next step, which is exactly the state a series
   * that has fallen behind sits in.
   *
   * Both halves stay gated on a series that can receive an issue at all and on
   * the desk that owns it: an issue opened on a DRAFT or RETIRED series is one
   * nothing will ever publish, and a create is a write another domain's admin
   * does not have.
   */
  protected offersCreate(issue: PublicationIssueRow): boolean {
    if (issue.pseudo === 'MISSING') {
      return this.facade.seriesAcceptsIssues() && this.facade.writable();
    }
    return issue.pseudo === 'UPCOMING' && this.facade.canCreateIssue();
  }

  /**
   * Review the issue a synthesized row stands for, before creating it.
   *
   * The row's own bounds are handed to the draft rather than a chain from the
   * preceding issue: they are what the reader is looking at, and asking the
   * question about a different period than the one on screen is how the answer
   * and the row stop agreeing.
   *
   * `recovered` is the one thing the two kinds of row do not share. A MISSING
   * period is being reconstructed after the fact, so its bounds are marked as
   * recovered — nobody stamped them. An UPCOMING period's start is the preceding
   * issue's real close, which WAS stamped, and marking it recovered would write a
   * provenance onto a date that has one.
   */
  protected createFromGap(issue: PublicationIssueRow): void {
    if (!this.offersCreate(issue)) {
      return;
    }
    this.draftSeed.set({
      intervalFrom: issue.intervalFrom,
      intervalTo: issue.intervalTo,
      recovered: issue.pseudo === 'MISSING',
    });
    this.draftDialogOpen.set(true);
  }

  /**
   * Open the next issue by hand, reviewed the same way.
   *
   * No bounds and no chain: the server derives the period from the newest issue
   * and the cadence, which is the same derivation the create would use. Not
   * recovered — nobody is reconstructing a period that was missed.
   */
  protected newIssue(): void {
    this.draftSeed.set({ recovered: false });
    this.draftDialogOpen.set(true);
  }

  protected submitDraft(decision: IssueDraftDecision): void {
    this.facade.createIssue(decision, () => this.draftDialogOpen.set(false));
  }

  /**
   * Back to the archive, which is refetched rather than redrawn from memory.
   *
   * Whatever was just done to the issue — released, withdrawn, amended, or a
   * successor edition opened beside it — changed the row the list is about to
   * render, and the cached list still holds the state it was in before.
   */
  protected closeIssue(): void {
    // The selection first, the refetch second. The archive is only read while it
    // is the view on screen, so a refresh spent while the issue is still open is
    // a refresh that produces no request at all.
    this.selectedIssueId.set(undefined);
    this.facade.refresh();
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { issue: null },
      queryParamsHandling: 'merge',
    });
  }

  constructor() {
    // Keyed on the input rather than read once at construction. The id is a
    // route param, and the panel is NOT rebuilt when only the param changes: a
    // link from a compiled issue to one of its source weeks lands on another
    // series through this same panel, and a one-shot handoff would have shown
    // the old series' issues under the new one's name.
    effect(() => {
      const seriesId = this.seriesId();
      untracked(() => this.facade.load(seriesId));
    });

    // Which of the two views is on screen is this screen's state, and the
    // archive is the heaviest request the section makes -- so the facade is told
    // rather than left to read the archive for a workbench that ignores it.
    //
    // The VIEW alone. Whether the publication belongs to this desk is settled by
    // the series, which the facade has in hand before this could answer at all --
    // so the facade refuses it inside the read chain, and a second gate here
    // would be one flush late on the request it means to prevent.
    effect(() => {
      const onArchive = this.selectedIssueId() === undefined;
      untracked(() => this.facade.setArchiveShown(onArchive));
    });

    // The series' own trail is read when the fold that shows it is opened, and
    // not before: most visits never open it, and this screen's request budget
    // has no room for an answer nobody looked at. The fold lives on the archive
    // view, so an issue opened on top of it must not start the read either.
    effect(() => {
      const onArchive = this.selectedIssueId() === undefined;
      const panelOpen = this.historyOpen();
      if (onArchive && panelOpen) {
        untracked(() => this.facade.loadHistory());
      }
    });

    // Told rather than watched: the drawer that wrote the series belongs to the
    // host, and this screen's own fetch is the only thing that can restate it.
    effect(() => {
      const tick = this.refreshTick();
      if (tick > 0) {
        untracked(() => this.facade.refresh());
      }
    });
  }

  /**
   * Whose series this is, from the desk being worked at.
   *
   * ONE ANSWER for the whole screen: the archive, the issue panel and the
   * refusal all hang off it, so a series cannot be refused at the top and drawn
   * below. Read from the fetched series alone, never from the row that was
   * clicked — the owner is not a fact a list hands down, and refusing on a guess
   * is worse than waiting.
   *
   * The same answer covers a desk changed while this page was open. The picker
   * applies its choice by reloading the application onto the same address, so
   * the screen comes back up at the series it was showing, reads it, and refuses
   * it — naming the desk that owns it and offering the way back — rather than
   * bouncing the reader to the dashboard with nothing said.
   */
  protected readonly ownerState = computed(() =>
    ownerVerdict(this.facade.series(), this.domains.sessionDomain()?.domainId),
  );

  /** The owning desk to refuse in favour of, and nothing while there is none. */
  protected readonly foreignOwnerDomainId = computed(() =>
    this.ownerState() === 'foreign' ? this.facade.series()?.domainId : undefined,
  );

  /**
   * What the banner says when the series cannot be shown (L-174).
   *
   * A series the server says is not there is named as such, and the banner
   * offers no Retry (the template leaves its label out): the same read can only
   * meet the same 404. Any other failure keeps "could not be loaded" and Retry.
   * Read only while the banner is on screen.
   */
  protected readonly loadErrorKey = computed(() =>
    this.facade.notFound()
      ? 'admin.publications.error.seriesNotFound'
      : 'admin.publications.detail.loadError',
  );

  /**
   * How the archive is ordered. Cut-off descending, which is what it was before
   * the headers became clickable — the default answers "what went out last".
   */
  protected readonly sort = signal<IssueSort>(DEFAULT_ISSUE_SORT);

  /**
   * What the reader is looking for in this archive, held in the address bar.
   *
   * Linked to the query parameter exactly as the open issue is: the writes below
   * are this screen's own and the address echoes them back unchanged, while a
   * navigation that lands here from another series starts from whatever that
   * address carries rather than from the box the reader filled on the previous
   * one. A weekly series carries ~500 issues, which is the case the box exists
   * for — and a filtered archive that survives a reload is what makes "the one
   * I was looking at" findable twice.
   */
  protected readonly issueFilter = linkedSignal(() => this.routedIssueFilter());

  /**
   * Take a settled filter, mirror it into the address bar, and go back to page one.
   *
   * The page reset is not cosmetic: filtering from page nine otherwise leaves the
   * reader on a page the narrowed list no longer reaches, which reads as a filter
   * that emptied the archive.
   */
  protected setIssueFilter(value: string): void {
    this.issueFilter.set(value);
    this.pager.reset();
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { issueFilter: value === '' ? null : value },
      queryParamsHandling: 'merge',
    });
  }

  /** Whether anything is being filtered at all — an empty box is not a filter. */
  protected readonly filtering = computed(() => this.issueFilter().trim() !== '');

  /**
   * The rows the filter leaves, synthesized periods included.
   *
   * Matched against every name a row has — and against what the series WOULD
   * call a period nobody published, which is the only name a synthesized row
   * has. Dropping the gaps from a filtered list would hide exactly the rows the
   * screen exists to surface.
   */
  protected readonly filteredIssues = computed(() => {
    const query = this.issueFilter();
    return this.facade
      .issues()
      .filter((row) =>
        matchesFilter([...localizedNames(row.descs), ...localizedNames(row.suggestedDescs)], query),
      );
  });

  /**
   * The rows in the reader's order, synthesized periods included.
   *
   * Sorted before paging, not after: paging a list and then reordering the page
   * sorts twenty rows out of five hundred, which reads as a sort that does not
   * work rather than as one applied to the wrong scope.
   */
  protected readonly sortedIssues = computed(() =>
    sortIssueRows(this.filteredIssues(), this.sort(), (row) => this.issueName(row)),
  );

  /**
   * The archive is paged, on the client, because the endpoint returns it whole.
   *
   * weekly-ntm has 498 issues and weekly-ntm-p-t another 498 — rendering both
   * archives in full is a table nobody scrolls to the end of, and the question
   * being asked at the top of it ("what went out most recently") is answered by
   * the first rows. No page reset is wired up for the series itself: the id is a
   * route param, so moving to another series re-creates the component and the
   * pager with it.
   */
  protected readonly pager = createClientPager(this.sortedIssues, ADMIN_PAGE_SIZE);

  /**
   * Re-order on a header click, and go back to the first page.
   *
   * Without the reset a reader sorting from page nine keeps page nine, which now
   * holds a different twenty rows for reasons nothing on screen explains.
   */
  protected toggleSort(column: IssueSortColumn): void {
    this.sort.update((current) => nextIssueSort(current, column));
    this.pager.reset();
  }

  protected ariaSort(column: IssueSortColumn): 'ascending' | 'descending' | 'none' {
    return issueAriaSort(this.sort(), column);
  }

  protected sortIcon(column: IssueSortColumn): string | undefined {
    return issueSortIcon(this.sort(), column);
  }

  /**
   * What the page says about gaps — and it says nothing about a clean archive.
   *
   * Two states are worth a line, not three. Missing periods are a finding and
   * read as a warning; "nobody looked" is the absence of one, and the reason it
   * was not checked is also the fix, so it is said quietly. A complete archive
   * is the ordinary case and gets no line at all: a green tick on every screen
   * is a line readers stop seeing, and it costs the two that matter their
   * prominence.
   */
  protected readonly gapSummary = computed(() => {
    const detection = this.facade.gapDetection();
    if (!detection) {
      return undefined;
    }
    if (!detection.enabled) {
      return { state: 'notChecked' as const, reasonCode: detection.reasonCode, count: 0 };
    }
    const count = this.facade.gapCount() ?? 0;
    return count > 0
      ? { state: 'found' as const, reasonCode: detection.reasonCode, count }
      : undefined;
  });

  /**
   * The series as the screen knows it: the fetched one, or the row that was clicked.
   *
   * The fetch wins the moment it answers. Until then the header renders from
   * what the list already had, which is the name and the status — everything
   * below it waits for the real answer.
   *
   * Only while the row IS this series. The row was handed over by whichever
   * navigation built the page, and the page outlives that navigation: a link
   * from one series to another keeps the panel and changes the id, and the row
   * then names the series the reader just left. A header briefly absent beats
   * one stating the wrong publication.
   */
  protected readonly shownSeries = computed(() => {
    const initial = this.initial();
    return this.facade.series() ?? (initial?.seriesId === this.seriesId() ? initial : undefined);
  });

  protected readonly seriesName = computed(() => {
    const series = this.shownSeries();
    return series ? this.nameOf(series.descs, series.seriesId) : '';
  });

  /**
   * The badge beside the name — and for a live series the answer is no badge.
   *
   * An ACTIVE series is the ordinary case, and badging every screen with
   * "active" spends the reader's attention on the state that needs none. Only
   * DRAFT and RETIRED change what may be done here, so only they are marked.
   */
  protected readonly seriesBadge = computed(() => seriesBadgeClass(this.shownSeries()?.status));

  protected readonly seriesBadgeKey = computed(() =>
    seriesStatusLabelKey(this.shownSeries()?.status),
  );

  /** How often this series comes out, as the label for the header line. */
  protected readonly cadenceKey = computed(
    () => 'admin.publications.cadence.' + (this.shownSeries()?.cadence ?? 'NONE'),
  );

  /**
   * How the header says that nothing of this series is public right now.
   *
   * Two sentences, because the branch covers two different series. One that has
   * published something and whose windows have all closed has a public history
   * to point at; one that has never published anything — a new series with a
   * single open issue, or one whose issues were all withdrawn — has none, and
   * telling its reader that only historic publications remain would invent an
   * archive that does not exist.
   */
  protected readonly publicNowNoneKey = computed(() =>
    this.facade.issues().some((issue) => issue.status === 'PUBLISHED')
      ? 'admin.publications.detail.publicNowNoneHistoric'
      : 'admin.publications.detail.publicNowNone',
  );

  /** Still a draft: it accepts no issues, and the one line at the top says so. */
  protected readonly isDraft = computed(() => this.facade.series()?.status === 'DRAFT');

  /**
   * Finished, and deliberately given no "activate this first" hint.
   *
   * A DRAFT series is one step from accepting issues and the hint names that
   * step. A RETIRED one is not: telling an operator to activate a series that
   * was retired on purpose reads as an instruction rather than as an
   * explanation.
   */
  protected readonly isRetired = computed(() => this.shownSeries()?.status === 'RETIRED');

  /**
   * What to call a row.
   *
   * A synthesized row has no descs of its own — nobody has authored it — so it
   * falls back to what the series WOULD name that period. The fallback of last
   * resort is the row kind rather than a publicId, because a row that has no
   * name also has no id.
   */
  protected issueName(issue: PublicationIssueRow): string {
    const descs = issue.descs?.length ? issue.descs : (issue.suggestedDescs ?? []);
    return this.nameOf(descs, issue.publicId ?? '');
  }

  /**
   * The label key for the stored status this screen renders.
   *
   * A stored status is the database's word for the state, in English, in every
   * language. The key space is closed and covered by a spec, so an unexpected
   * value renders as "unknown" rather than as a raw key.
   */
  protected issueStatusKey(issue: PublicationIssueRow): string {
    return issueStatusLabelKey(issue.status);
  }

  /**
   * The tint a row's badge wears, from either vocabulary.
   *
   * A period nobody published is tinted for what it IS; a real issue for the
   * status it holds. One call so the two can never disagree about a status.
   */
  protected issueBadge(issue: PublicationIssueRow): string {
    return computedIssueStatusClass(issue.pseudo ?? issue.status);
  }

  /** Whether the archive numbers its issues in a way worth its own column. */
  protected readonly showEdition = computed(() =>
    EDITION_SCHEMES.has(this.shownSeries()?.numberingScheme ?? 'NONE'),
  );

  /** The cut-off to show: stamped where there is one, the period end otherwise. */
  protected cutoff(issue: PublicationIssueRow): number | undefined {
    return issue.effectiveCutoff ?? issue.cutoffStampedAt;
  }

  /**
   * Whether the cut-off on a row is still a projection.
   *
   * An OPEN issue has not been stamped — publishing is what writes the instant —
   * so the date beside it says what WOULD be recorded rather than what was. A
   * synthesized period is the same: nobody has released it.
   */
  protected expectedCutoff(issue: PublicationIssueRow): boolean {
    return issue.cutoffStampedAt == null;
  }

  /**
   * Where that cut-off came from, so a derived one is not read as a record.
   *
   * `undefined` where the row has no cut-off at all, and on a row whose date is
   * already marked as expected: saying "nominal" beside a date the screen has
   * just called a projection is the same caveat twice.
   */
  protected cutoffSource(issue: PublicationIssueRow): string | undefined {
    return this.cutoff(issue) == null || this.expectedCutoff(issue)
      ? undefined
      : cutoffBoundSource(issue);
  }

  /**
   * Whether the release moment says something the cut-off does not.
   *
   * Publishing stamps the cut-off and then does the work, so the two normally
   * differ by seconds — printing both would be two spellings of one moment. They
   * are worth separating when a release was deliberately run later than the
   * instant membership was frozen, and then the release is a muted suffix rather
   * than a column: it is a footnote on the cut-off, not a second date to scan.
   */
  protected publishedApart(issue: PublicationIssueRow): boolean {
    return publishedApartFromCutoff(issue);
  }

  /**
   * The start of the public window as the column should read it.
   *
   * The server's projection where it has one, the stored instant otherwise. An
   * issue that has not been released has no stored window at all — nothing was
   * stamped, and a date written on it in advance is a claim about an action
   * nobody has taken — so on an OPEN row the projection is the only value there
   * is.
   */
  protected publicStart(issue: PublicationIssueRow): number | undefined {
    return issue.expectedPublicFrom ?? issue.publicFrom;
  }

  /**
   * The end of the public window as the column should read it.
   *
   * The projection wins over the stored value on purpose: a published issue
   * stays out until its successor is released, so its end is whatever the
   * successor's release will stamp — which is what the site is going to do,
   * rather than what the record happens to say today.
   */
  protected publicEnd(issue: PublicationIssueRow): number | undefined {
    return issue.expectedPublicTo ?? issue.publicTo;
  }

  /**
   * Whether the end of a public window is still a projection.
   *
   * Three rows answer yes. One the server has projected an end for; an OPEN
   * issue, which has published nothing; and the issue that is live right now,
   * whose window closes when its successor goes out and nobody has done that
   * yet. Every other row's window closed at an instant that has already passed.
   */
  protected expectedPublicEnd(issue: PublicationIssueRow): boolean {
    if (issue.expectedPublicTo != null) {
      return true;
    }
    if (issue.publicTo == null) {
      return false;
    }
    return issue.status === 'OPEN' || issue.publicId === this.facade.publicNowIssue()?.publicId;
  }

  /**
   * The clock the public windows are read against.
   *
   * A moving one, because "public since" is a statement about now: a window
   * whose start is still in the future is scheduled rather than running, and a
   * bare `Date.now()` read inside the derivation below would pin that answer to
   * whenever the screen was opened.
   */
  private readonly now = nowTick();

  /**
   * The public standing of every row on the page, as the shared rule reads it.
   *
   * ONE derivation for the whole application. The rule already knows that a
   * window with a start and no end is a publication that has been public SINCE a
   * date — the one shape a range cannot express — and re-deciding that here would
   * be a second answer that can disagree with the one-off screens and with the
   * public site.
   *
   * Built as a map over the page rather than called per row from the template: a
   * method would hand the translate pipe a fresh params object on every
   * change-detection pass, so a cell nothing had touched would be re-translated.
   *
   * The bounds are the ones the column already prefers — the server's projection
   * over the stored instant — so a published issue whose successor will close its
   * window reads as the bounded period it is going to be rather than as open
   * ended.
   */
  private readonly publicLines = computed<ReadonlyMap<string, PublicationVisibility>>(() => {
    const now = this.now();
    return new Map(
      this.pager.paged().map((issue) => [
        rowKey(issue),
        publicationVisibility(
          {
            issueStatus: issue.status,
            publicFrom: this.publicStart(issue),
            publicTo: this.publicEnd(issue),
            now,
          },
          (value) => this.date(value),
        ),
      ]),
    );
  });

  /**
   * The row's window where it is open ended, and nothing otherwise.
   *
   * An open end is a complete answer, not half a range: "9 Sep –" reads as a
   * date somebody forgot to finish typing, and the dash invites the eye to look
   * for the end that is deliberately not there.
   */
  protected publicSince(issue: PublicationIssueRow): PublicationVisibility | undefined {
    const line = this.publicLines().get(rowKey(issue));
    return line?.state === 'SINCE' ? line : undefined;
  }

  /**
   * The same answer for the issue the public site is serving right now.
   *
   * Read through the same rule as the archive rows, so the header band and the
   * table cell for one issue cannot describe its window in two different ways.
   */
  protected readonly publicNowSince = computed<PublicationVisibility | undefined>(() => {
    const live = this.facade.publicNowIssue();
    if (!live) {
      return undefined;
    }
    const line = publicationVisibility(
      {
        issueStatus: live.status,
        publicFrom: this.publicStart(live),
        publicTo: this.publicEnd(live),
        now: this.now(),
      },
      (value) => this.date(value),
    );
    return line.state === 'SINCE' ? line : undefined;
  });

  /**
   * Whether the WHOLE public window is still a projection, not just its end.
   *
   * An open issue has published nothing, so neither bound has been stamped —
   * the window opens at the moment somebody releases it and closes when its
   * successor goes out. Marking only the end there leaves the start reading as a
   * recorded date beside a projected one, which is the one reading that is
   * wrong: the pair is one expectation and is marked once, over the range.
   */
  protected expectedPublicWindow(issue: PublicationIssueRow): boolean {
    return issue.status === 'OPEN' && this.publicStart(issue) != null;
  }

  /**
   * What the release rail says about the open issue, in one clause.
   *
   * Three answers, and each is worth a different amount of the reader's
   * attention: nothing standing in the way is said quietly, warnings are named
   * — with the first one spelled out, because "1 warning" alone only tells
   * somebody to go and look — and something that stops the release is said in
   * the colour that stops people.
   *
   * `undefined` while the read has not answered or could not: a card that
   * cannot say how the issue stands says nothing about it rather than guessing
   * "ready".
   *
   * The counts come from the same function the workbench's own verdict does, so
   * the card and the rail cannot disagree about the same issue.
   */
  protected readonly checklistSummary = computed<ChecklistSummary | undefined>(() => {
    const checklist = this.facade.currentChecklist();
    if (!checklist) {
      return undefined;
    }
    const verdict = checklistVerdict(checklist.rows);
    if (verdict.state === 'blocked') {
      return { state: 'blocked', count: verdict.blocking, detailText: '' };
    }
    if (verdict.state === 'ready') {
      return { state: 'ready', count: 0, detailText: '' };
    }
    const first = checklist.rows.find(
      (row) => row.applicable !== false && !row.passed && row.severity !== 'BLOCK',
    );
    return {
      state: 'warnings',
      count: verdict.warnings,
      detailKey: first ? checkSummaryKey(first.code) : undefined,
      detailParams: { detail: first?.detail ?? '' },
      // A code nobody has worded yet falls back to the server's own sentence,
      // and to the code itself where there is none. Building the key blind would
      // print a key path where the explanation belongs.
      detailText: first?.detail ?? first?.code ?? '',
    };
  });

  /**
   * The count sentence, in the form that fits the number it carries.
   *
   * "1 message(s)" is the shape of a string nobody wrote for the number it ended
   * up with. Two keys and a choice, rather than one key that is wrong once per
   * screen.
   */
  protected memberCountKey(count: number): string {
    return count === 1
      ? 'admin.publications.detail.memberCountOne'
      : 'admin.publications.detail.memberCount';
  }

  /** The same choice for the warning clause: "1 warning", never "1 warning(s)". */
  protected checklistWarningsKey(count: number): string {
    return count === 1
      ? 'admin.publications.detail.checklistWarningsOne'
      : 'admin.publications.detail.checklistWarnings';
  }

  protected readonly issueCountKey = computed(() =>
    this.filteredIssues().length === 1
      ? 'admin.publications.detail.issueCountOne'
      : 'admin.publications.detail.issueCount',
  );

  protected date(value: number | undefined): string {
    return value ? this.localeFormat.formatDate(value) : '—';
  }

  protected dateTime(value: number | undefined): string {
    return value ? this.localeFormat.formatDateTime(value) : '—';
  }

  private nameOf(descs: readonly { lang: string; name?: string }[], fallback: string): string {
    const lang = this.language.selectedLanguage();
    return (descs.find((d) => d.lang === lang) ?? descs[0])?.name ?? fallback;
  }

  // --------------------------------------------------------- the actions menu

  private readonly copyLabel = translateSignal('admin.publications.menu.copySeries');
  private readonly transferLabel = translateSignal('admin.publications.transfer.action');
  private readonly activateLabel = translateSignal('admin.publications.series.editor.activate');
  private readonly retireLabel = translateSignal('admin.publications.series.editor.retire');
  private readonly reinstateLabel = translateSignal('admin.publications.series.editor.reinstate');

  /**
   * The `⋯` menu: everything that is not "open the settings".
   *
   * Only the transitions that apply are offered — a series is in exactly one
   * state, and listing the other two greyed out asks the reader to work out
   * which one they are looking at. There is no per-series export: the export the
   * section carries writes every series definition at once, and a menu item here
   * promising one series' worth of it would be a different action under the same
   * word.
   */
  protected readonly menuItems = computed<MenuItem[]>(() => {
    const items: MenuItem[] = [
      { label: this.copyLabel(), icon: 'lucideCopy', command: () => this.copySeries.emit() },
    ];
    if (this.canTransfer()) {
      items.push({ label: this.transferLabel(), command: () => this.openTransfer() });
    }
    const lifecycle = this.lifecycleItem();
    if (lifecycle) {
      items.push({ separator: true }, lifecycle);
    }
    return items;
  });

  /** The one transition this series is in a state to make, if any. */
  private lifecycleItem(): MenuItem | undefined {
    if (!this.facade.writable() || this.statusWorking()) {
      return undefined;
    }
    switch (this.facade.series()?.status) {
      case 'DRAFT':
        return { label: this.activateLabel(), command: () => this.activate() };
      case 'ACTIVE':
        return { label: this.retireLabel(), command: () => this.openRetire() };
      case 'RETIRED':
        return { label: this.reinstateLabel(), command: () => this.reinstate() };
      default:
        return undefined;
    }
  }

  // ------------------------------------------------ activate, retire, reinstate

  /** Set while a transition is in flight, so a second click cannot start another. */
  private readonly changingStatus = signal(false);
  protected readonly statusWorking = computed(() => this.changingStatus());

  protected readonly retireDialogOpen = signal(false);
  protected readonly retireErrorKey = signal<string | undefined>(undefined);

  /** Whether an activation can be offered at all — the inline link asks the same. */
  protected readonly canActivate = computed(
    () => this.isDraft() && this.facade.writable() && !this.statusWorking(),
  );

  /**
   * Put the series into use, behind a plain confirm.
   *
   * ACTIVE is what puts a series in the message editor's publication picker and
   * lets issues be created against it, so it is a change other people see —
   * asked as "whether", not "why": nothing new is being recorded about the
   * series, and the rules are re-run server-side either way.
   */
  protected activate(): void {
    if (!this.canActivate()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.series.editor.activateTitle'),
      message: this.transloco.translate('admin.publications.series.editor.activateMessage'),
      accept: () =>
        this.changeStatus('ACTIVE', undefined, {
          success: 'admin.publications.series.toast.activated',
          failure: 'admin.publications.series.toast.activateError',
        }),
    });
  }

  protected openRetire(): void {
    this.retireErrorKey.set(undefined);
    this.retireDialogOpen.set(true);
  }

  /**
   * Take the series out of use, with the reason the transition records.
   *
   * Retiring a series retires NOTHING it has already released: published issues
   * stay on the public listing and their files stay at their links. The dialog
   * says so rather than leaving an admin to discover that withdrawing a series
   * did not withdraw its documents.
   */
  protected submitRetire(reason: string): void {
    this.retireErrorKey.set(undefined);
    this.changeStatus(
      'RETIRED',
      reason,
      {
        success: 'admin.publications.series.toast.retired',
        failure: 'admin.publications.series.toast.retireError',
      },
      // Left OPEN, holding what was typed: a refused transition is usually a
      // rule the admin can answer, and closing the dialog throws the reason away.
      (key) => this.retireErrorKey.set(key),
      () => this.retireDialogOpen.set(false),
    );
  }

  /**
   * Put a retired series back into use, behind a plain confirm.
   *
   * No reason is asked for: reinstating restores the state the series was in
   * before, and the invariants are re-run server-side — a series may well have
   * been retired *because* it was misconfigured.
   */
  protected reinstate(): void {
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.series.editor.reinstateTitle'),
      message: this.transloco.translate('admin.publications.series.editor.reinstateMessage'),
      accept: () =>
        this.changeStatus('ACTIVE', undefined, {
          success: 'admin.publications.series.toast.reinstated',
          failure: 'admin.publications.series.toast.reinstateError',
        }),
    });
  }

  /**
   * One transition, however it was asked for.
   *
   * The revision this screen read goes out with it, so a change made against a
   * series somebody has moved underneath is refused rather than applied blind —
   * and a refusal over the revision refetches, because the next attempt has to
   * carry the current one.
   *
   * A refusal by the series rules (SERIES_INVALID) is the save's code, and its
   * shared sentence says a save failed. Nothing was saved here, so the step's
   * own failure sentence is said instead, with the rules the answer named
   * listed under it in the words the series editor uses (L-044). The toast
   * stays until it is closed: a list of rules is not read in five seconds.
   */
  private changeStatus(
    status: SeriesStatus,
    reason: string | undefined,
    keys: { readonly success: string; readonly failure: string },
    onError?: (key: string) => void,
    onDone?: () => void,
  ): void {
    const series = this.facade.series();
    if (!series || this.changingStatus()) {
      return;
    }
    this.changingStatus.set(true);
    this.api.setSeriesStatus(series.seriesId, status, reason, series.version).subscribe({
      next: () => {
        this.changingStatus.set(false);
        this.notifications.success(keys.success);
        this.facade.refresh();
        onDone?.();
      },
      error: (response: unknown) => {
        this.changingStatus.set(false);
        const code = publicationErrorCode(response);
        const failedRules = code === SERIES_INVALID;
        const key = failedRules ? keys.failure : publicationErrorKey(response, keys.failure);
        const rules = failedRules
          ? seriesRuleTexts(
              (ruleKey, params) => this.transloco.translate(ruleKey, params),
              publicationFieldErrors(response),
            )
          : [];
        if (onError) {
          onError(key);
        } else if (rules.length > 0) {
          this.notifications.notify({
            severity: 'error',
            summaryKey: key,
            detailKey: 'admin.publications.series.toast.failedRules',
            detailParams: { rules: rules.join(' ') },
            life: 0,
          });
        } else {
          this.notifications.error(key);
        }
        if (code === STALE_VERSION) {
          this.facade.refresh();
        }
      },
    });
  }

  // ---------------------------------------------------- what happened to it

  /**
   * Whether the series' own trail is open. Closed, always.
   *
   * It never opens itself: it is a record rather than a decision, so nothing
   * about a series makes reading it the reason somebody came — and opening it is
   * what pays for the request behind it.
   */
  protected readonly historyOpen = signal(false);

  protected toggleHistory(): void {
    this.historyOpen.update((open) => !open);
  }

  /** The label key for a trail action, or `undefined` to render the code itself. */
  protected historyActionKey(entry: IssueAuditEntry): string | undefined {
    return auditActionKey(entry.action);
  }

  /**
   * Who the entry was recorded for, as a key — or `undefined` for a named person.
   *
   * The caller prints `actorLabel` in that case. A machine actor has no name and
   * gets a label saying which kind of machine it was.
   */
  protected historyActorKey(entry: IssueAuditEntry): string | undefined {
    return auditActorKey(entry);
  }

  /**
   * What a series-level line is ABOUT, in one clause.
   *
   * The line that survives a removed issue is the case this exists for: with the
   * issue gone, the entry's own detail is the only place its name, its period
   * and the state it was in are still written down.
   */
  protected historySubject(entry: IssueAuditEntry): string | undefined {
    const detail = entry.detail;
    if (typeof detail === 'string') {
      return detail || undefined;
    }
    if (!isRecord(detail)) {
      return undefined;
    }
    const name = this.detailName(detail);
    const period = this.detailPeriod(detail);
    return [name, period].filter((part) => !!part).join(' · ') || undefined;
  }

  /** The issue's name in the reader's language, or whatever name the entry kept. */
  private detailName(detail: Readonly<Record<string, unknown>>): string | undefined {
    const names = detail['names'];
    if (isRecord(names)) {
      const lang = this.language.selectedLanguage();
      const picked = names[lang] ?? Object.values(names).find((value) => !!value);
      return typeof picked === 'string' ? picked : undefined;
    }
    const publicId = detail['publicId'];
    return typeof publicId === 'string' ? publicId : undefined;
  }

  /** The content period the entry recorded, as the two dates it stood between. */
  private detailPeriod(detail: Readonly<Record<string, unknown>>): string | undefined {
    const from = detail['intervalFrom'];
    const to = detail['intervalTo'];
    if (typeof from !== 'number' && typeof to !== 'number') {
      return undefined;
    }
    return `${this.date(typeof from === 'number' ? from : undefined)} – ${this.date(
      typeof to === 'number' ? to : undefined,
    )}`;
  }

  /** The status the issue was in when the entry was written, where it says. */
  protected historyStatusKey(entry: IssueAuditEntry): string | undefined {
    const detail = entry.detail;
    if (!isRecord(detail)) {
      return undefined;
    }
    const status = detail['statusAtDeletion'];
    return typeof status === 'string' ? issueStatusLabelKey(status) : undefined;
  }

  // -------------------------------------------------------- moving it to a desk

  protected readonly transferDialogOpen = signal(false);

  /** The domain that owns the series now, which the picker excludes. */
  protected readonly ownerDomainId = computed(() => this.facade.series()?.domainId);

  /**
   * Whether moving this series to another desk is on the table.
   *
   * Two conditions, and both are about not offering something that cannot work.
   * The series has to be this desk's to change, because a transfer is a write
   * like any other — and there has to be somewhere to move it TO: an admin who
   * administers one domain has no target, and a button opening a dialog with an
   * empty picker explains nothing.
   */
  protected readonly canTransfer = computed(() => {
    const writable = this.facade.writable();
    const loaded = this.facade.series() !== undefined;
    const targets = this.transferTargets.targetsFor(this.ownerDomainId());
    return loaded && writable && targets.length > 0;
  });

  protected openTransfer(): void {
    if (!this.canTransfer()) {
      return;
    }
    this.transferDialogOpen.set(true);
  }

  protected submitTransfer(decision: TransferOwnerDecision): void {
    this.facade.transferOwner(decision.domainId, decision.reason, () =>
      this.transferDialogOpen.set(false),
    );
  }
}
