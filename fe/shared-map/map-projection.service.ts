import { Injectable } from '@angular/core';
import { fromLonLat, toLonLat } from 'ol/proj';
import type { Coordinate } from 'ol/coordinate';
import type { Extent } from 'ol/extent';

export type LonLat = readonly [number, number];
export type LonLatExtent = readonly [number, number, number, number];

@Injectable({ providedIn: 'root' })
export class MapProjectionService {
  fromLonLat(coord: LonLat): Coordinate {
    return fromLonLat([coord[0], coord[1]]);
  }

  toLonLat(coord: Coordinate): [number, number] {
    const [lon, lat] = toLonLat(coord);
    return [lon, lat];
  }

  fromLonLatExtent(extent: LonLatExtent): Extent {
    const [minLon, minLat, maxLon, maxLat] = extent;
    const [minX, minY] = this.fromLonLat([minLon, minLat]);
    const [maxX, maxY] = this.fromLonLat([maxLon, maxLat]);
    return [minX, minY, maxX, maxY];
  }

  toLonLatExtent(extent: Extent): [number, number, number, number] {
    const [minX, minY, maxX, maxY] = extent;
    const [minLon, minLat] = this.toLonLat([minX, minY]);
    const [maxLon, maxLat] = this.toLonLat([maxX, maxY]);
    return [minLon, minLat, maxLon, maxLat];
  }

  round(value: number, decimals = 4): number {
    const factor = 10 ** decimals;
    return Math.round(value * factor) / factor;
  }
}
