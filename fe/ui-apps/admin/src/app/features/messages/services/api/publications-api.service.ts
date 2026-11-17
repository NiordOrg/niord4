import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap, throwError } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { withEpochDates } from '../../../../shared/utils/epoch-dates';
import type {
  PublicationDescription,
  PublicationRef,
} from '../../models/messages-filter-params.model';
import { PUBLICATION_PICKER_STATUSES } from '../../models/messages-filter-params.model';

/** One page of picker rows, plus how many rows the query matched in total. */
export interface PublicationSearchPage {
  readonly items: readonly PublicationRef[];
  /** Matches across the whole catalogue, so a caller can say the page is not all of it. */
  readonly total: number;
}

/** What a caller may narrow the picker by. */
export interface PublicationSearchOptions {
  /**
   * Narrows both halves' `descs` to one language and matches `title` inside it.
   *
   * The pickers deliberately leave it unset: a row whose only desc is in another
   * language would come back with no title at all and render as a bare id, and the
   * per-message link override has to see every language's link to know whether the
   * publication carries one. Omitting it widens the title match to any language,
   * which only ever adds rows.
   */
  readonly language?: string;
  /** Rows per page. Never sent as `limit` — see {@link PublicationsApiService.search}. */
  readonly maxSize?: number;
  /** Zero-based page index. */
  readonly page?: number;
  readonly messagePublication?: 'EXTERNAL' | 'INTERNAL';
  /** `undefined` falls back to `MESSAGE_REPORT`; `null` omits the param (reaches LINK rows). */
  readonly type?: string | null;
  /** Issue statuses (`OPEN` / `PUBLISHED` / `RETIRED`); translated for the legacy half. */
  readonly statuses?: readonly string[];
  /**
   * The domain the picker is being used FROM — a visible-from filter, not an
   * owner one.
   *
   * A publication is offered where it is owned and where its owner has shared
   * it, which is the rule that keeps a citation-only publication in every desk's
   * picker while keeping one desk's weekly notices out of the others'. The two
   * callers send different domains on purpose: the message editor cites from the
   * desk being worked at, and the messages list filters within the domain being
   * searched.
   *
   * Omitted returns every publication, which is what a script or an unscoped
   * caller wants — and what {@link PublicationsApiService.getByIds} always does,
   * because a citation that exists has to resolve wherever it is read.
   */
  readonly domain?: string | null;
}

/** The page size a caller gets when it names none — a type-ahead cap, not a catalogue. */
const DEFAULT_MAX_SIZE = 10;

/**
 * Issue status → the legacy statuses that mean the same thing.
 *
 * The legacy endpoint's `status` parameter is typed as the legacy enum, so an
 * untranslated `PUBLISHED` is not tolerated there — it fails parameter conversion
 * and the picker breaks for every user. The translation is one-way: callers speak
 * the issue vocabulary and the legacy half is met where it is.
 */
const LEGACY_STATUSES: Readonly<Record<string, readonly string[]>> = {
  OPEN: ['RECORDING'],
  PUBLISHED: ['ACTIVE'],
  RETIRED: ['INACTIVE'],
};

/** The picker row as the issue endpoints emit it. */
interface IssuePickerDescVo {
  readonly lang?: string;
  readonly title?: string;
  readonly link?: string;
  readonly messagePublicationFormat?: string;
}

interface IssuePickerVo {
  readonly publicId: string;
  readonly publicationSeriesId?: string;
  /** The series' name per language, unfiltered — the caller resolves its own fallback. */
  readonly seriesNames?: Readonly<Record<string, string>>;
  readonly type?: string;
  readonly status?: string;
  readonly messagePublication?: 'EXTERNAL' | 'INTERNAL';
  readonly publicFrom?: number;
  readonly languageSpecific?: boolean;
  readonly descs?: readonly IssuePickerDescVo[];
}

interface PagedResponse<T> {
  readonly data?: readonly T[];
  readonly total?: number;
  readonly size?: number;
}

/** One half's answer, with its failure kept rather than thrown. */
interface Half {
  readonly items: readonly PublicationRef[];
  readonly total: number;
  readonly error: unknown;
}

/**
 * The publication picker and the citation-chip hydration.
 *
 * Both read **two** catalogues. Publication issues are the model everything new is
 * created in, and they are the only half that carries a status and the
 * language-specific flag — the legacy payload is a published XSD with a fixed field
 * order and structurally cannot gain either. Publications that were never converted
 * to a series keep answering from the legacy half. Until every series has moved, a
 * picker offering one half offers a catalogue with holes in it, so both are asked and
 * the answers are merged on the id they share.
 *
 * **Issues win a collision.** An imported issue reuses its legacy row's id, so the two
 * halves return the same publication under the same id by design — and the row a
 * citation resolves to is the issue. Offering the legacy row would show one title and
 * cite another.
 *
 * **A failing half is not an empty half.** Each request keeps its own error; a page is
 * built from whichever answered, and only a failure on both is reported as a failure.
 * That is what lets the picker keep working while one of the two catalogues is down.
 *
 * Both halves are run through {@link withEpochDates} before anything reads them: the
 * internal endpoints serialise their instants as ISO 8601 and `publicFrom` is read as
 * epoch milliseconds, so the conversion happens at this boundary or not at all.
 */
@Injectable({ providedIn: 'root' })
export class PublicationsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * Search both catalogues and merge them into one page, issues first.
   *
   * The page-size parameter is `maxSize` on both endpoints. `limit` is not a
   * parameter either of them reads — sending it leaves the server on its own
   * default, which caps the response however large a page the caller asked for, and
   * nothing anywhere says the list was cut. `page` pages through the rest instead of
   * asking for one enormous window that the server silently truncates anyway.
   *
   * Both halves are sent the caller's `domain` where it has one, and it is a
   * VISIBLE-FROM filter rather than an owner one: a publication reaches the
   * picker where it is owned and wherever its owner has shared it. That is what
   * keeps the citation-only publications — the journal number every desk quotes —
   * in every picker while keeping one desk's weekly notices out of the others'.
   */
  search(query: string, options?: PublicationSearchOptions): Observable<PublicationSearchPage> {
    const type = options?.type === null ? undefined : (options?.type ?? 'MESSAGE_REPORT');
    const statuses = options?.statuses ?? PUBLICATION_PICKER_STATUSES;
    const maxSize = options?.maxSize ?? DEFAULT_MAX_SIZE;
    const page = Math.max(0, options?.page ?? 0);

    return forkJoin([
      this.searchIssues(query, { ...options, type, statuses, maxSize, page }),
      this.searchLegacy(query, { ...options, type, statuses, maxSize, page }),
    ]).pipe(
      switchMap(([issues, legacy]) =>
        issues.error !== undefined && legacy.error !== undefined
          ? throwError(() => issues.error)
          : of(mergeHalves(issues, legacy, maxSize)),
      ),
    );
  }

  /**
   * Resolve ids the caller already holds — a restored filter chip, a stored citation.
   *
   * Issues answer first and the legacy lookup is asked only for what is left over, so
   * an id resolves through whichever catalogue holds it. **No status narrowing on
   * either side**: a message citing a publication that was later withdrawn must still
   * render what it cited, and a chip that blanks tells the reader nothing.
   *
   * Unknown ids are omitted rather than refused — one dead citation must not blank the
   * four beside it — and the answer keeps the order the ids were asked in.
   */
  getByIds(ids: readonly string[]): Observable<PublicationRef[]> {
    if (ids.length === 0) {
      return of([]);
    }
    return this.hydrateIssues(ids).pipe(
      switchMap((issues) => {
        const found = new Map(issues.items.map((pub) => [pub.publicationId, pub]));
        // A failed issue lookup resolves nothing, so the legacy half is asked for
        // every id rather than for the ones the failure happened to leave missing.
        const remaining =
          issues.error === undefined ? ids.filter((id) => !found.has(id)) : [...ids];
        if (remaining.length === 0) {
          return of(inRequestedOrder(ids, found));
        }
        return this.hydrateLegacy(remaining).pipe(
          map((legacy) => {
            for (const pub of legacy) {
              if (!found.has(pub.publicationId)) {
                found.set(pub.publicationId, pub);
              }
            }
            return inRequestedOrder(ids, found);
          }),
          catchError((error: unknown) =>
            issues.error === undefined ? of(inRequestedOrder(ids, found)) : throwError(() => error),
          ),
        );
      }),
    );
  }

  /** The issue half of the picker. */
  private searchIssues(query: string, options: PublicationSearchOptions): Observable<Half> {
    let params = new HttpParams()
      .set('maxSize', String(options.maxSize))
      .set('page', String(options.page));
    if (options.language) {
      params = params.set('lang', options.language);
    }
    if (query) {
      params = params.set('title', query);
    }
    if (options.type) {
      params = params.set('type', options.type);
    }
    if (options.messagePublication) {
      params = params.set('messagePublication', options.messagePublication);
    }
    if (options.domain) {
      params = params.set('domain', options.domain);
    }
    for (const status of options.statuses ?? []) {
      params = params.append('status', status);
    }
    return this.http
      .get<PagedResponse<IssuePickerVo>>(`${this.apiBaseUrl}/publication-issues/picker`, { params })
      .pipe(
        map(withEpochDates),
        map((res) => half((res?.data ?? []).map(toPublicationRef), res?.total)),
        catchError((error: unknown) => of(half([], 0, error))),
      );
  }

  /** The legacy half of the picker, which speaks the legacy status vocabulary. */
  private searchLegacy(query: string, options: PublicationSearchOptions): Observable<Half> {
    let params = new HttpParams()
      .set('mainType', 'PUBLICATION')
      .set('maxSize', String(options.maxSize))
      .set('page', String(options.page));
    if (options.language) {
      params = params.set('lang', options.language);
    }
    if (query) {
      params = params.set('title', query);
    }
    if (options.type) {
      params = params.set('type', options.type);
    }
    if (options.messagePublication) {
      params = params.set('messagePublication', options.messagePublication);
    }
    if (options.domain) {
      params = params.set('domain', options.domain);
    }
    for (const status of legacyStatuses(options.statuses ?? [])) {
      params = params.append('status', status);
    }
    return this.http
      .get<PagedResponse<PublicationRef>>(`${this.apiBaseUrl}/publications/search`, { params })
      .pipe(
        map(withEpochDates),
        map((res) => half(res?.data ?? [], res?.total)),
        catchError((error: unknown) => of(half([], 0, error))),
      );
  }

  /** Hydration through the issue endpoint: anonymous, status-blind, unknown ids omitted. */
  private hydrateIssues(ids: readonly string[]): Observable<Half> {
    const params = new HttpParams().set('ids', ids.join(','));
    return this.http
      .get<readonly IssuePickerVo[]>(`${this.apiBaseUrl}/publication-issues/by-ids`, { params })
      .pipe(
        map(withEpochDates),
        map((rows) => half((rows ?? []).map(toPublicationRef))),
        catchError((error: unknown) => of(half([], 0, error))),
      );
  }

  /**
   * Hydration through the legacy lookup — the direct `/publications/publication/{ids}`
   * read, NOT `/publications/search`, whose `title`-driven filter ignores an id list
   * and returns the whole catalogue.
   */
  private hydrateLegacy(ids: readonly string[]): Observable<readonly PublicationRef[]> {
    const path = ids.map((id) => encodeURIComponent(id)).join(',');
    return this.http
      .get<readonly PublicationRef[]>(`${this.apiBaseUrl}/publications/publication/${path}`)
      .pipe(
        map(withEpochDates),
        map((res) => res ?? []),
      );
  }
}

function half(items: readonly PublicationRef[], total?: number, error?: unknown): Half {
  return { items, total: total ?? items.length, error };
}

/**
 * The merge: issues first, a colliding legacy row dropped, the cap applied last.
 *
 * The total is the larger of the two halves rather than their sum, because the legacy
 * endpoint merges the issues into its own answer server-side — adding the two would
 * count the same publication twice. Whichever half reports the larger catalogue is the
 * one that counted both.
 */
function mergeHalves(issues: Half, legacy: Half, maxSize: number): PublicationSearchPage {
  const seen = new Set(issues.items.map((pub) => pub.publicationId));
  const merged = [...issues.items];
  for (const pub of legacy.items) {
    if (!seen.has(pub.publicationId)) {
      seen.add(pub.publicationId);
      merged.push(pub);
    }
  }
  const items = maxSize > 0 && merged.length > maxSize ? merged.slice(0, maxSize) : merged;
  return { items, total: Math.max(issues.total, legacy.total, merged.length) };
}

/** Translate issue statuses into the legacy vocabulary; an unknown token passes through. */
function legacyStatuses(statuses: readonly string[]): readonly string[] {
  const out = new Set<string>();
  for (const status of statuses) {
    for (const legacy of LEGACY_STATUSES[status] ?? [status]) {
      out.add(legacy);
    }
  }
  return [...out];
}

/** A picker row as the rest of the app reads publications. */
function toPublicationRef(vo: IssuePickerVo): PublicationRef {
  const descs: PublicationDescription[] = (vo.descs ?? []).map((desc) => ({
    lang: desc.lang,
    title: desc.title,
    link: desc.link,
    messagePublicationFormat: desc.messagePublicationFormat,
  }));
  return {
    publicationId: vo.publicId,
    publicationSeriesId: vo.publicationSeriesId,
    // Every language's name, not the active one: the endpoint deliberately
    // leaves this unfiltered so the picker — which sends no `lang` at all — can
    // fall back through the configured order rather than render a blank.
    seriesNames: vo.seriesNames,
    type: vo.type,
    mainType: 'PUBLICATION',
    status: vo.status,
    messagePublication: vo.messagePublication,
    publicFrom: vo.publicFrom,
    languageSpecific: vo.languageSpecific,
    // Always an array, never absent: a resolved row is told from an id-only restore
    // stub by whether it carries descs at all.
    descs,
  };
}

function inRequestedOrder(
  ids: readonly string[],
  found: ReadonlyMap<string, PublicationRef>,
): PublicationRef[] {
  const out: PublicationRef[] = [];
  for (const id of ids) {
    const pub = found.get(id);
    if (pub) {
      out.push(pub);
    }
  }
  return out;
}
