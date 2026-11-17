import { describe, expect, it } from 'vitest';

import {
  MESSAGE_SORT_ORDER_OPTIONS,
  buildDomainPayload,
  timeZoneOptions,
  type DomainFormValue,
} from './domain-rules';

const FULL: DomainFormValue = {
  domainId: 'niord-nw',
  active: true,
  sortOrder: 3,
  name: 'Warnings',
  timeZone: 'Europe/Copenhagen',
  lat: 56.13,
  lon: 11.28,
  zoomLevel: 6,
  messageSortOrder: 'AREA ASC',
  color: '#ff0000',
  areas: [
    { id: 1, mrn: 'urn:mrn:x', active: true, descs: [{ lang: 'en', name: 'Kattegat' }] },
    { id: 2, descs: [{ lang: 'en', name: 'Skagerrak' }] },
  ],
  categories: [{ id: 7, active: false, descs: [{ lang: 'en', name: 'Firing Exercises' }] }],
  messageSeries: [
    { seriesId: 'dma-nw', mainType: 'NW', types: ['COASTAL_WARNING'], name: 'NW series' },
    { seriesId: 'dma-fa' },
  ],
  publish: true,
  atons: false,
  templates: true,
};

const BLANK: DomainFormValue = {
  domainId: 'niord-new',
  active: true,
  sortOrder: null,
  name: '',
  timeZone: '',
  lat: null,
  lon: null,
  zoomLevel: null,
  messageSortOrder: '',
  color: '',
  areas: [],
  categories: [],
  messageSeries: [],
  publish: false,
  atons: false,
  templates: false,
};

describe('buildDomainPayload', () => {
  it('reduces the picked areas/categories/series to minimal {id} / {seriesId} refs', () => {
    const payload = buildDomainPayload(FULL);
    expect(payload.areas).toEqual([{ id: 1 }, { id: 2 }]);
    expect(payload.categories).toEqual([{ id: 7 }]);
    expect(payload.messageSeries).toEqual([{ seriesId: 'dma-nw' }, { seriesId: 'dma-fa' }]);
  });

  it('never carries the backend-derived read-only fields', () => {
    const payload = buildDomainPayload(FULL);
    expect('inKeycloak' in payload).toBe(false);
    expect('firingSchedule' in payload).toBe(false);
  });

  it('keeps the scalars the form holds', () => {
    const payload = buildDomainPayload(FULL);
    expect(payload).toMatchObject({
      domainId: 'niord-nw',
      active: true,
      sortOrder: 3,
      name: 'Warnings',
      timeZone: 'Europe/Copenhagen',
      lat: 56.13,
      lon: 11.28,
      zoomLevel: 6,
      messageSortOrder: 'AREA ASC',
      color: '#ff0000',
      publish: true,
      atons: false,
      templates: true,
    });
  });

  it('omits blank optional strings and cleared numbers (the backend nulls blanks)', () => {
    const body = JSON.parse(JSON.stringify(buildDomainPayload(BLANK))) as Record<string, unknown>;
    for (const field of [
      'name',
      'timeZone',
      'lat',
      'lon',
      'zoomLevel',
      'messageSortOrder',
      'color',
    ]) {
      expect(field in body, field).toBe(false);
    }
    expect(body['areas']).toEqual([]);
    expect(body['categories']).toEqual([]);
    expect(body['messageSeries']).toEqual([]);
  });

  it('passes an unknown persisted messageSortOrder through untouched (no forcing)', () => {
    const payload = buildDomainPayload({ ...BLANK, messageSortOrder: 'LEGACY ODD' });
    expect(payload.messageSortOrder).toBe('LEGACY ODD');
  });
});

describe('timeZoneOptions', () => {
  it('offers the canonical Intl zone list', () => {
    const zones = timeZoneOptions(undefined);
    expect(zones).toEqual(Intl.supportedValuesOf('timeZone'));
    expect(zones).toContain('Europe/Copenhagen');
  });

  it('prepends a persisted zone missing from the list (alias/deprecated) so it survives an untouched save', () => {
    const zones = timeZoneOptions('Custom/Legacy_Zone');
    expect(zones[0]).toBe('Custom/Legacy_Zone');
    expect(zones.slice(1)).toEqual(Intl.supportedValuesOf('timeZone'));
  });

  it('does not duplicate a persisted zone that is already offered', () => {
    const zones = timeZoneOptions('Europe/Copenhagen');
    expect(zones.filter((zone) => zone === 'Europe/Copenhagen')).toHaveLength(1);
  });

  it('adds no blank entry for a blank persisted value (blank renders empty, not offerable)', () => {
    expect(timeZoneOptions('')).not.toContain('');
    expect(timeZoneOptions(undefined)).not.toContain('');
  });
});

describe('MESSAGE_SORT_ORDER_OPTIONS', () => {
  it('carries the six exact message-sort-order option values', () => {
    expect(MESSAGE_SORT_ORDER_OPTIONS.map((option) => option.value)).toEqual([
      'AREA ASC',
      'AREA DESC',
      'ID ASC',
      'ID DESC',
      'DATE ASC',
      'DATE DESC',
    ]);
  });
});
