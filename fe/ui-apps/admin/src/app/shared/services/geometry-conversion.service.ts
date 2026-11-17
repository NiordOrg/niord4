import { Injectable } from '@angular/core';
import type { Geometry as GeoJsonGeometry } from 'geojson';
import { Feature as OlFeature } from 'ol';
import type { Coordinate } from 'ol/coordinate';
import { GeoJSON } from 'ol/format';
import { LineString, MultiLineString, MultiPolygon, Point, Polygon } from 'ol/geom';
import type Geometry from 'ol/geom/Geometry';
import { getCenter as olGetExtentCenter } from 'ol/extent';

import type { GeometryFeature, GeometryFeatureCollection } from '../models/geometry.model';

const DATA_PROJECTION = 'EPSG:4326';
const FEATURE_PROJECTION = 'EPSG:3857';

const SELECTED = 'selected';

/**
 * OL ↔ GeoJSON conversion between the app's wire shape (GeoJSON in EPSG:4326)
 * and OpenLayers' working shape (features/geometries in EPSG:3857).
 */
@Injectable({ providedIn: 'root' })
export class GeometryConversionService {
  private readonly geoJson = new GeoJSON();

  /** Reads a GeoJSON feature (in 4326) into an OL feature (in 3857). */
  gjToOlFeature(feature: GeometryFeature): OlFeature {
    return this.geoJson.readFeature(feature, {
      dataProjection: DATA_PROJECTION,
      featureProjection: FEATURE_PROJECTION,
    }) as OlFeature;
  }

  /** Reads a GeoJSON geometry (in 4326) into an OL geometry (in 3857). */
  gjToOlGeometry(geometry: GeoJsonGeometry): Geometry {
    return this.geoJson.readGeometry(geometry, {
      dataProjection: DATA_PROJECTION,
      featureProjection: FEATURE_PROJECTION,
    });
  }

  /**
   * Writes an OL feature (in 3857) back to a GeoJSON feature (in 4326) — the
   * inverse of {@link gjToOlFeature}, used by the geometry editor's commit path.
   * OL v10 round-trips a `GeometryCollection` correctly (verified by the service
   * spec), so no special-casing is needed.
   */
  olToGjFeature(feature: OlFeature): GeometryFeature {
    const gjFeature = this.geoJson.writeFeatureObject(feature, {
      dataProjection: DATA_PROJECTION,
      featureProjection: FEATURE_PROJECTION,
    }) as GeometryFeature;
    // `selected` is a transient editor-only view flag (drives selection styling); it must
    // not leak into persisted geometry, so it is stripped before save. OL writes
    // `properties: null` for a feature with no other properties, but the Niord backend
    // NPEs on a null feature `properties` (500 on templates/execute), so normalize to {}.
    if (gjFeature.properties) {
      delete gjFeature.properties[SELECTED];
    } else {
      gjFeature.properties = {};
    }
    return gjFeature;
  }

  /** Writes a list of OL features into a GeoJSON `FeatureCollection` (in 4326). */
  olFeaturesToCollection(features: readonly OlFeature[]): GeometryFeatureCollection {
    return {
      type: 'FeatureCollection',
      features: features.map((feature) => this.olToGjFeature(feature)),
    };
  }

  /** Returns a center coordinate for any geometry — interior point for polygons,
   * longest-segment midpoint for lines, identity for points, extent center otherwise. */
  getGeometryCenter(geometry: Geometry): Coordinate {
    if (geometry instanceof Point) {
      return geometry.getCoordinates();
    }
    if (geometry instanceof Polygon) {
      return geometry.getInteriorPoint().getCoordinates().slice(0, 2);
    }
    if (geometry instanceof MultiPolygon) {
      return (
        geometry.getInteriorPoints().getCoordinates()[0]?.slice(0, 2) ??
        olGetExtentCenter(geometry.getExtent())
      );
    }
    if (geometry instanceof LineString) {
      return geometry.getCoordinateAt(0.5);
    }
    if (geometry instanceof MultiLineString) {
      const lines = geometry.getLineStrings();
      let longest = lines[0];
      let longestLength = longest?.getLength() ?? 0;
      for (const line of lines) {
        const length = line.getLength();
        if (length > longestLength) {
          longest = line;
          longestLength = length;
        }
      }
      return longest?.getCoordinateAt(0.5) ?? olGetExtentCenter(geometry.getExtent());
    }
    return olGetExtentCenter(geometry.getExtent());
  }
}
