/**
 * Patches `HTMLCanvasElement.prototype.getContext` so every 2D context the
 * page creates is given `willReadFrequently: true` unless the caller has
 * explicitly set it. Chrome (and other Chromium-based browsers) warn — and
 * silently fall back to a slow GPU→CPU readback path — for repeated
 * `getImageData` calls on canvases without this hint. OL v10 hits that path
 * on every pan/zoom animation frame (hit-detection canvas for vector icons,
 * plus layer-composition reads in the tile renderer), so the warning fires
 * dozens of times per gesture. OL doesn't expose this option, so a one-time
 * prototype patch is the only realistic fix.
 *
 * The hint costs a small amount of extra system memory for canvases that
 * never read back (the backing store stays on the CPU), which is fine for
 * this app — we don't host any large draw-only canvases outside OL.
 *
 * Idempotent: calling twice has no further effect.
 */
let patched = false;

export function enableWillReadFrequentlyHint(): void {
  if (patched) return;
  if (typeof HTMLCanvasElement === 'undefined') return;
  patched = true;
  const original = HTMLCanvasElement.prototype.getContext as (
    this: HTMLCanvasElement,
    contextId: string,
    options?: unknown,
  ) => RenderingContext | null;
  HTMLCanvasElement.prototype.getContext = function (
    this: HTMLCanvasElement,
    contextId: string,
    options?: unknown,
  ): RenderingContext | null {
    if (contextId !== '2d') return original.call(this, contextId, options);
    const opts = (options ?? {}) as CanvasRenderingContext2DSettings;
    const finalOpts: CanvasRenderingContext2DSettings =
      opts.willReadFrequently === undefined ? { ...opts, willReadFrequently: true } : opts;
    return original.call(this, contextId, finalOpts);
  } as typeof HTMLCanvasElement.prototype.getContext;
}
