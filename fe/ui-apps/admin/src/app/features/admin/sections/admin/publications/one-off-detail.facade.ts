import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import {
  catchError,
  concatMap,
  forkJoin,
  map,
  of,
  startWith,
  switchMap,
  tap,
  type Observable,
} from 'rxjs';

import {
  OneOffApiService,
  type OneOffPublication,
} from '../../../services/api/one-off-api.service';
import {
  PublicationSeriesApiService,
  type IssueAuditEntry,
  type PublicationIssue,
} from '../../../services/api/publication-series-api.service';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { publicationErrorKey, seriesNotFound } from './publication-errors';
import { ownerVerdict } from './series-rules';

interface OneOffDetailState {
  readonly publication?: OneOffPublication;
  /** The single issue behind it, for the file names, the links and the public window. */
  readonly issue?: PublicationIssue;
  readonly audit: readonly IssueAuditEntry[];
  readonly loading: boolean;
  readonly error: boolean;
  /** The read failed because the publication is not there: a 404 with SERIES_NOT_FOUND. */
  readonly notFound?: boolean;
}

const INITIAL: OneOffDetailState = { audit: [], loading: true, error: false };
const IDLE: OneOffDetailState = { audit: [], loading: false, error: false };

/**
 * One one-off publication, as its own page reads it.
 *
 * THREE READS, ONE PUBLICATION. The publication is read by id through the
 * one-off endpoint rather than through `getSeries`, because the facts that
 * decide its public standing are not fields on the series: the issue's status
 * and the category's publish flag come off this payload, and re-deriving either
 * here would be a second definition of visibility that can disagree with the
 * public site. The read itself is UNSCOPED, because the owner is part of what it
 * answers — a link into another desk's publication has to resolve far enough to
 * name the desk it belongs to before the screen can refuse it.
 *
 * The issue read is what the document card is about: file names, hrefs and the
 * public period live on the issue, not on the series. The audit read is the
 * history rail. Both are keyed on the issue the publication carries, so a
 * publication whose issue has not been created yet simply has neither — and both
 * stop at the publication when the publication is not this desk's.
 *
 * The two go out side by side once the publication has answered: each needs only
 * the issue it names and neither needs the other, so the page costs two round
 * trips rather than three. Either failing fails the page, as a failed
 * publication read does.
 */
@Injectable()
export class OneOffDetailFacade {
  private readonly oneOffApi = inject(OneOffApiService);
  private readonly api = inject(PublicationSeriesApiService);
  private readonly domains = inject(DomainFacade);

  private readonly seriesId = signal<string | undefined>(undefined);
  private readonly refreshTick = signal(0);
  private readonly busy = signal(false);

  private readonly trigger = computed(() => ({
    seriesId: this.seriesId(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ seriesId }) => {
        if (!seriesId) {
          return of(IDLE);
        }
        return this.oneOffApi.get(seriesId).pipe(
          concatMap((publication) => {
            const publicId = publication.issuePublicId;
            // A publication another desk owns is refused in place of the page,
            // so the two reads behind the document card and the history rail
            // answer questions nothing on screen is going to ask. The owner
            // arrives with the publication, which is why this is the first
            // moment it can be asked at all.
            if (
              !publicId ||
              ownerVerdict(publication.series, this.domains.sessionDomain()?.domainId) === 'foreign'
            ) {
              return of<OneOffDetailState>({
                publication,
                audit: [],
                loading: false,
                error: false,
              });
            }
            return forkJoin({
              issue: this.api.getIssue(publicId),
              audit: this.api.issueAudit(publicId),
            }).pipe(
              map(
                ({ issue, audit }): OneOffDetailState => ({
                  publication,
                  issue,
                  audit,
                  loading: false,
                  error: false,
                }),
              ),
            );
          }),
          // A failed read and a URL pointing at nothing both fail the page, but
          // they are not the same answer. A publication that is not in
          // the estate (a series that is not a one-off included) is refused with
          // a coded 404, and the page says so without Retry, which could only
          // meet the same 404. Any other failure keeps Retry.
          catchError((response: unknown) =>
            of<OneOffDetailState>({
              audit: [],
              loading: false,
              error: true,
              notFound: seriesNotFound(response),
            }),
          ),
          startWith(INITIAL),
        );
      }),
    ),
    { initialValue: INITIAL },
  );

  readonly publication = computed(() => this.state().publication);
  readonly series = computed(() => this.state().publication?.series);
  readonly issue = computed(() => this.state().issue);
  readonly audit = computed(() => this.state().audit);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);
  /** Whether the failed read said the publication is not there. Only true while {@link error} is. */
  readonly notFound = computed(() => this.state().notFound === true);
  readonly working = computed(() => this.busy());

  /** A refused write, as a translation key. Cleared by the next attempt. */
  private readonly failure = signal<string | undefined>(undefined);
  readonly errorKey = computed(() => this.failure());

  /**
   * The server's instant of the last release made from this page, epoch ms.
   *
   * The release stamps the start of the public period with this very instant, so
   * the page must read the period against a clock that is at least this far on.
   * The page's own clock ticks once a minute and the officer's PC may trail the
   * server, and against that clock a publication released a moment ago reads as
   * "scheduled" for up to a minute. Cleared when another publication is loaded.
   */
  private readonly lastRelease = signal<number | undefined>(undefined);
  readonly releasedAt = this.lastRelease.asReadonly();

  load(seriesId: string): void {
    this.failure.set(undefined);
    if (seriesId !== this.seriesId()) {
      this.lastRelease.set(undefined);
    }
    this.seriesId.set(seriesId);
  }

  refresh(): void {
    this.failure.set(undefined);
    this.refreshTick.update((tick) => tick + 1);
  }

  /**
   * Put a file behind one language.
   *
   * Straight through to the issue's file endpoint, then a refetch: the stored
   * name, the history entry and — for a publication that was waiting on its
   * bytes — whether it is live at all can each change, and refetching is one
   * answer instead of three guesses.
   */
  uploadFile(lang: string, file: File): void {
    const publicId = this.publication()?.issuePublicId;
    if (!publicId || this.busy()) {
      return;
    }
    this.run(
      this.api.uploadIssueFile(publicId, lang, file, this.issue()?.version),
      'admin.publications.oneOff.detail.uploadFailed',
    );
  }

  /**
   * Take the publication off the public site, or put it back.
   *
   * Withdrawing retires the issue behind the publication: it leaves the public
   * site, history included, while the file stays at its link so the messages
   * that cite it keep resolving. Publishing again reactivates the same issue.
   *
   * THE ISSUE'S OWN ENDPOINTS, not the save. The save takes the status as a
   * transition it works out by comparing what was asked for with whether the
   * publication is live at this instant — and "live" folds the public period in,
   * so a publication that is published but whose period starts next January, or
   * ended in 2017, already reads as not live. Asking that save to take such a
   * publication off the public site compares false with false, decides there is
   * nothing to do and answers 200 with the row unchanged: the dialog closes, the
   * screen says it worked, and the publication is still published. The retire
   * and reactivate endpoints act on the status alone, refuse what they cannot
   * do, and version-check against the issue.
   *
   * The reason is required by the withdrawal alone — it is the only part of the
   * decision that survives into the history.
   */
  setPublished(published: boolean, reason?: string, onDone?: () => void): void {
    const publication = this.publication();
    const publicId = publication?.issuePublicId;
    if (!publication || !publicId || this.busy()) {
      return;
    }
    // The issue's own revision, which this payload is the only source of. The
    // issue read beside it is the fallback, and both being absent means the call
    // goes out unguarded rather than not at all — the server's own refusal is a
    // better answer than a button that does nothing.
    const version = publication.issueVersion ?? this.issue()?.version;
    this.run(
      published
        ? this.api.reactivateIssue(publicId, undefined, version)
        : this.api.retireIssue(publicId, reason ?? '', version),
      'admin.publications.oneOff.detail.statusFailed',
      onDone,
    );
  }

  /**
   * Release a publication that has been prepared.
   *
   * THROUGH THE ISSUE'S OWN RELEASE, for the same reason the withdrawal is: the
   * one-off save works its status out by comparing what was asked for with
   * whether the publication is live, and a draft that is not live because it has
   * never been released compares the same either way — so it answered 200 having
   * changed nothing, and a publication prepared here stayed a draft for ever
   * with nothing on the screen able to move it.
   *
   * Nothing is acknowledged: a one-off is a document that was uploaded, linked
   * or cited, so there is no member resolution to raise a warning about. A
   * query-backed one that does raise one is REFUSED, naming the codes, which is
   * an answer an admin can act on — where the save's silence was not.
   *
   * A publication carrying a hand-set start keeps it: the release does not move
   * a period somebody chose, which is what lets one be prepared today and go
   * public on a set day.
   *
   * The answer's `stampedAt` is kept as {@link releasedAt}: it is the server's
   * clock at the release, and the start of the period it stamped.
   */
  publish(onDone?: () => void): void {
    const publication = this.publication();
    const publicId = publication?.issuePublicId;
    if (!publication || !publicId || this.busy()) {
      return;
    }
    this.run(
      this.api
        .publishIssue(publicId, {
          acknowledgedWarnings: [],
          version: publication.issueVersion ?? this.issue()?.version,
        })
        .pipe(tap((result) => this.lastRelease.set(result.stampedAt))),
      'admin.publications.oneOff.detail.publishFailed',
      onDone,
    );
  }

  /**
   * Set the publication's public period — both ends, in one call.
   *
   * A separate endpoint from {@link setPublished} because it is a separate fact.
   * The status says whether the publication has been released and whether it has
   * been taken back; the period says when it is on the public site, and it is
   * hand-set both before the release (to prepare a start still to come) and long
   * after it (to end a superseded document, or to put one back).
   */
  setPublicWindow(publicFrom: number | null, publicTo: number | null, onDone?: () => void): void {
    const publication = this.publication();
    if (!publication || this.busy()) {
      return;
    }
    this.run(
      this.oneOffApi.setPublicWindow(
        publication.series.seriesId,
        publicFrom,
        publicTo,
        publication.series.version,
      ),
      'admin.publications.oneOff.detail.publicWindowFailed',
      onDone,
    );
  }

  /**
   * Move the publication to another desk.
   *
   * The same endpoint a series uses, because a one-off IS a series. Refetched
   * afterwards rather than patched: the move changes who may write it, which is
   * what every action on this page is gated on.
   */
  transferOwner(domainId: string, reason: string, onDone?: () => void): void {
    const publication = this.publication();
    if (!publication || this.busy()) {
      return;
    }
    this.run(
      this.api.transferOwner(publication.series.seriesId, {
        domainId,
        reason,
        version: publication.series.version,
      }),
      'admin.publications.transfer.failed',
      onDone,
    );
  }

  private run(call: Observable<unknown>, fallbackKey: string, onDone?: () => void): void {
    this.busy.set(true);
    this.failure.set(undefined);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.refreshTick.update((tick) => tick + 1);
        onDone?.();
      },
      error: (response: unknown) => {
        this.busy.set(false);
        this.failure.set(publicationErrorKey(response, fallbackKey));
      },
    });
  }
}
