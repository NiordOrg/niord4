import { DefaultUrlSerializer, UrlTree } from '@angular/router';
import { describe, expect, it } from 'vitest';

import type { Domain } from '../../../core/domain/models/domain.model';
import { MAIN_TYPES } from '../../../shared/models/main-type.model';
import { MESSAGE_STATUSES } from '../../../shared/models/message-status.model';
import { MESSAGE_TYPES } from '../models/message.model';
import {
  COMMENTS_VALUES,
  DATE_TYPES,
  emptyEnabledMap,
  emptySliceValues,
  KNOWN_COMMENTS_VALUES,
  KNOWN_DATE_TYPES,
  KNOWN_SORT_BY_VALUES,
  KNOWN_SORT_ORDERS,
  REFERENCE_LEVELS_MAX,
  REFERENCE_LEVELS_MIN,
  SORT_BY_VALUES,
  SORT_ORDERS,
  USER_TYPES,
  type FilterName,
  type MapExtent,
  type MessagesFilterParams,
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

/**
 * Properties of the Messages filter's address, over generated filters from a fixed seed:
 * a bookmark, a shared link and a saved filter must give back the filter they were made
 * from. The round trip runs the way the application runs it: `toQuery`, the URL sync's map of
 * parameters (the reference filter renamed to `refMessageId`), Angular's own URL serializer
 * writing the address and reading it back, the sync's rename back, and `fromQuery`.
 */

/** A small seeded generator (mulberry32): the same seed gives the same inputs on every run. */
function seeded(seed: number) {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  const some = <T>(items: readonly T[], max = items.length): T[] =>
    [...items].sort(() => next() - 0.5).slice(0, int(1, Math.min(max, items.length)));
  return { next, int, pick, some };
}

type Rng = ReturnType<typeof seeded>;

/** What people type and what ids hold: letters, Danish letters, digits and every URL delimiter. */
const ALPHABET = [
  ...'abcxyzABCXYZæøåÆØÅéü0123456789',
  ' ',
  '  ',
  '&',
  '=',
  '+',
  '%',
  '%20',
  '#',
  '?',
  '/',
  ';',
  ',',
  ':',
  '@',
  '$',
  "'",
  '"',
  '(',
  ')',
  '*',
  '!',
  '~',
  '[',
  ']',
  '<',
  '>',
  '\\',
  '°',
  ' ',
  '🌊',
];

function text(rng: Rng, min = 1, max = 12): string {
  return Array.from({ length: rng.int(min, max) }, () => rng.pick(ALPHABET)).join('');
}

function ids(rng: Rng, max: number): string[] {
  return [...new Set(Array.from({ length: rng.int(1, max) }, () => text(rng, 1, 8)))];
}

const DOMAINS: readonly Domain[] = [
  { domainId: 'niord-nw', name: 'NW' },
  { domainId: 'niord-nm', name: 'NM' },
  { domainId: 'niord-fe', name: 'FE' },
];

/** A filter of whatever slices the seed turns on, each with a value (an empty slice writes nothing). */
function filter(
  rng: Rng,
  defaults: { sortBy: SortBy; sortOrder: SortOrder },
): MessagesFilterParams {
  const enabled = emptyEnabledMap();
  const slices = emptySliceValues();
  const on = (name: FilterName): boolean => {
    const turnOn = rng.next() < 0.4;
    if (turnOn) enabled[name] = true;
    return turnOn;
  };
  if (on('domain')) slices.domain = { domain: rng.pick(DOMAINS) };
  if (on('text')) slices.text = { query: text(rng, 1, 30) };
  if (on('type')) {
    const mainType = rng.next() < 0.5 ? rng.pick(MAIN_TYPES) : '';
    slices.type = {
      mainType,
      types: mainType === '' || rng.next() < 0.5 ? rng.some(MESSAGE_TYPES) : [],
    };
  }
  if (on('status')) slices.status = { statuses: rng.some(MESSAGE_STATUSES) };
  if (on('tag')) {
    slices.tag = {
      tags: ids(rng, 4).map((tagId) => ({
        tagId,
        name: tagId,
        type: 'PUBLIC',
        messageCount: 0,
        locked: false,
      })),
    };
  }
  if (on('publication')) {
    slices.publication = { publications: ids(rng, 3).map((publicationId) => ({ publicationId })) };
  }
  if (on('promulgationType')) {
    slices.promulgationType = { promulgationTypes: ids(rng, 3).map((typeId) => ({ typeId })) };
  }
  if (on('user')) {
    slices.user = {
      username: text(rng, 1, 10),
      userType: rng.next() < 0.7 ? rng.pick(USER_TYPES) : undefined,
    };
  }
  if (on('comments')) slices.comments = { value: rng.pick(COMMENTS_VALUES.slice(1)) };
  if (on('reference')) {
    slices.reference = {
      messageId: text(rng, 1, 12),
      referenceLevels: rng.int(REFERENCE_LEVELS_MIN, REFERENCE_LEVELS_MAX),
    };
  }
  if (on('aton')) slices.aton = { atonUids: ids(rng, 3) };
  if (on('chart')) slices.chart = { charts: ids(rng, 3).map((chartNumber) => ({ chartNumber })) };
  if (on('messageSeries')) {
    slices.messageSeries = { messageSeries: ids(rng, 3).map((seriesId) => ({ seriesId })) };
  }
  if (on('area'))
    slices.area = {
      areas: [...new Set(Array.from({ length: rng.int(1, 4) }, () => rng.int(1, 99999)))].map(
        (id) => ({ id }),
      ),
    };
  if (on('category')) {
    slices.category = {
      categories: [...new Set(Array.from({ length: rng.int(1, 4) }, () => rng.int(1, 99999)))].map(
        (id) => ({ id }),
      ),
    };
  }
  if (on('date')) {
    const instant = (): number => rng.int(-2_000_000_000_000, 4_000_000_000_000);
    const which = rng.int(0, 2);
    slices.date = {
      dateType: rng.pick(DATE_TYPES),
      fromDate: which !== 1 ? instant() : undefined,
      toDate: which !== 0 ? instant() : undefined,
    };
  }
  return {
    enabled,
    ...slices,
    sortBy: rng.next() < 0.5 ? defaults.sortBy : rng.pick(SORT_BY_VALUES),
    sortOrder: rng.next() < 0.5 ? defaults.sortOrder : rng.pick(SORT_ORDERS),
  };
}

function mapExtent(rng: Rng): MapExtent {
  const lon = (): number => Math.round((rng.next() * 360 - 180) * 1e4) / 1e4;
  const lat = (): number => Math.round((rng.next() * 170 - 85) * 1e4) / 1e4;
  return { zoom: rng.int(0, 18), center: [lon(), lat()], extent: [lon(), lat(), lon(), lat()] };
}

const SERIALIZER = new DefaultUrlSerializer();
const ROOT = SERIALIZER.parse('/messages/grid');

/**
 * What the address bar holds and what is read back from it, as `MessagesFilterUrlSync` does: the
 * query as a parameter map (the reference filter's `messageId` as `refMessageId`), Angular's URL
 * serializer both ways, and the parameter map read back into a query.
 */
function throughTheAddressBar(query: string): { address: string; read: URLSearchParams } {
  const search = new URLSearchParams(query);
  const params: Record<string, string | string[]> = {};
  for (const key of new Set(search.keys())) {
    const values = search.getAll(key);
    params[key === 'messageId' ? 'refMessageId' : key] = values.length === 1 ? values[0] : values;
  }
  const address = SERIALIZER.serialize(new UrlTree(ROOT.root, params));
  const map = SERIALIZER.parse(address).queryParamMap;
  const read = new URLSearchParams();
  for (const key of map.keys) {
    if (key === 'messageId') continue;
    for (const value of map.getAll(key))
      read.append(key === 'refMessageId' ? 'messageId' : key, value);
  }
  return { address, read };
}

function outbound(
  defaults: { sortBy: SortBy; sortOrder: SortOrder },
  map?: MapExtent,
): SerializerContext {
  return {
    defaultSortBy: defaults.sortBy,
    defaultSortOrder: defaults.sortOrder,
    mapExtent: map,
    isMap: map !== undefined,
    searchPublicMessages: false,
    isAuthenticated: true,
  };
}

const INBOUND: DeserializerContext = {
  isAuthenticated: true,
  resolveDomain: (id) => DOMAINS.find((domain) => domain.domainId === id),
};

/** What a filter searches for, by the identities the backend is sent: the comparison of a round trip. */
function searched(params: MessagesFilterParams): unknown {
  const e = params.enabled;
  return {
    domain: e.domain ? params.domain.domain?.domainId : undefined,
    text: e.text ? params.text.query : undefined,
    type: e.type ? [params.type.mainType, [...params.type.types]] : undefined,
    status: e.status ? [...params.status.statuses] : undefined,
    tag: e.tag ? params.tag.tags.map((t) => t.tagId) : undefined,
    publication: e.publication
      ? params.publication.publications.map((p) => p.publicationId)
      : undefined,
    promulgationType: e.promulgationType
      ? params.promulgationType.promulgationTypes.map((p) => p.typeId)
      : undefined,
    user: e.user ? [params.user.username, params.user.userType] : undefined,
    comments: e.comments ? params.comments.value : undefined,
    reference: e.reference
      ? [params.reference.messageId, params.reference.referenceLevels]
      : undefined,
    aton: e.aton ? [...params.aton.atonUids] : undefined,
    chart: e.chart ? params.chart.charts.map((c) => c.chartNumber) : undefined,
    messageSeries: e.messageSeries
      ? params.messageSeries.messageSeries.map((s) => s.seriesId)
      : undefined,
    area: e.area ? params.area.areas.map((a) => a.id) : undefined,
    category: e.category ? params.category.categories.map((c) => c.id) : undefined,
    date: e.date ? [params.date.dateType, params.date.fromDate, params.date.toDate] : undefined,
    sortBy: params.sortBy,
    sortOrder: params.sortOrder,
  };
}

const AREA_ASC = { sortBy: 'AREA', sortOrder: 'ASC' } as const;
const ID_DESC = { sortBy: 'ID', sortOrder: 'DESC' } as const;

describe('the Messages filter address, properties over generated filters', () => {
  it('gives back the same filter from a bookmark, through the address bar', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 3000; i++) {
      const defaults = rng.pick([AREA_ASC, ID_DESC]);
      const map = rng.next() < 0.3 ? mapExtent(rng) : undefined;
      const params = filter(rng, defaults);
      const query = toQuery(params, outbound(defaults, map));
      const { address, read } = throughTheAddressBar(query);
      const result = fromQuery(read, defaultParams(defaults), INBOUND);

      expect(searched(result.params), address).toEqual(searched(params));
      expect(result.pendingDomainId).toBeUndefined();
      expect(result.mapExtent, address).toEqual(map);
      // And the address of the filter read back is the same address.
      expect(toQuery(result.params, outbound(defaults, result.mapExtent))).toBe(query);
    }
  });

  it('reads any query without failing, into known values only', () => {
    const rng = seeded(20261116);
    const KEYS = [
      'domain',
      'query',
      'mainType',
      'type',
      'status',
      'tag',
      'comments',
      'messageId',
      'referenceLevels',
      'area',
      'category',
      'fromDate',
      'toDate',
      'dateType',
      'sortBy',
      'sortOrder',
      'zoom',
      'lon',
      'lat',
      'username',
      'userType',
    ];
    const VALUES = [
      ...MESSAGE_STATUSES,
      ...MESSAGE_TYPES,
      'NW',
      'xx',
      '',
      '-1',
      '7',
      '1e3',
      '0x10',
      ' 3',
      'NaN',
      'Infinity',
      'OWN',
      'AREA',
      'asc',
      'DESC',
      'niord-nw',
      'unknown-domain',
    ];
    for (let i = 0; i < 3000; i++) {
      const query = Array.from(
        { length: rng.int(0, 8) },
        () =>
          `${rng.pick(KEYS)}=${encodeURIComponent(rng.next() < 0.7 ? rng.pick(VALUES) : text(rng))}`,
      ).join('&');
      const { params } = fromQuery(query, defaultParams(), INBOUND);
      expect(
        params.status.statuses.every((s) => MESSAGE_STATUSES.includes(s)),
        query,
      ).toBe(true);
      expect(KNOWN_COMMENTS_VALUES.has(params.comments.value), query).toBe(true);
      expect(KNOWN_DATE_TYPES.has(params.date.dateType), query).toBe(true);
      expect(KNOWN_SORT_BY_VALUES.has(params.sortBy), query).toBe(true);
      expect(KNOWN_SORT_ORDERS.has(params.sortOrder), query).toBe(true);
      expect(params.reference.referenceLevels).toBeGreaterThanOrEqual(REFERENCE_LEVELS_MIN);
      expect(params.reference.referenceLevels).toBeLessThanOrEqual(REFERENCE_LEVELS_MAX);
      expect(
        params.area.areas.every((a) => Number.isInteger(a.id)),
        query,
      ).toBe(true);
      for (const date of [params.date.fromDate, params.date.toDate]) {
        if (date !== undefined) expect(Number.isFinite(date), query).toBe(true);
      }
    }
  });

  it("gives a reader whose domain sorts otherwise the reader's own order when the bookmark's order was its maker's default — by design, the documented contract (PF-2)", () => {
    // The order is written only when it differs from the maker's domain default, and read against
    // the reader's: the documented contract (fe/docs/features/messages.md, the address table and
    // "Domain-driven filter visibility"), as the old system did it. The smallest case: the maker's
    // domain sorts by area, ascending; the reader's by id, descending. The maker's list, sorted by
    // area, gives the reader a list sorted by id: the same messages, in the reader's default order.
    const made = { ...defaultParams(AREA_ASC), sortBy: 'AREA' as const, sortOrder: 'ASC' as const };
    const query = toQuery(made, outbound(AREA_ASC));
    expect(query).toBe('');
    const read = fromQuery(query, defaultParams(ID_DESC), INBOUND).params;
    expect([read.sortBy, read.sortOrder]).toEqual(['ID', 'DESC']);

    // Over generated filters: every one in its maker's default order reads back in the reader's.
    const rng = seeded(20261117);
    let differs = 0;
    let inDefault = 0;
    for (let i = 0; i < 1000; i++) {
      const params = filter(rng, AREA_ASC);
      const back = fromQuery(
        toQuery(params, outbound(AREA_ASC)),
        defaultParams(ID_DESC),
        INBOUND,
      ).params;
      const keptOrder = back.sortBy === params.sortBy && back.sortOrder === params.sortOrder;
      if (params.sortBy === 'AREA' || params.sortOrder === 'ASC') {
        inDefault++;
        if (!keptOrder) differs++;
      } else {
        expect(keptOrder).toBe(true);
      }
    }
    expect(inDefault).toBeGreaterThan(500);
    expect(differs).toBe(inDefault);
  });
});
