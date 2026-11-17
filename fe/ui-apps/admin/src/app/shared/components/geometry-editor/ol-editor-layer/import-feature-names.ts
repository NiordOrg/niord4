import type { Feature as OlFeature } from 'ol';

/**
 * Copies a dropped feature's plain `name` (set by OL's GeoJSON/KML reader from
 * `properties.name` / the KML `<name>`) into the per-language `name:<lang>` keys the
 * editor renders on the map + the geometry tree edits — for every model language that
 * does not already have one.
 *
 * `description` is intentionally left untouched: it round-trips as a property but is not
 * drawn on the map. WKT carries no properties, so this is a no-op there.
 */
export function applyImportedFeatureNames(feature: OlFeature, languages: readonly string[]): void {
  const name = feature.get('name');
  if (typeof name !== 'string' || name.length === 0) return;
  for (const lang of languages) {
    if (feature.get(`name:${lang}`) === undefined) {
      feature.set(`name:${lang}`, name);
    }
  }
}
