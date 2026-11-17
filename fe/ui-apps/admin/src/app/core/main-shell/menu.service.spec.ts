import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { EDITOR_AREA_ROLES } from '../auth/area-roles';
import { DomainFacade } from '../domain/facades/domain.facade';
import { provideFakeAuthFacade, type FakeAuthOptions } from '../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { COUNTRY_MENU, type CountryMenuEntry } from './country-menu';
import { MenuService } from './menu.service';

function menuLinks(
  opts: FakeAuthOptions,
  firingSchedule = false,
  countryMenu?: readonly CountryMenuEntry[],
): string[] {
  TestBed.configureTestingModule({
    providers: [
      ...(countryMenu ? [{ provide: COUNTRY_MENU, useValue: countryMenu }] : []),
      provideFakeAuthFacade(opts),
      provideTestTransloco(),
      {
        provide: DomainFacade,
        useValue: {
          capabilities: signal({
            atons: false,
            templates: false,
            publish: false,
            firingSchedule,
          }),
        },
      },
    ],
  });
  return TestBed.inject(MenuService)
    .menuItems()
    .map((item) => String(item.routerLink));
}

describe('MenuService', () => {
  it('anonymous users see only Messages', () => {
    expect(menuLinks({})).toEqual(['messages']);
  });

  it('a plain authenticated user sees only Messages (no editor/admin areas)', () => {
    expect(menuLinks({ isAuthenticated: true })).toEqual(['messages']);
  });

  it('an editor sees Messages + Editor', () => {
    expect(menuLinks({ isAuthenticated: true, editor: true })).toEqual(['messages', 'editor']);
  });

  it('an admin sees Messages + Editor + Admin but NOT Sysadmin', () => {
    expect(menuLinks({ isAuthenticated: true, admin: true })).toEqual([
      'messages',
      'editor',
      'admin',
    ]);
  });

  it('a sysadmin sees all four areas (separate Admin and Sysadmin items)', () => {
    expect(menuLinks({ isAuthenticated: true, sysadmin: true })).toEqual([
      'messages',
      'editor',
      'admin',
      'sysadmin',
    ]);
  });

  it('an editor in a firing-schedule domain also sees Schedule (between Editor and Admin)', () => {
    expect(menuLinks({ isAuthenticated: true, editor: true }, true)).toEqual([
      'messages',
      'editor',
      'schedule',
    ]);
  });

  it('an admin in a firing-schedule domain sees Schedule between Editor and Admin', () => {
    expect(menuLinks({ isAuthenticated: true, admin: true }, true)).toEqual([
      'messages',
      'editor',
      'schedule',
      'admin',
    ]);
  });

  it('a plain user in a firing-schedule domain does NOT see Schedule (role-gated)', () => {
    expect(menuLinks({ isAuthenticated: true }, true)).toEqual(['messages']);
  });

  it('an editor outside a firing-schedule domain does NOT see Schedule', () => {
    expect(menuLinks({ isAuthenticated: true, editor: true }, false)).toEqual([
      'messages',
      'editor',
    ]);
  });

  describe('country entries', () => {
    function entry(
      routerLink: string,
      after: CountryMenuEntry['after'],
      roles: CountryMenuEntry['roles'] = EDITOR_AREA_ROLES,
    ): CountryMenuEntry {
      return { after, labelKey: `country.${routerLink}`, icon: 'lucideBook', routerLink, roles };
    }

    it('adds none by default', () => {
      expect(menuLinks({ isAuthenticated: true, sysadmin: true }, true)).toEqual([
        'messages',
        'editor',
        'schedule',
        'admin',
        'sysadmin',
      ]);
    });

    it('places an entry after its anchor, for the roles it names only', () => {
      const harbour = [entry('harbour', 'messages')];
      expect(menuLinks({ isAuthenticated: true, editor: true }, false, harbour)).toEqual([
        'messages',
        'harbour',
        'editor',
      ]);
      TestBed.resetTestingModule();
      expect(menuLinks({}, false, harbour)).toEqual(['messages']);
    });

    it('shows an entry with no roles to everyone, anonymous included', () => {
      expect(menuLinks({}, false, [entry('about', 'messages', [])])).toEqual(['messages', 'about']);
    });

    it('keeps the file order of two entries after one anchor', () => {
      const entries = [entry('first', 'editor'), entry('second', 'editor')];
      expect(menuLinks({ isAuthenticated: true, admin: true }, false, entries)).toEqual([
        'messages',
        'editor',
        'first',
        'second',
        'admin',
      ]);
    });

    it("keeps an entry's place when its anchor is hidden from the user", () => {
      // No firing-schedule domain, so Schedule is hidden; the entry after it follows Editor.
      expect(
        menuLinks({ isAuthenticated: true, admin: true }, false, [entry('range', 'schedule')]),
      ).toEqual(['messages', 'editor', 'range', 'admin']);
    });

    it('labels an entry from its key', () => {
      TestBed.configureTestingModule({
        providers: [
          provideFakeAuthFacade({}),
          provideTestTransloco(),
          { provide: COUNTRY_MENU, useValue: [entry('about', 'messages', [])] },
          {
            provide: DomainFacade,
            useValue: { capabilities: signal({ firingSchedule: false }) },
          },
        ],
      });
      const about = TestBed.inject(MenuService)
        .menuItems()
        .find((item) => item.routerLink === 'about');
      expect(about).toEqual({ label: 'country.about', routerLink: 'about', icon: 'lucideBook' });
    });
  });
});
