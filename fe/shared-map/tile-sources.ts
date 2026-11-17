import { InjectionToken } from '@angular/core';

/**
 * The tile URL templates a deployment serves its base map from, each with the `{z}`, `{x}` and
 * `{y}` placeholders OpenLayers fills in. An address that is unset or blank leaves the layer's own
 * default: OpenLayers' `https://tile.openstreetmap.org/{z}/{x}/{y}.png` for OpenStreetMap, and the
 * public OpenSeaMap tile server for the seamark overlay.
 */
export interface MapTileSources {
  readonly osmUrl?: string;
  readonly openSeaMapUrl?: string;
}

/**
 * The deployment's tile addresses — the one thing an application owns that reaches the kit by
 * injection instead of as an input. The addresses are the same for every map an application
 * draws, so each application provides this once, at its root, from its own settings, and a tile
 * layer's `url` input still wins over it for a map that needs another source.
 *
 * The default is empty, so a map with nothing provided (a spec, a host that never sets one) draws
 * from the layers' own defaults. An application provides it as getters over its configuration,
 * not as a copy: the settings arrive at boot, after the providers are built, and a layer reads the
 * addresses when it is created.
 *
 * This file imports nothing but `@angular/core`: both applications provide the token from their
 * initial chunk, which must not pull OpenLayers in.
 */
export const MAP_TILE_SOURCES = new InjectionToken<MapTileSources>('MAP_TILE_SOURCES', {
  providedIn: 'root',
  factory: () => ({}),
});
