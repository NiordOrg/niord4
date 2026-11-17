import { TestBed } from '@angular/core/testing';
import { signal, type WritableSignal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { EditorFacade } from '../../services/editor-facade';
import { EditorStatusPage } from './editor-status-page';

interface Internals {
  previewLang: WritableSignal<string>;
  languages: readonly { value: string }[];
}

function setup(appLanguage = 'en') {
  const selectedLanguage = signal(appLanguage);
  const language = {
    modelLanguages: [
      { value: 'en', flagPath: '/img/flags/en.png', locale: 'en-GB' },
      { value: 'da', flagPath: '/img/flags/da.png', locale: 'da' },
    ],
    selectedLanguage,
  };
  const facade = {
    message: signal(undefined),
    loadStatus: signal('idle'),
    loadExisting: vi.fn(),
  };
  const route = {
    paramMap: of(convertToParamMap({ id: 'm-1' })),
    snapshot: { paramMap: convertToParamMap({ id: 'm-1' }) },
  };

  TestBed.configureTestingModule({
    providers: [
      { provide: LanguageService, useValue: language },
      { provide: EditorFacade, useValue: facade },
      { provide: ActivatedRoute, useValue: route },
      // The editor header hosts a route-based <app-help-anchor> → HelpUrlService needs both.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      {
        provide: Router,
        useValue: {
          navigate: vi.fn(),
          url: '/editor/status/m-1',
          events: of(),
          routerState: { snapshot: { root: { data: {}, firstChild: null } } },
        },
      },
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });

  const fixture = TestBed.createComponent(EditorStatusPage);
  const internals = fixture.componentInstance as unknown as Internals;
  return { internals, selectedLanguage };
}

describe('EditorStatusPage preview-language switcher', () => {
  it('defaults the preview language to the current app language', () => {
    const { internals } = setup('da');
    expect(internals.previewLang()).toBe('da');
  });

  it('exposes one pill per configured model language', () => {
    const { internals } = setup();
    expect(internals.languages.map((l) => l.value)).toEqual(['en', 'da']);
  });

  it('changing the preview language does NOT touch the app language', () => {
    const { internals, selectedLanguage } = setup('en');
    internals.previewLang.set('da');
    expect(internals.previewLang()).toBe('da');
    expect(selectedLanguage()).toBe('en');
  });
});
