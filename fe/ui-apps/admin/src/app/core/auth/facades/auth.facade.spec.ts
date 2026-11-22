import { TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade, BASE_CLIENT_ID } from './auth.facade';
import { AuthService } from '../services/auth.service';
import { DomainService } from '../../domain/services/domain.service';
import type { Domain } from '../../domain/models/domain.model';

interface AuthStub {
  service: AuthService;
  setAuthenticated(value: boolean): void;
  setRealmRoles(roles: readonly string[]): void;
  setResourceRoles(roles: Record<string, readonly string[]>): void;
  login: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
  accountManagement: ReturnType<typeof vi.fn>;
}

interface DomainStub {
  service: DomainService;
  setSessionDomain(domainId: string | undefined): void;
}

function createAuthStub(): AuthStub {
  const authenticated = signal(false);
  const realmRoles = signal<ReadonlySet<string>>(new Set());
  const resourceRoles = signal<ReadonlyMap<string, ReadonlySet<string>>>(new Map());

  const stub = {
    isAuthenticated: computed(() => authenticated()),
    preferredUsername: computed(() => (authenticated() ? 'test-user' : undefined)),
    userName: computed(() => (authenticated() ? 'Test User' : undefined)),
    idTokenClaims: computed(() => undefined),
    hasRealmRole: (role: string) => authenticated() && realmRoles().has(role),
    hasResourceRole: (role: string, resource: string) =>
      authenticated() && !!resourceRoles().get(resource)?.has(role),
    hasRolesFor: (resource: string) =>
      authenticated() && (resourceRoles().get(resource)?.size ?? 0) > 0,
    init: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    accountManagement: vi.fn(),
    ensureFreshToken: vi.fn(),
  } as unknown as AuthService;

  return {
    service: stub,
    setAuthenticated(value) {
      authenticated.set(value);
    },
    setRealmRoles(roles) {
      // Realm roles imply authentication — mirrors reality where roles come from a token.
      authenticated.set(true);
      realmRoles.set(new Set(roles));
    },
    setResourceRoles(roles) {
      authenticated.set(true);
      resourceRoles.set(new Map(Object.entries(roles).map(([k, v]) => [k, new Set(v)] as const)));
    },
    login: stub.login as unknown as ReturnType<typeof vi.fn>,
    logout: stub.logout as unknown as ReturnType<typeof vi.fn>,
    accountManagement: stub.accountManagement as unknown as ReturnType<typeof vi.fn>,
  };
}

function createDomainStub(): DomainStub {
  const sessionDomain = signal<Domain | undefined>(undefined);
  const stub = { sessionDomain } as unknown as DomainService;
  return {
    service: stub,
    setSessionDomain(domainId) {
      sessionDomain.set(domainId ? ({ domainId } as Domain) : undefined);
    },
  };
}

describe('AuthFacade', () => {
  let facade: AuthFacade;
  let auth: AuthStub;
  let domain: DomainStub;

  beforeEach(() => {
    auth = createAuthStub();
    domain = createDomainStub();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth.service },
        { provide: DomainService, useValue: domain.service },
      ],
    });
    facade = TestBed.inject(AuthFacade);
  });

  it('hasRole(user) reflects isAuthenticated', () => {
    expect(facade.hasRole('user')()).toBe(false);
    auth.setAuthenticated(true);
    expect(facade.hasRole('user')()).toBe(true);
    auth.setAuthenticated(false);
    expect(facade.hasRole('user')()).toBe(false);
  });

  it('hasRole(editor/admin/sysadmin) ignores realm roles — the backend grants nothing from them', () => {
    domain.setSessionDomain('niord-dk');
    auth.setRealmRoles(['editor', 'admin', 'sysadmin']);
    expect(facade.hasRole('editor')()).toBe(false);
    expect(facade.hasRole('admin')()).toBe(false);
    expect(facade.hasRole('sysadmin')()).toBe(false);
  });

  it('hasRole returns false when unauthenticated even if resource roles were stale', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'] });
    domain.setSessionDomain('niord-dk');
    expect(facade.hasRole('editor')()).toBe(true);
    auth.setAuthenticated(false);
    expect(facade.hasRole('editor')()).toBe(false);
  });

  it('returns the same Signal instance on repeated calls', () => {
    expect(facade.hasRole('editor')).toBe(facade.hasRole('editor'));
    expect(facade.hasRole('user')).toBe(facade.hasRole('user'));
  });

  it('hasRole(editor) is true when user has the role as a resource role on the current domain', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'] });
    domain.setSessionDomain('niord-dk');
    expect(facade.hasRole('editor')()).toBe(true);
  });

  it('hasRole(editor) flips false when domain changes to one without the resource role', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'] });
    domain.setSessionDomain('niord-dk');
    expect(facade.hasRole('editor')()).toBe(true);

    domain.setSessionDomain('niord-gl');
    expect(facade.hasRole('editor')()).toBe(false);

    domain.setSessionDomain('niord-dk');
    expect(facade.hasRole('editor')()).toBe(true);
  });

  it('a realm role does not stand in for a missing resource role on the session domain', () => {
    auth.setRealmRoles(['editor']);
    auth.setResourceRoles({ 'niord-dk': [] });
    domain.setSessionDomain('niord-dk');
    expect(facade.hasRole('editor')()).toBe(false);
  });

  it('hasRole(editor) returns false when no domain is selected', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'] });
    // No setSessionDomain call → undefined.
    expect(facade.hasRole('editor')()).toBe(false);
  });

  describe('a role on the base client', () => {
    it('counts in every session domain, as the backend applies it on every request', () => {
      auth.setResourceRoles({ [BASE_CLIENT_ID]: ['editor', 'admin'], 'niord-gl': ['user'] });
      domain.setSessionDomain('niord-dk');
      expect(facade.hasRole('editor')()).toBe(true);
      expect(facade.hasRole('admin')()).toBe(true);
      expect(facade.hasRole('sysadmin')()).toBe(false);

      domain.setSessionDomain('niord-gl');
      expect(facade.hasRole('editor')()).toBe(true);
    });

    it('counts with no session domain too', () => {
      auth.setResourceRoles({ [BASE_CLIENT_ID]: ['sysadmin'] });
      expect(facade.hasRole('sysadmin')()).toBe(true);
      expect(facade.hasAnyRole(['admin', 'sysadmin'])).toBe(true);
    });

    it('is the client niord-client-master, the backend default', () => {
      expect(BASE_CLIENT_ID).toBe('niord-client-master');
    });

    it('does not make adminIn true for a named domain', () => {
      auth.setResourceRoles({ [BASE_CLIENT_ID]: ['admin', 'sysadmin'] });
      expect(facade.adminIn('niord-dk')).toBe(false);
    });
  });

  describe('hasDomainUserRole', () => {
    it('is true with the user client role on the session domain', () => {
      auth.setResourceRoles({ 'niord-dk': ['user'] });
      domain.setSessionDomain('niord-dk');
      expect(facade.hasDomainUserRole()).toBe(true);
    });

    it('is true with the user client role on the base client, in any domain', () => {
      auth.setResourceRoles({ [BASE_CLIENT_ID]: ['user'] });
      domain.setSessionDomain('niord-dk');
      expect(facade.hasDomainUserRole()).toBe(true);
    });

    it('is false for a signed-in user whose roles are all in another domain', () => {
      auth.setResourceRoles({ 'niord-gl': ['user', 'editor'] });
      domain.setSessionDomain('niord-dk');
      expect(facade.hasRole('user')()).toBe(true);
      expect(facade.hasDomainUserRole()).toBe(false);

      domain.setSessionDomain('niord-gl');
      expect(facade.hasDomainUserRole()).toBe(true);
    });

    it('asks for the user role itself, as the backend does, not any role', () => {
      // A realm whose editor role is not composite of user: the backend refuses USER endpoints.
      auth.setResourceRoles({ 'niord-dk': ['editor'], [BASE_CLIENT_ID]: ['editor'] });
      domain.setSessionDomain('niord-dk');
      expect(facade.hasDomainUserRole()).toBe(false);
    });

    it('is false with no session domain and no base-client role, and when signed out', () => {
      auth.setResourceRoles({ 'niord-dk': ['user'] });
      expect(facade.hasDomainUserRole()).toBe(false);

      domain.setSessionDomain('niord-dk');
      expect(facade.hasDomainUserRole()).toBe(true);
      auth.setAuthenticated(false);
      expect(facade.hasDomainUserRole()).toBe(false);
    });

    it('ignores a realm role named user', () => {
      auth.setRealmRoles(['user']);
      domain.setSessionDomain('niord-dk');
      expect(facade.hasDomainUserRole()).toBe(false);
    });
  });

  it('hasRolesFor is true when the user holds ANY client role on that domain', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'], 'niord-gl': ['user'] });
    expect(facade.hasRolesFor('niord-dk')).toBe(true);
    // `hasRolesFor` counts ANY role, including the read-only 'user' role.
    expect(facade.hasRolesFor('niord-gl')).toBe(true);
    // An unknown resource (no roles array) is false.
    expect(facade.hasRolesFor('niord-unknown')).toBe(false);
  });

  describe('adminIn', () => {
    it('is true on the admin or sysadmin client role of the named domain', () => {
      auth.setResourceRoles({
        'niord-dk': ['admin'],
        'niord-gl': ['sysadmin'],
        'niord-fo': ['editor'],
      });
      expect(facade.adminIn('niord-dk')).toBe(true);
      expect(facade.adminIn('niord-gl')).toBe(true);
      expect(facade.adminIn('niord-fo')).toBe(false);
      expect(facade.adminIn('')).toBe(false);
    });

    it('ignores realm roles — a realm admin/sysadmin administers no domain', () => {
      auth.setRealmRoles(['admin', 'sysadmin']);
      expect(facade.adminIn('niord-dk')).toBe(false);
    });
  });

  it('hasRolesFor is false once the user is unauthenticated', () => {
    auth.setResourceRoles({ 'niord-dk': ['editor'] });
    expect(facade.hasRolesFor('niord-dk')).toBe(true);
    auth.setAuthenticated(false);
    expect(facade.hasRolesFor('niord-dk')).toBe(false);
  });

  it('toggleAuthentication calls AuthService.login with current URL when unauthenticated', () => {
    facade.toggleAuthentication();
    expect(auth.login).toHaveBeenCalledTimes(1);
    expect(auth.login).toHaveBeenCalledWith(window.location.href);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('toggleAuthentication calls AuthService.logout when authenticated', () => {
    auth.setAuthenticated(true);
    facade.toggleAuthentication();
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('accountManagement delegates to AuthService.accountManagement', () => {
    facade.accountManagement();
    expect(auth.accountManagement).toHaveBeenCalledTimes(1);
  });

  it('login(returnUrl) is a passthrough to AuthService.login', () => {
    facade.login('http://localhost:4200/some-path');
    expect(auth.login).toHaveBeenCalledTimes(1);
    expect(auth.login).toHaveBeenCalledWith('http://localhost:4200/some-path');
  });

  it('login() with no arg passes undefined to AuthService.login', () => {
    facade.login();
    expect(auth.login).toHaveBeenCalledWith(undefined);
  });

  it('preferredUsername reads from AuthService', () => {
    expect(facade.preferredUsername()).toBeUndefined();
    auth.setAuthenticated(true);
    expect(facade.preferredUsername()).toBe('test-user');
  });
  describe('canManageUsers', () => {
    it('is true ONLY on the manage-users client role on realm-management', () => {
      expect(facade.canManageUsers()).toBe(false);

      auth.setResourceRoles({ 'realm-management': ['manage-users'] });
      expect(facade.canManageUsers()).toBe(true);
    });

    it('has NO realm-role fallback — realm admin/sysadmin do not open the gate', () => {
      auth.setRealmRoles(['admin', 'sysadmin']);
      expect(facade.canManageUsers()).toBe(false);
    });

    it('has NO domain fallback — manage-users on a domain client does not open the gate', () => {
      domain.setSessionDomain('niord-client-nw');
      auth.setResourceRoles({ 'niord-client-nw': ['manage-users'] });
      expect(facade.canManageUsers()).toBe(false);
    });
  });
});
