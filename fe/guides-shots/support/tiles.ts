import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';

import { test, type Page } from '@playwright/test';

import { TILE_CACHE } from './env';
import { joinPath } from './paths';

/*
 * The map tiles a picture shows, kept on disk the first time they are fetched and served from there
 * afterwards. OpenStreetMap's tile servers render a tile again whenever the data under it changes,
 * and two runs a minute apart were seen to get one tile in two versions, so a map taken twice
 * differed in a few hundred pixels along its roads. Served from the cache, a picture taken again
 * shows the same tiles, and the tile servers are asked for each tile once, as their usage policy
 * asks of a tool that loads the same view again and again. Deleting the folder takes current tiles.
 */

// The tile servers whose answers are kept: OpenStreetMap's (the base product's default) and
// OpenSeaMap's sea marks, both addressed /{z}/{x}/{y}.png.
const TILE_URL = /^https:\/\/(?:[a-c]\.)?tile\.(?:openstreetmap\.org|openseamap\.org)\/.*\.png$/;

function cachePath(url: string): string {
  const address = new URL(url);
  return joinPath(cacheDir(), address.hostname, ...address.pathname.split('/').filter(Boolean));
}

function cacheDir(): string {
  return TILE_CACHE || joinPath(test.info().project.testDir, '.tile-cache');
}

/** Serves the page's map tiles from the rig's tile cache, filling it on the first fetch. */
export async function cacheTiles(page: Page): Promise<void> {
  if (TILE_CACHE === 'off') return;
  await page.route(TILE_URL, async (route) => {
    const path = cachePath(route.request().url());
    if (existsSync(path)) {
      await route.fulfill({ status: 200, contentType: 'image/png', body: readFileSync(path) });
      return;
    }
    const response = await route.fetch();
    const body = await response.body();
    if (response.ok() && body.length > 0) {
      mkdirSync(path.slice(0, path.lastIndexOf('/')), { recursive: true });
      writeFileSync(path, body);
    }
    await route.fulfill({ response, body });
  });
}
