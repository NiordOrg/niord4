import { TestBed } from '@angular/core/testing';
import { fromLonLat } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import { type CoordinateEdit, GeometryTreeNode } from './geometry-tree-node';
import type { TreeNode } from './geometry-tree-model';

interface NodeHandle {
  startEdit(): void;
  commit(value: string): void;
  editing(): boolean;
  editError(): boolean;
  expanded(): boolean;
  toggleExpanded(): void;
}

const positionNode: TreeNode = {
  type: 'Position',
  key: '0.0',
  coordIndex: 0,
  coordCount: 1,
  coordinate: fromLonLat([11, 56]),
  children: [],
};

function folderNode(coordCount: number): TreeNode {
  return { type: 'LineString', key: '0', coordIndex: 0, coordCount, children: [] };
}

function setup(node: TreeNode = positionNode) {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(GeometryTreeNode);
  fixture.componentRef.setInput('node', node);
  fixture.detectChanges();
  const emitted: CoordinateEdit[] = [];
  fixture.componentInstance.coordinateEdit.subscribe((edit) => emitted.push(edit));
  return { fixture, view: fixture.componentInstance as unknown as NodeHandle, emitted };
}

describe('GeometryTreeNode inline edit', () => {
  it('commits a valid coordinate and emits the parsed lat/lon', () => {
    const { view, emitted } = setup();
    view.startEdit();
    view.commit("57° 00.000'N - 010° 30.000'E");

    expect(emitted).toHaveLength(1);
    expect(emitted[0].coordIndex).toBe(0);
    expect(emitted[0].lat).toBeCloseTo(57, 5);
    expect(emitted[0].lon).toBeCloseTo(10.5, 5);
    expect(view.editing()).toBe(false);
    expect(view.editError()).toBe(false);
  });

  it('flags ONLY this position on an invalid value and keeps it in edit mode (enhancement #1)', () => {
    const { view, emitted } = setup();
    view.startEdit();
    view.commit('not a coordinate');

    expect(emitted).toHaveLength(0);
    expect(view.editError()).toBe(true);
    expect(view.editing()).toBe(true);
  });

  it('flags an impossible position (minutes of 60 or more) and emits no edit', () => {
    const { view, emitted } = setup();
    view.startEdit();
    view.commit('56 75.0N - 009 17.6E');

    expect(emitted).toHaveLength(0);
    expect(view.editError()).toBe(true);
    expect(view.editing()).toBe(true);
  });

  it('clears the error once a subsequent valid value is committed', () => {
    const { view } = setup();
    view.startEdit();
    view.commit('garbage');
    expect(view.editError()).toBe(true);
    view.commit("57° 00.000'N - 010° 30.000'E");
    expect(view.editError()).toBe(false);
    expect(view.editing()).toBe(false);
  });

  it('drops an open edit when the bound vertex shifts underneath it (key-reuse guard)', () => {
    const { fixture, view } = setup();
    view.startEdit();
    expect(view.editing()).toBe(true);
    // An add/delete elsewhere re-derives the tree and may rebind this @for slot to a
    // different vertex; the open edit must not then commit onto the wrong coordinate.
    fixture.componentRef.setInput('node', { ...positionNode, coordinate: fromLonLat([20, 60]) });
    fixture.detectChanges();
    expect(view.editing()).toBe(false);
  });
});

describe('GeometryTreeNode auto-expand (large-geometry guard)', () => {
  it('auto-expands a small folder', () => {
    expect(setup(folderNode(5)).view.expanded()).toBe(true);
  });

  it('starts a large folder collapsed so a huge geometry does not flood the panel', () => {
    expect(setup(folderNode(2000)).view.expanded()).toBe(false);
  });

  it('lets the user override the default by toggling', () => {
    const { view } = setup(folderNode(2000));
    expect(view.expanded()).toBe(false);
    view.toggleExpanded();
    expect(view.expanded()).toBe(true);
  });
});
