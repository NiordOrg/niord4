import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { TranslocoHttpLoader } from '../../transloco-loader';
import { TranslationRefreshService } from './translation-refresh.service';

describe('TranslationRefreshService', () => {
  it('re-fetches the active language bundle and REPLACES the live translations (merge:false)', () => {
    const bundle = { 'msg.type.LOCAL_WARNING': 'Farvandseft.2' };
    const getTranslation = vi.fn(() => of(bundle));
    const setTranslation = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: TranslocoHttpLoader, useValue: { getTranslation } },
        { provide: TranslocoService, useValue: { getActiveLang: () => 'da', setTranslation } },
      ],
    });

    TestBed.inject(TranslationRefreshService).refreshActiveLanguage().subscribe();

    expect(getTranslation).toHaveBeenCalledWith('da'); // re-fetch bypasses Transloco's cache
    expect(setTranslation).toHaveBeenCalledWith(bundle, 'da', { merge: false }); // full replace
  });
});
