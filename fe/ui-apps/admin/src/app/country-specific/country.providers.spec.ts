import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import * as defaultProviders from './default/country.providers';
import defaultRoutes from './default/country.routes';
import { COUNTRY_MENU_ENTRIES } from './default/country.menu';
import { PROMULGATION_EDITORS } from '../features/editor/promulgation/promulgation-editor.token';
import { PROMULGATION_RENDERERS } from '../features/messages/promulgation/promulgation-renderer.token';

function registered(module: typeof defaultProviders) {
  TestBed.configureTestingModule({ providers: [module.provideCountry()] });
  return {
    renderers: TestBed.inject(PROMULGATION_RENDERERS, null) ?? [],
    editors: TestBed.inject(PROMULGATION_EDITORS, null) ?? [],
  };
}

describe('country providers', () => {
  // Audio's renderer and editor are registered by the core, not by provideCountry.
  it('registers no promulgation renderer or editor in the default build', () => {
    const { renderers, editors } = registered(defaultProviders);
    expect(renderers).toHaveLength(0);
    expect(editors).toHaveLength(0);
  });

  it('has no country routes or menu entries in the default build', () => {
    expect(defaultRoutes).toEqual([]);
    expect(COUNTRY_MENU_ENTRIES).toEqual([]);
  });

  it('exports a single provider function from the default build', () => {
    expect(Object.keys(defaultProviders).sort()).toEqual(['provideCountry']);
  });
});
