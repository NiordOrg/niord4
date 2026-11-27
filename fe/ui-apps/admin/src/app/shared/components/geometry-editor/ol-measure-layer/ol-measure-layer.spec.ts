import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../testing/transloco-testing';
import { measureLabel } from './ol-measure-layer';

describe('measureLabel', () => {
  // The tooltip wrote the units m, km and nm as fixed English, so a Danish page read "nm" where
  // Danish writes "sm".
  it("writes each unit as the language's text for it", () => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslation(
      {
        'common.distanceUnit.m': 'm',
        'common.distanceUnit.km': 'km',
        'common.distanceUnit.nm': 'sm',
      },
      'da',
    );
    transloco.setActiveLang('da');

    expect(measureLabel(1852, 'da', transloco)).toBe('1.852 m · 1,85 km · 1,00 sm');
  });
});
