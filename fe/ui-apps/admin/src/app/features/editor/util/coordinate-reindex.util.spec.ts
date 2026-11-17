import { describe, expect, it } from 'vitest';

import type { GeometryFeature } from '../../../shared/models/geometry.model';
import type { MessagePart } from '../../messages/models/message.model';
import { reindexStartCoordIndices } from './coordinate-reindex.util';

// A test counter: each feature declares its readable-coordinate count via a `coordCount` property,
// so the spec exercises the counter threading without depending on the real geometry serializer.
const countCoords = (feature: GeometryFeature): number =>
  (feature.properties?.['coordCount'] as number | undefined) ?? 0;

function feature(coordCount: number): GeometryFeature {
  return {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [0, 0] },
    properties: { coordCount },
  };
}

function part(...features: GeometryFeature[]): MessagePart {
  return { type: 'DETAILS', geometry: { type: 'FeatureCollection', features } };
}

/** The stamped `startCoordIndex` per feature per part (undefined = feature was left unstamped). */
function starts(parts: readonly MessagePart[]): (number | undefined)[][] {
  return parts.map((p) =>
    (p.geometry?.features ?? []).map(
      (f) => f.properties?.['startCoordIndex'] as number | undefined,
    ),
  );
}

describe('reindexStartCoordIndices', () => {
  it('seeds at 1 and accumulates by each feature coordinate count within a part', () => {
    const result = reindexStartCoordIndices(
      [part(feature(2), feature(5), feature(1))],
      countCoords,
    );
    expect(starts(result)).toEqual([[1, 3, 8]]);
  });

  it('threads one running counter continuously across all parts', () => {
    const result = reindexStartCoordIndices(
      [part(feature(3), feature(2)), part(feature(1)), part(feature(4))],
      countCoords,
    );
    // 3 + 2 in part 0 → part 1 starts at 6 → part 2 starts at 7.
    expect(starts(result)).toEqual([[1, 4], [6], [7]]);
  });

  it('editing part 1 shifts part 2 numbering (continuity is message-wide, not per-part)', () => {
    const before = reindexStartCoordIndices([part(feature(3)), part(feature(2))], countCoords);
    expect(starts(before)).toEqual([[1], [4]]);

    // Grow part 0's feature from 3 to 5 coordinates — part 1 must shift from 4 to 6.
    const after = reindexStartCoordIndices([part(feature(5)), part(feature(2))], countCoords);
    expect(starts(after)).toEqual([[1], [6]]);
  });

  it('reordering parts re-threads the numbering to follow the new order (move parity)', () => {
    const original = [part(feature(2)), part(feature(3))];
    expect(starts(reindexStartCoordIndices(original, countCoords))).toEqual([[1], [3]]);

    const swapped = [part(feature(3)), part(feature(2))];
    expect(starts(reindexStartCoordIndices(swapped, countCoords))).toEqual([[1], [4]]);
  });

  it('inserting an empty part keeps the surrounding numbering continuous (add parity)', () => {
    const result = reindexStartCoordIndices(
      [part(feature(2)), part(), part(feature(3))],
      countCoords,
    );
    // The empty middle part neither stamps nor advances — part 2 still follows part 0.
    expect(starts(result)).toEqual([[1], [], [3]]);
  });

  it('a zero-coordinate feature is neither stamped nor advances the counter (affected / buffered)', () => {
    const result = reindexStartCoordIndices(
      [part(feature(3), feature(0), feature(2))],
      countCoords,
    );
    expect(starts(result)).toEqual([[1, undefined, 4]]);
  });

  it('leaves a geometry-less or empty part by reference (nothing to stamp)', () => {
    const emptyPart = part();
    const noGeometry: MessagePart = { type: 'DETAILS' };
    const result = reindexStartCoordIndices([emptyPart, noGeometry], countCoords);
    expect(result[0]).toBe(emptyPart);
    expect(result[1]).toBe(noGeometry);
  });

  it('is pure — it never mutates the input parts or features', () => {
    const input = feature(3);
    const parts = [part(input)];
    const result = reindexStartCoordIndices(parts, countCoords);

    expect(input.properties?.['startCoordIndex']).toBeUndefined();
    expect(result[0]).not.toBe(parts[0]);
    expect(result[0].geometry?.features[0]).not.toBe(input);
    expect(result[0].geometry?.features[0].properties?.['startCoordIndex']).toBe(1);
  });
});
