import { Injectable, inject } from '@angular/core';
import GeoJSON from 'ol/format/GeoJSON.js';
import type Feature from 'ol/Feature.js';
import type Geometry from 'ol/geom/Geometry.js';
import type Point from 'ol/geom/Point.js';
import { getCenter } from 'ol/extent.js';
import { LanguageService } from './language.service';

const PROJ_MERCATOR = 'EPSG:3857';
const PROJ_4326 = 'EPSG:4326';

/**
 * What the public site does with a message's geometry beyond drawing it: turning its GeoJSON into
 * map features, finding the middle a name is written at, and listing the coordinates a details map
 * numbers. Projecting a position is the map kit's (`MapProjectionService`), not this service's.
 */
@Injectable({ providedIn: 'root' })
export class MapService {
  private readonly languageService = inject(LanguageService);
  private readonly geoJsonFormat = new GeoJSON();

  /** Returns a "sensible" center point of the geometry */
  getGeometryCenter(g: Geometry): number[] | undefined {
    try {
      switch (g.getType()) {
        case 'MultiPolygon': {
          const mp = g as import('ol/geom/MultiPolygon.js').default;
          const poly = mp
            .getPolygons()
            .reduce((left, right) =>
              (left.getArea?.() ?? 0) > (right.getArea?.() ?? 0) ? left : right,
            );
          return poly.getInteriorPoint().getCoordinates();
        }
        case 'MultiLineString': {
          const mls = g as import('ol/geom/MultiLineString.js').default;
          const lineString = mls
            .getLineStrings()
            .reduce((left, right) =>
              (left.getLength?.() ?? 0) > (right.getLength?.() ?? 0) ? left : right,
            );
          return getCenter(lineString.getExtent());
        }
        case 'Polygon': {
          const pg = g as import('ol/geom/Polygon.js').default;
          return pg.getInteriorPoint().getCoordinates();
        }
        case 'Point':
          return (g as Point).getCoordinates();
        case 'LineString':
        case 'MultiPoint':
        case 'GeometryCollection':
          return getCenter(g.getExtent());
        default:
          return undefined;
      }
    } catch {
      return undefined;
    }
  }

  /** Converts a GeoJSON feature to an OL feature */
  gjToOlFeature(feature: unknown): Feature {
    return this.geoJsonFormat.readFeature(feature, {
      dataProjection: PROJ_4326,
      featureProjection: PROJ_MERCATOR,
    }) as Feature;
  }

  /**
   * Appends to `coords` the positions of a geometry that a details map numbers and lists, in the
   * order the geometry gives them, with each position's name in the reader's language, and
   * returns the index after the last position visited. The index counts every position, listed
   * or not, because a feature names its positions by that index (`name:<index>:<lang>`), and it
   * starts again at 0 for each feature. Left out are the closing repeat of a polygon's outer
   * ring, the rings of its holes, and every position of a buffer drawn around another feature
   * or of an affected area — none of them is a position the message gives.
   */
  serializeReadableCoordinates(
    g: unknown,
    coords: { lon: number; lat: number; index: number; name?: string }[],
    props?: Record<string, unknown>,
    index = 0,
    polygonType?: string,
  ): number {
    props = props || {};
    const language = this.languageService.language();

    if (!g) return index;

    if (Array.isArray(g)) {
      if (g.length >= 2 && typeof g[0] === 'number') {
        const bufferFeature = props['parentFeatureIds'];
        const affectedArea = props['restriction'] === 'affected';
        const includeCoord = polygonType !== 'Exterior';
        if (includeCoord && !bufferFeature && !affectedArea) {
          coords.push({
            lon: g[0],
            lat: g[1],
            index,
            name: props[`name:${index}:${language}`] as string | undefined,
          });
        }
        index++;
      } else {
        for (let x1 = 0; x1 < g.length; x1++) {
          const pt = polygonType === 'Interior' && x1 === g.length - 1 ? 'Exterior' : polygonType;
          index = this.serializeReadableCoordinates(g[x1], coords, props, index, pt);
        }
      }
    } else if (typeof g === 'object' && g !== null) {
      const gObj = g as Record<string, unknown>;
      if (gObj['type'] === 'FeatureCollection') {
        const features = (gObj['features'] as unknown[]) || [];
        for (const feat of features) {
          index = this.serializeReadableCoordinates(feat, coords);
        }
      } else if (gObj['type'] === 'Feature') {
        index = this.serializeReadableCoordinates(
          gObj['geometry'],
          coords,
          gObj['properties'] as Record<string, unknown>,
          0,
        );
      } else if (gObj['type'] === 'GeometryCollection') {
        const geometries = (gObj['geometries'] as unknown[]) || [];
        for (const geom of geometries) {
          index = this.serializeReadableCoordinates(geom, coords, props, index);
        }
      } else if (gObj['type'] === 'MultiPolygon') {
        const coordinates = gObj['coordinates'] as unknown[][][];
        for (const polygon of coordinates) {
          for (let x4 = 0; x4 < polygon.length; x4++) {
            index = this.serializeReadableCoordinates(
              polygon[x4],
              coords,
              props,
              index,
              x4 === 0 ? 'Interior' : 'Exterior',
            );
          }
        }
      } else if (gObj['type'] === 'Polygon') {
        const coordinates = gObj['coordinates'] as unknown[][];
        for (let x5 = 0; x5 < coordinates.length; x5++) {
          index = this.serializeReadableCoordinates(
            coordinates[x5],
            coords,
            props,
            index,
            x5 === 0 ? 'Interior' : 'Exterior',
          );
        }
      } else if (gObj['type']) {
        index = this.serializeReadableCoordinates(gObj['coordinates'], coords, props, index);
      }
    }
    return index;
  }
}
