import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import {
  AUDIT_ACTIONS,
  AUDIT_ACTOR_KINDS,
  COMPUTED_ISSUE_STATUSES,
  ISSUE_STATUSES,
  SERIES_STATUSES,
} from '../../sections/admin/publications/publication-status';
import {
  AVAILABILITIES,
  CHECK_SEVERITIES,
  CONTENT_MODES,
  CRITERIA_MATCHES,
  CRITERION_KINDS,
  CRITERION_OPERATORS,
  CUTOFF_DAYS,
  CUTOFF_DEFAULTS,
  CUTOFF_SOURCES,
  GAP_DETECTION_REASONS,
  INTERVAL_BOUND_SOURCES,
  ISSUE_AUDIT_ACTIONS,
  ISSUE_FILE_SOURCES,
  ISSUE_LIST_MAX_SIZE,
  ISSUE_ROW_KINDS,
  MEMBERSHIP_PROVENANCES,
  MEMBER_DRIFT_FIELDS,
  MEMBER_REASONS,
  MEMBER_SOURCES,
  MESSAGE_PUBLICATIONS,
  NEXT_ISSUE_CREATIONS,
  NUMBERING_SCHEMES,
  OVERRIDE_KINDS,
  PAGE_ORIENTATIONS,
  PAGE_SIZES,
  PublicationSeriesApiService,
  RELEASE_MODES,
  SERIES_CADENCES,
  SERIES_KINDS,
  SORT_DIRECTIONS,
  SOURCE_ISSUE_STATUSES,
  TIMELINE_DEFAULT_PERIODS,
  TIME_RELATIONS,
  UNSCHEDULED_ARCHIVE_LIMIT,
  type AuditActorKind,
  type Availability,
  type CheckSeverity,
  type ComputedIssueStatus,
  type ContentMode,
  type CriteriaMatch,
  type CriterionKind,
  type CriterionOperator,
  type CutoffDay,
  type CutoffDefault,
  type CutoffSource,
  type GapDetectionReason,
  type IntervalBoundSource,
  type IssueAuditAction,
  type IssueCriteria,
  type IssueFileSource,
  type IssueRowKind,
  type IssueWorkbench,
  type IssueStatus,
  type MemberDriftField,
  type MemberReason,
  type MemberSource,
  type MembershipProvenance,
  type MessagePublication,
  type NextIssueCreation,
  type NumberingScheme,
  type OverrideKind,
  type PageOrientation,
  type PageSize,
  type PublicationCategory,
  type PublicationIssue,
  type PublicationSeries,
  type PublishChecklist,
  type ReleaseMode,
  type SeriesCadence,
  type SeriesKind,
  type SeriesStatus,
  type SortDirection,
  type SourceIssueStatus,
  type TimeRelation,
} from './publication-series-api.service';

const API_BASE = '/rest';

/**
 * Every method the service exposes, with the test below that pins it.
 *
 * The guard at the end of the file walks the prototype against this list. A
 * method that lands without a spec is a URL, a verb, a set of query parameters
 * and a body that nobody has ever compared against the resource — which is
 * exactly how a request ends up sending `limit` to an endpoint that reads
 * `maxSize` and silently getting the server's own page size back.
 */
const PINNED_METHODS = [
  'searchSeries',
  'searchSeriesRequest',
  'getSeries',
  'leanSeriesRequest',
  'issuesOfSeries',
  'recentTimelines',
  'recentTimelinesForDomain',
  'getIssue',
  'leanIssueRequest',
  'issueWorkbench',
  'setSeriesStatus',
  'createSeries',
  'validateSeries',
  'publicationCategories',
  'createCategory',
  'updateCategory',
  'deleteCategory',
  'nameTokens',
  'createIssue',
  'exportSeries',
  'uploadUrl',
  'updateIssue',
  'uploadIssueFile',
  'clearIssueFile',
  'setIssueLink',
  'updateSeries',
  'newSeriesTemplate',
  'resolvePreview',
  'issueMembers',
  'issueAudit',
  'seriesHistory',
  'issueOverrides',
  'publishChecklist',
  'publishIssue',
  'previewIssue',
  'previewFile',
  'archiveFileUrl',
  'retireIssue',
  'reactivateIssue',
  'deleteIssue',
  'includeMembers',
  'excludeMembers',
  'clearOverride',
  'amendIssue',
  'newEdition',
  'resolveMessage',
  'issueDraft',
  'copySeriesTemplate',
  'transferOwner',
  'exportCategories',
  'importCategories',
] as const;

/** An id that is not URL-safe, so the encoding of every path segment is visible. */
const AWKWARD_ID = 'efs uge/27';
const AWKWARD_PATH = 'efs%20uge%2F27';

function series(over: Partial<PublicationSeries> = {}): PublicationSeries {
  return {
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    descs: [{ lang: 'en', name: 'Weekly notices' }],
    cadence: 'WEEKLY',
    kind: 'SCHEDULED',
    contentMode: 'GENERATED_FROM_QUERY',
    domainId: 'niord-annex',
    languages: ['en', 'da'],
    ...over,
  };
}

function category(over: Partial<PublicationCategory> = {}): PublicationCategory {
  return { categoryId: 'ntm', descs: [{ lang: 'en', name: 'Notices' }], ...over };
}

const CRITERIA: IssueCriteria = {
  match: 'ALL',
  criteria: [{ kind: 'messageSeries', operator: 'IN', values: ['dma-nm'] }],
};

describe('PublicationSeriesApiService', () => {
  let service: PublicationSeriesApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(PublicationSeriesApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  /** Matches on the path alone, so the parameters can be asserted by name below. */
  function expectPath(url: string) {
    return httpTesting.expectOne((request) => request.url === url);
  }

  function paramsOf(url: string): Record<string, string | null> {
    const req = httpTesting.expectOne((request) => request.url === url);
    const out: Record<string, string | null> = {};
    for (const key of req.request.params.keys()) {
      out[key] = req.request.params.get(key);
    }
    req.flush({});
    return out;
  }

  // ------------------------------------------------------------------- series

  /**
   * `status` is the only parameter the admin list reads.
   *
   * Not `lang`: the admin shape carries every configured language because it is
   * what the editor saves back, so there is nothing to narrow.
   */
  it('searchSeries GETs the admin list with no parameters when no status is named', () => {
    service.searchSeries().subscribe();
    const req = expectPath(`${API_BASE}/publication-series/search-details`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush([]);
  });

  it('searchSeries narrows by status when one is named', () => {
    service.searchSeries('DRAFT').subscribe();
    expect(paramsOf(`${API_BASE}/publication-series/search-details`)).toEqual({ status: 'DRAFT' });
  });

  /**
   * `domain` is the OWNER filter the admin lists send.
   *
   * Not a visibility one: the section lists the publications one desk is
   * accountable for, and a series shared with this domain is a citation target
   * rather than a row to administer here.
   */
  it('searchSeries narrows by owner domain when one is named', () => {
    service.searchSeries(undefined, 'niord-nm').subscribe();
    expect(paramsOf(`${API_BASE}/publication-series/search-details`)).toEqual({
      domain: 'niord-nm',
    });
  });

  it('searchSeries sends both narrowings together', () => {
    service.searchSeries('ACTIVE', 'niord-annex').subscribe();
    expect(paramsOf(`${API_BASE}/publication-series/search-details`)).toEqual({
      status: 'ACTIVE',
      domain: 'niord-annex',
    });
  });

  /**
   * The reactive reader asks for exactly what the imperative one does.
   *
   * A descriptor rather than a second URL: a picker that only exists under one
   * setting reads the estate when that setting is chosen, and the endpoint it
   * reads has to be the one this service owns rather than a string spelled out
   * in a component.
   */
  it('searchSeriesRequest describes the same read, narrowings included', () => {
    expect(service.searchSeriesRequest()).toEqual({
      url: `${API_BASE}/publication-series/search-details`,
      params: {},
    });
    expect(service.searchSeriesRequest('ACTIVE', 'niord-nm')).toEqual({
      url: `${API_BASE}/publication-series/search-details`,
      params: { status: 'ACTIVE', domain: 'niord-nm' },
    });
  });

  /** The citizen shape, for a screen that wants a name and nothing else. */
  it('leanSeriesRequest describes the lean read and encodes the id', () => {
    expect(service.leanSeriesRequest(AWKWARD_ID)).toEqual({
      url: `${API_BASE}/publication-series/series/${AWKWARD_PATH}`,
    });
  });

  /** The editable shape, not `/series/{id}` — that one omits the admin fields. */
  it('getSeries GETs the editable series and encodes the id', () => {
    service.getSeries(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/editable-series/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('GET');
    req.flush(series());
  });

  it('createSeries POSTs the series to the trailing-slash path', () => {
    const body = series();
    service.createSeries(body).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(body);
  });

  it('updateSeries PUTs the series to the id path', () => {
    const body = series();
    service.updateSeries(AWKWARD_ID, body).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(body);
  });

  /**
   * The status is a bare JSON string, and the reason is a query parameter.
   *
   * The body is already spoken for by the status itself, which is why the reason
   * travels beside it rather than inside an object the server does not read.
   */
  it('setSeriesStatus PUTs the bare quoted status with the reason as a query parameter', () => {
    service.setSeriesStatus('weekly-ntm', 'RETIRED', 'superseded').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/status`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe('"RETIRED"');
    expect(req.request.headers.get('Content-Type')).toBe('application/json');
    expect(req.request.params.get('reason')).toBe('superseded');
    req.flush(series({ status: 'RETIRED' }));
  });

  it('setSeriesStatus sends no reason parameter when there is none', () => {
    service.setSeriesStatus('weekly-ntm', 'ACTIVE').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/status`);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush(series());
  });

  it('validateSeries POSTs the candidate and hands back a copy of the report', () => {
    let report: readonly { rule: string }[] | undefined;
    service.validateSeries(series()).subscribe((errors) => (report = errors));
    const req = expectPath(`${API_BASE}/publication-series/validate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(series());
    req.flush([{ rule: 'S-1', field: 'criteria', message: 'no criteria' }]);
    expect(report).toEqual([{ rule: 'S-1', field: 'criteria', message: 'no criteria' }]);
  });

  it('newSeriesTemplate GETs the server-built blank series', () => {
    service.newSeriesTemplate().subscribe();
    const req = expectPath(`${API_BASE}/publication-series/new-series-template`);
    expect(req.request.method).toBe('GET');
    req.flush(series());
  });

  it('copySeriesTemplate GETs the copy for one series', () => {
    service.copySeriesTemplate(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/copy-series-template/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('GET');
    req.flush(series());
  });

  it('nameTokens GETs the token vocabulary and hands back a copy', () => {
    let tokens: readonly string[] | undefined;
    service.nameTokens().subscribe((result) => (tokens = result));
    const req = expectPath(`${API_BASE}/publication-series/name-tokens`);
    expect(req.request.method).toBe('GET');
    req.flush(['week', 'year']);
    expect(tokens).toEqual(['week', 'year']);
  });

  it('resolvePreview POSTs the unsaved document and the interval it is run over', () => {
    const body = {
      criteria: CRITERIA,
      cutoff: 1_700_000_000_000,
      intervalFrom: 1_699_000_000_000,
      timeRelation: 'PUBLISHED_IN_INTERVAL' as const,
      aliveAtCutoff: true,
    };
    service.resolvePreview(body).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/resolve-preview`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({ source: 'PROBE', total: 0, candidates: 0, sample: [], truncated: false });
  });

  /**
   * The language names the answer; it does not change it.
   *
   * The sampled rows and the dropped candidates come back titled, and a panel
   * that omits the language shows a reader names in whichever one the server
   * found first — beside a count that would have been the same either way.
   */
  it('resolvePreview asks for the names in the language it was given', () => {
    service.resolvePreview({ criteria: CRITERIA, cutoff: 1_700_000_000_000 }, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/resolve-preview`);
    expect(req.request.params.get('lang')).toBe('da');
    req.flush({ source: 'PROBE', total: 0, candidates: 0, sample: [], truncated: false });
  });

  it('exportSeries GETs the catalogue with the caller’s own credentials, not a ticket', () => {
    service.exportSeries().subscribe();
    const req = expectPath(`${API_BASE}/publication-series/export`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('uploadUrl is the series import endpoint and makes no request of its own', () => {
    expect(service.uploadUrl()).toBe(`${API_BASE}/publication-series/upload-series`);
  });

  /**
   * The draft endpoint answers three questions, and sends only what it was asked.
   *
   * An absent bound is omitted rather than sent empty: the server reads "the next
   * one" from the parameters being missing, and a blank one would name a period
   * starting at the epoch.
   */
  it('issueDraft GETs with no parameters when neither a predecessor nor bounds are named', () => {
    service.issueDraft('weekly-ntm').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/issue-draft`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({});
  });

  /**
   * The answer names the edition this one is chained from, and a name only
   * exists in a language, so the caller says which one.
   */
  it('issueDraft sends the language it was given', () => {
    service.issueDraft('weekly-ntm', { lang: 'da' }).subscribe();
    expect(paramsOf(`${API_BASE}/publication-series/series/weekly-ntm/issue-draft`)).toEqual({
      lang: 'da',
    });
  });
  it('issueDraft sends afterPublicId, intervalFrom and intervalTo as query parameters', () => {
    service
      .issueDraft(AWKWARD_ID, {
        afterPublicId: 'prev-1',
        intervalFrom: 1_700_000_000_000,
        intervalTo: 1_700_600_000_000,
      })
      .subscribe();
    expect(paramsOf(`${API_BASE}/publication-series/series/${AWKWARD_PATH}/issue-draft`)).toEqual({
      afterPublicId: 'prev-1',
      intervalFrom: '1700000000000',
      intervalTo: '1700600000000',
    });
  });

  /**
   * The transfer, which moves who is accountable for a publication.
   *
   * The revision travels in the body beside the reason: a transfer changes who
   * may write the series at all, so being refused because somebody moved it in
   * the meantime is worth a refusal rather than a change made against a series
   * nobody has looked at since.
   */
  it('transferOwner PUTs the target, the reason and the revision to the owner path', () => {
    service
      .transferOwner(AWKWARD_ID, { domainId: 'niord-annex', reason: 'moved desk', version: 7 })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/${AWKWARD_PATH}/owner`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      domainId: 'niord-annex',
      reason: 'moved desk',
      version: 7,
    });
    req.flush(series());
  });

  /** An absent revision is the caller declining the check, never a blank one. */
  it('transferOwner omits the revision entirely when the caller has none', () => {
    service.transferOwner('weekly-ntm', { domainId: 'niord-annex', reason: 'moved' }).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/owner`);
    expect(req.request.body).toEqual({ domainId: 'niord-annex', reason: 'moved' });
    expect(Object.keys(req.request.body as object)).not.toContain('version');
    req.flush(series());
  });

  // --------------------------------------------------------------- categories

  it('publicationCategories GETs every category, narrowed by language when one is given', () => {
    service.publicationCategories('da').subscribe();
    httpTesting.expectOne(`${API_BASE}/publication-categories/all?lang=da`).flush([]);
  });

  it('publicationCategories omits the language parameter entirely when none is given', () => {
    let categories: readonly PublicationCategory[] | undefined;
    service.publicationCategories().subscribe((result) => (categories = result));
    const req = httpTesting.expectOne(`${API_BASE}/publication-categories/all`);
    expect(req.request.method).toBe('GET');
    req.flush([category()]);
    expect(categories).toEqual([category()]);
  });

  it('createCategory POSTs to the trailing-slash category path', () => {
    const body = category();
    service.createCategory(body).subscribe();
    const req = expectPath(`${API_BASE}/publication-categories/publication-category/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(body);
  });

  it('updateCategory PUTs to the id path, which the body does not change', () => {
    const body = category({ priority: null });
    service.updateCategory(AWKWARD_ID, body).subscribe();
    const req = expectPath(
      `${API_BASE}/publication-categories/publication-category/${AWKWARD_PATH}`,
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(body);
  });

  it('deleteCategory DELETEs the id path', () => {
    service.deleteCategory(AWKWARD_ID).subscribe();
    const req = expectPath(
      `${API_BASE}/publication-categories/publication-category/${AWKWARD_PATH}`,
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  /** The export is taken as bytes: a round trip through JS loses a field this build does not model. */
  it('exportCategories GETs the same list as a blob', () => {
    service.exportCategories().subscribe();
    const req = expectPath(`${API_BASE}/publication-categories/all`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['[]']));
  });

  it('importCategories POSTs the file as multipart and reads the job’s text back', () => {
    const file = new File(['[]'], 'categories.json', { type: 'application/json' });
    service.importCategories(file).subscribe();
    const req = expectPath(`${API_BASE}/publication-categories/upload-publication-categories`);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    const body = req.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('categories.json');
    req.flush('submitted');
  });

  // ------------------------------------------------------------------- issues

  /**
   * `maxSize` is the wire name and a CEILING, not a page.
   *
   * The gap envelope is computed over the whole set, so asking for a screenful
   * would report gaps against twenty rows out of five hundred. `limit` is not a
   * parameter this endpoint reads at all.
   */
  it('issuesOfSeries GETs the series’ archive with maxSize defaulting to the ceiling', () => {
    service.issuesOfSeries(AWKWARD_ID).subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/series/${AWKWARD_PATH}`)).toEqual({
      maxSize: String(ISSUE_LIST_MAX_SIZE),
    });
  });

  it('issuesOfSeries sends a caller-chosen ceiling under the same name', () => {
    service.issuesOfSeries('weekly-ntm', 25).subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/series/weekly-ntm`)).toEqual({
      maxSize: '25',
    });
  });

  /** One request for the whole landing screen: the series are named, never inferred. */
  it('recentTimelines GETs one comma-separated request for every named series', () => {
    service.recentTimelines(['a', 'b', 'c'], 'da').subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/recent`)).toEqual({
      publicationSeriesId: 'a,b,c',
      periods: String(TIMELINE_DEFAULT_PERIODS),
      lang: 'da',
    });
  });

  it('recentTimelines sends a caller-chosen period count', () => {
    service.recentTimelines(['a'], 'en', 4).subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/recent`)).toEqual({
      publicationSeriesId: 'a',
      periods: '4',
      lang: 'en',
    });
  });

  /**
   * The desk-scoped strip names the domain and never the series — and asks for
   * BOTH kinds at once, each with the count its own cards can use.
   *
   * There is deliberately no `cadenced` flag: splitting the two kinds into two
   * requests made the dashboard pay a second round trip for an answer the server
   * assembles from the same scan.
   */
  it('recentTimelinesForDomain GETs both kinds in one request, with a count for each', () => {
    service.recentTimelinesForDomain('niord-nm', 'en').subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/recent-by-domain`)).toEqual({
      domain: 'niord-nm',
      periods: String(TIMELINE_DEFAULT_PERIODS),
      unscheduledIssues: String(UNSCHEDULED_ARCHIVE_LIMIT),
      lang: 'en',
    });
  });

  it('recentTimelinesForDomain sends caller-chosen counts for either kind', () => {
    service
      .recentTimelinesForDomain('niord-nm', 'da', { periods: 4, unscheduledIssues: 12 })
      .subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/recent-by-domain`)).toEqual({
      domain: 'niord-nm',
      periods: '4',
      unscheduledIssues: '12',
      lang: 'da',
    });
  });

  /**
   * The editable issue arrives as it is sent, `cutoffSource` included.
   *
   * That field is the server's own record of how the cut-off was arrived at —
   * stamped at the release, stamped at an instant somebody chose, or recovered
   * from the archive by one stage or another. A shape that dropped it would leave
   * the screen deriving an answer the response already carries, and the two
   * derivations it can reach cannot tell a chosen instant from a released one.
   */
  it('getIssue GETs the editable issue, encodes the public id and keeps the cut-off source', () => {
    let issue: PublicationIssue | undefined;
    service.getIssue(AWKWARD_ID).subscribe((value) => (issue = value));
    const req = expectPath(`${API_BASE}/publication-issues/editable-issue/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('GET');
    req.flush({
      publicId: AWKWARD_ID,
      status: 'PUBLISHED',
      descs: [],
      cutoffStampedAt: 1_700_000_000_000,
      cutoffSource: 'STAMPED_MANUAL_TIME',
    } satisfies PublicationIssue);

    expect(issue?.cutoffSource).toBe('STAMPED_MANUAL_TIME');
  });

  /** The lean issue, for a screen that wants a name and nothing else. */
  it('leanIssueRequest describes the lean read and encodes the public id', () => {
    expect(service.leanIssueRequest(AWKWARD_ID)).toEqual({
      url: `${API_BASE}/publication-issues/issue/${AWKWARD_PATH}`,
    });
  });

  /**
   * The chained bound and the RECOVERED marker travel with the create.
   *
   * `intervalFrom: null` rather than an omitted key: the server reads the absence
   * of a chosen start, and a missing field would be indistinguishable from a
   * caller that forgot to send it.
   */
  it('createIssue POSTs the series, both reviewed bounds and the recovered marker', () => {
    service
      .createIssue({
        seriesId: 'weekly-ntm',
        intervalFrom: 1_700_000_000_000,
        intervalTo: 1_700_600_000_000,
        recovered: true,
      })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      seriesId: 'weekly-ntm',
      intervalFrom: 1_700_000_000_000,
      intervalTo: 1_700_600_000_000,
      recovered: true,
    });
    req.flush({});
  });

  /**
   * An unchosen END is OMITTED, where an unchosen START is null.
   *
   * The two absences mean different things. A null start says "chain from the
   * series' own anchor", which the server reads; an absent end asks it to derive
   * the nominal close, and a null there would be an end the caller chose and
   * left empty.
   */
  it('createIssue sends a null start, no end and an unrecovered marker by default', () => {
    service.createIssue({ seriesId: 'weekly-ntm' }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue`);
    expect(req.request.body).toEqual({
      seriesId: 'weekly-ntm',
      intervalFrom: null,
      recovered: false,
    });
    req.flush({});
  });

  /** Every field is optional, and an absent one means "leave it alone". */
  it('updateIssue PUTs only the fields the caller named', () => {
    service.updateIssue(AWKWARD_ID, { names: { en: 'Week 27' } }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ names: { en: 'Week 27' } });
    req.flush({});
  });

  it('updateIssue carries the override, and the separate flag that hands it back', () => {
    service.updateIssue('i-1', { criteriaOverride: CRITERIA }).subscribe();
    expectPath(`${API_BASE}/publication-issues/issue/i-1`).flush({});

    service.updateIssue('i-1', { clearCriteriaOverride: true }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.body).toEqual({ clearCriteriaOverride: true });
    req.flush({});
  });

  /**
   * The fields that tailor one edition, and the empty string four of them need.
   *
   * `''` is not "absent with extra steps": absent already means "leave it
   * alone", so it is the only way to say "hand this back to the series" — and a
   * body that dropped it would leave the label, the file name or the report
   * exactly where they were while the drawer reported success.
   *
   * Deliberately not `null`. A field the request omits and a field it sends as
   * null are the same value by the time the server has read the body, so a null
   * clear would make every partial save — one flag, one date — silently wipe
   * every field it did not mention.
   */
  it('updateIssue carries the printed numbering, the file names and the report', () => {
    service
      .updateIssue('i-1', {
        weekLabel: '36+37',
        yearLabel: '',
        fileNames: { da: 'EfS-PT-Uge-36+37-2026.pdf', en: '' },
        reportId: 'nm-annex',
        reportParams: { overskrift: 'Aktive P&T uge 36+37' },
      })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      weekLabel: '36+37',
      yearLabel: '',
      fileNames: { da: 'EfS-PT-Uge-36+37-2026.pdf', en: '' },
      reportId: 'nm-annex',
      reportParams: { overskrift: 'Aktive P&T uge 36+37' },
    });
    req.flush({});
  });

  it('updateIssue hands the report back to the series with an empty string', () => {
    service.updateIssue('i-1', { reportId: '' }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.body).toEqual({ reportId: '' });
    req.flush({});
  });

  /**
   * The clear that has no other spelling: a name is never absent, so an empty
   * one is how the series is asked to name the issue again.
   */
  it('updateIssue hands a name back to the series with an empty string', () => {
    service.updateIssue('i-1', { names: { da: '' } }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.body).toEqual({ names: { da: '' } });
    req.flush({});
  });

  it('uploadIssueFile POSTs one multipart file to the language path', () => {
    const file = new File(['%PDF'], 'efs-27.pdf', { type: 'application/pdf' });
    service.uploadIssueFile(AWKWARD_ID, 'da', file).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/file/da`);
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('efs-27.pdf');
    req.flush({});
  });

  it('clearIssueFile DELETEs the language’s file path', () => {
    service.clearIssueFile(AWKWARD_ID, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/file/da`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('setIssueLink PUTs { link } to the language’s link path', () => {
    service.setIssueLink('i-1', 'en', 'https://example.test/doc.pdf').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/link/en`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ link: 'https://example.test/doc.pdf' });
    req.flush({});
  });

  it('issueWorkbench GETs the whole issue screen in one request, with the language for the rows', () => {
    service.issueWorkbench(AWKWARD_ID, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/workbench`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('lang')).toBe('da');
    req.flush({ issue: { publicId: AWKWARD_ID }, members: [], audit: [], overrides: [] });
  });

  /**
   * The stored renders ride in the same answer as everything else.
   *
   * A second request for them would be a second resolution of the same issue,
   * and the screen exists to make its parts one answer.
   */
  it('issueWorkbench hands back the stored previews the answer carries', () => {
    let workbench: IssueWorkbench | undefined;
    service.issueWorkbench(AWKWARD_ID).subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/workbench`).flush({
      issue: { publicId: AWKWARD_ID },
      members: [],
      audit: [],
      overrides: [],
      previews: [{ lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/x' }],
    });

    expect(workbench?.previews).toEqual([
      { lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/x' },
    ]);
  });

  /**
   * ISO on the wire, epoch milliseconds on the model.
   *
   * The backend serialises a `Date` as an ISO 8601 string and every date on
   * these models is a number that gets compared and subtracted. A string
   * arriving unconverted is not a type error at runtime — it is an answer, and
   * `"2019-12-31T22:59:59.999+00:00" <= Date.now()` is false, which is how a
   * cut-off six years past came to read as one that had not happened yet.
   */
  it('issueWorkbench converts every ISO instant in the answer to epoch milliseconds', () => {
    let workbench: IssueWorkbench | undefined;
    service.issueWorkbench('i-1').subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`).flush({
      issue: {
        publicId: 'i-1',
        intervalFrom: '2018-12-31T23:00:00.000+00:00',
        intervalTo: '2019-12-31T22:59:59.999+00:00',
      },
      members: [{ messageUid: 'm-1', frozenPublishDateFrom: '2019-06-01T10:00:00.000+00:00' }],
      audit: [{ id: 1, created: '2019-12-31T22:59:59.999+00:00' }],
      overrides: [],
    });

    expect(workbench?.issue.intervalTo).toBe(1_577_833_199_999);
    expect(workbench?.issue.intervalFrom).toBe(1_546_297_200_000);
    expect(workbench?.members[0].frozenPublishDateFrom).toBe(Date.parse('2019-06-01T10:00:00Z'));
    expect(workbench?.audit[0].created).toBe(1_577_833_199_999);
  });

  /** Nothing stored, and a server older than the field, are both "no previews". */
  it('issueWorkbench leaves the previews absent when the answer carries none', () => {
    let workbench: IssueWorkbench | undefined;
    service.issueWorkbench('i-1').subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`).flush({
      issue: { publicId: 'i-1' },
      members: [],
      audit: [],
      overrides: [],
    });

    expect(workbench?.previews).toBeUndefined();
  });

  it('issueWorkbench sends no language parameter when none is given', () => {
    service.issueWorkbench(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/workbench`);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({ issue: { publicId: AWKWARD_ID }, members: [], audit: [], overrides: [] });
  });

  /**
   * The whole answer is resolved AT an instant, so the instant travels with it.
   *
   * Members, the rail and the omissions come from one resolution: asking for
   * them as of a past moment is a different question with a different answer,
   * and the screen asks it whole rather than filtering the answer to now.
   */
  it('issueWorkbench asks for a past instant as epoch milliseconds', () => {
    service.issueWorkbench('i-1', 'da', 1_700_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`);
    expect(req.request.params.get('lang')).toBe('da');
    expect(req.request.params.get('at')).toBe('1700000000000');
    req.flush({ issue: { publicId: 'i-1' }, members: [], audit: [], overrides: [] });
  });

  /** No instant asked for is "now", said by not carrying the parameter at all. */
  it('issueWorkbench omits the instant when none is named', () => {
    service.issueWorkbench('i-1', 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`);
    expect(req.request.params.keys()).toEqual(['lang']);
    req.flush({ issue: { publicId: 'i-1' }, members: [], audit: [], overrides: [] });
  });

  /**
   * A period being typed is asked about, not saved.
   *
   * The start travels beside the instant because the two are one question: the
   * resolution runs over the window they bound, and a start sent without the
   * instant it ends at would be half a period.
   */
  it('issueWorkbench asks about a proposed period start as epoch milliseconds', () => {
    service.issueWorkbench('i-1', 'da', 1_700_000_000_000, 1_600_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`);
    expect(req.request.params.get('at')).toBe('1700000000000');
    expect(req.request.params.get('from')).toBe('1600000000000');
    req.flush({ issue: { publicId: 'i-1' }, members: [], audit: [], overrides: [] });
  });

  /**
   * A proposed start with no instant is the period as it stands NOW.
   *
   * Absent means now on this endpoint, so a start that ends at this moment is
   * said by carrying one parameter rather than by naming the client's clock.
   */
  it('issueWorkbench sends a proposed start without an instant when none is named', () => {
    service.issueWorkbench('i-1', 'da', undefined, 1_600_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`);
    expect(req.request.params.keys().sort()).toEqual(['from', 'lang']);
    expect(req.request.params.get('from')).toBe('1600000000000');
    req.flush({ issue: { publicId: 'i-1' }, members: [], audit: [], overrides: [] });
  });

  /** The issue's own period start is the ordinary case, and carries no parameter. */
  it('issueWorkbench omits the period start when none is proposed', () => {
    service.issueWorkbench('i-1', 'da', 1_700_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`);
    expect(req.request.params.keys().sort()).toEqual(['at', 'lang']);
    req.flush({ issue: { publicId: 'i-1' }, members: [], audit: [], overrides: [] });
  });

  /**
   * The start the server resolved over comes back, like the instant does.
   *
   * The screen must be able to say which period its numbers describe, and what
   * it believes it asked for is not evidence of what was answered.
   */
  it('issueWorkbench hands back the period start the answer was resolved over', () => {
    let workbench: IssueWorkbench | undefined;
    service
      .issueWorkbench('i-1', 'da', undefined, 1_600_000_000_000)
      .subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`).flush({
      issue: { publicId: 'i-1' },
      members: [],
      audit: [],
      overrides: [],
      viewedAt: 1_700_000_000_000,
      viewedFrom: 1_600_000_000_000,
    });

    expect(workbench?.viewedFrom).toBe(1_600_000_000_000);
  });

  /**
   * The instant the server actually used, and the rows the two readings differ
   * over, come back with everything else.
   *
   * Both are answers about the resolution — the client re-deriving either would
   * be a second reading of instants only the server took.
   */
  it('issueWorkbench hands back the instant used and the messages published after the cut-off', () => {
    let workbench: IssueWorkbench | undefined;
    service.issueWorkbench('i-1').subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`).flush({
      issue: { publicId: 'i-1' },
      members: [{ messageUid: 'uid-a' }, { messageUid: 'uid-late' }],
      audit: [],
      overrides: [],
      viewedAt: 1_700_000_000_000,
      afterPlannedCutoff: [{ messageUid: 'uid-late' }],
      lateAfter: 1_690_000_000_000,
    });

    expect(workbench?.viewedAt).toBe(1_700_000_000_000);
    expect(workbench?.afterPlannedCutoff).toEqual([{ messageUid: 'uid-late' }]);
    // The instant those rows are late FOR: the one the request named where it
    // named one, and the stored planned cut-off otherwise. Without it a screen
    // reading a period being typed would head the list with the saved value.
    expect(workbench?.lateAfter).toBe(1_690_000_000_000);
  });

  /**
   * `null` is a real answer, and a different one from an absent field.
   *
   * It says the resolution had no instant to be late against — an issue with no
   * planned cut-off, a frozen one — and the list beside it is empty.
   */
  it('issueWorkbench keeps a null late-after instant as null', () => {
    let workbench: IssueWorkbench | undefined;
    service.issueWorkbench('i-1').subscribe((result) => (workbench = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/workbench`).flush({
      issue: { publicId: 'i-1' },
      members: [],
      audit: [],
      overrides: [],
      viewedAt: 1_700_000_000_000,
      afterPlannedCutoff: [],
      lateAfter: null,
    });

    expect(workbench?.lateAfter).toBeNull();
  });

  it('issueMembers GETs the member list with no live-or-frozen parameter', () => {
    service.issueMembers(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/members`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush([]);
  });

  it('issueAudit GETs the history panel', () => {
    service.issueAudit(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/audit`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  /**
   * The SERIES' own trail, which is a different read from any issue's.
   *
   * It is where the line left behind by a removed issue lives: the issue's own
   * entries went with it, so this is the only record that it existed.
   */
  it('seriesHistory GETs the series-level trail in the language it was asked for', () => {
    service.seriesHistory(AWKWARD_ID, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/${AWKWARD_PATH}/history`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });

  it('seriesHistory sends no language when none is named', () => {
    service.seriesHistory('weekly-ntm').subscribe();
    const req = expectPath(`${API_BASE}/publication-series/weekly-ntm/history`);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush([]);
  });

  /**
   * The decisions that STAND, which is not what the history says.
   *
   * The trail records an exclude and its later withdrawal as two entries; only
   * this endpoint says whether anything is still in force.
   */
  it('issueOverrides GETs the standing curation decisions', () => {
    let rows: readonly { messageUid: string; kind?: string }[] | undefined;
    service.issueOverrides(AWKWARD_ID).subscribe((result) => (rows = result));
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/overrides`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush([{ messageUid: 'uid-z', kind: 'EXCLUDE', author: 'rasmus', at: 1, reason: 'why' }]);
    expect(rows).toEqual([
      { messageUid: 'uid-z', kind: 'EXCLUDE', author: 'rasmus', at: 1, reason: 'why' },
    ]);
  });

  it('issueOverrides titles each decision’s message in the language it was given', () => {
    service.issueOverrides('i-1', 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides`);
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });

  it('publishChecklist GETs the rail, allowFuture false by default', () => {
    service.publishChecklist('i-1').subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/issue/i-1/publish-checklist`)).toEqual({
      allowFuture: 'false',
    });
  });

  it('publishChecklist can ask what publishing ahead of the cut-off would say', () => {
    service.publishChecklist('i-1', true).subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/issue/i-1/publish-checklist`)).toEqual({
      allowFuture: 'true',
    });
  });

  /**
   * The rail resolves AS OF the cut-off, so a chosen instant gets its own.
   *
   * A release closed at a past moment is judged against a member list the
   * default rail never looked at — asking without the instant answers a question
   * about a release nobody is making.
   */
  it('publishChecklist asks for a chosen instant as epoch milliseconds', () => {
    service.publishChecklist('i-1', false, 1_700_000_000_000).subscribe();
    expect(paramsOf(`${API_BASE}/publication-issues/issue/i-1/publish-checklist`)).toEqual({
      allowFuture: 'false',
      cutoff: '1700000000000',
    });
  });

  /**
   * The count the rail was computed over travels with the rail.
   *
   * It is the count for THAT instant, which is the whole reason it is asked
   * for: a release closed at a chosen moment freezes a different member list
   * from the one the screen resolved.
   */
  it('publishChecklist hands back the member count the rail was computed over', () => {
    let checklist: PublishChecklist | undefined;
    service
      .publishChecklist('i-1', false, 1_700_000_000_000)
      .subscribe((result) => (checklist = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/publish-checklist`).flush({
      rows: [],
      canPublish: true,
      blockingCodes: [],
      memberCount: 187,
    });

    expect(checklist?.memberCount).toBe(187);
  });

  /**
   * An unchosen cut-off is OMITTED, never sent as null.
   *
   * Absent means "now"; a null would be a chosen instant with no value, and the
   * server would have to guess which of the two the caller meant.
   */
  it('publishIssue PUTs the acknowledged warnings and omits an unchosen cut-off', () => {
    service.publishIssue(AWKWARD_ID, { acknowledgedWarnings: ['X'] }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/publish`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ acknowledgedWarnings: ['X'] });
    req.flush({});
  });

  it('publishIssue sends a chosen cut-off as epoch milliseconds', () => {
    service
      .publishIssue('i-1', {
        cutoff: 1_700_000_000_000,
        acknowledgedWarnings: [],
      })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/publish`);
    expect(req.request.body).toEqual({
      acknowledgedWarnings: [],
      cutoff: 1_700_000_000_000,
    });
    req.flush({});
  });

  it('previewIssue POSTs with no body when given no instant, and hands back a copy of the rows', () => {
    let rows: readonly { lang: string }[] | undefined;
    service.previewIssue(AWKWARD_ID).subscribe((result) => (rows = result));
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/preview`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush([{ lang: 'da', renderedAt: 1, href: '/rest/x' }]);
    expect(rows).toEqual([{ lang: 'da', renderedAt: 1, href: '/rest/x' }]);
  });

  /**
   * The instant the screen shows travels with the render.
   *
   * Without it the server rendered at the series' default cut-off, so a reader
   * on the other view previewed a document that was not the one on screen.
   */
  it('previewIssue POSTs the instant it is given', () => {
    service.previewIssue('i-1', 1_700_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/preview`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ at: 1_700_000_000_000 });
    req.flush([]);
  });

  /** A render that reports no rows must not throw: an empty preview is an answer. */
  it('previewIssue turns a null body into an empty list', () => {
    let rows: readonly unknown[] | undefined;
    service.previewIssue('i-1').subscribe((result) => (rows = result));
    expectPath(`${API_BASE}/publication-issues/issue/i-1/preview`).flush(null);
    expect(rows).toEqual([]);
  });

  /**
   * The bytes are fetched over HttpClient, from an address this client composed.
   *
   * The endpoint answers only to a request carrying the bearer, which a plain
   * anchor does not send — and the href the render reports is root-relative while
   * the API lives on its own origin.
   */
  it('previewFile GETs one language’s PDF as a blob', () => {
    service.previewFile(AWKWARD_ID, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/preview/da`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF']));
  });

  /**
   * The archive is an ADDRESS, not a request — the one document endpoint here
   * that is opened rather than fetched.
   *
   * It checks the admin role in code rather than by annotation so a one-time
   * ticket satisfies it, which is what lets a top-level navigation carrying no
   * bearer reach it. Composing a URL and returning it is therefore the whole
   * method, and nothing must go out over HttpClient — the `httpTesting.verify()`
   * in `afterEach` is what holds that.
   */
  it('archiveFileUrl composes the address and encodes every segment', () => {
    expect(service.archiveFileUrl(AWKWARD_ID, 40122, 'da')).toBe(
      `${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/archive/40122/da`,
    );
  });

  /** Retiring is an action about one named thing, so the reason is a query parameter. */
  it('retireIssue PUTs with a null body and the reason as a query parameter', () => {
    service.retireIssue(AWKWARD_ID, 'withdrawn').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/retire`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBeNull();
    expect(req.request.params.get('reason')).toBe('withdrawn');
    req.flush({});
  });

  it('reactivateIssue PUTs with the optional reason, and omits it when there is none', () => {
    service.reactivateIssue('i-1', 'restored').subscribe();
    const withReason = expectPath(`${API_BASE}/publication-issues/issue/i-1/reactivate`);
    expect(withReason.request.params.get('reason')).toBe('restored');
    withReason.flush({});

    service.reactivateIssue('i-1').subscribe();
    const without = expectPath(`${API_BASE}/publication-issues/issue/i-1/reactivate`);
    expect(without.request.method).toBe('PUT');
    expect(without.request.params.keys()).toHaveLength(0);
    without.flush({});
  });

  /**
   * Removal is the ISSUE's own address with no verb after it.
   *
   * The same shape the withdrawal beside it uses for its revision and its
   * optional reason — a DELETE has no body to put either in — and the same path
   * the edit PUTs to, which is what makes it the issue itself being removed
   * rather than one of the things hanging off it.
   */
  it('deleteIssue DELETEs the issue, carrying the revision and an optional reason', () => {
    service.deleteIssue(AWKWARD_ID, 7, 'opened by mistake').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.params.get('version')).toBe('7');
    expect(req.request.params.get('reason')).toBe('opened by mistake');
    req.flush(null);
  });

  it('deleteIssue sends nothing beside the path where the caller names neither', () => {
    service.deleteIssue('i-1').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush(null);
  });

  /**
   * The language is for the REFUSAL.
   *
   * A removal blocked by citations answers with the messages that cite the
   * issue, and those names are the whole of what the reader has to act on — so
   * they are asked for in the language the screen is being read in, even though
   * nothing about the removal itself depends on it.
   */
  it('deleteIssue names the citing messages in the language it was given', () => {
    service.deleteIssue('i-1', 3, undefined, 'da').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.params.get('lang')).toBe('da');
    expect(req.request.params.get('version')).toBe('3');
    expect(req.request.params.get('reason')).toBeNull();
    req.flush(null);
  });

  /**
   * Curation is ONE decision about a list, not a request per message.
   *
   * The server applies all of them or none, and names every unresolvable uid in a
   * single refusal rather than making the curator find them one round trip at a
   * time.
   */
  it('includeMembers PUTs every uid and the reason in one body', () => {
    service.includeMembers(AWKWARD_ID, ['uid-1', 'uid-2'], 'requested by the pilots').subscribe();
    const req = expectPath(
      `${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/overrides/include`,
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      messageUids: ['uid-1', 'uid-2'],
      reason: 'requested by the pilots',
    });
    req.flush(null);
  });

  it('excludeMembers PUTs the same shape to the exclude path', () => {
    service.excludeMembers('i-1', ['uid-1'], 'duplicate').subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides/exclude`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ messageUids: ['uid-1'], reason: 'duplicate' });
    req.flush(null);
  });

  /** Addressed by the message the decision was about, with the reason as a parameter. */
  it('clearOverride DELETEs the message’s override, encoding the uid', () => {
    service.clearOverride(AWKWARD_ID, AWKWARD_ID, 'mistake').subscribe();
    const req = expectPath(
      `${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/overrides/${AWKWARD_PATH}`,
    );
    expect(req.request.method).toBe('DELETE');
    expect(req.request.params.get('reason')).toBe('mistake');
    req.flush(null);
  });

  /** No cut-off: an amend replaces the document a citation already points at. */
  it('amendIssue PUTs the reason and the acknowledged warnings', () => {
    service
      .amendIssue(AWKWARD_ID, {
        reason: 'wrong chart quoted',
        acknowledgedWarnings: ['TYPE_DRIFT'],
      })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/amend`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      reason: 'wrong chart quoted',
      acknowledgedWarnings: ['TYPE_DRIFT'],
    });
    req.flush({});
  });

  it('newEdition POSTs an empty body when no interval start is chosen', () => {
    service.newEdition(AWKWARD_ID).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/${AWKWARD_PATH}/new-edition`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({});
  });

  it('newEdition POSTs the chosen interval start', () => {
    service.newEdition('i-1', 1_700_000_000_000).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/new-edition`);
    expect(req.request.body).toEqual({ intervalFrom: 1_700_000_000_000 });
    req.flush({});
  });

  /**
   * The message lookup, which answers 200 with an empty body for an unknown id.
   *
   * Nobody knows a uid — what is written on a message, and quoted in the mail
   * asking for it, is the short id — so either form is sent as-is and the server
   * resolves it.
   */
  it('resolveMessage GETs the messages endpoint with the typed id encoded', () => {
    let resolved: unknown = 'untouched';
    service.resolveMessage(AWKWARD_ID).subscribe((result) => (resolved = result));
    const req = expectPath(`${API_BASE}/messages/message/${AWKWARD_PATH}`);
    expect(req.request.method).toBe('GET');
    req.flush(null);
    expect(resolved).toBeNull();
  });

  /**
   * A method with no spec is a request nobody has compared against the resource.
   *
   * Reflection rather than a reviewer's memory: the declared list above is the
   * contract, and a method added without one fails here before it is reviewed. A
   * private helper counts too — write it as a module function instead.
   */
  // ------------------------------------------ the revision a write is judged on

  /**
   * Every write against a row that already exists names the revision it was
   * composed against, and the server refuses it with `STALE_VERSION` when
   * somebody else has moved the row since.
   *
   * WHERE it travels is the whole contract, and it is not the caller's choice:
   * the body where there is one to put it in, `?version=` where the body is
   * already spoken for — by a bare status token, by a multipart envelope, or by
   * nothing at all on a DELETE. A revision sent in the wrong half is a request
   * that looks guarded and is not, which is exactly the failure the guard exists
   * to close, and it is invisible from either end.
   *
   * The pairs below are the table, transcribed. Each names one endpoint, the
   * half its revision travels in, and — for the query half — that it sits beside
   * whatever else was already there rather than replacing it.
   */
  it('updateSeries carries the revision in the body it is already sending', () => {
    const body = series({ version: 7 });
    service.updateSeries(AWKWARD_ID, body).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/${AWKWARD_PATH}`);
    expect(req.request.body).toEqual(expect.objectContaining({ version: 7 }));
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush(body);
  });

  /** The body is the status token, so the revision travels where the reason does. */
  it('setSeriesStatus sends the revision beside the reason, not instead of it', () => {
    service.setSeriesStatus('weekly-ntm', 'RETIRED', 'superseded', 4).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/status`);
    expect(req.request.body).toBe('"RETIRED"');
    expect(req.request.params.get('reason')).toBe('superseded');
    expect(req.request.params.get('version')).toBe('4');
    req.flush(series());
  });

  /** A transition with no reason still carries one revision and nothing else. */
  it('setSeriesStatus sends the revision alone when there is no reason', () => {
    service.setSeriesStatus('weekly-ntm', 'ACTIVE', undefined, 0).subscribe();
    const req = expectPath(`${API_BASE}/publication-series/series/weekly-ntm/status`);
    expect(req.request.params.keys()).toEqual(['version']);
    expect(req.request.params.get('version')).toBe('0');
    req.flush(series());
  });

  it('updateIssue carries the revision among the fields it is asked to change', () => {
    service.updateIssue('i-1', { names: { en: 'Week 27' }, version: 3 }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1`);
    expect(req.request.body).toEqual({ names: { en: 'Week 27' }, version: 3 });
    req.flush({});
  });

  it('publishIssue carries the revision beside the cut-off and the acknowledgements', () => {
    service.publishIssue('i-1', { acknowledgedWarnings: ['X'], version: 5 }).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/publish`);
    expect(req.request.body).toEqual({
      acknowledgedWarnings: ['X'],
      version: 5,
    });
    req.flush({});
  });

  it('amendIssue carries the revision beside the mandatory reason', () => {
    service.amendIssue('i-1', { reason: 'wrong chart', acknowledgedWarnings: [] }).subscribe();
    expectPath(`${API_BASE}/publication-issues/issue/i-1/amend`).flush({});

    service
      .amendIssue('i-1', {
        reason: 'wrong chart',
        acknowledgedWarnings: [],
        version: 9,
      })
      .subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/amend`);
    expect(req.request.body).toEqual({
      reason: 'wrong chart',
      acknowledgedWarnings: [],
      version: 9,
    });
    req.flush({});
  });

  /** The PREDECESSOR's revision: the successor does not exist yet to have one. */
  it('newEdition carries the predecessor’s revision, with or without a start', () => {
    service.newEdition('i-1', undefined, 2).subscribe();
    const bare = expectPath(`${API_BASE}/publication-issues/issue/i-1/new-edition`);
    expect(bare.request.body).toEqual({ version: 2 });
    bare.flush({});

    service.newEdition('i-1', 1_700_000_000_000, 2).subscribe();
    const withStart = expectPath(`${API_BASE}/publication-issues/issue/i-1/new-edition`);
    expect(withStart.request.body).toEqual({ intervalFrom: 1_700_000_000_000, version: 2 });
    withStart.flush({});
  });

  it('setIssueLink carries the revision beside the link', () => {
    service.setIssueLink('i-1', 'en', 'https://example.test/doc.pdf', 6).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/link/en`);
    expect(req.request.body).toEqual({ link: 'https://example.test/doc.pdf', version: 6 });
    req.flush({});
  });

  /**
   * THE ISSUE's revision, not the override's.
   *
   * A curation is a decision about the member set, the member set belongs to the
   * issue, and a curation forces the issue's revision on — so two curators who
   * both loaded the same open issue collide here.
   */
  it('the curation writes carry the issue’s revision in their body', () => {
    service.includeMembers('i-1', ['m1'], 'asked for', 8).subscribe();
    const include = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides/include`);
    expect(include.request.body).toEqual({
      messageUids: ['m1'],
      reason: 'asked for',
      version: 8,
    });
    include.flush(null);

    service.excludeMembers('i-1', ['m2'], 'withdrawn', 8).subscribe();
    const exclude = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides/exclude`);
    expect(exclude.request.body).toEqual({
      messageUids: ['m2'],
      reason: 'withdrawn',
      version: 8,
    });
    exclude.flush(null);
  });

  /** A DELETE has no body, so the revision travels beside the reason. */
  it('clearOverride sends the revision beside the reason', () => {
    service.clearOverride('i-1', 'uid-1', 'reinstated', 8).subscribe();
    const req = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides/uid-1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.params.get('reason')).toBe('reinstated');
    expect(req.request.params.get('version')).toBe('8');
    req.flush(null);
  });

  it('retireIssue and reactivateIssue send the revision as a query parameter', () => {
    service.retireIssue('i-1', 'wrong document', 3).subscribe();
    const retire = expectPath(`${API_BASE}/publication-issues/issue/i-1/retire`);
    expect(retire.request.params.get('reason')).toBe('wrong document');
    expect(retire.request.params.get('version')).toBe('3');
    retire.flush({});

    service.reactivateIssue('i-1', undefined, 4).subscribe();
    const reactivate = expectPath(`${API_BASE}/publication-issues/issue/i-1/reactivate`);
    expect(reactivate.request.params.keys()).toEqual(['version']);
    expect(reactivate.request.params.get('version')).toBe('4');
    reactivate.flush({});
  });

  /** The multipart envelope is the body, so the revision cannot travel in it. */
  it('the document calls send the revision as a query parameter', () => {
    const file = new File(['%PDF'], 'efs-27.pdf', { type: 'application/pdf' });
    service.uploadIssueFile('i-1', 'da', file, 12).subscribe();
    const upload = expectPath(`${API_BASE}/publication-issues/issue/i-1/file/da`);
    expect(upload.request.body).toBeInstanceOf(FormData);
    expect(upload.request.params.get('version')).toBe('12');
    upload.flush({});

    service.clearIssueFile('i-1', 'da', 12).subscribe();
    const clear = expectPath(`${API_BASE}/publication-issues/issue/i-1/file/da`);
    expect(clear.request.method).toBe('DELETE');
    expect(clear.request.params.get('version')).toBe('12');
    clear.flush({});
  });

  /**
   * An absent revision is the caller DECLINING the check, not revision zero.
   *
   * Sent blank it would be a value the server refuses as not a whole number, and
   * sent as `0` it would claim a revision — so a screen that has not read one
   * omits the parameter entirely and keeps the older last-write-wins.
   */
  it('omits the revision entirely where the caller names none', () => {
    service.retireIssue('i-1', 'wrong document').subscribe();
    const retire = expectPath(`${API_BASE}/publication-issues/issue/i-1/retire`);
    expect(retire.request.params.keys()).toEqual(['reason']);
    retire.flush({});

    service.clearIssueFile('i-1', 'da').subscribe();
    const clear = expectPath(`${API_BASE}/publication-issues/issue/i-1/file/da`);
    expect(clear.request.params.keys()).toHaveLength(0);
    clear.flush({});

    service.setIssueLink('i-1', 'en', '').subscribe();
    const link = expectPath(`${API_BASE}/publication-issues/issue/i-1/link/en`);
    expect(link.request.body).toEqual({ link: '' });
    link.flush({});

    service.includeMembers('i-1', ['m1'], 'asked for').subscribe();
    const include = expectPath(`${API_BASE}/publication-issues/issue/i-1/overrides/include`);
    expect(include.request.body).toEqual({ messageUids: ['m1'], reason: 'asked for' });
    include.flush(null);

    service.newEdition('i-1').subscribe();
    const edition = expectPath(`${API_BASE}/publication-issues/issue/i-1/new-edition`);
    expect(edition.request.body).toEqual({});
    edition.flush({});
  });

  it('pins every method the service declares', () => {
    const declared = Object.getOwnPropertyNames(PublicationSeriesApiService.prototype).filter(
      (name) => name !== 'constructor',
    );
    expect([...declared].sort()).toEqual([...PINNED_METHODS].sort());
  });
});

/**
 * The contract vocabularies, transcribed from the backend enums.
 *
 * Each list below is one Java enum written out by hand, and the assertion is set
 * equality against the array this application carries. A value added on one side
 * and not the other is red here rather than discovered on a screen: a value the
 * server can send and this build does not list renders as a raw identifier, and a
 * value listed here that the server does not have is a control offering something
 * every save will refuse.
 *
 * The transcriptions are typed by the union they are checked against, so a value
 * the union does not contain does not even compile.
 */
describe('the contract vocabularies', () => {
  function sameVocabulary(actual: readonly string[], backend: readonly string[]) {
    expect([...actual].sort()).toEqual([...backend].sort());
  }

  it('SeriesStatus', () => {
    const backend: readonly SeriesStatus[] = ['DRAFT', 'ACTIVE', 'RETIRED'];
    sameVocabulary(SERIES_STATUSES, backend);
  });

  it('IssueStatus', () => {
    const backend: readonly IssueStatus[] = ['OPEN', 'PUBLISHED', 'RETIRED'];
    sameVocabulary(ISSUE_STATUSES, backend);
  });

  /** The synthesized halves of a merged period list, which no Java enum names. */
  it('IssueRowKind, and the computed status that is both vocabularies at once', () => {
    const backend: readonly IssueRowKind[] = ['MISSING', 'UPCOMING'];
    sameVocabulary(ISSUE_ROW_KINDS, backend);
    const computed: readonly ComputedIssueStatus[] = [...ISSUE_STATUSES, ...ISSUE_ROW_KINDS];
    sameVocabulary(COMPUTED_ISSUE_STATUSES, computed);
  });

  it('SeriesCadence', () => {
    const backend: readonly SeriesCadence[] = ['NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
    sameVocabulary(SERIES_CADENCES, backend);
  });

  it('SeriesKind', () => {
    const backend: readonly SeriesKind[] = ['SCHEDULED', 'UNSCHEDULED', 'ONE_OFF'];
    sameVocabulary(SERIES_KINDS, backend);
  });

  it('ContentMode', () => {
    const backend: readonly ContentMode[] = [
      'GENERATED_FROM_QUERY',
      'UPLOADED_FILE',
      'EXTERNAL_LINK',
      'NONE',
    ];
    sameVocabulary(CONTENT_MODES, backend);
  });

  it('TimeRelation — three regimes, the third one compiling another series', () => {
    const backend: readonly TimeRelation[] = [
      'PUBLISHED_IN_INTERVAL',
      'IN_FORCE_AT_CUTOFF',
      'COMPILED_FROM_SOURCE',
    ];
    sameVocabulary(TIME_RELATIONS, backend);
  });

  it('CutoffDefault', () => {
    const backend: readonly CutoffDefault[] = ['RELEASE_MOMENT', 'PERIOD_START', 'PERIOD_END'];
    sameVocabulary(CUTOFF_DEFAULTS, backend);
  });

  it('CutoffDay', () => {
    const backend: readonly CutoffDay[] = [
      'MONDAY',
      'TUESDAY',
      'WEDNESDAY',
      'THURSDAY',
      'FRIDAY',
      'SATURDAY',
      'SUNDAY',
    ];
    sameVocabulary(CUTOFF_DAYS, backend);
  });

  it('IntervalBoundSource', () => {
    const backend: readonly IntervalBoundSource[] = ['STAMPED', 'NOMINAL', 'RECOVERED', 'MANUAL'];
    sameVocabulary(INTERVAL_BOUND_SOURCES, backend);
  });

  /**
   * The cut-off's own vocabulary, which is not the interval bounds' four.
   *
   * Two stamps this application writes and seven answers an imported issue can
   * carry, and the split matters on screen: only `STAMPED_AT_PUBLISH` goes
   * unmarked. A value the server sends and this list omits renders as an unknown
   * source, which is the one case the field is loosely typed to survive.
   */
  it('CutoffSource', () => {
    const backend: readonly CutoffSource[] = [
      'STAMPED_AT_PUBLISH',
      'STAMPED_MANUAL_TIME',
      'RECOVERED_FROM_UPDATED',
      'RECOVERED_FROM_NEXT_TAG',
      'RECOVERED_FROM_COVER',
      'NOMINAL_CLOSE',
      'PUBLIC_WINDOW',
      'MANUAL',
      'NOT_RELEASED',
    ];
    sameVocabulary(CUTOFF_SOURCES, backend);
  });

  /**
   * Who, besides the owner, may see and cite a publication.
   *
   * Three values, and the middle one is the only one the domain list beside it
   * means anything for. A value this build does not list would reach the editor
   * as a radio nothing selects, and a value listed here that the server has not
   * got is a radio every save refuses.
   */
  it('Availability', () => {
    const backend: readonly Availability[] = ['OWNER_ONLY', 'SELECTED_DOMAINS', 'ALL_DOMAINS'];
    sameVocabulary(AVAILABILITIES, backend);
  });

  it('ReleaseMode', () => {
    const backend: readonly ReleaseMode[] = ['MANUAL_GATE', 'AUTO_RELEASE'];
    sameVocabulary(RELEASE_MODES, backend);
  });

  it('NextIssueCreation', () => {
    const backend: readonly NextIssueCreation[] = ['AUTO_ON_PUBLISH', 'MANUAL'];
    sameVocabulary(NEXT_ISSUE_CREATIONS, backend);
  });

  it('MessagePublication', () => {
    const backend: readonly MessagePublication[] = ['NONE', 'INTERNAL', 'EXTERNAL'];
    sameVocabulary(MESSAGE_PUBLICATIONS, backend);
  });

  it('NumberingScheme', () => {
    const backend: readonly NumberingScheme[] = [
      'ISO_WEEK_YEAR',
      'YEAR_EDITION',
      'MONTH_YEAR',
      'EDITION_SEQUENCE',
      'NONE',
    ];
    sameVocabulary(NUMBERING_SCHEMES, backend);
  });

  it('PageSize', () => {
    const backend: readonly PageSize[] = [
      'A3',
      'A4',
      'A5',
      'B4',
      'B5',
      'LETTER',
      'LEGAL',
      'LEDGER',
    ];
    sameVocabulary(PAGE_SIZES, backend);
  });

  it('PageOrientation', () => {
    const backend: readonly PageOrientation[] = ['PORTRAIT', 'LANDSCAPE'];
    sameVocabulary(PAGE_ORIENTATIONS, backend);
  });

  /** `ASC` / `DESC`, as the message search reads them. */
  it('SortDirection', () => {
    const backend: readonly SortDirection[] = ['ASC', 'DESC'];
    sameVocabulary(SORT_DIRECTIONS, backend);
  });

  /**
   * The criterion kinds travel under their WIRE names, not the Java constants.
   *
   * The enum carries the wire form as a field of its own, so a client that
   * guessed at `MESSAGE_SERIES` from the constant name would be refused on every
   * save with an unknown-kind parse failure.
   */
  it('CriterionKind, in the wire spelling', () => {
    const backend: readonly CriterionKind[] = [
      'messageSeries',
      'messageMainType',
      'messageType',
      'domain',
      'area',
      'category',
      'chart',
    ];
    sameVocabulary(CRITERION_KINDS, backend);
  });

  it('CriterionOperator', () => {
    const backend: readonly CriterionOperator[] = ['IN', 'NOT_IN'];
    sameVocabulary(CRITERION_OPERATORS, backend);
  });

  /** One value, and that is the contract: a document sent with any other fails to parse. */
  it('CriteriaMatch has exactly one member', () => {
    const backend: readonly CriteriaMatch[] = ['ALL'];
    sameVocabulary(CRITERIA_MATCHES, backend);
  });

  it('GapDetection reason', () => {
    const backend: readonly GapDetectionReason[] = [
      'CADENCED_SERIES',
      'NO_CADENCE',
      'SERIES_NOT_ACTIVE',
      'SERIES_DORMANT',
      'CADENCE_PERIOD_UNKNOWN',
    ];
    sameVocabulary(GAP_DETECTION_REASONS, backend);
  });

  it('MemberSource — four values, because an exclusion is not a member', () => {
    const backend: readonly MemberSource[] = [
      'CRITERIA',
      'COMPILED',
      'OVERRIDE_INCLUDE',
      'IMPORTED',
    ];
    sameVocabulary(MEMBER_SOURCES, backend);
  });

  /**
   * The reason codes the member list actually emits.
   *
   * Not the resolver's own enum: the list derives one of five from the member's
   * source and the snapshot's time relation, and the rest describe why a
   * candidate was DROPPED, which no member row can carry.
   */
  it('MemberReason', () => {
    const backend: readonly MemberReason[] = [
      'IN_INTERVAL',
      'IN_FORCE_AT_CUTOFF',
      'FROM_SOURCE_ISSUE',
      'MANUAL_INCLUDE',
      'IMPORTED',
    ];
    sameVocabulary(MEMBER_REASONS, backend);
  });

  it('OverrideKind', () => {
    const backend: readonly OverrideKind[] = ['INCLUDE', 'EXCLUDE'];
    sameVocabulary(OVERRIDE_KINDS, backend);
  });

  /** The two compared fields, plus the message being gone entirely. */
  it('MemberDriftField', () => {
    const backend: readonly MemberDriftField[] = ['exists', 'type', 'status'];
    sameVocabulary(MEMBER_DRIFT_FIELDS, backend);
  });

  it('IssueFileSource', () => {
    const backend: readonly IssueFileSource[] = ['GENERATED', 'UPLOADED'];
    sameVocabulary(ISSUE_FILE_SOURCES, backend);
  });

  it('MembershipProvenance', () => {
    const backend: readonly MembershipProvenance[] = [
      'EXACT',
      'EXPLAINED_DIFF',
      'UNION_SNAPSHOT',
      'COMPILED',
      'NO_MEMBERSHIP',
      'IMPORTED',
    ];
    sameVocabulary(MEMBERSHIP_PROVENANCES, backend);
  });

  /**
   * Not a Java enum: two issue statuses and the absence of an issue, which is
   * what the sources panel actually distinguishes.
   */
  it('SourceIssueStatus', () => {
    const backend: readonly SourceIssueStatus[] = ['PUBLISHED', 'OPEN', 'MISSING'];
    sameVocabulary(SOURCE_ISSUE_STATUSES, backend);
  });

  /**
   * `OK | WARN | BLOCK`, because that is what the wire carries.
   *
   * The union once read `BLOCKER | WARNING | INFO`, which no response ever
   * contained, so every comparison against it was dead and the screen derived the
   * tier from `acknowledgeable` instead — under which every failing row but one
   * painted as a hard blocker.
   */
  it('CheckSeverity', () => {
    const backend: readonly CheckSeverity[] = ['OK', 'WARN', 'BLOCK'];
    sameVocabulary(CHECK_SEVERITIES, backend);
  });

  it('AuditActorKind — three kinds, because an imported entry was nobody’s action', () => {
    const backend: readonly AuditActorKind[] = ['USER', 'SYSTEM', 'IMPORT'];
    sameVocabulary(AUDIT_ACTOR_KINDS, backend);
  });

  /**
   * The history vocabulary, and the two lists that both have to hold it.
   *
   * The contract list is what the wire carries; the one beside the labels is what
   * this build can put a sentence to. They are different questions with the same
   * answer, and an action added to one and not the other is either a raw code in
   * the middle of the trail or a translation nothing reaches.
   */
  it('the audit actions, on the wire and beside the labels', () => {
    const backend: readonly IssueAuditAction[] = [
      'CREATED',
      'CREATED_FROM_PREVIOUS_PUBLISH',
      'CREATED_RETROACTIVELY',
      'CREATED_NEW_EDITION',
      'PUBLISHED',
      'AMENDED',
      'RETIRED',
      'REACTIVATED',
      'SUPERSEDED_BY',
      'DELETED',
      'INTERVAL_CHANGED',
      'NAME_CHANGED',
      'EDITION_CHANGED',
      'NUMBERING_CHANGED',
      'FILE_NAME_CHANGED',
      'REPORT_CHANGED',
      'CRITERIA_OVERRIDDEN',
      'OVERRIDE_INCLUDED',
      'OVERRIDE_EXCLUDED',
      'OVERRIDE_REMOVED',
      'FILE_UPLOADED',
      'FILE_REPLACED_MANUALLY',
      'FILE_CLEARED',
      'LINK_SET',
      'LINK_CLEARED',
      'PREVIEW_GENERATED',
      'WINDOW_ADJUSTED',
      'VISIBILITY_CAPPED',
      'VISIBILITY_WINDOW_CHANGED',
      'IMPORTED',
      'SERIES_ACTIVATED',
      'SERIES_RETIRED',
      'OWNER_TRANSFERRED',
    ];
    sameVocabulary(ISSUE_AUDIT_ACTIONS, backend);
    sameVocabulary(AUDIT_ACTIONS, backend);
  });
});
