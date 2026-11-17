import type { Feature as OlFeature } from 'ol';
import type { ReadOptions } from 'ol/format/Feature';
import { WKT } from 'ol/format';

const WKT_DATA_PROJECTION = 'EPSG:4326';

/**
 * WKT import format for drag-and-drop.
 *
 * Plain WKT carries no coordinate-reference system, so OL's `DragAndDrop` (which only
 * passes `featureProjection`) would leave the lon/lat coordinates untransformed and drop
 * the features near null-island — invisible at any normal view. This wrapper forces
 * `dataProjection = EPSG:4326` so the coordinates are projected into the map view.
 *
 * It also tolerates files that emit one WKT geometry per line (some exporters do).
 * OL's `WKT.readFeatures` parses only the *first* geometry of a multi-geometry string,
 * so when the text is several non-empty lines and each line parses on its own, every line
 * is read as a separate feature. Pretty-printed single geometries (whose individual lines
 * are not complete WKT) fall back to a whole-text read.
 */
export class WktImportFormat extends WKT {
  override readFeatures(
    source: Document | Element | ArrayBuffer | string | object,
    options?: ReadOptions,
  ): OlFeature[] {
    const opts: ReadOptions = { ...options, dataProjection: WKT_DATA_PROJECTION };
    if (typeof source !== 'string') {
      return super.readFeatures(source, opts) as OlFeature[];
    }

    const lines = source
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);

    if (lines.length > 1) {
      const perLine: OlFeature[] = [];
      let everyLineParsed = true;
      for (const line of lines) {
        try {
          const features = super.readFeatures(line, opts) as OlFeature[];
          if (features.length === 0) {
            everyLineParsed = false;
            break;
          }
          perLine.push(...features);
        } catch {
          everyLineParsed = false;
          break;
        }
      }
      if (everyLineParsed && perLine.length > 0) {
        return perLine;
      }
    }

    return super.readFeatures(source, opts) as OlFeature[];
  }
}
