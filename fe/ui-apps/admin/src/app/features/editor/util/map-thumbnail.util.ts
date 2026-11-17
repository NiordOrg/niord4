import type { Map as OlMap } from 'ol';

/** The message thumbnail is a fixed 256×256 PNG. */
export const THUMBNAIL_SIZE = 256;

/**
 * Capture the currently-rendered OpenLayers map as a square PNG data URL.
 *
 * OpenLayers 10 renders each layer to its OWN `<canvas>` element, so there is no single viewport
 * canvas to read — the per-layer canvases are composited into one, honouring each canvas's opacity
 * and CSS transform (the OL-recommended export recipe). The composite is then centre-cropped to a
 * square and scaled to {@link THUMBNAIL_SIZE}, so even a non-square viewport yields an undistorted
 * thumbnail.
 *
 * Resolves with a `data:image/png;base64,…` string (the shape the map-image endpoint expects), or
 * rejects if the map is unsized, has no 2D context, or the canvas is cross-origin tainted. A tainted
 * canvas throws on `toDataURL`; the thumbnail map deliberately shows only the CORS-enabled OSM base
 * plus vector geometry to avoid this, but a caller that adds a non-CORS tile/WMS layer would trip it.
 */
export function captureMapThumbnail(map: OlMap, size = THUMBNAIL_SIZE): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    map.once('rendercomplete', () => {
      try {
        const mapSize = map.getSize();
        if (!mapSize) {
          reject(new Error('Map has no size'));
          return;
        }
        const [width, height] = mapSize;

        const composite = document.createElement('canvas');
        composite.width = width;
        composite.height = height;
        const ctx = composite.getContext('2d');
        if (!ctx) {
          reject(new Error('No 2D rendering context'));
          return;
        }

        const layerCanvases = map
          .getViewport()
          .querySelectorAll<HTMLCanvasElement>('.ol-layer canvas, canvas.ol-layer');
        for (const canvas of layerCanvases) {
          if (canvas.width === 0) {
            continue;
          }
          const opacity =
            (canvas.parentElement as HTMLElement | null)?.style.opacity || canvas.style.opacity;
          ctx.globalAlpha = opacity === '' ? 1 : Number(opacity);

          // Re-apply the canvas's own CSS transform (device-pixel → CSS-pixel scale/offset) so the
          // layer lands in the right place; fall back to identity when there is no matrix transform.
          const match = /^matrix\(([^)]+)\)$/.exec(canvas.style.transform);
          const [a = 1, b = 0, c = 0, d = 1, e = 0, f = 0] =
            match?.[1]?.split(',').map(Number) ?? [];
          ctx.setTransform(a, b, c, d, e, f);
          ctx.drawImage(canvas, 0, 0);
        }
        ctx.globalAlpha = 1;
        ctx.setTransform(1, 0, 0, 1, 0, 0);

        // Centre-crop to a square, then down-scale to the thumbnail size (undistorted).
        const side = Math.min(width, height);
        const out = document.createElement('canvas');
        out.width = size;
        out.height = size;
        const outCtx = out.getContext('2d');
        if (!outCtx) {
          reject(new Error('No 2D rendering context'));
          return;
        }
        outCtx.drawImage(
          composite,
          (width - side) / 2,
          (height - side) / 2,
          side,
          side,
          0,
          0,
          size,
          size,
        );
        resolve(out.toDataURL('image/png'));
      } catch (error) {
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
    // Force a synchronous render cycle so `rendercomplete` fires (immediately if all tiles are
    // already loaded, otherwise once the outstanding tiles finish).
    map.renderSync();
  });
}
