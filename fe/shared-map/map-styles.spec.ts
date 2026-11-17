import Feature from 'ol/Feature';
import type Geometry from 'ol/geom/Geometry';
import LineString from 'ol/geom/LineString';
import MultiPoint from 'ol/geom/MultiPoint';
import Point from 'ol/geom/Point';
import Polygon from 'ol/geom/Polygon';
import Circle from 'ol/style/Circle';
import Icon from 'ol/style/Icon';
import Style from 'ol/style/Style';
import { describe, expect, it } from 'vitest';

import { type PortrayalSelectRow, selectStyleId, styleForMessageFeature } from './map-styles';

function feat(geom: Point | LineString | Polygon, props: Record<string, unknown> = {}): Feature {
  const feature = new Feature({ geometry: geom });
  for (const [key, value] of Object.entries(props)) feature.set(key, value);
  return feature;
}

const POINT = (): Point => new Point([0, 0]);
const LINE = (): LineString =>
  new LineString([
    [0, 0],
    [100000, 0],
  ]);
const POLY = (): Polygon =>
  new Polygon([
    [
      [0, 0],
      [100000, 0],
      [100000, 100000],
      [0, 100000],
      [0, 0],
    ],
  ]);

const BASE = { showOutline: false, isSelected: false } as const;

function iconSrc(style: Style | undefined): string {
  const img = style?.getImage();
  return img instanceof Icon ? (img.getSrc() ?? '') : '';
}

describe('styleForMessageFeature — S-124 portrayal', () => {
  it('NW point → the NW-in-circle symbol (no fill)', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType: 'NW' }), BASE);
    expect(styles).toHaveLength(1);
    expect(iconSrc(styles[0])).toBe('assets/portrayal/nw-point.svg');
  });

  it('NM point → its own (currently identical) NM symbol', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType: 'NM' }), BASE);
    expect(iconSrc(styles[0])).toBe('assets/portrayal/nm-point.svg');
  });

  it('missing mainType defaults to NW', () => {
    const styles = styleForMessageFeature(feat(POINT()), BASE);
    expect(iconSrc(styles[0])).toContain('nw-point.svg');
  });

  it('NW line → dashed magenta stroke (no image) + a tag style placed along the line', () => {
    const styles = styleForMessageFeature(feat(LINE(), { mainType: 'NW' }), BASE, 50);
    expect(styles).toHaveLength(2);
    // body: dashed stroke, no image, no fill
    expect(styles[0].getStroke()?.getLineDash()).toEqual([13.6, 6.8]);
    expect(styles[0].getImage()).toBeNull();
    expect(styles[0].getFill()).toBeNull();
    // along-line tags: the NW tag icon, driven by a geometry function
    expect(iconSrc(styles[1])).toContain('nw-tag.svg');
    expect(typeof styles[1].getGeometry()).toBe('function');
  });

  it('NW polygon → dashed outline with NO fill + a centred tag', () => {
    const styles = styleForMessageFeature(feat(POLY(), { mainType: 'NW' }), BASE);
    expect(styles).toHaveLength(2);
    expect(styles[0].getStroke()?.getLineDash()).toEqual([13.6, 6.8]);
    expect(styles[0].getFill()).toBeNull(); // S-124: surfaces are not filled
    expect(iconSrc(styles[1])).toContain('nw-tag.svg');
    expect(typeof styles[1].getGeometry()).toBe('function');
  });

  it('buffered feature → the single buffered overlay style, no halos even when selected', () => {
    const styles = styleForMessageFeature(feat(POLY(), { parentFeatureIds: 'fid-1' }), {
      showOutline: false,
      isSelected: true,
    });
    expect(styles).toHaveLength(1);
    expect(styles[0].getStroke()?.getLineDash()).toEqual([4, 4]);
    expect(styles[0].getFill()).not.toBeNull(); // buffer IS filled (translucent)
  });

  it('selection stacks the halo UNDER the base symbol (symbol stays on top)', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType: 'NW' }), {
      showOutline: false,
      isSelected: true,
    });
    expect(styles).toHaveLength(2);
    expect(iconSrc(styles[styles.length - 1])).toContain('nw-point.svg'); // base last = on top
  });

  it('highlightEnabled:false suppresses the halos (strictly-standard output)', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType: 'NW' }), {
      showOutline: false,
      isSelected: true,
      isHovered: true,
      highlightEnabled: false,
    });
    expect(styles).toHaveLength(1);
    expect(iconSrc(styles[0])).toContain('nw-point.svg');
  });

  it('co-location: the primary feature draws a count badge', () => {
    const styles = styleForMessageFeature(
      feat(POINT(), { mainType: 'NW', coLocationCount: 3, coLocationPrimary: true }),
      BASE,
    );
    const withText = styles.find((s) => s.getText()?.getText() === '3');
    expect(withText).toBeDefined();
  });

  it('co-location: non-primary members do NOT draw a badge', () => {
    const styles = styleForMessageFeature(
      feat(POINT(), { mainType: 'NW', coLocationCount: 3, coLocationPrimary: false }),
      BASE,
    );
    expect(styles.some((s) => s.getText())).toBe(false);
  });

  it('approximate flag adds the approximate-position adornment', () => {
    const styles = styleForMessageFeature(
      feat(POINT(), { mainType: 'NW', approximate: true }),
      BASE,
    );
    expect(styles.some((s) => iconSrc(s).includes('nw-approx.svg'))).toBe(true);
  });
});

describe('selectStyleId — the selection rows', () => {
  // A table with message-type rows, which the definition does not have yet: the matching rule
  // has to hold for them the day it does.
  const ROWS: readonly PortrayalSelectRow[] = [
    { mainType: 'NM', messageType: 'TEMPORARY_NOTICE', style: 'nm-temporary' },
    { mainType: 'NM', style: 'nm' },
    { messageType: 'COASTAL_WARNING', style: 'coastal' },
    { style: 'catch-all' },
  ];

  it("takes the first row whose every key equals the feature's value", () => {
    expect(selectStyleId(ROWS, 'NM', 'TEMPORARY_NOTICE')).toBe('nm-temporary');
    expect(selectStyleId(ROWS, 'NM', 'PERMANENT_NOTICE')).toBe('nm');
    expect(selectStyleId(ROWS, 'NW', 'COASTAL_WARNING')).toBe('coastal');
  });

  it('does not match a row on a key the feature does not carry', () => {
    expect(selectStyleId(ROWS, 'NM', undefined)).toBe('nm');
    expect(selectStyleId(ROWS, undefined, 'TEMPORARY_NOTICE')).toBe('catch-all');
  });

  it('ends at the keyless row for anything else', () => {
    expect(selectStyleId(ROWS, 'NW', undefined)).toBe('catch-all');
    expect(selectStyleId(ROWS, undefined, undefined)).toBe('catch-all');
    expect(selectStyleId(ROWS, 'XX', 'XX')).toBe('catch-all');
  });
});

describe('styleForMessageFeature — selection through the definition', () => {
  it('an unknown mainType falls to the NW style', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType: 'XX' }), BASE);
    expect(iconSrc(styles[0])).toBe('assets/portrayal/nw-point.svg');
  });

  it('a messageType changes nothing while no row of the definition names one', () => {
    const nm = styleForMessageFeature(
      feat(POINT(), { mainType: 'NM', messageType: 'TEMPORARY_NOTICE' }),
      BASE,
    );
    const nw = styleForMessageFeature(
      feat(POINT(), { mainType: 'NW', messageType: 'LOCAL_WARNING' }),
      BASE,
    );
    expect(iconSrc(nm[0])).toBe('assets/portrayal/nm-point.svg');
    expect(iconSrc(nw[0])).toBe('assets/portrayal/nw-point.svg');
  });
});

// ---- pins: what both applications draw -------------------------------------
// The exact screen portrayal, written out as numbers: a change to the definition, or to how the
// style module reads it, that moves a mark on either application's map fails here first.

interface IconOptions {
  readonly width: number;
  readonly height: number;
  readonly anchor: readonly number[];
}

/**
 * The size and anchor an Icon was built with. OpenLayers reports an Icon's size only once its
 * image has loaded, which never happens in a DOM without a renderer, so this reads the options the
 * Icon holds until then — and throws, rather than passing, if OpenLayers stops holding them.
 */
function iconOptions(style: Style | undefined): IconOptions {
  const icon = style?.getImage();
  if (!(icon instanceof Icon)) throw new Error('not an Icon style');
  const internals = icon as unknown as {
    readonly initialOptions_?: { readonly width?: number; readonly height?: number };
    readonly anchor_?: readonly number[];
  };
  const width = internals.initialOptions_?.width;
  const height = internals.initialOptions_?.height;
  const anchor = internals.anchor_;
  if (width === undefined || height === undefined || anchor === undefined) {
    throw new Error("OpenLayers no longer keeps an unloaded Icon's options");
  }
  return { width, height, anchor };
}

/** The anchors are stored to six decimals, which is within 5e-7 of the viewBox fractions. */
function expectAnchor(actual: readonly number[], expected: readonly [number, number]): void {
  expect(actual).toHaveLength(2);
  expect(actual[0]).toBeCloseTo(expected[0], 6);
  expect(actual[1]).toBeCloseTo(expected[1], 6);
}

function tagXs(style: Style, feature: Feature): number[] {
  const geometry = style.getGeometryFunction()(feature) as Geometry;
  expect(geometry).toBeInstanceOf(MultiPoint);
  return (geometry as MultiPoint).getCoordinates().map(([x]) => x);
}

const PEN_COLOUR = '#c045d1';

describe.each([
  { mainType: 'NW', prefix: 'nw' },
  { mainType: 'NM', prefix: 'nm' },
])('the screen portrayal of $mainType, pinned', ({ mainType, prefix }) => {
  it('draws the point symbol 32 × 32 at its pivot', () => {
    const [point] = styleForMessageFeature(feat(POINT(), { mainType }), BASE);
    expect(iconSrc(point)).toBe(`assets/portrayal/${prefix}-point.svg`);
    const options = iconOptions(point);
    expect([options.width, options.height]).toEqual([32, 32]);
    expectAnchor(options.anchor, [6.97 / 13.96, 6.96 / 13.96]);
  });

  it('draws the tag 44 × 27 at its pivot', () => {
    const [, tag] = styleForMessageFeature(feat(POLY(), { mainType }), BASE);
    expect(iconSrc(tag)).toBe(`assets/portrayal/${prefix}-tag.svg`);
    const options = iconOptions(tag);
    expect([options.width, options.height]).toEqual([44, 27]);
    expectAnchor(options.anchor, [8.11 / 15.81, 4.8 / 9.71]);
  });

  it('draws the approximate-position adornment 43 × 43 at its pivot', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType, approximate: true }), BASE);
    const approx = styles.find((s) => iconSrc(s).endsWith('-approx.svg'));
    expect(iconSrc(approx)).toBe(`assets/portrayal/${prefix}-approx.svg`);
    const options = iconOptions(approx);
    expect([options.width, options.height]).toEqual([43, 43]);
    expectAnchor(options.anchor, [16.7 / 18.7, 16.7 / 18.7]);
  });

  it('draws lines and outlines with the magenta pen: width 2, dash 13.6 : 6.8, round', () => {
    for (const geometry of [LINE(), POLY()]) {
      const stroke = styleForMessageFeature(feat(geometry, { mainType }), BASE)[0].getStroke();
      expect(String(stroke?.getColor()).toLowerCase()).toBe(PEN_COLOUR);
      expect(stroke?.getWidth()).toBe(2);
      expect(stroke?.getLineDash()).toEqual([13.6, 6.8]);
      // OpenLayers' own defaults, which the pen was drawn with while it named none.
      expect(stroke?.getLineCap()).toBe('round');
      expect(stroke?.getLineJoin()).toBe('round');
      expect(stroke?.getMiterLimit()).toBe(10);
    }
  });

  it('repeats the tag every 140 px along a line, whatever the resolution', () => {
    // The line is 100 000 map units long: at resolution 50 the spacing is 7 000 units, the first
    // tag half a spacing in; at resolution 100 it is 14 000.
    const feature = feat(LINE(), { mainType });
    const xs = tagXs(styleForMessageFeature(feature, BASE, 50)[1], feature);
    expect(xs).toHaveLength(14);
    expect(xs[0]).toBeCloseTo(3500, 6);
    for (let i = 1; i < xs.length; i++) expect(xs[i] - xs[i - 1]).toBeCloseTo(7000, 6);

    const coarse = tagXs(styleForMessageFeature(feature, BASE, 100)[1], feature);
    expect(coarse).toHaveLength(7);
    expect(coarse[0]).toBeCloseTo(7000, 6);
  });

  it('rings the point symbol with the selection halo at 17 px and the hover halo at 21 px', () => {
    const styles = styleForMessageFeature(feat(POINT(), { mainType }), {
      showOutline: false,
      isSelected: true,
      isHovered: true,
    });
    const [hover, selected] = styles.map((s) => s.getImage());
    expect(hover).toBeInstanceOf(Circle);
    expect(selected).toBeInstanceOf(Circle);
    expect((hover as Circle).getRadius()).toBe(21);
    expect((hover as Circle).getStroke()?.getWidth()).toBe(4);
    expect((selected as Circle).getRadius()).toBe(17);
    expect((selected as Circle).getStroke()?.getWidth()).toBe(2);
  });

  it('sets the co-location badge 14 px up and right, a white numeral on the pen colour', () => {
    const styles = styleForMessageFeature(
      feat(POINT(), { mainType, coLocationCount: 4, coLocationPrimary: true }),
      BASE,
    );
    const badge = styles.find((s) => s.getText()?.getText() === '4');
    const disc = badge?.getImage();
    expect(disc).toBeInstanceOf(Circle);
    expect((disc as Circle).getDisplacement()).toEqual([14, 14]);
    expect(String((disc as Circle).getFill()?.getColor()).toLowerCase()).toBe(PEN_COLOUR);
    expect(badge?.getText()?.getOffsetX()).toBe(14);
    expect(badge?.getText()?.getOffsetY()).toBe(-14);
    expect(badge?.getText()?.getFill()?.getColor()).toBe('#ffffff');
  });
});

describe('the affected-area overlay, pinned', () => {
  it('is a translucent magenta fill under a dashed 1 px stroke', () => {
    const [buffered] = styleForMessageFeature(feat(POLY(), { parentFeatureIds: 'fid-1' }), BASE);
    expect(buffered.getFill()?.getColor()).toBe('rgba(192, 69, 209, 0.15)');
    expect(buffered.getStroke()?.getColor()).toBe('rgba(192, 69, 209, 0.6)');
    expect(buffered.getStroke()?.getWidth()).toBe(1);
    expect(buffered.getStroke()?.getLineDash()).toEqual([4, 4]);
  });
});
