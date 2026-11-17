/**
 * Self-contained recursive JSON diff (no external dependency). Treats both inputs as OPAQUE
 * parsed JSON and produces a renderable tree where each node is `unchanged` / `changed` /
 * `added` / `removed`. Comparing a value to itself yields an all-`unchanged` tree (the
 * 1-selected "show data, no highlights" case).
 */
export type DiffKind = 'unchanged' | 'changed' | 'added' | 'removed';

export interface DiffNode {
  /** Stable path (root/key/index …) — used as the collapse-state key. */
  readonly path: string;
  /** The object key or array index this node represents. */
  readonly label: string;
  readonly kind: DiffKind;
  /** Rendered leaf value on the left/old side (for `changed` / `removed`). */
  readonly oldValue?: string;
  /** Rendered leaf value on the right/new side (for `changed` / `added` / `unchanged`). */
  readonly newValue?: string;
  /** Child nodes for object/array containers; absent for leaves. */
  readonly children?: readonly DiffNode[];
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function formatLeaf(value: unknown): string {
  if (value === undefined) {
    return '';
  }
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'string') {
    return value;
  }
  return JSON.stringify(value);
}

/** Build a one-sided subtree (every node `added` or `removed`) for a key present on only one side. */
function singleSided(
  value: unknown,
  label: string,
  path: string,
  kind: 'added' | 'removed',
): DiffNode {
  if (isPlainObject(value)) {
    return {
      path,
      label,
      kind,
      children: Object.keys(value).map((key) =>
        singleSided(value[key], key, `${path}/${key}`, kind),
      ),
    };
  }
  if (Array.isArray(value)) {
    return {
      path,
      label,
      kind,
      children: value.map((item, index) =>
        singleSided(item, String(index), `${path}/${index}`, kind),
      ),
    };
  }
  return kind === 'added'
    ? { path, label, kind, newValue: formatLeaf(value) }
    : { path, label, kind, oldValue: formatLeaf(value) };
}

function containerKind(children: readonly DiffNode[]): DiffKind {
  return children.every((child) => child.kind === 'unchanged') ? 'unchanged' : 'changed';
}

/** Diff two parsed JSON values into a renderable tree. `label`/`path` seed the root node. */
export function diffJson(left: unknown, right: unknown, label = 'root', path = 'root'): DiffNode {
  if (left === undefined) {
    return singleSided(right, label, path, 'added');
  }
  if (right === undefined) {
    return singleSided(left, label, path, 'removed');
  }

  if (isPlainObject(left) && isPlainObject(right)) {
    const keys = Array.from(new Set([...Object.keys(left), ...Object.keys(right)]));
    const children = keys.map((key) => diffJson(left[key], right[key], key, `${path}/${key}`));
    return { path, label, kind: containerKind(children), children };
  }

  if (Array.isArray(left) && Array.isArray(right)) {
    const length = Math.max(left.length, right.length);
    const children: DiffNode[] = [];
    for (let index = 0; index < length; index++) {
      children.push(diffJson(left[index], right[index], String(index), `${path}/${index}`));
    }
    return { path, label, kind: containerKind(children), children };
  }

  // Leaf: both primitives, or a structural-vs-primitive mismatch (an opaque-snapshot change).
  return left === right
    ? { path, label, kind: 'unchanged', newValue: formatLeaf(right) }
    : { path, label, kind: 'changed', oldValue: formatLeaf(left), newValue: formatLeaf(right) };
}
