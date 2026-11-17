import type { Coordinate } from 'ol/coordinate';
import {
  GeometryCollection,
  LineString,
  MultiLineString,
  MultiPoint,
  MultiPolygon,
  Point,
  Polygon,
} from 'ol/geom';
import type Geometry from 'ol/geom/Geometry';

/**
 * The recursive geometry-tree model — engine-agnostic pure functions, no projection or
 * Angular deps:
 *
 * - `geometryToTree` turns an OL geometry (in the map projection) into a node tree.
 * - `treeToGeometry` rebuilds the OL geometry from a (possibly edited) node tree,
 *   re-closing polygon rings.
 * - `setCoordinateAt` replaces the coordinate of the position at a breadth-first index.
 *
 * Every position carries a breadth-first `coordIndex` (matching the on-map label index
 * and the `name:<coordIndex>:<lang>` keys); folder nodes carry the `[coordIndex,
 * coordIndex+coordCount)` span they cover (including the skipped closing ring vertex),
 * which the tree's add/delete + name-offset bookkeeping relies on.
 */
export type GeometryNodeType =
  | 'Point'
  | 'LineString'
  | 'Polygon'
  | 'MultiPoint'
  | 'MultiLineString'
  | 'MultiPolygon'
  | 'GeometryCollection'
  | 'Exterior'
  | 'Interior'
  | 'Position';

export interface TreeNode {
  readonly type: GeometryNodeType;
  /** Stable path key, e.g. `0.1.2`. */
  readonly key: string;
  /** Breadth-first index of the first position covered by this node. */
  readonly coordIndex: number;
  /** Number of coordinate slots this node spans (incl. skipped ring-closing vertices). */
  readonly coordCount: number;
  /** Set only on `Position` nodes — the coordinate in the map projection. */
  readonly coordinate?: Coordinate;
  readonly children: readonly TreeNode[];
}

function isRing(type: GeometryNodeType): boolean {
  return type === 'Exterior' || type === 'Interior';
}

/** Builds the `Position` children for a coordinate list (skipping a ring's closing vertex). */
function positionsToNodes(
  coords: readonly Coordinate[],
  parentType: GeometryNodeType,
  key: string,
  startIndex: number,
): { nodes: TreeNode[]; nextIndex: number } {
  const ring = isRing(parentType);
  const nodes: TreeNode[] = [];
  let coordIndex = startIndex;
  coords.forEach((coord, index) => {
    // A polygon ring repeats its first vertex as the last — don't show it, but still
    // consume its breadth-first index (it stays reserved for name bookkeeping).
    if (ring && index === coords.length - 1) {
      coordIndex++;
      return;
    }
    nodes.push({
      type: 'Position',
      key: `${key}.${index}`,
      coordIndex: coordIndex++,
      coordCount: 1,
      coordinate: coord,
      children: [],
    });
  });
  return { nodes, nextIndex: coordIndex };
}

/** Converts an OL geometry into the recursive tree, assigning breadth-first indices. */
export function geometryToTree(geometry: Geometry, key = '0', startIndex = 0): TreeNode {
  const type = geometry.getType() as GeometryNodeType;
  const children: TreeNode[] = [];
  let coordIndex = startIndex;

  switch (type) {
    case 'Point': {
      const res = positionsToNodes([(geometry as Point).getCoordinates()], type, key, coordIndex);
      children.push(...res.nodes);
      coordIndex = res.nextIndex;
      break;
    }
    case 'LineString': {
      const res = positionsToNodes(
        (geometry as LineString).getCoordinates(),
        type,
        key,
        coordIndex,
      );
      children.push(...res.nodes);
      coordIndex = res.nextIndex;
      break;
    }
    case 'Polygon': {
      const ringKeyBase = key.length > 0 ? `${key}.` : key;
      (geometry as Polygon).getLinearRings().forEach((ring, index) => {
        const ringType: GeometryNodeType = index === 0 ? 'Exterior' : 'Interior';
        const ringKey = ringKeyBase + index;
        const res = positionsToNodes(ring.getCoordinates(), ringType, ringKey, coordIndex);
        children.push({
          type: ringType,
          key: ringKey,
          coordIndex,
          coordCount: res.nextIndex - coordIndex,
          children: res.nodes,
        });
        coordIndex = res.nextIndex;
      });
      break;
    }
    case 'MultiPoint': {
      (geometry as MultiPoint).getPoints().forEach((point, index) => {
        const child = geometryToTree(point, `${key}.${index}`, coordIndex);
        children.push(child);
        coordIndex = child.coordIndex + child.coordCount;
      });
      break;
    }
    case 'MultiLineString': {
      (geometry as MultiLineString).getLineStrings().forEach((line, index) => {
        const child = geometryToTree(line, `${key}.${index}`, coordIndex);
        children.push(child);
        coordIndex = child.coordIndex + child.coordCount;
      });
      break;
    }
    case 'MultiPolygon': {
      (geometry as MultiPolygon).getPolygons().forEach((polygon, index) => {
        const child = geometryToTree(polygon, `${key}.${index}`, coordIndex);
        children.push(child);
        coordIndex = child.coordIndex + child.coordCount;
      });
      break;
    }
    case 'GeometryCollection': {
      (geometry as GeometryCollection).getGeometries().forEach((geom, index) => {
        const child = geometryToTree(geom, `${key}.${index}`, coordIndex);
        children.push(child);
        coordIndex = child.coordIndex + child.coordCount;
      });
      break;
    }
    default:
      break;
  }

  return {
    type,
    key,
    coordIndex: startIndex,
    coordCount: coordIndex - startIndex,
    children,
  };
}

/** Recursively reads the (possibly multi-dimensional) coordinate array from a node. */
function readCoordinates(
  node: TreeNode,
): Coordinate | Coordinate[] | Coordinate[][] | Coordinate[][][] {
  if (node.type === 'Position') {
    return node.coordinate as Coordinate;
  }
  if (node.type === 'Point') {
    return readCoordinates(node.children[0]);
  }
  const coords = node.children.map((child) => readCoordinates(child)) as Coordinate[];
  // Polygon rings repeat their first vertex as the last.
  if (isRing(node.type) && node.children.length > 0) {
    coords.push(readCoordinates(node.children[0]) as Coordinate);
  }
  return coords;
}

/** Rebuilds an OL geometry from a (possibly edited) node tree. */
export function treeToGeometry(node: TreeNode): Geometry {
  switch (node.type) {
    case 'Point':
      return new Point(readCoordinates(node) as Coordinate);
    case 'LineString':
      return new LineString(readCoordinates(node) as Coordinate[]);
    case 'Polygon':
      return new Polygon(readCoordinates(node) as Coordinate[][]);
    case 'MultiPoint':
      return new MultiPoint(readCoordinates(node) as Coordinate[]);
    case 'MultiLineString':
      return new MultiLineString(readCoordinates(node) as Coordinate[][]);
    case 'MultiPolygon':
      return new MultiPolygon(readCoordinates(node) as Coordinate[][][]);
    case 'GeometryCollection':
      return new GeometryCollection(node.children.map((child) => treeToGeometry(child)));
    default:
      throw new Error(`Cannot rebuild geometry from a ${node.type} node`);
  }
}

/** Returns a new tree with the position at `coordIndex` moved to `coordinate`. */
export function setCoordinateAt(
  node: TreeNode,
  coordIndex: number,
  coordinate: Coordinate,
): TreeNode {
  if (node.type === 'Position') {
    return node.coordIndex === coordIndex ? { ...node, coordinate } : node;
  }
  return {
    ...node,
    children: node.children.map((child) => setCoordinateAt(child, coordIndex, coordinate)),
  };
}

/** Collects every position's `coordIndex → coordinate` (for on-map per-coordinate labels). */
export function positionCoordinatesByIndex(
  node: TreeNode,
  out = new Map<number, Coordinate>(),
): Map<number, Coordinate> {
  if (node.type === 'Position') {
    if (node.coordinate) out.set(node.coordIndex, node.coordinate);
  } else {
    for (const child of node.children) {
      positionCoordinatesByIndex(child, out);
    }
  }
  return out;
}

/** Finds the node with `key` (depth-first), or undefined. */
export function findNodeByKey(node: TreeNode, key: string): TreeNode | undefined {
  if (node.key === key) return node;
  for (const child of node.children) {
    const found = findNodeByKey(child, key);
    if (found) return found;
  }
  return undefined;
}

/**
 * Whether `node` may be deleted, given its parent's type + child count. A Position needs its
 * line/ring to keep the minimum vertices (LineString ≥ 2, polygon ring ≥ 3 visible); a folder is
 * deletable when it has a sibling.
 */
export function isDeletableNode(
  node: TreeNode,
  parentType: GeometryNodeType,
  siblingCount: number,
): boolean {
  if (node.type === 'Position') {
    if (parentType === 'Point') return false;
    if (parentType === 'LineString') return siblingCount > 2;
    if (parentType === 'Exterior' || parentType === 'Interior') return siblingCount > 3;
    return false;
  }
  return siblingCount > 1;
}

/**
 * Returns a new tree with the node `key` removed from its parent. Indices are not
 * recomputed — rebuild the geometry with `treeToGeometry` and re-derive with
 * `geometryToTree` to renumber.
 */
export function removeNodeByKey(node: TreeNode, key: string): TreeNode {
  return {
    ...node,
    children: node.children
      .filter((child) => child.key !== key)
      .map((child) => removeNodeByKey(child, key)),
  };
}

/**
 * Returns a new tree with a position inserted (with `coordinate`) immediately after the
 * `Position` node `afterKey`. Indices are not recomputed (re-derive after rebuild).
 */
export function insertPositionAfter(
  node: TreeNode,
  afterKey: string,
  coordinate: Coordinate,
): TreeNode {
  const children: TreeNode[] = [];
  for (const child of node.children) {
    children.push(insertPositionAfter(child, afterKey, coordinate));
    if (child.key === afterKey && child.type === 'Position') {
      children.push({
        type: 'Position',
        key: `${child.key}+`,
        coordIndex: -1,
        coordCount: 1,
        coordinate,
        children: [],
      });
    }
  }
  return { ...node, children };
}

function midpoint(a: Coordinate, b: Coordinate): Coordinate {
  return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
}

/**
 * The coordinate (map projection) for a new vertex inserted after the position `afterKey`:
 * the midpoint to the next vertex, wrapping to the ring's first vertex at a ring's end, or
 * extending the last line segment for a line's end. Returns undefined if `afterKey` is not
 * a position. The user edits it immediately, so this is just a sensible, visible default.
 */
export function computeInsertCoordinate(node: TreeNode, afterKey: string): Coordinate | undefined {
  for (let i = 0; i < node.children.length; i++) {
    const child = node.children[i];
    if (child.key === afterKey && child.type === 'Position' && child.coordinate) {
      const next = node.children[i + 1];
      if (next?.coordinate) return midpoint(child.coordinate, next.coordinate);
      if (isRing(node.type) && node.children[0]?.coordinate) {
        return midpoint(child.coordinate, node.children[0].coordinate);
      }
      const prev = node.children[i - 1];
      if (prev?.coordinate) {
        return [
          child.coordinate[0] + (child.coordinate[0] - prev.coordinate[0]),
          child.coordinate[1] + (child.coordinate[1] - prev.coordinate[1]),
        ];
      }
      return [child.coordinate[0] + 1000, child.coordinate[1]];
    }
  }
  for (const child of node.children) {
    const found = computeInsertCoordinate(child, afterKey);
    if (found) return found;
  }
  return undefined;
}
