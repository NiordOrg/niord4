import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  IssueTimeline,
  PublicationIssue,
  PublicationSeries,
  PublicationSeriesApiService,
  TIMELINE_DEFAULT_PERIODS,
  TIMELINE_MAX_SERIES,
  UNSCHEDULED_ARCHIVE_LIMIT,
} from '../../../services/api/publication-series-api.service';
import type { IssueDraftDecision } from './issue-draft-dialog';
import { publicationErrorKey } from './publication-errors';

interface PublicationsState {
  readonly series: readonly PublicationSeries[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: PublicationsState = { series: [], loading: true, error: false };

interface TimelineState {
  readonly timelines: ReadonlyMap<string, IssueTimeline>;
  readonly error: boolean;
}

const NO_TIMELINES: TimelineState = { timelines: new Map(), error: false };

/** The answer indexed by the series it is about, which is how a card looks it up. */
function toTimelineState(timelines: readonly IssueTimeline[]): TimelineState {
  return {
    timelines: new Map(timelines.map((timeline) => [timeline.publicationSeriesId, timeline])),
    error: false,
  };
}

/**
 * How often a series comes out, as the order its card is read in.
 *
 * The dashboard is scanned for the publication that is due next, so the cards
 * are ranked by how often each series produces one — the weeklies an editor
 * works every week first, the annuals they touch once a year last, and the
 * series with no calendar at all after those. A value the vocabulary does not
 * know sorts with the cadence-less ones rather than jumping the queue.
 */
const CADENCE_ORDER: readonly string[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'NONE'];

function cadenceRank(cadence: string | undefined): number {
  const rank = CADENCE_ORDER.indexOf(cadence ?? 'NONE');
  return rank === -1 ? CADENCE_ORDER.length : rank;
}

/**
 * Page-scoped series list for the Publications section.
 *
 * The list is split rather than shown flat, because the groups are answers to
 * different questions:
 *
 * - **cadenced** series are what the section is for: anything ACTIVE, plus a
 *   DRAFT somebody is still building. Keyed on what the series IS NOW rather
 *   than on where it came from -- a series carried in by the importer is a
 *   series like any other, and filing it apart presents it as something nobody
 *   may publish into;
 * - **retired** series are finished. Collapsed, because they are history nobody
 *   is acting on -- and because three retired development probes were the ONLY
 *   thing the live list showed while the estate's one active series sat hidden
 *   in a group of its own;
 * - **one-offs** came out once and stopped. They are NOT partitioned here: the
 *   dashboard reads them from the one-off endpoint instead, because a one-off's
 *   row is the series AND its single issue (its type, its file or link, and
 *   whether it is genuinely live) and this list carries none of the issue half.
 *   Every group below excludes them so they are not listed twice.
 *
 * EVERY GROUP IS ONE DESK'S. The list asks for the series this domain OWNS, and
 * that is not the same question as which series can be cited from here: a
 * publication another desk has shared is a citation target in the message
 * editor and nothing to administer in this section, so listing it would offer
 * settings, activation and issue creation that the server refuses.
 */
@Injectable()
export class PublicationsFacade {
  private readonly api = inject(PublicationSeriesApiService);
  private readonly language = inject(LanguageService);
  private readonly domains = inject(DomainFacade);

  private readonly refreshTick = signal(0);

  /** The desk whose publications this section lists. */
  private readonly ownerDomainId = computed(() => this.domains.sessionDomain()?.domainId);

  /**
   * Whether the dashboard is the view on screen.
   *
   * TRUE until told otherwise: a facade nobody has said anything to is one whose
   * page is showing the card grid, which is the shape every caller starts in.
   * Everything this facade fetches — the list and the strip read beside it —
   * belongs to that one view, and the page keeps a whole second screen inside
   * the same component, so without this the series screen pays for a grid it
   * never draws.
   */
  private readonly dashboardShown = signal(true);

  /** Told by the page, which owns which of its two views is rendered. */
  setDashboardShown(shown: boolean): void {
    this.dashboardShown.set(shown);
  }

  private readonly trigger = computed(() => ({
    lang: this.language.selectedLanguage(),
    domain: this.ownerDomainId(),
    shown: this.dashboardShown(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ domain, shown }) =>
        shown
          ? this.api.searchSeries(undefined, domain).pipe(
              map((series): PublicationsState => ({ series, loading: false, error: false })),
              catchError(() => of<PublicationsState>({ series: [], loading: false, error: true })),
              startWith(INITIAL),
            )
          : of<PublicationsState>({ series: [], loading: false, error: false }),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly series = computed(() => this.state().series);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /**
   * The series the section is actually for: live, or being built.
   *
   * Keyed on the KIND rather than on `cadence !== 'NONE'`, because three real
   * series have no cadence and belong here: eleven NCAGS editions, eight
   * ice-service notices and four editions of Dansk Fyrliste.
   *
   * And keyed on STATUS alone, never on `importSource`, because every real
   * series in this estate was imported. A DRAFT series appears here whatever its
   * provenance, with its status badge saying what it is; keyed any other way,
   * the list showed three retired development probes while the one ACTIVE series
   * sat collapsed in a group of its own, and the imported drafts had no route in
   * at all.
   *
   * ORDERED BY FREQUENCY, then by name. The server answers in its own order,
   * which put the once-a-year publication ahead of the two weeklies an editor
   * works every week — so the card that is due next was the last one read.
   */
  readonly cadenced = computed(() =>
    this.byFrequencyThenName(
      this.series().filter(
        (s) => s.kind !== 'ONE_OFF' && (s.status === 'ACTIVE' || s.status === 'DRAFT'),
      ),
    ),
  );

  /**
   * The cards' reading order: how often the series comes out, then its name.
   *
   * Copied before sorting — the array belongs to the state signal, and sorting
   * in place would reorder what every other reader of it sees. The name is the
   * one the reader can actually see on the card, compared in their own language
   * so Å sorts where a Danish reader looks for it.
   */
  private byFrequencyThenName(series: readonly PublicationSeries[]): readonly PublicationSeries[] {
    const lang = this.language.selectedLanguage();
    return [...series].sort((a, b) => {
      const byFrequency = cadenceRank(a.cadence) - cadenceRank(b.cadence);
      return byFrequency !== 0
        ? byFrequency
        : this.nameOf(a, lang).localeCompare(this.nameOf(b, lang), lang);
    });
  }

  /**
   * The name a card shows, falling back to whatever the series has.
   *
   * The same fallback the card itself makes: a series named only in a language
   * nobody is reading still has to sort somewhere, and sorting it under an empty
   * string would collect every such series at the top.
   */
  private nameOf(series: PublicationSeries, lang: string): string {
    // Read defensively: this runs for every row, and a comparator that throws
    // on one malformed series takes the whole card grid down with it.
    const descs = series.descs ?? [];
    const match = descs.find((desc) => desc.lang === lang) ?? descs[0];
    return match?.name ?? series.seriesId;
  }

  /** Finished. History, and not what the screen is for. */
  readonly retired = computed(() =>
    this.series().filter((s) => s.kind !== 'ONE_OFF' && s.status === 'RETIRED'),
  );

  readonly isEmpty = computed(() => !this.loading() && !this.error() && this.series().length === 0);

  /**
   * The series drawn as a strip of periods: everything with a calendar.
   *
   * Split from the ones without because the two are asked different questions.
   * A cadenced series is asked "what happened in each of the last eight
   * periods", which is the strip; a cadence-less one has no periods at all and
   * is asked "how many issues are there, and what was the last one" — see
   * {@link unscheduledSeries}.
   *
   * Named only where there is no desk to ask by: the desk-scoped read answers
   * both questions at once and picks its own series, so it needs neither list.
   */
  private readonly stripSeries = computed(() =>
    this.cadenced().filter((series) => series.cadence !== 'NONE'),
  );

  /** The series with no calendar, whose card is a one-line summary of its archive. */
  private readonly unscheduledSeries = computed(() =>
    this.cadenced().filter((series) => series.cadence === 'NONE'),
  );

  /**
   * Every series the desk owns, with its recent periods, in ONE request.
   *
   * Driven off a signal through `toObservable` rather than from an effect that
   * subscribes: a fetch started inside an effect tracks whatever the request
   * pipeline reads on its way out, and re-runs when any of it changes.
   *
   * Asked BESIDE the series list rather than after it: the server picks the
   * series itself, from the desk alone, so naming them here would make the
   * strips wait for a list this read does not depend on. The price of picking
   * twice is that a card the answer says nothing about shows no strip at all, so
   * anything that widens the group of series on the grid has to widen what this
   * read picks with it.
   *
   * ONE request for both halves of the desk. The two kinds are asked different
   * questions — a strip of periods for the series with a calendar, the newest
   * issues for the ones without — but the server answers both from the same
   * scan, so splitting them cost a second round trip on the slowest read of the
   * dashboard and bought nothing. Where there is no desk to ask by the list IS
   * the estate and there is nothing to bound a scan with, so the named form
   * answers instead — see {@link recentFeed}.
   *
   * A failure is recorded rather than thrown away, but it is not the list's
   * failure: the strips decorate a page that is perfectly usable without them,
   * so the cards still render and the section says separately that the recent
   * periods could not be read.
   */
  private readonly deskState = toSignal(
    toObservable(
      computed(() => ({
        domain: this.ownerDomainId(),
        shown: this.dashboardShown(),
        lang: this.language.selectedLanguage(),
        // Re-asked on an explicit refresh as well as on a change of desk:
        // publishing an issue moves a cell without changing anything this read
        // is keyed on, so the key alone would leave the strip showing the state
        // before it.
        tick: this.refreshTick(),
      })),
    ).pipe(
      switchMap(({ domain, shown, lang }) =>
        !shown || domain === undefined
          ? of(NO_TIMELINES)
          : this.api.recentTimelinesForDomain(domain, lang).pipe(
              map(toTimelineState),
              catchError(() => of<TimelineState>({ timelines: new Map(), error: true })),
              startWith(NO_TIMELINES),
            ),
      ),
    ),
    { initialValue: NO_TIMELINES },
  );

  /**
   * The same strips for a caller with no desk to ask by, naming its own series.
   *
   * Two reads rather than one here, and they have to be: the named endpoint
   * truncates a cadence-less series' archive to whatever period count it was
   * asked for, so the two kinds cannot share a number. This path runs only where
   * there is no session domain — the desk-scoped read above is what the app
   * itself takes.
   */
  private readonly stripState = this.recentFeed(this.stripSeries, TIMELINE_DEFAULT_PERIODS);

  private readonly archiveState = this.recentFeed(
    this.unscheduledSeries,
    UNSCHEDULED_ARCHIVE_LIMIT,
  );

  /**
   * One batched read of the named recent endpoint, for a caller with no desk.
   *
   * Written once and used twice rather than copied, because everything
   * interesting about it — the comma-joined key it triggers on, the truncation
   * to what the endpoint accepts, the failure that is recorded without failing
   * the list — has to be true of both reads or the two drift the first time
   * either is corrected.
   */
  private recentFeed(
    series: Signal<readonly PublicationSeries[]>,
    periods: number,
  ): Signal<TimelineState> {
    // A string rather than an array so the fetch is triggered by a change of
    // membership and not by a change of array identity: the list signal produces
    // a fresh array on every refresh, and keying on it would re-ask for the same
    // fifty series each time the page was redrawn.
    //
    // Truncated rather than split into further requests. The endpoint answers
    // for at most fifty named series and refuses more; a page showing more than
    // that is one whose reader is scrolling, not scanning.
    const key = computed(() =>
      series()
        .slice(0, TIMELINE_MAX_SERIES)
        .map((entry) => entry.seriesId)
        .join(','),
    );
    return toSignal(
      toObservable(
        computed(() => {
          // Read FIRST and unconditionally: the desk decides whether this read
          // runs at all, so everything else has to be re-evaluated under it.
          const domain = this.ownerDomainId();
          const shown = this.dashboardShown();
          return {
            scoped: domain !== undefined,
            shown,
            // Read only when there is no desk to ask by. Reading it always would
            // put the series list into the dependency graph of a read that never
            // fires with a desk, and every desk-scoped refresh would re-evaluate
            // this for an answer it then discards.
            key: domain === undefined ? key() : '',
            lang: this.language.selectedLanguage(),
            tick: this.refreshTick(),
          };
        }),
      ).pipe(
        switchMap(({ scoped, shown, key: named, lang }) =>
          !shown || scoped || named === ''
            ? of(NO_TIMELINES)
            : this.api.recentTimelines(named.split(','), lang, periods).pipe(
                map(toTimelineState),
                catchError(() => of<TimelineState>({ timelines: new Map(), error: true })),
                startWith(NO_TIMELINES),
              ),
        ),
      ),
      { initialValue: NO_TIMELINES },
    );
  }

  readonly timelineError = computed(
    () => this.deskState().error || this.stripState().error || this.archiveState().error,
  );

  /**
   * One series' recent issues, or `undefined` while in flight or not asked for.
   *
   * Any of the reads answers, and a caller does not have to know which: a card
   * asks for the series in front of it, and whether that series has a calendar —
   * or whether there was a desk to ask by — is a fact about the request rather
   * than about the question.
   */
  timeline(seriesId: string): IssueTimeline | undefined {
    return (
      this.deskState().timelines.get(seriesId) ??
      this.stripState().timelines.get(seriesId) ??
      this.archiveState().timelines.get(seriesId)
    );
  }

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }

  // ------------------------------------------------- opening the next issue

  /** Set while a create is in flight, so a double click cannot make two issues. */
  private readonly creating = signal(false);
  readonly issueCreating = computed(() => this.creating());

  private readonly createError = signal<string | undefined>(undefined);

  /** A refused create, as the key that names it. Cleared on the next attempt. */
  readonly createIssueErrorKey = computed(() => this.createError());

  /**
   * Create the issue an admin reviewed in the draft dialog.
   *
   * The bounds arrive REVIEWED rather than derived here: the dialog asked the
   * server what the period would be, showed it with the provenance of each
   * bound, and this is what came back approved. Both bounds go out in the one
   * create, because a period is one decision — creating the issue and then
   * correcting its end leaves an issue standing with a period nobody agreed to.
   *
   * Refetched on success, and the new issue is handed to the caller: from a
   * dashboard card the next thing anybody does is open what they just made.
   */
  createIssue(
    seriesId: string,
    decision: IssueDraftDecision,
    onDone?: (issue: PublicationIssue) => void,
  ): void {
    if (this.creating()) {
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
        next: (issue) => {
          this.creating.set(false);
          this.refresh();
          onDone?.(issue);
        },
        error: (response: unknown) => {
          this.creating.set(false);
          this.createError.set(
            publicationErrorKey(response, 'admin.publications.issueDraft.createFailed'),
          );
        },
      });
  }
}
