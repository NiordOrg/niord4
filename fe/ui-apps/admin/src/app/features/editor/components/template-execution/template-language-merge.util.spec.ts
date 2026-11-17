import { describe, expect, it } from 'vitest';

import type { SystemCategory } from '../../../messages/models/template.model';
import { mergeTemplateLanguages } from './template-language-merge.util';

const cat = (over: Partial<SystemCategory>): SystemCategory => ({
  id: 1,
  type: 'TEMPLATE',
  ...over,
});

describe('mergeTemplateLanguages', () => {
  it('combines category + param descs from every language response', () => {
    const en: SystemCategory[] = [
      cat({
        id: 7,
        descs: [{ lang: 'en', name: 'Wreck' }],
        templateParams: [{ paramId: 'p', type: 'text', descs: [{ lang: 'en', name: 'Depth' }] }],
      }),
    ];
    const da: SystemCategory[] = [
      cat({
        id: 7,
        descs: [{ lang: 'da', name: 'Vrag' }],
        templateParams: [{ paramId: 'p', type: 'text', descs: [{ lang: 'da', name: 'Dybde' }] }],
      }),
    ];

    const [merged] = mergeTemplateLanguages([en, da]);
    expect(merged.descs).toEqual([
      { lang: 'en', name: 'Wreck' },
      { lang: 'da', name: 'Vrag' },
    ]);
    expect(merged.templateParams?.[0].descs).toEqual([
      { lang: 'en', name: 'Depth' },
      { lang: 'da', name: 'Dybde' },
    ]);
  });

  it('matches categories by id and params by paramId regardless of response order', () => {
    const en: SystemCategory[] = [
      cat({ id: 1, descs: [{ lang: 'en', name: 'A' }] }),
      cat({ id: 2, descs: [{ lang: 'en', name: 'B' }] }),
    ];
    const da: SystemCategory[] = [
      cat({ id: 2, descs: [{ lang: 'da', name: 'Bd' }] }),
      cat({ id: 1, descs: [{ lang: 'da', name: 'Ad' }] }),
    ];

    const merged = mergeTemplateLanguages([en, da]);
    // Structure/order comes from the primary (en) response; descs combined by id.
    expect(merged.map((c) => c.id)).toEqual([1, 2]);
    expect(merged[0].descs).toEqual([
      { lang: 'en', name: 'A' },
      { lang: 'da', name: 'Ad' },
    ]);
    expect(merged[1].descs).toEqual([
      { lang: 'en', name: 'B' },
      { lang: 'da', name: 'Bd' },
    ]);
  });

  it('dedups by lang, keeping the first occurrence', () => {
    const a: SystemCategory[] = [cat({ id: 1, descs: [{ lang: 'en', name: 'First' }] })];
    const b: SystemCategory[] = [cat({ id: 1, descs: [{ lang: 'en', name: 'Second' }] })];
    expect(mergeTemplateLanguages([a, b])[0].descs).toEqual([{ lang: 'en', name: 'First' }]);
  });
});
