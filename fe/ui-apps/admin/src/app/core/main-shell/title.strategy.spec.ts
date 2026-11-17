import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import type { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../config/app-config.token';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { I18nTitleStrategy } from './title.strategy';

/** A minimal router snapshot whose root route carries one i18n title key. */
function snapshotWithTitle(titleKey: string): RouterStateSnapshot {
  const root = { routeConfig: { title: titleKey }, firstChild: null } as ActivatedRouteSnapshot;
  return { root } as RouterStateSnapshot;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
    ],
  });
  const strategy = TestBed.inject(I18nTitleStrategy);
  const title = TestBed.inject(Title);
  const transloco = TestBed.inject(TranslocoService);
  return { strategy, title, transloco };
}

describe('I18nTitleStrategy', () => {
  it('composes the app name with the translated route-title segments', () => {
    const { strategy, title, transloco } = setup();
    transloco.setTranslation({ 'menu.sysadmin': 'Sysadmin' }, 'en');

    strategy.updateTitle(snapshotWithTitle('menu.sysadmin'));

    expect(title.getTitle()).toBe('Niord - Sysadmin');
  });

  it('re-resolves a title whose key arrived AFTER navigation (cold-load race / runtime Dictionary refresh)', () => {
    const { strategy, title, transloco } = setup();

    // Navigation lands before the translation carries the key — the synchronous
    // translate() falls back to the raw key, exactly the cold-load race.
    strategy.updateTitle(snapshotWithTitle('menu.sysadmin'));
    expect(title.getTitle()).toBe('Niord - menu.sysadmin');

    // The translation for the active language is then (re)loaded/replaced — the
    // same path the async loader completion and the Dictionary refresh take.
    transloco.setTranslation({ 'menu.sysadmin': 'Sysadmin' }, 'en');

    expect(title.getTitle()).toBe('Niord - Sysadmin');
  });

  it('renders the bare app name when the route carries no title key', () => {
    const { strategy, title } = setup();

    strategy.updateTitle({
      root: { routeConfig: null, firstChild: null } as unknown as ActivatedRouteSnapshot,
    } as RouterStateSnapshot);

    expect(title.getTitle()).toBe('Niord');
  });
});
