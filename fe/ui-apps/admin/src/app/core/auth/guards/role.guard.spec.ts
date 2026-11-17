import { TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { Router, type Route, type UrlSegment, type UrlTree } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../notifications/notification.service';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { AuthFacade, type AuthRole } from '../facades/auth.facade';
import { roleGuard } from './role.guard';

interface Setup {
  authenticated: ReturnType<typeof signal<boolean>>;
  granted: ReturnType<typeof signal<ReadonlySet<AuthRole>>>;
  notifications: { error: ReturnType<typeof vi.fn> };
  login: ReturnType<typeof vi.fn>;
  router: Router;
}

function setup(): Setup {
  const authenticated = signal(false);
  const granted = signal<ReadonlySet<AuthRole>>(new Set());
  const login = vi.fn();

  // Minimal AuthFacade stub — only the surface the guard reads.
  const roleCache = new Map<AuthRole, ReturnType<typeof computed<boolean>>>();
  const hasRole = (role: AuthRole) => {
    let s = roleCache.get(role);
    if (!s) {
      s = computed(() => granted().has(role));
      roleCache.set(role, s);
    }
    return s;
  };
  const facade = {
    isAuthenticated: computed(() => authenticated()),
    hasRole,
    hasAnyRole: (roles: readonly AuthRole[]) => roles.some((role) => hasRole(role)()),
    login,
  } as unknown as AuthFacade;

  const notifications = { error: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: AuthFacade, useValue: facade },
      { provide: NotificationService, useValue: notifications },
    ],
  });

  return {
    authenticated,
    granted,
    notifications,
    login,
    router: TestBed.inject(Router),
  };
}

function run(route: Partial<Route>) {
  // Angular 22: CanMatchFn gained a required 3rd `currentSnapshot` arg (roleGuard ignores it).
  return TestBed.runInInjectionContext(() =>
    roleGuard(route as Route, [] as UrlSegment[], {} as never),
  );
}

describe('roleGuard', () => {
  let s: Setup;

  beforeEach(() => {
    s = setup();
  });

  it('allows navigation when no rolesRequired is declared', () => {
    expect(run({ data: {} })).toBe(true);
    expect(run({})).toBe(true);
  });

  it('allows navigation when rolesRequired is empty', () => {
    expect(run({ data: { rolesRequired: [] } })).toBe(true);
  });

  it('allows navigation when the user has at least one required role', () => {
    s.authenticated.set(true);
    s.granted.set(new Set(['editor']));
    expect(run({ data: { rolesRequired: ['editor', 'admin'] } })).toBe(true);
    expect(s.notifications.error).not.toHaveBeenCalled();
    expect(s.login).not.toHaveBeenCalled();
  });

  it('returns UrlTree("/") and fires access-denied notification when authenticated but unauthorized', () => {
    s.authenticated.set(true);
    // No roles granted.
    const result = run({ data: { rolesRequired: ['editor'] } });
    expect(result).toBeInstanceOf(Object);
    // Compare by string form so we don't depend on UrlTree identity.
    expect(s.router.serializeUrl(result as UrlTree)).toBe('/');
    expect(s.notifications.error).toHaveBeenCalledTimes(1);
    expect(s.notifications.error).toHaveBeenCalledWith('shared.auth.accessDenied');
    expect(s.login).not.toHaveBeenCalled();
  });

  it('triggers Keycloak login with current URL when unauthorized AND anonymous', () => {
    s.authenticated.set(false);
    const result = run({ data: { rolesRequired: ['editor'] } });
    expect(result).toBe(false);
    expect(s.login).toHaveBeenCalledTimes(1);
    expect(s.login).toHaveBeenCalledWith(window.location.href);
    expect(s.notifications.error).not.toHaveBeenCalled();
  });
});
