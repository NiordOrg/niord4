import type Feature from 'ol/Feature';
import type { Coordinate } from 'ol/coordinate';
import { getCenter } from 'ol/extent';
import type Geometry from 'ol/geom/Geometry';
import LineString from 'ol/geom/LineString';
import MultiLineString from 'ol/geom/MultiLineString';
import MultiPoint from 'ol/geom/MultiPoint';
import MultiPolygon from 'ol/geom/MultiPolygon';
import Point from 'ol/geom/Point';
import Polygon from 'ol/geom/Polygon';
import Circle from 'ol/style/Circle';
import Fill from 'ol/style/Fill';
import Icon from 'ol/style/Icon';
import Stroke from 'ol/style/Stroke';
import Style from 'ol/style/Style';
import Text from 'ol/style/Text';

import { readCssVar } from './css-tokens';
import definition from './portrayal/portrayal.json';

// IHO S-124 (Navigational Warnings) Ed 2.0.0 portrayal — Day palette. WHAT is drawn is data in
// `portrayal/portrayal.json`, the one definition of the portrayal, which the backend's map-image
// renderer reads as well: the colours, the symbol files and where each one's pivot sits, the pen
// and its dash, the sizes, and which style a message gets. This module holds only the rules that
// turn that data into OpenLayers styles, so a portrayal change is an edit to the definition and
// its SVGs, never a literal here. The symbols are the SVGs beside the definition, which each
// application's build copies into its own `assets/portrayal/`.
//
// Both applications draw a message with this one module, so a mariner reads the same marks
// whichever of them is in front of them.

/**
 * The two main types a message has, spelled as the definition's selection rows spell them. The
 * kit owns this spelling so it depends on no application's model; an application that has its own
 * — the administration app's `MainType` — is the same union of string literals and needs no
 * conversion.
 */
export type MainType = 'NW' | 'NM';

/**
 * One row of the definition's selection table. A row matches a feature when every key it names
 * equals the feature's value for that key; the first row that matches names the style. The last
 * row names no key, so every feature ends there at the latest.
 */
export interface PortrayalSelectRow {
  readonly mainType?: string;
  readonly messageType?: string;
  readonly style: string;
}

// ---- the definition, as this module reads it -------------------------------

/** A symbol file, and where its pivot sits inside its viewBox as fractions of the box. */
interface SymbolDefinition {
  readonly file: string;
  readonly anchor: readonly number[];
}

interface Pen {
  readonly width: number;
  readonly dash: readonly number[];
}

/** One size set. The maps draw with `screen`, whose values are CSS pixels. */
interface SizeSet {
  readonly symbols: Readonly<Record<string, readonly number[]>>;
  readonly line: Pen;
  /** Spacing between repeated tags along a line; multiplied by the view resolution. */
  readonly tagSpacing: number;
  readonly buffer: Pen;
}

/** A style: its pen colour (a colour name) and the symbol ids it draws. */
interface StyleDefinition {
  readonly stroke: string;
  readonly point: string;
  readonly tag: string;
  readonly approx: string;
}

interface PortrayalDefinition {
  readonly colours: Readonly<Record<string, string>>;
  readonly symbols: Readonly<Record<string, SymbolDefinition>>;
  readonly lineStyle: { readonly cap: string; readonly join: string; readonly miterLimit: number };
  readonly sizes: { readonly screen: SizeSet };
  readonly styles: Readonly<Record<string, StyleDefinition>>;
  /** The affected-area overlay's fill and stroke, as colour names. */
  readonly buffer: { readonly fill: string; readonly stroke: string };
  readonly select: readonly PortrayalSelectRow[];
}

const PORTRAYAL: PortrayalDefinition = definition;
const SCREEN = PORTRAYAL.sizes.screen;
/** Where each application's build puts the symbol files; the definition names them by file name. */
const SYMBOL_PATH = 'assets/portrayal/';

// ---- feature property keys ------------------------------------------------
const MAIN_TYPE_KEY = 'mainType';
/**
 * The message's own type (`TEMPORARY_NOTICE`, `LOCAL_WARNING`, …), which a selection row may match
 * on beside `mainType`. The kit reads it as a plain string, so it depends on no application's
 * model. No layer sets it yet; a row that names it matches only a feature that carries it.
 */
const MESSAGE_TYPE_KEY = 'messageType';
const PARENT_FEATURE_IDS_KEY = 'parentFeatureIds';
/** S-124 `restriction=1` (approximate position). Dormant — no niord data sets this yet. */
const APPROXIMATE_KEY = 'approximate';
const COLOCATION_COUNT_KEY = 'coLocationCount';
const COLOCATION_PRIMARY_KEY = 'coLocationPrimary';

// ---- how the definition's sizes and anchors become Icon options -----------
// The point symbol and the approximate-position adornment share one millimetre-to-pixel scale,
// because they are drawn at the same position and have to stay in register; the tag is sized on
// its own, for legible lettering, because it never has to register with the point symbol.
//
// Each Icon is given its size as `width`/`height`, and OpenLayers turns that into a scale PER AXIS
// once the SVG reports its natural size — so the size a file declares still shapes the drawing. A
// file whose `width` : `height` differs from its viewBox is drawn distorted: the browser fits the
// viewBox inside the declared box with a margin, and the margin is stretched along with it. And a
// browser reports the natural size in whole pixels while laying the file out at the fractional
// size, so a fractional declared size shrinks or stretches the symbol by a fraction of a pixel.
// That is why every symbol file declares its viewBox's aspect in whole pixels.
//
// What the declared size does NOT set is sharpness. A canvas re-rasterises an SVG at the size it is
// drawn at, so the sharpness of a symbol is set entirely by the DEVICE pixels the map gives it:
// these CSS sizes times the map's pixel ratio. That is why the map has to follow the screen's
// device pixel ratio (see OlMap) — a map rendering at a stale ratio stretches the symbol, and no
// amount of resolution in the SVG file can undo it.
//
// Each anchor is where the file's own pivot sits inside its own viewBox, as a fraction of that box.
// Every catalogue symbol draws around user coordinate (0, 0) and that point is what the position
// means, but only the point symbol's box is centred on it: the adornment's box lies almost entirely
// up and to the left of the pivot, so a centred anchor would hang it most of a symbol away from the
// position it marks.

// ---- halos and the co-location badge (niord, not S-124) --------------------
// Both halos are meant to be concentric rings around the point symbol, so they are DERIVED from
// the selected style's screen point size: a literal radius stops matching the moment the symbol
// size moves, and the selected halo ends up lying across the symbol instead of clearing it. The
// standoffs are the ones the halos read best at — the selected halo just outside the symbol, the
// hover halo a further four pixels out.
//
// The stroke widths are even on purpose. OpenLayers rasterises a Circle style into a sprite of
// `ceil(2 * radius + strokeWidth)` pixels, draws the circle at the sprite's centre, and then snaps
// the sprite's top-left corner to a whole device pixel. An odd sprite therefore puts the halo's
// centre on a pixel CENTRE, while the point symbol — an even size, its box centred on its pivot —
// puts the ring's centre on a pixel BOUNDARY: half a pixel apart, which is visible as soon as the
// two rings come close. With whole-pixel radii an even stroke width keeps the sprite even, and
// both centres land on the same sub-pixel phase.
const SELECTED_HALO_STANDOFF_PX = 1;
const SELECTED_HALO_WIDTH_PX = 2;
const HOVERED_HALO_STANDOFF_PX = 5;
const HOVERED_HALO_WIDTH_PX = 4;
/**
 * How far inside the point symbol's ring the co-location count badge's centre lands, on both axes,
 * so it reads as a badge on the symbol rather than a mark beside it, whatever size the symbol is
 * drawn at.
 */
const COUNT_BADGE_INSET_PX = 2;

const COUNT_FILL_FALLBACK = '#ffffff';
const COUNT_STROKE_FALLBACK = '#ffffff';
const SELECTED_HALO_FALLBACK = 'rgba(255, 165, 0, 0.95)';
const HOVERED_HALO_FALLBACK = 'rgba(0, 120, 215, 0.95)';

/** What one style of the definition draws with, built once per style id. */
interface StyleBundle {
  /** The style's pen colour: its lines and outlines, and the co-location badge's disc. */
  readonly stroke: string;
  readonly pointSymbol: Style;
  readonly tagIcon: Icon;
  readonly approxIcon: Icon;
  /** Stroke-only style: the dashed line body AND the no-fill polygon outline. */
  readonly dashedStroke: Style;
  readonly selectedHalo: Style;
  readonly hoveredHalo: Style;
  /** How far the co-location count badge sits from the symbol's centre, on both axes. */
  readonly countBadgeOffset: number;
}

interface SharedBundle {
  readonly buffered: Style;
  readonly selectedHalo: string;
  readonly hoveredHalo: string;
  /** Outline around the co-location count badge. */
  readonly countStroke: string;
  /** The co-location count's numeral. */
  readonly countFill: string;
}

// Built lazily + memoised so the token read happens after the stylesheet applies
// (module-eval can precede it), per the documented readCssVar bridge.
const bundles = new Map<string, StyleBundle>();
let shared: SharedBundle | undefined;

function bundleFor(styleId: string): StyleBundle {
  let bundle = bundles.get(styleId);
  if (!bundle) {
    bundle = buildBundle(styleId);
    bundles.set(styleId, bundle);
  }
  return bundle;
}

function symbolIcon(symbolId: string): Icon {
  const symbol = PORTRAYAL.symbols[symbolId];
  const [width, height] = SCREEN.symbols[symbolId];
  return new Icon({ src: SYMBOL_PATH + symbol.file, width, height, anchor: [...symbol.anchor] });
}

function pen(color: string, line: Pen): Stroke {
  const { cap, join, miterLimit } = PORTRAYAL.lineStyle;
  return new Stroke({
    color,
    width: line.width,
    lineDash: [...line.dash],
    lineCap: cap as CanvasLineCap,
    lineJoin: join as CanvasLineJoin,
    miterLimit,
  });
}

function buildBundle(styleId: string): StyleBundle {
  const style = PORTRAYAL.styles[styleId];
  const stroke = PORTRAYAL.colours[style.stroke];
  const tokens = sharedBundle();
  // Half the point symbol: the base every ring and offset below is derived from.
  const pointRadius = SCREEN.symbols[style.point][0] / 2;
  return {
    stroke,
    pointSymbol: new Style({ image: symbolIcon(style.point) }),
    tagIcon: symbolIcon(style.tag),
    approxIcon: symbolIcon(style.approx),
    // No `fill` key → an S-124 surface is drawn as outline-only (no area fill).
    dashedStroke: new Style({ stroke: pen(stroke, SCREEN.line) }),
    // niord interaction enhancement (not part of S-124) — suppressed when highlight is off.
    selectedHalo: new Style({
      image: new Circle({
        radius: pointRadius + SELECTED_HALO_STANDOFF_PX,
        stroke: new Stroke({ color: tokens.selectedHalo, width: SELECTED_HALO_WIDTH_PX }),
      }),
      stroke: new Stroke({ color: tokens.selectedHalo, width: 4 }),
    }),
    hoveredHalo: new Style({
      image: new Circle({
        radius: pointRadius + HOVERED_HALO_STANDOFF_PX,
        stroke: new Stroke({ color: tokens.hoveredHalo, width: HOVERED_HALO_WIDTH_PX }),
      }),
      stroke: new Stroke({ color: tokens.hoveredHalo, width: 6 }),
    }),
    countBadgeOffset: pointRadius - COUNT_BADGE_INSET_PX,
  };
}

function sharedBundle(): SharedBundle {
  shared ??= {
    // niord affected-radius/buffer overlay, in the definition's buffer colours. S-124 does not
    // portray NavwarnAreaAffected — kept as a deliberate niord enhancement.
    buffered: new Style({
      fill: new Fill({ color: PORTRAYAL.colours[PORTRAYAL.buffer.fill] }),
      stroke: pen(PORTRAYAL.colours[PORTRAYAL.buffer.stroke], SCREEN.buffer),
    }),
    selectedHalo: readCssVar('--niord-msg-selected-halo', SELECTED_HALO_FALLBACK),
    hoveredHalo: readCssVar('--niord-msg-hover-halo', HOVERED_HALO_FALLBACK),
    countStroke: readCssVar('--niord-msg-count-stroke', COUNT_STROKE_FALLBACK),
    countFill: readCssVar('--niord-msg-count-fill', COUNT_FILL_FALLBACK),
  };
  return shared;
}

/**
 * The id of the style the selection rows give a feature with these values: the first row whose
 * every key equals the feature's value for it. The definition's last row names no key, so a
 * missing or unknown value ends there; the fallback to the last row only keeps a table without
 * that row from throwing.
 */
export function selectStyleId(
  rows: readonly PortrayalSelectRow[],
  mainType: unknown,
  messageType: unknown,
): string {
  for (const row of rows) {
    if (row.mainType !== undefined && row.mainType !== mainType) continue;
    if (row.messageType !== undefined && row.messageType !== messageType) continue;
    return row.style;
  }
  return rows[rows.length - 1].style;
}

// ---- geometry helpers (for the along-line + interior-point symbol placement) ----

function interiorGeometry(geom: Geometry): Geometry {
  if (geom instanceof Polygon) return geom.getInteriorPoint();
  if (geom instanceof MultiPolygon) return geom.getInteriorPoints();
  return new Point(getCenter(geom.getExtent()));
}

/** Samples points along a line(s) at ~`spacing` map units, with at least one per line. */
function alongLineMultiPoint(geom: Geometry, spacing: number): MultiPoint {
  const coords: Coordinate[] = [];
  const sample = (line: LineString): void => {
    const before = coords.length;
    const total = line.getLength();
    if (total > 0 && spacing > 0) {
      for (let d = spacing / 2; d < total; d += spacing) {
        coords.push(line.getCoordinateAt(d / total));
      }
    }
    if (coords.length === before) coords.push(line.getCoordinateAt(0.5));
  };
  if (geom instanceof LineString) sample(geom);
  else if (geom instanceof MultiLineString) for (const line of geom.getLineStrings()) sample(line);
  return new MultiPoint(coords);
}

function countBadge(count: number, bundle: StyleBundle): Style {
  const tokens = sharedBundle();
  const offset = bundle.countBadgeOffset;
  return new Style({
    image: new Circle({
      radius: 8,
      fill: new Fill({ color: bundle.stroke }),
      stroke: new Stroke({ color: tokens.countStroke, width: 1.5 }),
      displacement: [offset, offset],
    }),
    text: new Text({
      text: String(count),
      font: 'bold 10px sans-serif',
      fill: new Fill({ color: tokens.countFill }),
      offsetX: offset,
      offsetY: -offset,
    }),
  });
}

// ---- the S-124 style for a single feature ---------------------------------

function s124Styles(feature: Feature, bundle: StyleBundle, resolution: number): Style[] {
  const geom = feature.getGeometry();
  const type = geom?.getType();
  const out: Style[] = [];

  switch (type) {
    case 'Point':
    case 'MultiPoint':
      out.push(bundle.pointSymbol);
      break;
    case 'LineString':
    case 'MultiLineString': {
      out.push(bundle.dashedStroke);
      const spacing = SCREEN.tagSpacing * resolution;
      out.push(
        new Style({
          image: bundle.tagIcon,
          geometry: (f) => {
            const g = (f as Feature).getGeometry();
            return g ? alongLineMultiPoint(g, spacing) : undefined;
          },
        }),
      );
      break;
    }
    case 'Polygon':
    case 'MultiPolygon':
    default:
      // Polygon (and any other / GeometryCollection): dashed outline, NO fill, + a
      // single centred NW tag. (S-124: surfaces are not filled.)
      out.push(bundle.dashedStroke);
      out.push(
        new Style({
          image: bundle.tagIcon,
          geometry: (f) => {
            const g = (f as Feature).getGeometry();
            return g ? interiorGeometry(g) : undefined;
          },
        }),
      );
      break;
  }

  // Approximate-position adornment (S-124 restriction=1). Dormant — no niord data
  // source sets the `approximate` flag yet (would need a backend change).
  if (feature.get(APPROXIMATE_KEY) === true) {
    const isPoint = type === 'Point' || type === 'MultiPoint';
    out.push(
      new Style({
        image: bundle.approxIcon,
        geometry: isPoint
          ? undefined
          : (f) => {
              const g = (f as Feature).getGeometry();
              return g ? interiorGeometry(g) : undefined;
            },
      }),
    );
  }

  // Co-location count: when several warnings share a point, the representative
  // ("primary") feature carries the count and draws a small badge beside the symbol.
  const count = feature.get(COLOCATION_COUNT_KEY);
  if (typeof count === 'number' && count > 1 && feature.get(COLOCATION_PRIMARY_KEY) === true) {
    out.push(countBadge(count, bundle));
  }

  return out;
}

export interface MessageFeatureStyleContext {
  /** Layer outline mode (geometry is collapsed to a centroid Point upstream). */
  readonly showOutline: boolean;
  readonly isSelected: boolean;
  /** Optional — defaults to false. Only the message-list layer drives this. */
  readonly isHovered?: boolean;
  /**
   * When false, the niord selection/hover halos are suppressed so the output is
   * strictly the S-124 portrayal. Defaults to true (the niord enhancement).
   */
  readonly highlightEnabled?: boolean;
}

/**
 * Returns the S-124 portrayal style(s) for a message map feature: the style the definition's
 * selection rows give the feature's `mainType` (and `messageType`, when it carries one), drawn by
 * geometry primitive (point → symbol; line → dashed + along-line tags; surface → no-fill dashed
 * outline + centred tag), plus the optional niord selection/hover halos (stacked underneath so the
 * symbol stays on top).
 *
 * `resolution` (the OL view resolution) scales the along-line tag spacing so it is
 * roughly constant on screen across zoom.
 */
export function styleForMessageFeature(
  feature: Feature,
  ctx: MessageFeatureStyleContext,
  resolution = 1,
): Style[] {
  // Buffered / affected-area overlay short-circuits (no halos), per the existing behaviour.
  if (feature.get(PARENT_FEATURE_IDS_KEY) !== undefined) {
    return [sharedBundle().buffered];
  }

  const bundle = bundleFor(
    selectStyleId(PORTRAYAL.select, feature.get(MAIN_TYPE_KEY), feature.get(MESSAGE_TYPE_KEY)),
  );
  const base = s124Styles(feature, bundle, resolution);

  const halosOn = ctx.highlightEnabled !== false;
  const hovered = halosOn && ctx.isHovered === true;
  const selected = halosOn && ctx.isSelected === true;
  if (!hovered && !selected) return base;

  // Halos go first so they render *under* the base styles (the symbol stays on top);
  // hover halo is outermost so both states are visible at once.
  const stack: Style[] = [];
  if (hovered) stack.push(bundle.hoveredHalo);
  if (selected) stack.push(bundle.selectedHalo);
  stack.push(...base);
  return stack;
}

// ---- coordinate pins and the names beside them ----------------------------
// A map that shows ONE message numbers every readable coordinate of it, so a reader can
// follow the positions written in the text against the chart, and draws the name a
// coordinate or a geometry carries beside the number. This is outside S-124 — the standard
// has no producer-authored text in this data — but it is the same mark in both
// applications, so it is built here with them.
//
// The colours are the `--niord-pin-*` tokens, country-locked like the portrayal: a reader
// moving between deployments must see the same marker.

const PIN_FILL_FALLBACK = '#8B008B';
const PIN_STROKE_FALLBACK = '#FFFFFF';
const PIN_INDEX_FALLBACK = '#FFFFFF';
const PIN_LABEL_FILL_FALLBACK = 'rgba(80, 0, 60, 1)';
const PIN_LABEL_STROKE_FALLBACK = 'rgba(255, 255, 255, 0.9)';

/** Radius of the numbered circle, in CSS pixels. */
const PIN_RADIUS_PX = 9;
/** Where a name sits relative to the position it belongs to: just clear of the circle. */
export const COORDINATE_NAME_OFFSET_Y = 18;

interface PinTokens {
  readonly fill: string;
  readonly stroke: string;
  readonly index: string;
  readonly labelFill: string;
  readonly labelStroke: string;
}

// Memoised for the same two reasons the portrayal bundles are: the token read has to happen
// after the stylesheet applies, and a labels layer builds one style per coordinate.
let pins: PinTokens | undefined;

function pinTokens(): PinTokens {
  pins ??= {
    fill: readCssVar('--niord-pin-fill', PIN_FILL_FALLBACK),
    stroke: readCssVar('--niord-pin-stroke', PIN_STROKE_FALLBACK),
    index: readCssVar('--niord-pin-label', PIN_INDEX_FALLBACK),
    labelFill: readCssVar('--niord-pin-label-fill', PIN_LABEL_FILL_FALLBACK),
    labelStroke: readCssVar('--niord-pin-label-stroke', PIN_LABEL_STROKE_FALLBACK),
  };
  return pins;
}

/**
 * The filled magenta circle carrying a coordinate's 1-based number, drawn in white.
 *
 * The caller places it: either on a feature whose geometry is that coordinate, or with
 * `setGeometry()` when the style rides on a feature of its own.
 */
export function coordinateIndexStyle(index: number): Style {
  const tokens = pinTokens();
  return new Style({
    image: new Circle({
      radius: PIN_RADIUS_PX,
      fill: new Fill({ color: tokens.fill }),
      stroke: new Stroke({ color: tokens.stroke, width: 2 }),
    }),
    text: new Text({
      text: String(index),
      font: 'bold 10px sans-serif',
      fill: new Fill({ color: tokens.index }),
    }),
  });
}

/**
 * The name a coordinate or a geometry carries, in the reader's language, with a white halo
 * that keeps it legible over chart imagery. `offsetY` moves it away from the position:
 * `COORDINATE_NAME_OFFSET_Y` clears a numbered circle, a smaller value suits a name that
 * stands alone at the middle of a geometry.
 */
export function coordinateNameStyle(name: string, offsetY = COORDINATE_NAME_OFFSET_Y): Style {
  const tokens = pinTokens();
  return new Style({
    text: new Text({
      text: name,
      font: '12px sans-serif',
      fill: new Fill({ color: tokens.labelFill }),
      stroke: new Stroke({ color: tokens.labelStroke, width: 3 }),
      offsetY,
    }),
  });
}
