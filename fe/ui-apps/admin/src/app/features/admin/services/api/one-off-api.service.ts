import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { withEpochDates } from '../../../../shared/utils/epoch-dates';
import type { PublicationSeries } from './publication-series-api.service';

/** One language's value: a link, or a file name. */
export interface OneOffLangText {
  readonly lang: string;
  readonly value: string | null;
}

/**
 * A one-off publication: the whole series, plus the parts of its single issue
 * that whoever maintains the publication actually deals with.
 *
 * `series` is the ORDINARY series shape, deliberately. A one-off can carry every
 * option a series can, including a query-backed content mode with its criteria
 * and its report — what it cannot have is a cadence, a cut-off schedule, a
 * numbering scheme or an automatic successor, and the server enforces that
 * rather than the wire shape leaving the fields out. A parallel value object
 * would be a second place to add every future series field, and the one that got
 * forgotten would be missing only here.
 *
 * Its public standing is THREE INDEPENDENT FACTS — {@link OneOffPublication.issueStatus},
 * the public period, and the category's own publish flag — and never a single
 * boolean. Each is invisible from the other two, which is why they travel
 * separately and are read together by one rule (`publicationVisibility`).
 */
export interface OneOffPublication {
  readonly series: PublicationSeries;
  /**
   * @deprecated The server's fold of series status, issue status and the public
   * period into one boolean. Nothing in the application reads it: a screen that
   * did could not say WHICH fact was off, could not tell a publication being
   * prepared from one that has been withdrawn, and had no word at all for one
   * that goes public next January. Kept for one release so a client that has not
   * been rebuilt still gets an answer.
   */
  readonly active: boolean;
  readonly issuePublicId?: string;
  /**
   * The ISSUE's revision, for the two actions addressed at the issue.
   *
   * Withdrawing and publishing again are status transitions taken on the issue's
   * own endpoints, and those version-check against the ISSUE rather than against
   * the series — this payload is the only place a one-off screen learns that
   * number. Without it both calls go out unguarded, which is last-write-wins on
   * the one action that takes a document off the public site.
   *
   * `series.version` stays the token for the save and for the public period,
   * which are written through the one-off resource and checked against the
   * series.
   */
  readonly issueVersion?: number;
  /** `OPEN` (being prepared), `PUBLISHED`, `RETIRED` (withdrawn) — see `oneOffStatus`. */
  readonly issueStatus?: string;
  readonly links: readonly OneOffLangText[];
  readonly fileNames: readonly OneOffLangText[];
  /** False while the publication still needs its bytes before it can go live. */
  readonly publishable: boolean;
  /**
   * Whether the publication's CATEGORY is one the public site lists at all.
   *
   * The public listing needs the category's own publish flag as well as a
   * published issue inside an open window, and the flag is the half nothing else
   * on this payload carries — so a screen that re-derived it from the category
   * catalogue would be a second definition of visibility that can disagree with
   * `active`.
   *
   * Absent from a server that does not report it yet, and that is NOT `false`:
   * a screen must treat it as unknown and say nothing about the category rather
   * than announcing a public publication as hidden.
   */
  readonly categoryPublish?: boolean;
  /**
   * What the category is called, in the language the read asked for.
   *
   * Carried so the one sentence that names a category the public site does not
   * list has a name to put in it. Absent from a server that does not report it,
   * and from a category with no name at all.
   */
  readonly categoryName?: string;
  /**
   * Epoch ms. When the publication goes, or went, onto the public site.
   *
   * Hand-set, and editable both before and after the release: a publication can
   * be prepared with a start still in the future, which is how one goes public
   * on a set day. Absent is a server that does not report the field.
   */
  readonly publicFrom?: number | null;
  /**
   * Epoch ms. When the publication's public period closes, where it has an end.
   *
   * `null` is the open-ended period — current until somebody ends it — and is a
   * different answer from absent, which is a server that does not report the
   * field.
   */
  readonly publicTo?: number | null;
}

/** What the create/edit form sends. */
export interface OneOffRequest {
  /**
   * The series, whose own `version` is what an edit is checked against.
   *
   * ONE revision for both rows, and it is the series'. A one-off is a series and
   * its single issue edited through one form: they are only ever written
   * together, from here, so a second token for the issue would be a second thing
   * to get wrong with nothing to gain.
   */
  readonly series: PublicationSeries;
  /**
   * Whether the publication should be live once the call returns.
   *
   * A TRANSITION RATHER THAN A FIELD, and the server decides what to do about it
   * by comparing it with the publication's current standing — so what it means
   * depends on the state it arrives at, not on the value alone.
   *
   * `true` on a create is the one deliberate value: it activates the series and
   * publishes its issue. An ordinary save carries whatever changes nothing, so
   * that a rename stays a rename; the status transitions are taken on the
   * issue's own retire and reactivate endpoints, where they are version-checked
   * against the issue and refuse rather than quietly doing nothing.
   */
  readonly active: boolean;
  /**
   * Per language, for an EXTERNAL_LINK publication.
   *
   * A row per DECLARED language, blank ones included — the value is nullable
   * because clearing a link has to be expressible: a row omitted reads as
   * "unchanged" and leaves the old address public.
   */
  readonly links: readonly OneOffLangText[];
  /** Why the publication is being taken offline. Required by that transition alone. */
  readonly reason?: string;
}

/**
 * The one-off endpoints.
 *
 * Separate from the series API because the surfaces are separate: creating a
 * one-off through the general endpoints is create-series, activate,
 * create-issue, publish — four calls in an order that cannot be got wrong
 * without the last one refusing for a reason naming none of the first three.
 * `POST /one-off-publications` does the whole sequence, and a link-backed or
 * empty publication is live when it returns.
 *
 * An UPLOADED_FILE one still needs its bytes, so it comes back with
 * `publishable: false`; the caller uploads through the issue file endpoint and
 * publishes.
 *
 * Every answer is run through {@link withEpochDates}: the endpoints serialise
 * their instants as ISO 8601 and `publicFrom`/`publicTo` are read as epoch
 * milliseconds, so the conversion happens here or it happens nowhere.
 */
@Injectable({ providedIn: 'root' })
export class OneOffApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * The one-off publications, filtered by OWNER.
   *
   * `domain` narrows to the publications one desk is accountable for, which is
   * what the admin list shows — a one-off another domain has shared with this
   * one is a citation target, not a row to administer here. Omitting it returns
   * the estate.
   */
  list(domain?: string): Observable<OneOffPublication[]> {
    return this.http
      .get<OneOffPublication[]>(`${this.apiBaseUrl}/one-off-publications/`, {
        params: domain ? { domain } : {},
      })
      .pipe(map(withEpochDates));
  }

  /**
   * One publication, for the page that shows exactly one.
   *
   * Unscoped like the list, so a deep link resolves from any desk; the server
   * answers 404 for a series that is missing or is not a one-off.
   */
  get(seriesId: string): Observable<OneOffPublication> {
    return this.http
      .get<OneOffPublication>(
        `${this.apiBaseUrl}/one-off-publications/${encodeURIComponent(seriesId)}`,
      )
      .pipe(map(withEpochDates));
  }

  create(request: OneOffRequest): Observable<OneOffPublication> {
    return this.http
      .post<OneOffPublication>(`${this.apiBaseUrl}/one-off-publications/`, request)
      .pipe(map(withEpochDates));
  }

  update(seriesId: string, request: OneOffRequest): Observable<OneOffPublication> {
    return this.http
      .put<OneOffPublication>(
        `${this.apiBaseUrl}/one-off-publications/${encodeURIComponent(seriesId)}`,
        request,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Set the publication's public period — BOTH ends, in one call.
   *
   * Its OWN endpoint rather than a field on the save, because it is its own
   * decision and its own history entry: the period is one of the three
   * independent facts that decide whether a publication is on the public site,
   * and it is hand-set at every point in the publication's life — before the
   * release, to prepare a start still in the future; after it, to end a document
   * that has been superseded, or to re-open one whose end has passed.
   *
   * Both ends travel on every call, and `null` means "no bound": `publicTo:
   * null` is the open-ended period most one-offs carry, and `publicFrom: null`
   * is a start not chosen yet — which the server accepts only while the issue is
   * still open. A start after the end, and a published publication with no start
   * at all, are refused with `PUBLIC_WINDOW_INVALID`. A start in the FUTURE is
   * allowed, and is what makes a publication go public on a set day.
   *
   * `version` is the series' revision, the one revision a one-off is written
   * against.
   */
  setPublicWindow(
    seriesId: string,
    publicFrom: number | null,
    publicTo: number | null,
    version?: number,
  ): Observable<OneOffPublication> {
    return this.http
      .put<OneOffPublication>(
        `${this.apiBaseUrl}/one-off-publications/${encodeURIComponent(seriesId)}/public-window`,
        { publicFrom, publicTo, version },
      )
      .pipe(map(withEpochDates));
  }
}
