/**
 * Reads a CSS custom property from `:root` at runtime.
 *
 * Use this to bridge JS code that needs to honour Layer 2 CSS tokens —
 * OpenLayers style configuration, canvas rendering, anywhere a JS string
 * value drives a color or dimension. For pure-CSS styling, prefer `var(...)`
 * directly.
 *
 * Returns `fallback` when:
 * - `document` is undefined (SSR / non-browser context).
 * - The custom property is not defined on `:root`.
 *
 * Reads once per call. For hot paths (per-feature OL style functions,
 * per-frame canvas), cache the result at component construction. The global
 * stylesheet is loaded before Angular bootstraps any component, so reading
 * in a constructor or class-field initializer is safe.
 *
 * @example
 *   private readonly fill = readCssVar('--niord-pin-fill', '#8B008B');
 */
export function readCssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}
