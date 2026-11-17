import { Feature } from 'ol';
import { Point } from 'ol/geom';
import { describe, expect, it } from 'vitest';

import { applyImportedFeatureNames } from './import-feature-names';

function named(props: Record<string, unknown>): Feature {
  const feature = new Feature(new Point([0, 0]));
  for (const [key, value] of Object.entries(props)) {
    feature.set(key, value);
  }
  return feature;
}

describe('applyImportedFeatureNames', () => {
  it('copies a dropped `name` into name:<lang> for each model language', () => {
    const feature = named({ name: 'kml_106' });
    applyImportedFeatureNames(feature, ['en', 'da']);
    expect(feature.get('name:en')).toBe('kml_106');
    expect(feature.get('name:da')).toBe('kml_106');
  });

  it('does not clobber an existing per-language name', () => {
    const feature = named({ name: 'generic', 'name:en': 'specific' });
    applyImportedFeatureNames(feature, ['en', 'da']);
    expect(feature.get('name:en')).toBe('specific');
    expect(feature.get('name:da')).toBe('generic');
  });

  it('is a no-op for a feature without a name (e.g. WKT)', () => {
    const feature = named({});
    applyImportedFeatureNames(feature, ['en', 'da']);
    expect(feature.get('name:en')).toBeUndefined();
  });

  it('leaves the description untouched (it round-trips but is not a name)', () => {
    const feature = named({ name: 'n', description: '<center><table></table></center>' });
    applyImportedFeatureNames(feature, ['en']);
    expect(feature.get('description')).toBe('<center><table></table></center>');
  });
});
