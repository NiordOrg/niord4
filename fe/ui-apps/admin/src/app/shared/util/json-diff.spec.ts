import { describe, expect, it } from 'vitest';

import { diffJson } from './json-diff';

describe('diffJson', () => {
  it('marks a self-compare as entirely unchanged (1-selected case)', () => {
    const value = { x: 1, y: { z: 'q' }, list: [1, 2] };
    expect(diffJson(value, value).kind).toBe('unchanged');
  });

  it('marks a changed leaf with old + new values', () => {
    const child = diffJson({ x: 1 }, { x: 2 }).children![0];
    expect(child.kind).toBe('changed');
    expect(child.oldValue).toBe('1');
    expect(child.newValue).toBe('2');
  });

  it('marks an added key as added (new value only)', () => {
    const child = diffJson({}, { x: 1 }).children![0];
    expect(child.kind).toBe('added');
    expect(child.newValue).toBe('1');
    expect(child.oldValue).toBeUndefined();
  });

  it('marks a removed key as removed (old value only)', () => {
    const child = diffJson({ x: 1 }, {}).children![0];
    expect(child.kind).toBe('removed');
    expect(child.oldValue).toBe('1');
    expect(child.newValue).toBeUndefined();
  });

  it('a container is changed when any child differs, unchanged otherwise', () => {
    const node = diffJson({ a: 1, b: 2 }, { a: 1, b: 3 });
    expect(node.kind).toBe('changed');
    expect(node.children!.find((c) => c.label === 'a')!.kind).toBe('unchanged');
    expect(node.children!.find((c) => c.label === 'b')!.kind).toBe('changed');
  });

  it('diffs arrays by index including added trailing elements', () => {
    const node = diffJson([1, 2], [1, 2, 3]);
    expect(node.children!.length).toBe(3);
    expect(node.children![2].kind).toBe('added');
    expect(node.children![2].newValue).toBe('3');
  });

  it('builds a whole added subtree for a nested added object', () => {
    const added = diffJson({}, { obj: { a: 1 } }).children![0];
    expect(added.kind).toBe('added');
    expect(added.children![0].kind).toBe('added');
    expect(added.children![0].newValue).toBe('1');
  });

  it('treats null as a leaf and a type change as changed', () => {
    expect(diffJson({ a: null }, { a: null }).kind).toBe('unchanged');
    const changed = diffJson({ a: 1 }, { a: { nested: true } }).children![0];
    expect(changed.kind).toBe('changed');
  });
});
