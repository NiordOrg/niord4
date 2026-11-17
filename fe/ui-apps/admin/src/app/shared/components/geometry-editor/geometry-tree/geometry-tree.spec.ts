import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Feature } from 'ol';
import { LineString } from 'ol/geom';
import { fromLonLat, toLonLat } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../testing/transloco-testing';
import { GeometryTree } from './geometry-tree';
import { GeometryTreeNode } from './geometry-tree-node';

function lineFeature(): Feature {
  const feature = new Feature(new LineString([fromLonLat([10, 55]), fromLonLat([12, 57])]));
  feature.setId('f1');
  return feature;
}

/** A LineString with `n` vertices — above the 50-vertex auto-expand limit it starts collapsed. */
function bigLineFeature(n: number): Feature {
  const coords = Array.from({ length: n }, (_, i) => fromLonLat([10 + i * 0.01, 55]));
  const feature = new Feature(new LineString(coords));
  feature.setId('big');
  return feature;
}

function setup(feature: Feature) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(GeometryTree);
  fixture.componentRef.setInput('feature', feature);
  fixture.detectChanges();
  return fixture;
}

describe('GeometryTree', () => {
  it('renders the geometry as a recursive tree (root + one position per vertex)', () => {
    const fixture = setup(lineFeature());
    const nodes = fixture.debugElement.queryAll(By.directive(GeometryTreeNode));
    // 1 LineString folder node + 2 Position nodes.
    expect(nodes.length).toBe(3);
  });

  it('applies a coordinate edit back onto the feature geometry (4326 → projected)', () => {
    const feature = lineFeature();
    const fixture = setup(feature);
    let edited = false;
    fixture.componentInstance.geometryEdited.subscribe(() => (edited = true));

    // The root node bubbles coordinateEdit up to the tree's onEdit.
    const root = fixture.debugElement.query(By.directive(GeometryTreeNode))
      .componentInstance as GeometryTreeNode;
    root.coordinateEdit.emit({ coordIndex: 0, lat: 56, lon: 11 });

    const [lon, lat] = toLonLat((feature.getGeometry() as LineString).getCoordinates()[0]);
    expect(lon).toBeCloseTo(11, 4);
    expect(lat).toBeCloseTo(56, 4);
    expect(edited).toBe(true);
  });

  it('emits selectRequested when the feature header is clicked', () => {
    const fixture = setup(lineFeature());
    let selected = false;
    fixture.componentInstance.selectRequested.subscribe(() => (selected = true));
    fixture.debugElement.query(By.css('button[aria-pressed]')).nativeElement.click();
    expect(selected).toBe(true);
  });
});

describe('GeometryTree — keyboard navigation', () => {
  const rows = (fixture: ComponentFixture<GeometryTree>) => [
    ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[data-tree-row]'),
  ];
  const press = (el: HTMLElement, key: string) =>
    el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));

  it('ArrowDown / ArrowUp move focus between tree rows', () => {
    const fixture = setup(lineFeature());
    const r = rows(fixture);
    // 1 LineString folder row + 2 Position rows.
    expect(r.length).toBe(3);

    r[0].focus();
    press(r[0], 'ArrowDown');
    expect(document.activeElement).toBe(r[1]);
    press(r[1], 'ArrowDown');
    expect(document.activeElement).toBe(r[2]);
    press(r[2], 'ArrowUp');
    expect(document.activeElement).toBe(r[1]);
  });

  it('ArrowRight expands a collapsed folder', () => {
    const fixture = setup(bigLineFeature(60));
    const folder = rows(fixture)[0];
    const toggle = () => folder.querySelector('[data-tree-toggle]')!;
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(rows(fixture).length).toBe(1); // children hidden while collapsed

    folder.focus();
    press(folder, 'ArrowRight');
    fixture.detectChanges();

    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(rows(fixture).length).toBeGreaterThan(1);
  });

  it('ArrowLeft collapses an expanded folder', () => {
    const fixture = setup(lineFeature()); // small line auto-expands
    const folder = rows(fixture)[0];
    const toggle = () => folder.querySelector('[data-tree-toggle]')!;
    expect(toggle().getAttribute('aria-expanded')).toBe('true');

    folder.focus();
    press(folder, 'ArrowLeft');
    fixture.detectChanges();

    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(rows(fixture).length).toBe(1);
  });

  it.each(['F2', 'Enter'])('%s on a position row opens the inline coordinate editor', (key) => {
    const fixture = setup(lineFeature());
    const posRow = rows(fixture)[1]; // first Position row
    expect(posRow.querySelector('[data-tree-edit]')).not.toBeNull();
    expect(posRow.querySelector('input')).toBeNull();

    posRow.focus();
    press(posRow, key);
    fixture.detectChanges();

    expect(posRow.querySelector('input')).not.toBeNull();
  });

  it('does not hijack arrow keys typed inside an open edit input', () => {
    const fixture = setup(lineFeature());
    const posRow = rows(fixture)[1];
    posRow.focus();
    press(posRow, 'F2');
    fixture.detectChanges();

    const input = posRow.querySelector('input') as HTMLInputElement;
    input.focus();
    const event = new KeyboardEvent('keydown', {
      key: 'ArrowDown',
      bubbles: true,
      cancelable: true,
    });
    input.dispatchEvent(event);

    // The handler returns early for input targets: nothing consumed, focus never moved.
    expect(event.defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(input);
  });
});
