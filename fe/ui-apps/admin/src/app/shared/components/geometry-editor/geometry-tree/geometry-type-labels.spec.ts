import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../languages/en/i18n/admin/en.json';
import { geometryTypeLabel, LABELLED_GEOMETRY_TYPES } from './geometry-type-labels';

type TranslationNode = string | { readonly [key: string]: TranslationNode };

function labelIn(bundle: object, key: string): string | undefined {
  let node: TranslationNode | undefined = bundle as TranslationNode;
  for (const segment of key.split('.')) {
    if (node === undefined || typeof node === 'string') {
      return undefined;
    }
    node = node[segment];
  }
  return typeof node === 'string' ? node : undefined;
}

describe('geometryTypeLabel', () => {
  it('names every geometry type and ring in words, never by its identifier', () => {
    const texts = LABELLED_GEOMETRY_TYPES.map((type) => {
      const label = geometryTypeLabel(type);
      return [type, label.key === undefined ? undefined : labelIn(en, label.key)] as const;
    });
    expect(texts.filter(([, text]) => text === undefined)).toEqual([]);
    expect(Object.fromEntries(texts)).toMatchObject({
      Point: 'Point',
      LineString: 'Line',
      Polygon: 'Area',
      Exterior: 'Outer ring',
      Interior: 'Inner ring',
    });
  });

  it('shows a type outside the set as it is, rather than as a text key', () => {
    expect(geometryTypeLabel('Circle')).toEqual({ text: 'Circle' });
    expect(geometryTypeLabel('')).toEqual({ text: '' });
  });
});
