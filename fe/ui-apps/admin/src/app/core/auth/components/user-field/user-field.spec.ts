import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal, type Signal } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { AuthFacade } from '../../facades/auth.facade';
import { toDisplayUser } from '../../models/display-user.model';
import type { UserSearchResult } from '../../models/user-search-result.model';
import { UserDirectoryApiService } from '../../services/users-api.service';
import { UserField } from './user-field';

interface AuthOpts {
  readonly isAuthenticated?: boolean;
  readonly preferredUsername?: string;
  readonly userName?: string;
  /** The session domain's user role; the directory search needs it (default true) */
  readonly hasDomainUserRole?: boolean;
}

function setup(
  opts: AuthOpts = {},
  searchResponse: UserSearchResult[] = [],
  searches: string[] = [],
): { component: UserField; fixture: ComponentFixture<UserField> } {
  const auth: Pick<
    AuthFacade,
    'isAuthenticated' | 'preferredUsername' | 'userName' | 'hasRole' | 'hasDomainUserRole'
  > = {
    isAuthenticated: signal(opts.isAuthenticated ?? true) as Signal<boolean>,
    preferredUsername: signal(opts.preferredUsername) as Signal<string | undefined>,
    userName: signal(opts.userName) as Signal<string | undefined>,
    hasRole: () => signal(false),
    hasDomainUserRole: signal(opts.hasDomainUserRole ?? true) as Signal<boolean>,
  };
  const api: Pick<UserDirectoryApiService, 'searchDisplay' | 'search'> = {
    searchDisplay: () => of(searchResponse.map(toDisplayUser)),
    // The chip's best-effort username→full-name resolution searches the directory by the raw
    // username; return the same fixture so an exact-username match can upgrade the chip.
    search: (name: string) => {
      searches.push(name);
      return of(searchResponse);
    },
  };

  TestBed.configureTestingModule({
    providers: [
      { provide: AuthFacade, useValue: auth },
      { provide: UserDirectoryApiService, useValue: api },
      provideTestTransloco(),
    ],
  });

  const fixture = TestBed.createComponent(UserField);
  fixture.detectChanges();
  return { component: fixture.componentInstance, fixture };
}

describe('UserField — value model + display', () => {
  it('an external username resolves to a DisplayUser stub with displayName==username', () => {
    const { component, fixture } = setup();
    component.value.set('alice');
    fixture.detectChanges();
    expect(component.selectedUser()).toEqual({ username: 'alice', displayName: 'alice' });
  });

  it('upgrades an external username to the full name when the directory search matches it', () => {
    const { component, fixture } = setup({}, [
      { username: 'alice', firstName: 'Alice', lastName: 'Jones' },
    ]);
    component.value.set('alice');
    fixture.detectChanges();
    expect(component.selectedUser()).toEqual({
      username: 'alice',
      firstName: 'Alice',
      lastName: 'Jones',
      displayName: 'Alice Jones',
    });
  });

  it('an external null clears the selection to null', () => {
    const { component, fixture } = setup();
    component.value.set('alice');
    fixture.detectChanges();
    component.value.set(null);
    fixture.detectChanges();
    expect(component.value()).toBeNull();
    expect(component.selectedUser()).toBeNull();
  });

  it('onSelect(DisplayUser) stores it and emits the username via valueChange', () => {
    const { component } = setup();
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    const display = {
      username: 'bob',
      firstName: 'Bob',
      lastName: 'Smith',
      displayName: 'Bob Smith',
    };
    component.onSelect(display);
    expect(component.selectedUser()).toBe(display);
    expect(changes).toEqual(['bob']);
  });

  it('onSelect(string) treats as a username stub and emits the string', () => {
    const { component } = setup();
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    component.onSelect('carol');
    expect(component.selectedUser()).toEqual({ username: 'carol', displayName: 'carol' });
    expect(changes).toEqual(['carol']);
  });

  it('onSelect(null) clears and emits null', () => {
    const { component, fixture } = setup();
    component.value.set('alice');
    fixture.detectChanges();
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    component.onSelect(null);
    expect(component.value()).toBeNull();
    expect(changes).toEqual([null]);
  });

  it('clear() resets value and emits null', () => {
    const { component, fixture } = setup();
    component.value.set('alice');
    fixture.detectChanges();
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    component.clear();
    expect(component.value()).toBeNull();
    expect(changes).toEqual([null]);
  });
});

describe('UserField — setMe', () => {
  it('uses userName claim as displayName when both userName and preferredUsername are present', () => {
    const { component } = setup({
      isAuthenticated: true,
      preferredUsername: 'jdoe',
      userName: 'John Doe',
    });
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    component.setMe();
    expect(component.selectedUser()).toEqual({ username: 'jdoe', displayName: 'John Doe' });
    expect(changes).toEqual(['jdoe']);
  });

  it('falls back to preferredUsername as displayName when userName claim is absent', () => {
    const { component } = setup({
      isAuthenticated: true,
      preferredUsername: 'jdoe',
      userName: undefined,
    });
    component.setMe();
    expect(component.selectedUser()).toEqual({ username: 'jdoe', displayName: 'jdoe' });
  });

  it('is a no-op when preferredUsername is absent', () => {
    const { component } = setup({ isAuthenticated: true, preferredUsername: undefined });
    const changes: (string | null)[] = [];
    component.value.subscribe((v) => changes.push(v));
    component.setMe();
    expect(component.value()).toBeNull();
    expect(changes).toHaveLength(0);
  });
});

describe('UserField — visibility flags', () => {
  it('canShowMe is true when preferredUsername set and not disabled', () => {
    const { component } = setup({ isAuthenticated: true, preferredUsername: 'jdoe' });
    expect(component.canShowMe()).toBe(true);
  });

  it('canShowMe is false when preferredUsername is absent', () => {
    const { component } = setup({ isAuthenticated: true, preferredUsername: undefined });
    expect(component.canShowMe()).toBe(false);
  });

  it('canShowMe is false when the form disables the field', () => {
    const { component, fixture } = setup({ isAuthenticated: true, preferredUsername: 'jdoe' });
    fixture.componentRef.setInput('disabled', true);
    expect(component.canShowMe()).toBe(false);
  });

  it('canShowMe is false when not authenticated', () => {
    const { component } = setup({ isAuthenticated: false, preferredUsername: 'jdoe' });
    expect(component.canShowMe()).toBe(false);
  });

  // The directory search (GET /rest/users/search) answers 403 without the domain's user role
  it('without the domain user role the search is disabled, and Me still works', () => {
    const searches: string[] = [];
    const { component, fixture } = setup(
      { isAuthenticated: true, preferredUsername: 'jdoe', hasDomainUserRole: false },
      [{ username: 'alice', firstName: 'Alice', lastName: 'Jones' }],
      searches,
    );
    expect(component.searchDisabled()).toBe(true);
    expect(component.canShowMe()).toBe(true);
    component.setMe();
    expect(component.value()).toBe('jdoe');
    // A restored username is shown as it is, not looked up
    component.value.set('alice');
    fixture.detectChanges();
    expect(component.selectedUser()).toEqual({ username: 'alice', displayName: 'alice' });
    expect(searches).toEqual([]);
  });

  it('with the domain user role the search is enabled', () => {
    const { component } = setup({ isAuthenticated: true, hasDomainUserRole: true });
    expect(component.searchDisabled()).toBe(false);
  });

  it('canShowClear is true after a value is set, false after clear', () => {
    const { component, fixture } = setup({ isAuthenticated: true });
    expect(component.canShowClear()).toBe(false);
    component.value.set('alice');
    fixture.detectChanges();
    expect(component.canShowClear()).toBe(true);
    component.clear();
    expect(component.canShowClear()).toBe(false);
  });
});
