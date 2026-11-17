import { describe, expect, it } from 'vitest';

import type { RootAreaSpec } from '../../niord-info.config';
import { initialMapView, rootAreaView, WORLD_VIEW } from './root-area-view';

const ROOT_XA: RootAreaSpec = { areaId: 'urn:xa', latitude: 56, longitude: 11, zoomLevel: 7 };
const ROOT_XB: RootAreaSpec = { areaId: 'urn:xb', latitude: 72, longitude: -40, zoomLevel: 4 };

describe('rootAreaView', () => {
  it('centres on the area at its own zoom, longitude first', () => {
    expect(rootAreaView(ROOT_XB)).toEqual({ center: [-40, 72], zoom: 4 });
  });

  it('frames nothing for an area whose framing is incomplete', () => {
    expect(rootAreaView(undefined)).toBeUndefined();
    expect(rootAreaView({ latitude: 56, longitude: 11 })).toBeUndefined();
  });

  it('takes the equator and the prime meridian as positions, not as missing', () => {
    expect(rootAreaView({ latitude: 0, longitude: 0, zoomLevel: 3 })).toEqual({
      center: [0, 0],
      zoom: 3,
    });
  });
});

describe('initialMapView', () => {
  it("starts on the country's first root area", () => {
    expect(initialMapView([ROOT_XB, ROOT_XA])).toEqual({ center: [-40, 72], zoom: 4 });
  });

  it('starts on the root area it is asked for, when the deployment has it', () => {
    expect(initialMapView([ROOT_XA, ROOT_XB], 'urn:xb')).toEqual({ center: [-40, 72], zoom: 4 });
    expect(initialMapView([ROOT_XA, ROOT_XB], 'urn:elsewhere')).toEqual({
      center: [11, 56],
      zoom: 7,
    });
  });

  it('starts on the world in a deployment with no root area', () => {
    expect(initialMapView([])).toEqual(WORLD_VIEW);
  });
});
