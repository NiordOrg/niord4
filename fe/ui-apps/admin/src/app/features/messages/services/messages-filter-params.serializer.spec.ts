import { describe, expect, it } from 'vitest';

import type { Domain } from '../../../core/domain/models/domain.model';
import type { MessageSeries } from '../models/message.model';
import {
  REFERENCE_LEVELS_DEFAULT,
  type AreaRef,
  type CategoryRef,
  type ChartRef,
  type FilterName,
  type MessagesFilterParams,
  type PublicationRef,
  type PromulgationTypeRef,
  type SortBy,
  type SortOrder,
} from '../models/messages-filter-params.model';
import {
  defaultParams,
  fromQuery,
  toQuery,
  type DeserializerContext,
  type SerializerContext,
} from './messages-filter-params.serializer';

const TEST_DOMAIN_A: Domain = { domainId: 'domain-a', name: 'Domain A' };
const TEST_DOMAIN_B: Domain = { domainId: 'domain-b', name: 'Domain B' };

function ctxOut(overrides?: Partial<SerializerContext>): SerializerContext {
  return {
    defaultSortBy: 'AREA',
    defaultSortOrder: 'ASC',
    mapExtent: undefined,
    isMap: true,
    searchPublicMessages: false,
    isAuthenticated: true,
    ...overrides,
  };
}

function ctxIn(overrides?: Partial<DeserializerContext>): DeserializerContext {
  return {
    isAuthenticated: true,
    resolveDomain: (id: string): Domain | undefined => {
      if (id === 'domain-a') return TEST_DOMAIN_A;
      if (id === 'domain-b') return TEST_DOMAIN_B;
      return undefined;
    },
    ...overrides,
  };
}

function withEnabled(
  base: MessagesFilterParams,
  changes: Partial<Record<FilterName, boolean>>,
): MessagesFilterParams {
  return { ...base, enabled: { ...base.enabled, ...changes } };
}

function patch<K extends keyof MessagesFilterParams>(
  base: MessagesFilterParams,
  key: K,
  value: MessagesFilterParams[K],
): MessagesFilterParams {
  return { ...base, [key]: value };
}

describe('messages-filter-params serializer', () => {
  describe('toQuery', () => {
    it('emits empty string for default params', () => {
      const params = defaultParams();
      expect(toQuery(params, ctxOut())).toBe('');
    });

    it('emits domain only when filter enabled and domain set', () => {
      let params = defaultParams();
      params = patch(params, 'domain', { domain: TEST_DOMAIN_A });
      expect(toQuery(params, ctxOut())).toBe('');

      params = withEnabled(params, { domain: true });
      expect(toQuery(params, ctxOut())).toBe('domain=domain-a');

      params = patch(params, 'domain', { domain: undefined });
      expect(toQuery(params, ctxOut())).toBe('');
    });

    it('emits text query when filter enabled', () => {
      let params = defaultParams();
      params = patch(params, 'text', { query: 'hello world' });
      params = withEnabled(params, { text: true });
      expect(toQuery(params, ctxOut())).toBe('query=hello%20world');
    });

    it('omits text param when query is empty even if enabled', () => {
      let params = defaultParams();
      params = withEnabled(params, { text: true });
      expect(toQuery(params, ctxOut())).toBe('');
    });

    it('emits mainType and types', () => {
      let params = defaultParams();
      params = patch(params, 'type', {
        mainType: 'NW',
        types: ['LOCAL_WARNING', 'COASTAL_WARNING'],
        cancellationWarnings: false,
      });
      params = withEnabled(params, { type: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('mainType=NW');
      expect(result).toContain('type=LOCAL_WARNING');
      expect(result).toContain('type=COASTAL_WARNING');
      expect(result).not.toContain('kind=');
    });

    it('asks for the cancellation warnings with kind=CANCELLATION, only while the Type filter is on', () => {
      let params = defaultParams();
      params = patch(params, 'type', { mainType: 'NW', types: [], cancellationWarnings: true });
      expect(toQuery(params, ctxOut())).toBe('');

      params = withEnabled(params, { type: true });
      expect(toQuery(params, ctxOut())).toBe('kind=CANCELLATION&mainType=NW');

      params = patch(params, 'type', { mainType: '', types: [], cancellationWarnings: true });
      expect(toQuery(params, ctxOut())).toBe('kind=CANCELLATION');
    });

    it('never asks for the cancellation warnings of Notices to Mariners, which have none', () => {
      let params = defaultParams();
      params = patch(params, 'type', { mainType: 'NM', types: [], cancellationWarnings: true });
      params = withEnabled(params, { type: true });
      expect(toQuery(params, ctxOut())).toBe('mainType=NM');
    });

    it('emits status values', () => {
      let params = defaultParams();
      params = patch(params, 'status', {
        statuses: ['PUBLISHED', 'DRAFT'],
      });
      params = withEnabled(params, { status: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('status=PUBLISHED');
      expect(result).toContain('status=DRAFT');
    });

    it('strips DRAFT/VERIFIED/DELETED statuses when searchPublicMessages is true (status mask)', () => {
      let params = defaultParams();
      params = patch(params, 'status', {
        statuses: ['PUBLISHED', 'DRAFT', 'VERIFIED', 'CANCELLED', 'EXPIRED', 'DELETED'],
      });
      params = withEnabled(params, { status: true });
      const result = toQuery(params, ctxOut({ searchPublicMessages: true }));
      expect(result).toContain('status=PUBLISHED');
      expect(result).toContain('status=CANCELLED');
      expect(result).toContain('status=EXPIRED');
      expect(result).not.toContain('DRAFT');
      expect(result).not.toContain('VERIFIED');
      expect(result).not.toContain('DELETED');
    });

    it('preserves all status selections when not in public mode', () => {
      let params = defaultParams();
      params = patch(params, 'status', {
        statuses: ['DRAFT', 'VERIFIED', 'DELETED'],
      });
      params = withEnabled(params, { status: true });
      const result = toQuery(params, ctxOut({ searchPublicMessages: false }));
      expect(result).toContain('status=DRAFT');
      expect(result).toContain('status=VERIFIED');
      expect(result).toContain('status=DELETED');
    });

    it('emits opaque tag IDs', () => {
      let params = defaultParams();
      params = patch(params, 'tag', {
        tags: [
          { tagId: 'tag-1', name: 'tag-1', type: 'PRIVATE', messageCount: 0, locked: false },
          { tagId: 'tag-2', name: 'tag-2', type: 'PRIVATE', messageCount: 0, locked: false },
        ],
      });
      params = withEnabled(params, { tag: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('tag=tag-1');
      expect(result).toContain('tag=tag-2');
    });

    it('emits opaque AtoN UIDs', () => {
      let params = defaultParams();
      params = patch(params, 'aton', { atonUids: ['uid-1', 'uid-2'] });
      params = withEnabled(params, { aton: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('aton=uid-1');
      expect(result).toContain('aton=uid-2');
    });

    it('emits username and userType when authenticated, enabled, non-empty', () => {
      let params = defaultParams();
      params = patch(params, 'user', { username: 'alice', userType: 'CREATED_BY' });
      params = withEnabled(params, { user: true });
      const result = toQuery(params, ctxOut({ isAuthenticated: true }));
      expect(result).toContain('username=alice');
      expect(result).toContain('userType=CREATED_BY');
    });

    it('omits userType when username is set without userType', () => {
      let params = defaultParams();
      params = patch(params, 'user', { username: 'alice', userType: undefined });
      params = withEnabled(params, { user: true });
      const result = toQuery(params, ctxOut({ isAuthenticated: true }));
      expect(result).toContain('username=alice');
      expect(result).not.toContain('userType=');
    });

    it('drops user filter when not authenticated', () => {
      let params = defaultParams();
      params = patch(params, 'user', { username: 'alice', userType: 'CREATED_BY' });
      params = withEnabled(params, { user: true });
      const result = toQuery(params, ctxOut({ isAuthenticated: false }));
      expect(result).not.toContain('username=');
      expect(result).not.toContain('userType=');
    });

    it('emits comments value when authenticated and non-empty', () => {
      let params = defaultParams();
      params = patch(params, 'comments', { value: 'OWN' });
      params = withEnabled(params, { comments: true });
      expect(toQuery(params, ctxOut({ isAuthenticated: true }))).toBe('comments=OWN');
    });

    it('masks comments OWN/OWN_UNACK to omitted when not authenticated (comments mask)', () => {
      let params = defaultParams();
      params = patch(params, 'comments', { value: 'OWN' });
      params = withEnabled(params, { comments: true });
      expect(toQuery(params, ctxOut({ isAuthenticated: false }))).toBe('');

      params = patch(params, 'comments', { value: 'OWN_UNACK' });
      expect(toQuery(params, ctxOut({ isAuthenticated: false }))).toBe('');
    });

    it('preserves comments ANY/ANY_UNACK when not authenticated', () => {
      let params = defaultParams();
      params = patch(params, 'comments', { value: 'ANY' });
      params = withEnabled(params, { comments: true });
      expect(toQuery(params, ctxOut({ isAuthenticated: false }))).toBe('comments=ANY');

      params = patch(params, 'comments', { value: 'ANY_UNACK' });
      expect(toQuery(params, ctxOut({ isAuthenticated: false }))).toBe('comments=ANY_UNACK');
    });

    it('emits messageId and referenceLevels when reference enabled', () => {
      let params = defaultParams();
      params = patch(params, 'reference', { messageId: 'NW-001', referenceLevels: 3 });
      params = withEnabled(params, { reference: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('messageId=NW-001');
      expect(result).toContain('referenceLevels=3');
    });

    it('emits chart by chartNumber, area/category by id', () => {
      let params = defaultParams();
      const chart: ChartRef = { chartNumber: '101' };
      const area: AreaRef = { id: 42 };
      const category: CategoryRef = { id: 99 };
      params = patch(params, 'chart', { charts: [chart] });
      params = patch(params, 'area', { areas: [area] });
      params = patch(params, 'category', { categories: [category] });
      params = withEnabled(params, { chart: true, area: true, category: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain('chart=101');
      expect(result).toContain('area=42');
      expect(result).toContain('category=99');
    });

    it('emits messageSeries by seriesId', () => {
      let params = defaultParams();
      const series: MessageSeries = { seriesId: 'series-1' };
      params = patch(params, 'messageSeries', { messageSeries: [series] });
      params = withEnabled(params, { messageSeries: true });
      expect(toQuery(params, ctxOut())).toBe('messageSeries=series-1');
    });

    it('emits publication by publicationId', () => {
      let params = defaultParams();
      const pub: PublicationRef = { publicationId: 'pub-1' };
      params = patch(params, 'publication', { publications: [pub] });
      params = withEnabled(params, { publication: true });
      expect(toQuery(params, ctxOut())).toBe('publication=pub-1');
    });

    it('emits promulgationType by typeId', () => {
      let params = defaultParams();
      const promType: PromulgationTypeRef = { typeId: 'navtex' };
      params = patch(params, 'promulgationType', { promulgationTypes: [promType] });
      params = withEnabled(params, { promulgationType: true });
      expect(toQuery(params, ctxOut())).toBe('promulgationType=navtex');
    });

    it('emits dateType only when fromDate or toDate is set', () => {
      let params = defaultParams();
      const fromMillis = 1700000000000;
      params = patch(params, 'date', {
        dateType: 'CREATED_DATE',
        fromDate: fromMillis,
        toDate: undefined,
      });
      params = withEnabled(params, { date: true });
      const result = toQuery(params, ctxOut());
      expect(result).toContain(`fromDate=${fromMillis}`);
      expect(result).toContain('dateType=CREATED_DATE');
    });

    it('omits dateType when neither fromDate nor toDate is set', () => {
      let params = defaultParams();
      params = patch(params, 'date', {
        dateType: 'CREATED_DATE',
        fromDate: undefined,
        toDate: undefined,
      });
      params = withEnabled(params, { date: true });
      expect(toQuery(params, ctxOut())).toBe('');
    });

    it('emits sortBy/sortOrder only when ≠ defaults', () => {
      let params = defaultParams();
      params = { ...params, sortBy: 'ID' as SortBy, sortOrder: 'DESC' as SortOrder };
      const result = toQuery(params, ctxOut({ defaultSortBy: 'AREA', defaultSortOrder: 'ASC' }));
      expect(result).toContain('sortBy=ID');
      expect(result).toContain('sortOrder=DESC');
    });

    it('omits sortBy/sortOrder when matching defaults', () => {
      const params = {
        ...defaultParams(),
        sortBy: 'AREA' as SortBy,
        sortOrder: 'ASC' as SortOrder,
      };
      expect(toQuery(params, ctxOut({ defaultSortBy: 'AREA', defaultSortOrder: 'ASC' }))).toBe('');
    });

    it('emits map extent values from context when isMap is true', () => {
      const params = defaultParams();
      const result = toQuery(
        params,
        ctxOut({
          isMap: true,
          mapExtent: {
            zoom: 7,
            center: [10.5, 55.5],
            extent: [9, 54, 12, 56],
          },
        }),
      );
      expect(result).toContain('zoom=7');
      expect(result).toContain('lon=10.5');
      expect(result).toContain('lat=55.5');
      expect(result).toContain('minLon=9');
      expect(result).toContain('minLat=54');
      expect(result).toContain('maxLon=12');
      expect(result).toContain('maxLat=56');
    });

    it('omits map extent values when isMap is false (no stale extent on non-map URLs)', () => {
      const params = defaultParams();
      const result = toQuery(
        params,
        ctxOut({
          isMap: false,
          mapExtent: {
            zoom: 7,
            center: [10.5, 55.5],
            extent: [9, 54, 12, 56],
          },
        }),
      );
      expect(result).not.toContain('zoom=');
      expect(result).not.toContain('lon=');
      expect(result).not.toContain('lat=');
      expect(result).not.toContain('minLon=');
      expect(result).not.toContain('maxLon=');
    });

    it('produces deterministic alphabetic key order', () => {
      let params = defaultParams();
      params = patch(params, 'text', { query: 'foo' });
      params = patch(params, 'domain', { domain: TEST_DOMAIN_A });
      params = withEnabled(params, { text: true, domain: true });
      const result = toQuery(params, ctxOut());
      expect(result).toBe('domain=domain-a&query=foo');
    });
  });

  describe('fromQuery', () => {
    it('returns defaults for empty query', () => {
      const result = fromQuery('', defaultParams(), ctxIn());
      expect(result.params).toEqual(defaultParams());
      expect(result.pendingDomainId).toBeUndefined();
    });

    it('parses domain and resolves it', () => {
      const result = fromQuery('domain=domain-a', defaultParams(), ctxIn());
      expect(result.params.domain.domain).toEqual(TEST_DOMAIN_A);
      expect(result.params.enabled.domain).toBe(true);
      expect(result.pendingDomainId).toBeUndefined();
    });

    it('records pendingDomainId when domain cannot be resolved', () => {
      const result = fromQuery('domain=unknown-x', defaultParams(), ctxIn());
      expect(result.pendingDomainId).toBe('unknown-x');
      expect(result.params.domain.domain).toBeUndefined();
      expect(result.params.enabled.domain).toBe(false);
    });

    it('parses text query', () => {
      const result = fromQuery('query=hello%20world', defaultParams(), ctxIn());
      expect(result.params.text.query).toBe('hello world');
      expect(result.params.enabled.text).toBe(true);
    });

    it('parses mainType and types', () => {
      const result = fromQuery(
        'mainType=NW&type=LOCAL_WARNING&type=COASTAL_WARNING',
        defaultParams(),
        ctxIn(),
      );
      expect(result.params.type.mainType).toBe('NW');
      expect(result.params.type.types).toEqual(['LOCAL_WARNING', 'COASTAL_WARNING']);
      expect(result.params.enabled.type).toBe(true);
    });

    it('reads kind=CANCELLATION as the Cancellation warnings choice of the Type filter', () => {
      const result = fromQuery('kind=CANCELLATION&mainType=NW', defaultParams(), ctxIn());
      expect(result.params.type).toEqual({
        mainType: 'NW',
        types: [],
        cancellationWarnings: true,
      });
      expect(result.params.enabled.type).toBe(true);

      const alone = fromQuery('kind=CANCELLATION', defaultParams(), ctxIn());
      expect(alone.params.type.cancellationWarnings).toBe(true);
      expect(alone.params.enabled.type).toBe(true);
    });

    it('ignores any other kind, and the cancellation warnings with mainType=NM', () => {
      for (const query of ['kind=WARNING', 'kind=BULLETIN', 'kind=cancellation', 'kind=']) {
        const result = fromQuery(query, defaultParams(), ctxIn());
        expect(result.params.type.cancellationWarnings, query).toBe(false);
        expect(result.params.enabled.type, query).toBe(false);
      }
      const nm = fromQuery('kind=CANCELLATION&mainType=NM', defaultParams(), ctxIn());
      expect(nm.params.type).toEqual({ mainType: 'NM', types: [], cancellationWarnings: false });
    });

    it('discards unknown type values', () => {
      const result = fromQuery('type=BOGUS&type=PERMANENT_NOTICE', defaultParams(), ctxIn());
      expect(result.params.type.types).toEqual(['PERMANENT_NOTICE']);
    });

    it('parses status values', () => {
      const result = fromQuery('status=PUBLISHED&status=DRAFT', defaultParams(), ctxIn());
      expect(result.params.status.statuses).toEqual(['PUBLISHED', 'DRAFT']);
      expect(result.params.enabled.status).toBe(true);
    });

    it('parses tag IDs as opaque placeholder tags', () => {
      const result = fromQuery('tag=t-1&tag=t-2', defaultParams(), ctxIn());
      expect(result.params.tag.tags.map((t) => t.tagId)).toEqual(['t-1', 't-2']);
      expect(result.params.enabled.tag).toBe(true);
    });

    it('parses AtoN UIDs as opaque strings', () => {
      const result = fromQuery('aton=u-1&aton=u-2', defaultParams(), ctxIn());
      expect(result.params.aton.atonUids).toEqual(['u-1', 'u-2']);
      expect(result.params.enabled.aton).toBe(true);
    });

    it('parses username/userType when authenticated', () => {
      const result = fromQuery(
        'username=alice&userType=CREATED_BY',
        defaultParams(),
        ctxIn({ isAuthenticated: true }),
      );
      expect(result.params.user.username).toBe('alice');
      expect(result.params.user.userType).toBe('CREATED_BY');
      expect(result.params.enabled.user).toBe(true);
    });

    it('discards username/userType when not authenticated (user inbound gate)', () => {
      const result = fromQuery(
        'username=alice&userType=CREATED_BY',
        defaultParams(),
        ctxIn({ isAuthenticated: false }),
      );
      expect(result.params.user.username).toBeUndefined();
      expect(result.params.user.userType).toBeUndefined();
      expect(result.params.enabled.user).toBe(false);
    });

    it('parses comments OWN even when not authenticated (preserve state for re-render on login)', () => {
      const result = fromQuery('comments=OWN', defaultParams(), ctxIn({ isAuthenticated: false }));
      expect(result.params.comments.value).toBe('OWN');
      expect(result.params.enabled.comments).toBe(true);
    });

    it('parses messageId and referenceLevels with default 1 when missing', () => {
      const result = fromQuery('messageId=NW-001', defaultParams(), ctxIn());
      expect(result.params.reference.messageId).toBe('NW-001');
      expect(result.params.reference.referenceLevels).toBe(REFERENCE_LEVELS_DEFAULT);
      expect(result.params.enabled.reference).toBe(true);
    });

    it('clamps referenceLevels to [0, 5]', () => {
      let result = fromQuery('messageId=NW&referenceLevels=10', defaultParams(), ctxIn());
      expect(result.params.reference.referenceLevels).toBe(5);

      result = fromQuery('messageId=NW&referenceLevels=-3', defaultParams(), ctxIn());
      expect(result.params.reference.referenceLevels).toBe(0);
    });

    it('parses chart, area, category, messageSeries, publication, promulgationType', () => {
      const result = fromQuery(
        'chart=101&area=42&category=99&messageSeries=series-1&publication=pub-1&promulgationType=navtex',
        defaultParams(),
        ctxIn(),
      );
      expect(result.params.chart.charts).toEqual([{ chartNumber: '101' }]);
      expect(result.params.area.areas).toEqual([{ id: 42 }]);
      expect(result.params.category.categories).toEqual([{ id: 99 }]);
      expect(result.params.messageSeries.messageSeries).toEqual([{ seriesId: 'series-1' }]);
      expect(result.params.publication.publications).toEqual([{ publicationId: 'pub-1' }]);
      expect(result.params.promulgationType.promulgationTypes).toEqual([{ typeId: 'navtex' }]);
    });

    it('parses date filter with fromDate/toDate/dateType', () => {
      const fromMillis = 1700000000000;
      const toMillis = 1700100000000;
      const result = fromQuery(
        `fromDate=${fromMillis}&toDate=${toMillis}&dateType=CREATED_DATE`,
        defaultParams(),
        ctxIn(),
      );
      expect(result.params.date.fromDate).toBe(fromMillis);
      expect(result.params.date.toDate).toBe(toMillis);
      expect(result.params.date.dateType).toBe('CREATED_DATE');
      expect(result.params.enabled.date).toBe(true);
    });

    it('parses sortBy/sortOrder when present', () => {
      const result = fromQuery('sortBy=ID&sortOrder=DESC', defaultParams(), ctxIn());
      expect(result.params.sortBy).toBe('ID');
      expect(result.params.sortOrder).toBe('DESC');
    });

    it('falls back to defaults when sortBy/sortOrder absent', () => {
      const defaults = {
        ...defaultParams(),
        sortBy: 'AREA' as SortBy,
        sortOrder: 'ASC' as SortOrder,
      };
      const result = fromQuery('', defaults, ctxIn());
      expect(result.params.sortBy).toBe('AREA');
      expect(result.params.sortOrder).toBe('ASC');
    });
  });

  describe('round-trip', () => {
    it('round-trips a complex filter losslessly', () => {
      let params = defaultParams();
      params = patch(params, 'domain', { domain: TEST_DOMAIN_A });
      params = patch(params, 'text', { query: 'navigation' });
      params = patch(params, 'type', {
        mainType: 'NW',
        types: ['LOCAL_WARNING'],
        cancellationWarnings: true,
      });
      params = patch(params, 'status', { statuses: ['PUBLISHED'] });
      params = patch(params, 'tag', {
        tags: [{ tagId: 'tag-1', name: 'tag-1', type: 'PRIVATE', messageCount: 0, locked: false }],
      });
      params = patch(params, 'comments', { value: 'ANY' });
      params = patch(params, 'reference', { messageId: 'NW-001', referenceLevels: 2 });
      params = patch(params, 'aton', { atonUids: ['uid-1'] });
      params = patch(params, 'chart', { charts: [{ chartNumber: '101' }] });
      params = patch(params, 'area', { areas: [{ id: 42 }] });
      params = patch(params, 'category', { categories: [{ id: 99 }] });
      params = patch(params, 'messageSeries', {
        messageSeries: [{ seriesId: 'series-1' }] as readonly MessageSeries[],
      });
      params = patch(params, 'publication', {
        publications: [{ publicationId: 'pub-1' }] as readonly PublicationRef[],
      });
      params = patch(params, 'promulgationType', {
        promulgationTypes: [{ typeId: 'navtex' }] as readonly PromulgationTypeRef[],
      });
      params = patch(params, 'date', {
        dateType: 'CREATED_DATE',
        fromDate: 1700000000000,
        toDate: undefined,
      });
      params = withEnabled(params, {
        domain: true,
        text: true,
        type: true,
        status: true,
        tag: true,
        comments: true,
        reference: true,
        aton: true,
        chart: true,
        area: true,
        category: true,
        messageSeries: true,
        publication: true,
        promulgationType: true,
        date: true,
      });
      params = { ...params, sortBy: 'ID' as SortBy, sortOrder: 'DESC' as SortOrder };

      const query = toQuery(params, ctxOut());
      const reparsed = fromQuery(query, defaultParams(), ctxIn());

      expect(reparsed.params.domain.domain).toEqual(TEST_DOMAIN_A);
      expect(reparsed.params.text.query).toBe('navigation');
      expect(reparsed.params.type.mainType).toBe('NW');
      expect(reparsed.params.type.types).toEqual(['LOCAL_WARNING']);
      expect(reparsed.params.type.cancellationWarnings).toBe(true);
      expect(reparsed.params.status.statuses).toEqual(['PUBLISHED']);
      expect(reparsed.params.tag.tags.map((t) => t.tagId)).toEqual(['tag-1']);
      expect(reparsed.params.comments.value).toBe('ANY');
      expect(reparsed.params.reference.messageId).toBe('NW-001');
      expect(reparsed.params.reference.referenceLevels).toBe(2);
      expect(reparsed.params.aton.atonUids).toEqual(['uid-1']);
      expect(reparsed.params.chart.charts).toEqual([{ chartNumber: '101' }]);
      expect(reparsed.params.area.areas).toEqual([{ id: 42 }]);
      expect(reparsed.params.category.categories).toEqual([{ id: 99 }]);
      expect(reparsed.params.messageSeries.messageSeries).toEqual([{ seriesId: 'series-1' }]);
      expect(reparsed.params.publication.publications).toEqual([{ publicationId: 'pub-1' }]);
      expect(reparsed.params.promulgationType.promulgationTypes).toEqual([{ typeId: 'navtex' }]);
      expect(reparsed.params.date.dateType).toBe('CREATED_DATE');
      expect(reparsed.params.date.fromDate).toBe(1700000000000);
      expect(reparsed.params.sortBy).toBe('ID');
      expect(reparsed.params.sortOrder).toBe('DESC');
    });
  });

  describe('fromQuery map extent (deep-link viewport restore)', () => {
    it('parses full zoom + center + extent', () => {
      const result = fromQuery(
        'zoom=7&lon=10.5&lat=55.5&minLon=9&minLat=54&maxLon=12&maxLat=56',
        defaultParams(),
        ctxIn(),
      );
      expect(result.mapExtent).toEqual({ zoom: 7, center: [10.5, 55.5], extent: [9, 54, 12, 56] });
    });

    it('parses zoom only (no center / extent)', () => {
      const result = fromQuery('zoom=5', defaultParams(), ctxIn());
      expect(result.mapExtent).toEqual({ zoom: 5, center: undefined, extent: undefined });
    });

    it('drops a partial center (lon without lat)', () => {
      const result = fromQuery('zoom=5&lon=10', defaultParams(), ctxIn());
      expect(result.mapExtent?.center).toBeUndefined();
    });

    it('drops a partial extent (3 of 4 bbox corners)', () => {
      const result = fromQuery('minLon=9&minLat=54&maxLon=12', defaultParams(), ctxIn());
      expect(result.mapExtent?.extent).toBeUndefined();
    });

    it('returns undefined when no map params are present', () => {
      const result = fromQuery('status=PUBLISHED', defaultParams(), ctxIn());
      expect(result.mapExtent).toBeUndefined();
    });
  });
});
