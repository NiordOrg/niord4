import type { Coordinate } from 'ol/coordinate';
import type { FeatureLike } from 'ol/Feature';
import { Point } from 'ol/geom';
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style';

import { readCssVar } from '@niord/map/css-tokens';

/**
 * Vector styles for the editable geometry layer. Distinct from the read-only
 * `map-styles` (which renders message icons/outlines) — the editor draws its
 * features as magenta vertices + outlines so the editing surface is legible,
 * plus the feature's name label (when set) at its centre.
 *
 * Colours come from the Layer-2 CSS tokens (`tokens.base.css`): the editing
 * surface reuses the country-locked Niord pin (`--niord-pin-*`) plus a
 * translucent `--niord-editor-fill`. A canvas style needs literal strings, so
 * the values are read via `readCssVar` (the documented JS bridge) rather than
 * `var(...)`. Built lazily + memoised on first render so the read happens after
 * the stylesheet is applied (module-eval can precede it); the literal fallbacks
 * keep parity if a token fails to resolve.
 */
const PIN_FILL_FALLBACK = '#8b008b';
const PIN_STROKE_FALLBACK = '#ffffff';
const EDITOR_FILL_FALLBACK = 'rgba(139, 0, 139, 0.1)';
const PIN_LABEL_FILL_FALLBACK = 'rgba(80, 0, 60, 1)';
const PIN_LABEL_STROKE_FALLBACK = 'rgba(255, 255, 255, 0.9)';
const BUFFER_FILL_FALLBACK = 'rgba(204, 0, 153, 0.15)';
const BUFFER_STROKE_FALLBACK = 'rgba(204, 0, 153, 0.6)';

interface EditorStyles {
  readonly normal: Style;
  readonly selected: Style;
  readonly buffer: Style;
  readonly labelFill: string;
  readonly labelStroke: string;
}

let cache: EditorStyles | undefined;

function editorStyles(): EditorStyles {
  if (cache) return cache;

  const pin = readCssVar('--niord-pin-fill', PIN_FILL_FALLBACK);
  const vertexStroke = readCssVar('--niord-pin-stroke', PIN_STROKE_FALLBACK);
  const fill = new Fill({ color: readCssVar('--niord-editor-fill', EDITOR_FILL_FALLBACK) });

  const vertex = new CircleStyle({
    radius: 5,
    fill: new Fill({ color: pin }),
    stroke: new Stroke({ color: vertexStroke, width: 1.5 }),
  });
  const vertexSelected = new CircleStyle({
    radius: 7,
    fill: new Fill({ color: pin }),
    stroke: new Stroke({ color: vertexStroke, width: 2 }),
  });

  cache = {
    normal: new Style({ fill, stroke: new Stroke({ color: pin, width: 2 }), image: vertex }),
    selected: new Style({
      fill,
      stroke: new Stroke({ color: pin, width: 4 }),
      image: vertexSelected,
    }),
    buffer: new Style({
      fill: new Fill({ color: readCssVar('--niord-msg-buffer-fill', BUFFER_FILL_FALLBACK) }),
      stroke: new Stroke({
        color: readCssVar('--niord-msg-buffer-stroke', BUFFER_STROKE_FALLBACK),
        width: 1,
        lineDash: [4, 4],
      }),
    }),
    labelFill: readCssVar('--niord-pin-label-fill', PIN_LABEL_FILL_FALLBACK),
    labelStroke: readCssVar('--niord-pin-label-stroke', PIN_LABEL_STROKE_FALLBACK),
  };
  return cache;
}

/** The style for an editable feature; the selected variant thickens the stroke + vertex. */
export function editorFeatureStyle(feature: FeatureLike): Style {
  const styles = editorStyles();
  return feature.get('selected') === true ? styles.selected : styles.normal;
}

/** The style for an affected-area buffer feature (translucent fill + dashed outline). */
export function editorBufferStyle(): Style {
  return editorStyles().buffer;
}

/**
 * A text label drawn at `center` — the feature's name.
 * Used to surface names carried by imported GeoJSON/KML features.
 */
export function editorNameLabelStyle(name: string, center: Coordinate): Style {
  const styles = editorStyles();
  return new Style({
    text: new Text({
      text: name,
      font: '11px sans-serif',
      fill: new Fill({ color: styles.labelFill }),
      stroke: new Stroke({ color: styles.labelStroke, width: 2 }),
      offsetY: 6,
    }),
    geometry: new Point(center),
  });
}
