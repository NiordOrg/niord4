import { expect, test } from '@playwright/test';

// Geometry-editor sandbox smoke tests. The sandbox is a dev-only route
// (`/dev/geo-editor`, registered only when !environment.production), so it needs
// no auth. It is the standalone regression net for the shared geometry editor,
// independent of any hosting feature. Requires `npm start` (the dev build exposes the route).
//
// The richer pixel-precise interactions (modify a vertex, shift-delete, ctrl box-select,
// translate, file drop) are exercised in manual testing — they need
// canvas-pixel precision that is flaky to drive headless. The delete logic is covered
// deterministically by the RemoveInteraction unit spec.

test.describe('geometry editor sandbox (dev harness)', () => {
  test('loads, seeds a point, and commits it back as GeoJSON', async ({ page }) => {
    await page.goto('/dev/geo-editor');

    await expect(page.getByRole('heading', { name: 'Geometry editor sandbox' })).toBeVisible();

    // Seed a Point shape, then EDIT it before committing. Seeding goes through the `[geometry]`
    // input, which re-imports and re-baselines, so the editor is not dirty — and `commit()`
    // deliberately closes like Cancel rather than emitting when nothing was touched (it must not
    // manufacture a change on a geometry nobody edited). A second point makes it a real edit.
    await page.getByRole('button', { name: 'Seed: Point' }).click();

    const canvas = page.locator('app-ol-map canvas').first();
    await expect(canvas).toBeVisible();
    await canvas.click({ position: { x: 220, y: 200 } }); // point mode is preselected on open

    await page.getByRole('button', { name: 'OK', exact: true }).click();

    const committed = page.getByTestId('committed-json');
    await expect(committed).toContainText('FeatureCollection');
    await expect(committed).toContainText('Point');
  });

  test('OK without an edit closes without emitting (it must not manufacture a change)', async ({
    page,
  }) => {
    await page.goto('/dev/geo-editor');

    // Seeding re-baselines the editor, so there is nothing to commit.
    await page.getByRole('button', { name: 'Seed: Point' }).click();
    await page.getByRole('button', { name: 'OK', exact: true }).click();

    await expect(page.getByTestId('committed-json')).toBeEmpty();
  });

  test('draws a point on the map and commits it', async ({ page }) => {
    await page.goto('/dev/geo-editor');

    // Switch to Point draw mode. Scoped to the editor so the aside's "Seed: Point" button
    // can't match; non-exact because the icon's CSS glyph participates in the accessible name.
    await page.locator('app-geometry-editor').getByRole('button', { name: 'Point' }).click();

    // Click the map canvas to drop a point (OL Draw 'Point' commits on a single click).
    const canvas = page.locator('app-ol-map canvas').first();
    await expect(canvas).toBeVisible();
    await canvas.click({ position: { x: 220, y: 200 } });

    await page.getByRole('button', { name: 'OK', exact: true }).click();

    const committed = page.getByTestId('committed-json');
    await expect(committed).toContainText('FeatureCollection');
    await expect(committed).toContainText('Point');
  });

  // Dispatches a synthetic file drop on the OL map viewport (OL's DragAndDrop listens for
  // the native `drop`). Standard Playwright cannot drop a File onto a div, so we build the
  // DataTransfer in-page.
  async function dropFile(
    page: import('@playwright/test').Page,
    name: string,
    type: string,
    content: string,
  ): Promise<void> {
    // The map mounts after Angular boots the lazy route — evaluate() would otherwise race it.
    await expect(page.locator('app-ol-map .ol-viewport')).toBeVisible();
    await page.evaluate(
      ([fileName, fileType, text]) => {
        const vp = document.querySelector('app-ol-map .ol-viewport');
        if (!vp) throw new Error('no map viewport');
        const dt = new DataTransfer();
        dt.items.add(new File([text], fileName, { type: fileType }));
        for (const evt of ['dragenter', 'dragover', 'drop']) {
          vp.dispatchEvent(
            new DragEvent(evt, { bubbles: true, cancelable: true, dataTransfer: dt }),
          );
        }
      },
      [name, type, content] as const,
    );
  }

  test('committing a large dropped geometry keeps the map visible (no layout collapse)', async ({
    page,
  }) => {
    await page.goto('/dev/geo-editor');

    // A large MultiPolygon — the case that can blow out the layout and collapse the map.
    const ring: [number, number][] = [];
    for (let i = 0; i < 2000; i++) {
      const a = (i / 2000) * 2 * Math.PI;
      ring.push([10 + Math.cos(a) * 1.5, 56 + Math.sin(a) * 0.8]);
    }
    ring.push(ring[0]);
    const geojson = JSON.stringify({
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: {},
          geometry: { type: 'MultiPolygon', coordinates: [[ring]] },
        },
      ],
    });
    await dropFile(page, 'big.geojson', 'application/geo+json', geojson);
    await page.getByRole('button', { name: 'OK', exact: true }).click();

    // The map canvas must still occupy real height (regression: it collapsed to ~0).
    const canvas = page.locator('app-ol-map canvas').first();
    await expect(canvas).toBeVisible();
    const box = await canvas.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThan(200);
  });

  test('imports a dropped WKT file (projected, multi-line)', async ({ page }) => {
    await page.goto('/dev/geo-editor');

    // Two geometries on separate lines — WKT carries no projection, so this also guards the
    // EPSG:4326 fix (otherwise the features land off-screen).
    await dropFile(
      page,
      'shapes.wkt',
      'text/plain',
      'POLYGON ((10 55, 12 55, 12 57, 10 57, 10 55))\nPOINT (5 50)',
    );
    await page.getByRole('button', { name: 'OK', exact: true }).click();

    const committed = page.getByTestId('committed-json');
    await expect(committed).toContainText('Polygon');
    await expect(committed).toContainText('Point');
  });

  test('imports feature names into per-language keys and preserves the description', async ({
    page,
  }) => {
    await page.goto('/dev/geo-editor');

    const geojson = JSON.stringify({
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: { name: 'kml_106', description: '<center>Attributes</center>' },
          geometry: { type: 'Point', coordinates: [11, 56] },
        },
      ],
    });
    await dropFile(page, 'named.geojson', 'application/geo+json', geojson);
    await page.getByRole('button', { name: 'OK', exact: true }).click();
    await expect(page.getByTestId('committed-json')).toContainText('kml_106');

    const result = await page.evaluate(() => {
      const pre = document.querySelector('[data-testid="committed-json"]');
      const parsed = JSON.parse(pre?.textContent ?? '{}');
      const props = (parsed.features?.[0]?.properties ?? {}) as Record<string, unknown>;
      return {
        perLangNameKeys: Object.keys(props).filter((k) => k.startsWith('name:')),
        description: props['description'],
      };
    });

    // The plain `name` was copied into per-language `name:<lang>` keys...
    expect(result.perLangNameKeys.length).toBeGreaterThan(0);
    // ...and the description round-trips untouched.
    expect(String(result.description)).toContain('Attributes');
  });

  test('adds an affected radius to a non-point geometry (polygon → buffered affected area)', async ({
    page,
  }) => {
    await page.goto('/dev/geo-editor');
    await page.getByRole('button', { name: 'Seed: Polygon' }).click();

    // The editor opens in POINT mode (adding a position is the common opening move), so switch to
    // Select before clicking the map — otherwise the click would drop a point instead of selecting.
    await page.locator('app-geometry-editor').getByRole('button', { name: 'Select' }).click();

    // Select the polygon by clicking its interior, then add the affected radius — which now works
    // on any geometry, not just points.
    const canvas = page.locator('app-ol-map canvas').first();
    await expect(canvas).toBeVisible();
    await canvas.click(); // centre of the canvas falls inside the seeded polygon

    await page.locator('app-geometry-editor').getByRole('button', { name: 'Actions' }).click();
    await page.getByRole('menuitem', { name: 'Add affected radius' }).click();

    await page.getByRole('button', { name: 'OK', exact: true }).click();

    // The commit gains a second feature: the buffered affected area (an offset polygon).
    const committed = page.getByTestId('committed-json');
    await expect(committed).toContainText('affected');
    const affected = await page.evaluate(() => {
      const pre = document.querySelector('[data-testid="committed-json"]');
      const parsed = JSON.parse(pre?.textContent || '{}') as {
        features?: { properties?: Record<string, unknown>; geometry?: { type?: string } }[];
      };
      return (
        (parsed.features ?? []).find((f) => f.properties?.['restriction'] === 'affected') ?? null
      );
    });
    expect(affected).not.toBeNull();
    expect(affected?.properties?.['bufferType']).toBe('radius');
    expect(['Polygon', 'MultiPolygon']).toContain(affected?.geometry?.type);
  });

  test('edits a vertex DDM in the geometry tree and the committed geometry reflects it', async ({
    page,
  }) => {
    await page.goto('/dev/geo-editor');
    await page.getByRole('button', { name: 'Seed: Polygon' }).click();

    // Click the first DDM position in the tree (a position button carries a degree sign).
    const positions = page.locator("app-geometry-tree-node button:has-text('°')");
    await expect(positions.first()).toBeVisible();
    await positions.first().click();

    // Symbol-free, dot-decimal DDM parses in any locale (parseLatLon is lenient).
    const input = page.locator('app-geometry-tree-node input');
    await input.fill('58 00.000 N 010 00.000 E');
    await input.press('Enter');

    await page.getByRole('button', { name: 'OK', exact: true }).click();

    await expect(page.getByTestId('committed-json')).toContainText('Polygon');
    const firstVertex = await page.evaluate(() => {
      const pre = document.querySelector('[data-testid="committed-json"]');
      const parsed = JSON.parse(pre?.textContent || '{}');
      return parsed.features?.[0]?.geometry?.coordinates?.[0]?.[0] ?? null;
    });
    // The edited vertex (and the closing vertex) moved to ~58° N.
    expect(firstVertex?.[1]).toBeCloseTo(58, 1);
  });
});
