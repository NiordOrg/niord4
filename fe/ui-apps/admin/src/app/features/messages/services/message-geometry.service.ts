import { inject, Injectable } from '@angular/core';
import type { Geometry as GeoJsonGeometry } from 'geojson';
import type { Feature as OlFeature } from 'ol';
import type { Coordinate } from 'ol/coordinate';
import type Geometry from 'ol/geom/Geometry';

import type { GeometryFeature } from '../../../shared/models/geometry.model';
import { GeometryConversionService } from '../../../shared/services/geometry-conversion.service';
import type { Message, MessageGeometryFeatureCollection } from '../models/message.model';

export type { GeoJsonProperties } from '../../../shared/models/geometry.model';
export type MessageGeoJsonFeature = GeometryFeature;

export interface ReadableCoord {
  readonly lonLat: readonly [number, number];
  readonly index: number;
  readonly name?: string;
}

const AFFECTED_RESTRICTION = 'affected';
const PARENT_FEATURE_IDS = 'parentFeatureIds';
const RESTRICTION = 'restriction';

type FeatureProperties = Record<string, unknown>;

@Injectable({ providedIn: 'root' })
export class MessageGeometryService {
  private readonly conversion = inject(GeometryConversionService);

  /** Walks message.parts[].geometry.features and returns the flat list. */
  getFeatures(
    message: Message,
    opts?: { readonly excludeAffectedAreas?: boolean },
  ): MessageGeoJsonFeature[] {
    const excludeAffected = opts?.excludeAffectedAreas === true;
    const out: MessageGeoJsonFeature[] = [];
    for (const part of message.parts ?? []) {
      const collection = part.geometry;
      if (!collection) continue;
      for (const feature of collection.features) {
        const typed = feature as MessageGeoJsonFeature;
        if (excludeAffected && typed.properties?.[RESTRICTION] === AFFECTED_RESTRICTION) {
          continue;
        }
        out.push(typed);
      }
    }
    return out;
  }

  /** Reads a GeoJSON feature (in 4326) into an OL feature (in 3857). */
  gjToOlFeature(feature: MessageGeoJsonFeature): OlFeature {
    return this.conversion.gjToOlFeature(feature);
  }

  /** Reads a GeoJSON geometry (in 4326) into an OL geometry (in 3857). */
  gjToOlGeometry(geometry: GeoJsonGeometry): Geometry {
    return this.conversion.gjToOlGeometry(geometry);
  }

  /** Writes an OL feature (in 3857) back to a GeoJSON feature (in 4326). */
  olToGjFeature(feature: OlFeature): MessageGeoJsonFeature {
    return this.conversion.olToGjFeature(feature);
  }

  /** Writes a list of OL features into a GeoJSON `FeatureCollection` (in 4326). */
  olFeaturesToCollection(features: readonly OlFeature[]): MessageGeometryFeatureCollection {
    return this.conversion.olFeaturesToCollection(features);
  }

  /** Returns a center coordinate for any geometry — interior point for polygons,
   * longest-segment midpoint for lines, identity for points, extent center otherwise. */
  getGeometryCenter(geometry: Geometry): Coordinate {
    return this.conversion.getGeometryCenter(geometry);
  }

  /** Flattens a GeoJSON feature's geometry into a list of readable coordinates.
   * Skips features tagged restriction='affected' or with parentFeatureIds (buffered).
   * Skips interior rings and closing coordinates of linear rings.
   *
   * The optional `lang` selects which per-coordinate name to surface when the
   * feature carries `name:<idx>:<lang>` properties; falls back to the feature's
   * own `language` property and then to any matching key. Without this filter,
   * a Danish user could see English coordinate names (or vice versa) when the
   * feature contains both — see the message-details map labels bug.
   */
  serializeReadableCoordinates(feature: MessageGeoJsonFeature, lang?: string): ReadableCoord[] {
    const props = feature.properties ?? {};
    if (props[PARENT_FEATURE_IDS] !== undefined) {
      return [];
    }
    if (props[RESTRICTION] === AFFECTED_RESTRICTION) {
      return [];
    }
    const out: ReadableCoord[] = [];
    const counter = { index: 0 };
    this.collectCoords(feature.geometry, props, out, counter, 'Exterior', lang);
    return out;
  }

  private collectCoords(
    geometry: GeoJsonGeometry | null,
    props: FeatureProperties,
    out: ReadableCoord[],
    counter: { index: number },
    ringRole: 'Exterior' | 'Interior',
    lang?: string,
  ): void {
    if (!geometry) return;
    switch (geometry.type) {
      case 'Point':
        this.pushCoord(geometry.coordinates as [number, number], props, out, counter, lang);
        return;
      case 'MultiPoint':
        for (const coord of geometry.coordinates) {
          this.pushCoord(coord as [number, number], props, out, counter, lang);
        }
        return;
      case 'LineString':
        this.pushCoords(
          geometry.coordinates as [number, number][],
          props,
          out,
          counter,
          false,
          lang,
        );
        return;
      case 'MultiLineString':
        for (const line of geometry.coordinates) {
          this.pushCoords(line as [number, number][], props, out, counter, false, lang);
        }
        return;
      case 'Polygon':
        for (let i = 0; i < geometry.coordinates.length; i++) {
          const role: 'Exterior' | 'Interior' = i === 0 ? 'Exterior' : 'Interior';
          if (role === 'Interior') continue;
          this.pushCoords(
            geometry.coordinates[i] as [number, number][],
            props,
            out,
            counter,
            true,
            lang,
          );
        }
        return;
      case 'MultiPolygon':
        for (const polygon of geometry.coordinates) {
          for (let i = 0; i < polygon.length; i++) {
            const role: 'Exterior' | 'Interior' = i === 0 ? 'Exterior' : 'Interior';
            if (role === 'Interior') continue;
            this.pushCoords(polygon[i] as [number, number][], props, out, counter, true, lang);
          }
        }
        return;
      case 'GeometryCollection':
        for (const child of geometry.geometries) {
          this.collectCoords(child, props, out, counter, ringRole, lang);
        }
        return;
    }
  }

  private pushCoords(
    coords: readonly [number, number][],
    props: FeatureProperties,
    out: ReadableCoord[],
    counter: { index: number },
    isLinearRing: boolean,
    lang?: string,
  ): void {
    const limit = isLinearRing ? coords.length - 1 : coords.length;
    for (let i = 0; i < limit; i++) {
      this.pushCoord(coords[i], props, out, counter, lang);
    }
  }

  private pushCoord(
    coord: readonly [number, number],
    props: FeatureProperties,
    out: ReadableCoord[],
    counter: { index: number },
    lang?: string,
  ): void {
    const idx = counter.index++;
    const name = this.findCoordName(props, idx, lang);
    out.push({ lonLat: [coord[0], coord[1]], index: idx, name });
  }

  private findCoordName(
    props: FeatureProperties,
    index: number,
    lang?: string,
  ): string | undefined {
    // Three-tier lookup: user's lang first, then the feature's own `language`
    // property, then any `name:<idx>:*` as a last resort. The fallbacks are
    // deliberate — a missing translation degrades gracefully instead of
    // dropping the label.
    const tryKey = (key: string): string | undefined => {
      const value = props[key];
      return typeof value === 'string' && value.length > 0 ? value : undefined;
    };
    if (lang) {
      const exact = tryKey(`name:${index}:${lang}`);
      if (exact !== undefined) return exact;
    }
    const featureLang = props['language'];
    if (typeof featureLang === 'string' && featureLang !== lang) {
      const fallback = tryKey(`name:${index}:${featureLang}`);
      if (fallback !== undefined) return fallback;
    }
    const prefix = `name:${index}`;
    for (const key of Object.keys(props)) {
      if (key === prefix || key.startsWith(`${prefix}:`)) {
        const v = tryKey(key);
        if (v !== undefined) return v;
      }
    }
    return undefined;
  }
}

export function isFeatureCollection(
  geometry: MessageGeometryFeatureCollection | undefined,
): geometry is MessageGeometryFeatureCollection {
  return geometry !== undefined && geometry.type === 'FeatureCollection';
}
