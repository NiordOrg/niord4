import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import type { GeometryFeatureCollection } from '../../../shared/models/geometry.model';
import type { AreaRef } from '../models/messages-filter-params.model';
import { AreasApiService } from './api/areas-api.service';
import { MessagesAreaGeometrySource } from './area-geometry-source';

const REF = { id: 'a1', name: 'Area 1' } as unknown as AreaRef;
const COLLECTION = {
  type: 'FeatureCollection',
  features: [],
} as unknown as GeometryFeatureCollection;

function setup(opts: { fetchError?: boolean; fetched?: AreaRef[] } = {}) {
  const getRoots = vi.fn(() => of([REF]));
  const getByIds = vi.fn(() =>
    opts.fetchError ? throwError(() => new Error('boom')) : of(opts.fetched ?? [REF]),
  );
  const areaToFeatureCollection = vi.fn(() => COLLECTION);
  const areasApi = { getRoots, getByIds, areaToFeatureCollection } as unknown as AreasApiService;

  TestBed.configureTestingModule({
    providers: [MessagesAreaGeometrySource, { provide: AreasApiService, useValue: areasApi }],
  });
  return {
    source: TestBed.inject(MessagesAreaGeometrySource),
    getRoots,
    getByIds,
    areaToFeatureCollection,
  };
}

describe('MessagesAreaGeometrySource', () => {
  it('getRoots delegates to the areas API', () => {
    const { source, getRoots } = setup();
    const next = vi.fn();
    source.getRoots().subscribe(next);
    expect(getRoots).toHaveBeenCalled();
    expect(next).toHaveBeenCalledWith([REF]);
  });

  it('getGeometry re-fetches the area with geometry + all languages, then maps the result', () => {
    const fetched = { id: 'a1', name: 'Area 1 (full)' } as unknown as AreaRef;
    const { source, getByIds, areaToFeatureCollection } = setup({ fetched: [fetched] });
    let result: GeometryFeatureCollection | undefined;
    source.getGeometry(REF).subscribe((r) => (result = r));

    expect(getByIds).toHaveBeenCalledWith(['a1'], { includeGeometry: true, allLanguages: true });
    expect(areaToFeatureCollection).toHaveBeenCalledWith(fetched);
    expect(result).toBe(COLLECTION);
  });

  it('falls back to the tree ref (no error surfaced) when the geometry fetch fails', () => {
    const { source, areaToFeatureCollection } = setup({ fetchError: true });
    let result: GeometryFeatureCollection | undefined;
    const error = vi.fn();
    source.getGeometry(REF).subscribe({ next: (r) => (result = r), error });

    expect(error).not.toHaveBeenCalled();
    expect(areaToFeatureCollection).toHaveBeenCalledWith(REF);
    expect(result).toBe(COLLECTION);
  });

  it('uses the tree ref when the fetch returns an empty array', () => {
    const { source, areaToFeatureCollection } = setup({ fetched: [] });
    source.getGeometry(REF).subscribe();
    expect(areaToFeatureCollection).toHaveBeenCalledWith(REF);
  });
});
