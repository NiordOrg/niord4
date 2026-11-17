import {
  GeometryCollection,
  LineString,
  MultiLineString,
  MultiPoint,
  Point,
  Polygon,
} from 'ol/geom';
import { describe, expect, it } from 'vitest';

import {
  computeInsertCoordinate,
  findNodeByKey,
  geometryToTree,
  insertPositionAfter,
  isDeletableNode,
  removeNodeByKey,
  setCoordinateAt,
  type TreeNode,
  treeToGeometry,
} from './geometry-tree-model';

function positions(node: TreeNode): TreeNode[] {
  if (node.type === 'Position') return [node];
  return node.children.flatMap((child) => positions(child));
}

function allKeys(node: TreeNode): string[] {
  return [node.key, ...node.children.flatMap((child) => allKeys(child))];
}

describe('geometry-tree-model', () => {
  describe('geometryToTree', () => {
    it('builds a Point with a single Position', () => {
      const tree = geometryToTree(new Point([10, 20]));
      expect(tree.type).toBe('Point');
      expect(positions(tree)).toHaveLength(1);
      expect(positions(tree)[0].coordinate).toEqual([10, 20]);
    });

    it('builds a LineString with one Position per vertex and breadth-first indices', () => {
      const tree = geometryToTree(
        new LineString([
          [0, 0],
          [1, 1],
          [2, 2],
        ]),
      );
      const pos = positions(tree);
      expect(pos).toHaveLength(3);
      expect(pos.map((p) => p.coordIndex)).toEqual([0, 1, 2]);
    });

    it('splits a Polygon into Exterior + Interior rings and hides the closing vertex', () => {
      const tree = geometryToTree(
        new Polygon([
          [
            [0, 0],
            [4, 0],
            [4, 4],
            [0, 4],
            [0, 0],
          ],
          [
            [1, 1],
            [2, 1],
            [2, 2],
            [1, 1],
          ],
        ]),
      );
      expect(tree.children.map((c) => c.type)).toEqual(['Exterior', 'Interior']);
      // Exterior shows 4 of its 5 (closing hidden); interior shows 3 of 4.
      expect(positions(tree.children[0])).toHaveLength(4);
      expect(positions(tree.children[1])).toHaveLength(3);
      // The closing vertex still consumes an index (exterior spans 0..4, interior 5..7).
      expect(tree.children[0].coordCount).toBe(5);
      expect(tree.children[1].coordIndex).toBe(5);
    });

    it('numbers a GeometryCollection breadth-first across its members', () => {
      const tree = geometryToTree(
        new GeometryCollection([
          new Point([0, 0]),
          new LineString([
            [1, 1],
            [2, 2],
          ]),
        ]),
      );
      expect(positions(tree).map((p) => p.coordIndex)).toEqual([0, 1, 2]);
    });

    it('assigns globally-unique keys across nested multi-geometries', () => {
      const members: (Point | MultiPoint)[] = [];
      for (let i = 0; i < 12; i++) {
        members.push(
          i === 1
            ? new MultiPoint([
                [0, 0],
                [1, 1],
              ])
            : new Point([i, i]),
        );
      }
      const keys = allKeys(geometryToTree(new GeometryCollection(members)));
      expect(new Set(keys).size).toBe(keys.length);
    });
  });

  describe('treeToGeometry round-trips', () => {
    it('round-trips a LineString', () => {
      const line = new LineString([
        [0, 0],
        [1, 1],
        [2, 3],
      ]);
      const back = treeToGeometry(geometryToTree(line)) as LineString;
      expect(back.getCoordinates()).toEqual(line.getCoordinates());
    });

    it('round-trips a Polygon and restores the closing vertex', () => {
      const polygon = new Polygon([
        [
          [0, 0],
          [4, 0],
          [4, 4],
          [0, 0],
        ],
      ]);
      const back = treeToGeometry(geometryToTree(polygon)) as Polygon;
      expect(back.getCoordinates()).toEqual(polygon.getCoordinates());
    });

    it('round-trips a MultiLineString', () => {
      const multi = new MultiLineString([
        [
          [0, 0],
          [1, 1],
        ],
        [
          [2, 2],
          [3, 3],
        ],
      ]);
      const back = treeToGeometry(geometryToTree(multi)) as MultiLineString;
      expect(back.getCoordinates()).toEqual(multi.getCoordinates());
    });
  });

  describe('setCoordinateAt', () => {
    it('replaces the coordinate of the position at the given index', () => {
      const tree = geometryToTree(
        new LineString([
          [0, 0],
          [1, 1],
          [2, 2],
        ]),
      );
      const edited = treeToGeometry(setCoordinateAt(tree, 1, [9, 9])) as LineString;
      expect(edited.getCoordinates()).toEqual([
        [0, 0],
        [9, 9],
        [2, 2],
      ]);
    });

    it('editing the first ring vertex also moves the closing vertex', () => {
      const tree = geometryToTree(
        new Polygon([
          [
            [0, 0],
            [4, 0],
            [4, 4],
            [0, 0],
          ],
        ]),
      );
      const edited = treeToGeometry(setCoordinateAt(tree, 0, [1, 1])) as Polygon;
      const ring = edited.getCoordinates()[0];
      expect(ring[0]).toEqual([1, 1]);
      expect(ring[ring.length - 1]).toEqual([1, 1]);
    });
  });

  describe('isDeletableNode (min-vertex rules)', () => {
    const pos: TreeNode = {
      type: 'Position',
      key: 'p',
      coordIndex: 0,
      coordCount: 1,
      children: [],
    };

    it('never deletes a Point position', () => {
      expect(isDeletableNode(pos, 'Point', 1)).toBe(false);
    });
    it('keeps a LineString at ≥ 2 vertices', () => {
      expect(isDeletableNode(pos, 'LineString', 3)).toBe(true);
      expect(isDeletableNode(pos, 'LineString', 2)).toBe(false);
    });
    it('keeps a polygon ring at ≥ 3 visible vertices', () => {
      expect(isDeletableNode(pos, 'Exterior', 4)).toBe(true);
      expect(isDeletableNode(pos, 'Exterior', 3)).toBe(false);
    });
    it('deletes a folder only when it has a sibling', () => {
      const ring: TreeNode = {
        type: 'Interior',
        key: 'r',
        coordIndex: 0,
        coordCount: 0,
        children: [],
      };
      expect(isDeletableNode(ring, 'Polygon', 2)).toBe(true);
      expect(isDeletableNode(ring, 'Polygon', 1)).toBe(false);
    });
  });

  describe('add / delete vertex (rebuild + re-derive)', () => {
    function line() {
      return new LineString([
        [0, 0],
        [2, 0],
        [4, 0],
      ]);
    }

    it('deletes a vertex by key and the rebuilt geometry drops it', () => {
      const tree = geometryToTree(line());
      const middle = findNodeByKey(tree, '0.1');
      expect(middle?.type).toBe('Position');
      const rebuilt = treeToGeometry(removeNodeByKey(tree, '0.1')) as LineString;
      expect(rebuilt.getCoordinates()).toEqual([
        [0, 0],
        [4, 0],
      ]);
    });

    it('inserts a midpoint vertex after a position', () => {
      const tree = geometryToTree(line());
      const coord = computeInsertCoordinate(tree, '0.0');
      expect(coord).toEqual([1, 0]); // midpoint of [0,0] and [2,0]
      const rebuilt = treeToGeometry(insertPositionAfter(tree, '0.0', coord!)) as LineString;
      expect(rebuilt.getCoordinates()).toEqual([
        [0, 0],
        [1, 0],
        [2, 0],
        [4, 0],
      ]);
    });

    it('wraps to the ring start when inserting after a ring last vertex', () => {
      const tree = geometryToTree(
        new Polygon([
          [
            [0, 0],
            [4, 0],
            [4, 4],
            [0, 0],
          ],
        ]),
      );
      // Exterior visible positions are 0.0(0,0) 0.0.1? keys depend on build; use last visible.
      const exterior = tree.children[0];
      const lastVisible = exterior.children[exterior.children.length - 1];
      const coord = computeInsertCoordinate(tree, lastVisible.key);
      // midpoint of last visible [4,4] and ring-first [0,0]
      expect(coord).toEqual([2, 2]);
    });
  });
});
