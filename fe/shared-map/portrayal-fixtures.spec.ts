import type Feature from 'ol/Feature';
import { asArray } from 'ol/color';
import { buffer, getForViewAndSize, getWidth, getHeight } from 'ol/extent';
import GeoJSON from 'ol/format/GeoJSON';
import type Geometry from 'ol/geom/Geometry';
import VectorLayer from 'ol/layer/Vector';
import { fromLonLat } from 'ol/proj';
import CanvasBuilderGroup from 'ol/render/canvas/BuilderGroup';
import ExecutorGroup, { ALL } from 'ol/render/canvas/ExecutorGroup';
import { defaultOrder, getSquaredTolerance, getTolerance, renderFeature } from 'ol/renderer/vector';
import VectorSource from 'ol/source/Vector';
import Icon from 'ol/style/Icon';
import Style from 'ol/style/Style';
import { compose, create } from 'ol/transform';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { styleForMessageFeature } from './map-styles';
import CASES from './portrayal/fixtures/cases.json';
import EXPECTED from './portrayal/fixtures/expected.json';
import DEFINITION from './portrayal/portrayal.json';

// The golden fixtures of the portrayal: what the web maps draw of each case of
// `portrayal/fixtures/cases.json`, drawn here by OpenLayers itself. The web maps are the
// reference, so `expected.json` is made from this spec (`npm run portrayal:fixtures`), and the
// backend's map-image renderer is checked against the same file by `MapImagePlannerTest`.
//
// A case is drawn the way a vector layer draws a frame (`CanvasVectorLayerRenderer.prepareFrame`
// and `renderWorlds`): the features the source finds in the view plus the render buffer, in the
// layer's order, each with the styles `styleForMessageFeature` gives it, built into OpenLayers'
// canvas builders and replayed by its executors onto a canvas that records every call. The
// replay runs one builder type at a time, in OpenLayers' own order (`ALL`), so each recorded
// draw call names the pass it belongs to.

/** One case of `cases.json`. */
interface FixtureCase {
  readonly id: string;
  readonly view: { readonly centre: number[]; readonly zoom: number; readonly size: number[] };
  readonly mainType: string;
  readonly messageType?: string;
  readonly features: readonly object[];
}

type Point2 = [number, number];

interface Subpath {
  closed: boolean;
  points: Point2[];
}

/** One draw call of the replay: a fill, a stroke or a symbol. */
type DrawOp =
  | { pass: string; op: 'fill'; colour: number[]; subpaths: Subpath[] }
  | {
      pass: string;
      op: 'stroke';
      colour: number[];
      width: number;
      dash: number[];
      cap: string;
      join: string;
      miterLimit: number;
      subpaths: Subpath[];
    }
  | {
      pass: string;
      op: 'icon';
      symbol: string;
      x: number;
      y: number;
      width: number;
      height: number;
    };

/** The resolution of zoom level 0 of EPSG:3857 with 256 px tiles, as the backend computes it. */
const RESOLUTION_0 = (2 * Math.PI * 6378137) / 256;
const HALF_SIZE = Math.PI * 6378137;
/** Coordinates in `expected.json` are kept to a millionth of a pixel. */
const DECIMALS = 1e6;
/** How far a coordinate may be from the expected one: a thousandth of a pixel. */
const TOLERANCE = 1e-3;
/** No selection or hover halo: the portrayal alone. */
const CONTEXT = { showOutline: false, isSelected: false, highlightEnabled: false } as const;

const SYMBOLS = DEFINITION.symbols as Record<string, { readonly file: string }>;

/** The id of the symbol an icon shows, from its file name. */
function symbolOf(src: string): string {
  const file = src.slice(src.lastIndexOf('/') + 1);
  const id = Object.keys(SYMBOLS).find((key) => SYMBOLS[key].file === file);
  if (!id) throw new Error(`no symbol of the definition has the file ${file}`);
  return id;
}

/** The symbol each stand-in image shows (see `standIn`). */
const STAND_IN_SYMBOLS = new WeakMap<object, string>();

/**
 * A 2D context that records the draw calls OpenLayers makes, with the path each fill or stroke
 * covers. It has exactly what the replay of the portrayal uses; any other call fails the spec.
 */
class CanvasRecorder {
  pass = '';
  readonly ops: DrawOp[] = [];
  fillStyle: unknown = '#000000';
  strokeStyle: unknown = '#000000';
  lineWidth = 1;
  lineCap = 'butt';
  lineJoin = 'miter';
  miterLimit = 10;
  lineDashOffset = 0;
  globalAlpha = 1;
  private lineDash: number[] = [];
  private path: Subpath[] = [];
  private readonly saved: object[] = [];

  save(): void {
    this.saved.push({
      fillStyle: this.fillStyle,
      strokeStyle: this.strokeStyle,
      lineWidth: this.lineWidth,
      lineCap: this.lineCap,
      lineJoin: this.lineJoin,
      miterLimit: this.miterLimit,
      lineDashOffset: this.lineDashOffset,
      globalAlpha: this.globalAlpha,
      lineDash: this.lineDash,
    });
  }

  restore(): void {
    Object.assign(this, this.saved.pop());
  }

  beginPath(): void {
    this.path = [];
  }

  moveTo(x: number, y: number): void {
    this.path.push({ closed: false, points: [[x, y]] });
  }

  lineTo(x: number, y: number): void {
    const current = this.path.at(-1);
    if (!current) throw new Error('lineTo without a moveTo');
    current.points.push([x, y]);
  }

  closePath(): void {
    const current = this.path.at(-1);
    if (current) current.closed = true;
  }

  /** The clip to the view plus the render buffer; it cuts nothing of an image-sized canvas. */
  clip(): void {
    this.path = [];
  }

  setLineDash(dash: number[]): void {
    this.lineDash = [...dash];
  }

  getLineDash(): number[] {
    return [...this.lineDash];
  }

  fill(): void {
    this.ops.push({
      pass: this.pass,
      op: 'fill',
      colour: asArray(this.fillStyle as string).slice(),
      subpaths: this.copyPath(),
    });
  }

  stroke(): void {
    this.ops.push({
      pass: this.pass,
      op: 'stroke',
      colour: asArray(this.strokeStyle as string).slice(),
      width: this.lineWidth,
      dash: [...this.lineDash],
      cap: this.lineCap,
      join: this.lineJoin,
      miterLimit: this.miterLimit,
      subpaths: this.copyPath(),
    });
  }

  drawImage(
    image: object,
    ...args: [number, number, number, number, number, number, number, number]
  ): void {
    const [, , , , x, y, width, height] = args;
    const symbol = STAND_IN_SYMBOLS.get(image);
    if (!symbol) throw new Error('an image that is not a symbol of the portrayal was drawn');
    this.ops.push({
      pass: this.pass,
      op: 'icon',
      symbol,
      x,
      y,
      width,
      height,
    });
  }

  private copyPath(): Subpath[] {
    const round = (v: number): number => Math.round(v * DECIMALS) / DECIMALS;
    return this.path.map((subpath) => ({
      closed: subpath.closed,
      points: subpath.points.map(([x, y]): Point2 => [round(x), round(y)]),
    }));
  }
}

/**
 * A loaded stand-in for an icon of the portrayal. The icons `map-styles.ts` builds are shared by
 * every map and every spec, and the test environment never loads their images; OpenLayers draws
 * only a loaded icon. So the replay draws a stand-in built with the options the icon was built with
 * — its size, its anchor, its opacity, rotation and displacement — on a canvas whose natural size is
 * that size. Where a symbol lands and how large it is drawn follow from those options alone, so the
 * stand-in draws where the icon would; and the icon itself stays as the other specs expect it.
 */
function standIn(icon: Icon): Icon {
  // OpenLayers keeps the size an icon was asked for until its image loads, and never exposes it
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
  const image = document.createElement('canvas');
  image.width = width;
  image.height = height;
  STAND_IN_SYMBOLS.set(image, symbolOf(icon.getSrc() ?? ''));
  return new Icon({
    img: image,
    width,
    height,
    anchor: [...anchor],
    opacity: icon.getOpacity(),
    rotation: icon.getRotation(),
    rotateWithView: icon.getRotateWithView(),
    displacement: [...icon.getDisplacement()],
  });
}

/** The style with its icon, if it has one, replaced by a loaded stand-in. */
function withStandIn(style: Style): Style {
  const image = style.getImage();
  if (!(image instanceof Icon)) return style;
  return new Style({
    geometry: style.getGeometry() ?? undefined,
    fill: style.getFill() ?? undefined,
    stroke: style.getStroke() ?? undefined,
    text: style.getText() ?? undefined,
    image: standIn(image),
    zIndex: style.getZIndex(),
  });
}

/** The draw list the web maps make of one case. */
function drawList(fixture: FixtureCase): DrawOp[] {
  const [width, height] = fixture.view.size;
  const resolution = RESOLUTION_0 / Math.pow(2, fixture.view.zoom);
  // The backend puts the image's corner on a whole pixel of the world (TileWindow.centredOn), so
  // the view is centred on the centre of that window.
  const [x, y] = fromLonLat(fixture.view.centre);
  const originX = Math.round((x + HALF_SIZE) / resolution - width / 2);
  const originY = Math.round((HALF_SIZE - y) / resolution - height / 2);
  const center = [
    (originX + width / 2) * resolution - HALF_SIZE,
    HALF_SIZE - (originY + height / 2) * resolution,
  ];

  const format = new GeoJSON();
  const features = fixture.features.map((geoJson) => {
    const feature = format.readFeature(geoJson, {
      dataProjection: 'EPSG:4326',
      featureProjection: 'EPSG:3857',
    }) as Feature<Geometry>;
    // What the message layers set on every feature of a message
    feature.set('mainType', fixture.mainType);
    if (fixture.messageType !== undefined) feature.set('messageType', fixture.messageType);
    return feature;
  });
  const source = new VectorSource({ wrapX: false, features });
  // The defaults of a vector layer, which every message layer keeps
  const renderBuffer = new VectorLayer({ source }).getRenderBuffer() ?? Number.NaN;

  // CanvasVectorLayerRenderer.prepareFrame
  const frameExtent = getForViewAndSize(center, resolution, 0, [width, height]);
  const extent = buffer(frameExtent, renderBuffer * resolution);
  const builderGroup = new CanvasBuilderGroup(getTolerance(resolution, 1), extent, resolution, 1);
  const squaredTolerance = getSquaredTolerance(resolution, 1);
  const rendered = source.getFeaturesInExtent(extent).sort(defaultOrder);
  rendered.forEach((feature, index) => {
    for (const style of styleForMessageFeature(feature, CONTEXT, resolution)) {
      renderFeature(
        builderGroup,
        feature,
        withStandIn(style),
        squaredTolerance,
        () => undefined,
        undefined,
        false,
        index,
      );
    }
  });
  const executorGroup = new ExecutorGroup(
    extent,
    resolution,
    1,
    source.getOverlaps(),
    builderGroup.finish(),
    renderBuffer,
  );

  // renderWorlds, with the transform of CanvasLayerRenderer.getRenderTransform
  const canvasWidth = Math.round(getWidth(frameExtent) / resolution);
  const canvasHeight = Math.round(getHeight(frameExtent) / resolution);
  const transform = compose(
    create(),
    canvasWidth / 2,
    canvasHeight / 2,
    1 / resolution,
    -1 / resolution,
    -0,
    -center[0] + 0,
    -center[1],
  );
  const recorder = new CanvasRecorder();
  for (const builderType of ALL) {
    recorder.pass = builderType;
    executorGroup.execute(
      recorder as unknown as CanvasRenderingContext2D,
      [canvasWidth, canvasHeight],
      transform,
      0,
      true,
      [builderType],
    );
  }
  return recorder.ops;
}

/** The differences between two draw lists, each with where it is; empty when they agree. */
function differences(actual: unknown, expected: unknown, path: string, out: string[]): string[] {
  if (typeof expected === 'number' && typeof actual === 'number') {
    const tolerance = path.includes('.points') ? TOLERANCE : 1e-9;
    if (!(Math.abs(actual - expected) <= tolerance))
      out.push(`${path}: ${actual} is not ${expected}`);
  } else if (Array.isArray(expected) && Array.isArray(actual)) {
    if (actual.length !== expected.length) {
      out.push(`${path}: ${actual.length} entries, not ${expected.length}`);
    } else {
      expected.forEach((value, i) => differences(actual[i], value, `${path}[${i}]`, out));
    }
  } else if (expected && typeof expected === 'object' && actual && typeof actual === 'object') {
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
    for (const key of keys) {
      differences(
        (actual as Record<string, unknown>)[key],
        (expected as Record<string, unknown>)[key],
        `${path}.${key}`,
        out,
      );
    }
  } else if (actual !== expected) {
    out.push(`${path}: ${JSON.stringify(actual)} is not ${JSON.stringify(expected)}`);
  }
  return out;
}

const FIXTURES = CASES.cases as readonly FixtureCase[];
const EXPECTED_CASES = EXPECTED.cases as Record<string, unknown>;
/** Set by `npm run portrayal:fixtures`: print the draw lists instead of checking them. */
const WRITE =
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env?.[
    'PORTRAYAL_FIXTURES'
  ] === 'write';

describe('the golden fixtures of the portrayal, drawn by OpenLayers', () => {
  // OpenLayers draws a loaded icon into a 1 x 1 canvas once, to learn whether it may read its
  // pixels back for hit detection. The test environment has no 2D context, so that canvas gets one
  // that draws nothing and lets the pixels be read, as a same-origin icon does in a browser.
  // (The stand-in images are canvases as well; nothing is ever drawn on them.)
  beforeAll(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(
      () =>
        ({
          drawImage: () => undefined,
          getImageData: () => ({ data: new Uint8ClampedArray(4) }),
        }) as unknown as CanvasRenderingContext2D,
    );
  });
  afterAll(() => vi.restoreAllMocks());

  it('draws the fixtures with the screen size set, the one the maps draw with', () => {
    expect(CASES.sizeSet).toBe('screen');
  });

  if (WRITE) {
    it('prints the draw lists for expected.json', () => {
      const cases: Record<string, DrawOp[]> = {};
      for (const fixture of FIXTURES) cases[fixture.id] = drawList(fixture);
      const out = (globalThis as unknown as { process: { stdout: { write(s: string): void } } })
        .process.stdout;
      out.write(`PORTRAYAL-FIXTURES-BEGIN${JSON.stringify({ cases })}PORTRAYAL-FIXTURES-END\n`);
    });
    return;
  }

  it('has an expected draw list for exactly the cases there are', () => {
    expect(Object.keys(EXPECTED_CASES).sort()).toEqual(
      FIXTURES.map((fixture) => fixture.id).sort(),
    );
  });

  for (const fixture of FIXTURES) {
    it(`draws ${fixture.id} as expected.json has it`, () => {
      const ops = drawList(fixture);
      expect(differences(ops, EXPECTED_CASES[fixture.id], fixture.id, [])).toEqual([]);
    });
  }
});
