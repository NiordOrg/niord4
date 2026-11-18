import { TestBed } from '@angular/core/testing';
import type Keycloak from 'keycloak-js';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from './auth.service';
import { KEYCLOAK } from './keycloak.token';

interface FakeKeycloak {
  token: string | undefined;
  tokenParsed: unknown;
  idTokenParsed: unknown;
  init: ReturnType<typeof vi.fn>;
  login: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
  accountManagement: ReturnType<typeof vi.fn>;
  updateToken: ReturnType<typeof vi.fn>;
  isTokenExpired: ReturnType<typeof vi.fn>;
}

function makeKeycloakStub(overrides: Partial<FakeKeycloak> = {}): FakeKeycloak {
  return {
    token: undefined,
    tokenParsed: undefined,
    idTokenParsed: undefined,
    init: vi.fn().mockResolvedValue(false),
    // The redirect trio resolve like the real adapter — the service chains
    // a rejection logger onto each.
    login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    accountManagement: vi.fn().mockResolvedValue(undefined),
    updateToken: vi.fn().mockResolvedValue(true),
    isTokenExpired: vi.fn(() => false),
    ...overrides,
  };
}

function configure(keycloak: FakeKeycloak) {
  TestBed.configureTestingModule({
    providers: [{ provide: KEYCLOAK, useValue: keycloak as unknown as Keycloak }],
  });
  return TestBed.inject(AuthService);
}

describe('AuthService', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  describe('init', () => {
    it('on success when authenticated, captures session and exposes signals', async () => {
      const tokenParsed = {
        sub: 'sub-1',
        realm_access: { roles: ['editor', 'admin'] },
        resource_access: {
          'niord-dk': { roles: ['editor'] },
          'niord-gl': { roles: ['user'] },
        },
      };
      const idTokenParsed = { sub: 'sub-1', preferred_username: 'alice', name: 'Alice' };
      const keycloak = makeKeycloakStub({
        init: vi.fn().mockResolvedValue(true),
        tokenParsed,
        idTokenParsed,
        token: 'access-token-xyz',
      });
      const auth = configure(keycloak);

      await auth.init();

      expect(auth.isAuthenticated()).toBe(true);
      expect(auth.hasRealmRole('editor')).toBe(true);
      expect(auth.hasRealmRole('admin')).toBe(true);
      expect(auth.hasRealmRole('sysadmin')).toBe(false);
      expect(auth.preferredUsername()).toBe('alice');
      expect(auth.userName()).toBe('Alice');
      expect(auth.idTokenClaims()).toEqual(idTokenParsed);
    });

    it('on success when not authenticated, leaves session empty', async () => {
      const keycloak = makeKeycloakStub({ init: vi.fn().mockResolvedValue(false) });
      const auth = configure(keycloak);

      await auth.init();

      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.hasRealmRole('editor')).toBe(false);
      expect(auth.hasRealmRole('admin')).toBe(false);
      expect(auth.preferredUsername()).toBeUndefined();
      expect(auth.userName()).toBeUndefined();
    });

    it('falls back to anonymous (no reload) when keycloak.init rejects', async () => {
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
      const keycloak = makeKeycloakStub({
        init: vi.fn().mockRejectedValue(new Error('boom')),
      });
      const auth = configure(keycloak);

      await auth.init();

      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.authFallback()).toBe('unreachable');
      errorSpy.mockRestore();
    });

    it('falls back to anonymous when keycloak.init hangs past the timeout', async () => {
      vi.useFakeTimers();
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
      const keycloak = makeKeycloakStub({
        init: vi.fn().mockReturnValue(new Promise<boolean>(() => undefined)), // never settles
      });
      const auth = configure(keycloak);

      const done = auth.init();
      await vi.advanceTimersByTimeAsync(8000);
      await done;

      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.authFallback()).toBe('unreachable');
      vi.useRealTimers();
      errorSpy.mockRestore();
    });
  });

  describe('role queries', () => {
    async function authenticatedWith(realm: string[], resource: Record<string, string[]> = {}) {
      const keycloak = makeKeycloakStub({
        init: vi.fn().mockResolvedValue(true),
        tokenParsed: {
          sub: 'sub-x',
          realm_access: { roles: realm },
          resource_access: Object.fromEntries(
            Object.entries(resource).map(([k, v]) => [k, { roles: v }]),
          ),
        },
        idTokenParsed: { sub: 'sub-x' },
      });
      const auth = configure(keycloak);
      await auth.init();
      return auth;
    }

    it('hasRealmRole returns true when role is in the captured realmRoles set', async () => {
      const auth = await authenticatedWith(['editor']);
      expect(auth.hasRealmRole('editor')).toBe(true);
      expect(auth.hasRealmRole('admin')).toBe(false);
    });

    it('hasRealmRole returns false when not authenticated', () => {
      const auth = configure(makeKeycloakStub());
      expect(auth.hasRealmRole('editor')).toBe(false);
    });

    it('hasResourceRole returns true only for the matching resource', async () => {
      const auth = await authenticatedWith([], { 'niord-dk': ['editor'] });
      expect(auth.hasResourceRole('editor', 'niord-dk')).toBe(true);
      expect(auth.hasResourceRole('editor', 'niord-gl')).toBe(false);
      expect(auth.hasResourceRole('admin', 'niord-dk')).toBe(false);
    });

    it('hasRolesFor returns true when the domain has at least one role', async () => {
      const auth = await authenticatedWith([], { 'niord-dk': ['user'], 'niord-empty': [] });
      expect(auth.hasRolesFor('niord-dk')).toBe(true);
      expect(auth.hasRolesFor('niord-empty')).toBe(false);
      expect(auth.hasRolesFor('niord-unknown')).toBe(false);
    });
  });

  describe('ensureFreshToken', () => {
    it('resolves undefined when there is no token (anonymous)', async () => {
      const auth = configure(makeKeycloakStub());
      await expect(auth.ensureFreshToken()).resolves.toBeUndefined();
    });

    it('returns the current token after a successful updateToken', async () => {
      const keycloak = makeKeycloakStub({
        token: 'tkn',
        updateToken: vi.fn().mockResolvedValue(true),
      });
      const auth = configure(keycloak);
      await expect(auth.ensureFreshToken()).resolves.toBe('tkn');
      expect(keycloak.updateToken).toHaveBeenCalledWith(60);
    });

    it('drops to guest (no keycloak.logout redirect) and rethrows when updateToken rejects', async () => {
      const keycloak = makeKeycloakStub({
        token: 'tkn',
        updateToken: vi.fn().mockRejectedValue(new Error('refresh failed')),
      });
      const auth = configure(keycloak);

      await expect(auth.ensureFreshToken()).rejects.toThrow('refresh failed');
      // Must NOT full-page-redirect — that's the loop. Local guest fallback instead.
      expect(keycloak.logout).not.toHaveBeenCalled();
      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.authFallback()).toBe('expired');
    });
  });

  describe('currentFreshToken', () => {
    it('gives the token while it stays valid for the leeway, without refreshing it', () => {
      const keycloak = makeKeycloakStub({ token: 'tkn' });
      const auth = configure(keycloak);
      expect(auth.currentFreshToken()).toBe('tkn');
      expect(keycloak.isTokenExpired).toHaveBeenCalledWith(60);
      expect(keycloak.updateToken).not.toHaveBeenCalled();
    });

    it('gives nothing, and leaves the session alone, when the token would need a refresh', () => {
      const keycloak = makeKeycloakStub({ token: 'tkn', isTokenExpired: vi.fn(() => true) });
      const auth = configure(keycloak);
      expect(auth.currentFreshToken()).toBeUndefined();
      expect(keycloak.updateToken).not.toHaveBeenCalled();
      expect(auth.authFallback()).toBeUndefined();
    });

    it('gives nothing without a token, or when the adapter cannot judge it', () => {
      expect(configure(makeKeycloakStub()).currentFreshToken()).toBeUndefined();
      TestBed.resetTestingModule();
      const throwing = makeKeycloakStub({
        token: 'tkn',
        isTokenExpired: vi.fn(() => {
          throw new Error('Not authenticated');
        }),
      });
      expect(configure(throwing).currentFreshToken()).toBeUndefined();
    });
  });

  describe('commands', () => {
    it('login(returnUrl) calls keycloak.login with redirectUri', () => {
      const keycloak = makeKeycloakStub();
      const auth = configure(keycloak);
      auth.login('http://localhost:4200/messages/table');
      expect(keycloak.login).toHaveBeenCalledWith({
        redirectUri: 'http://localhost:4200/messages/table',
      });
    });

    it('login() with no arg calls keycloak.login with undefined', () => {
      const keycloak = makeKeycloakStub();
      const auth = configure(keycloak);
      auth.login();
      expect(keycloak.login).toHaveBeenCalledWith(undefined);
    });

    it('logout calls keycloak.logout and clears the session', async () => {
      const keycloak = makeKeycloakStub({
        init: vi.fn().mockResolvedValue(true),
        tokenParsed: { sub: 's', realm_access: { roles: ['editor'] } },
        idTokenParsed: { sub: 's' },
      });
      const auth = configure(keycloak);
      await auth.init();
      expect(auth.isAuthenticated()).toBe(true);

      auth.logout();
      expect(keycloak.logout).toHaveBeenCalledTimes(1);
      expect(auth.isAuthenticated()).toBe(false);
    });

    it('accountManagement delegates to keycloak.accountManagement', () => {
      const keycloak = makeKeycloakStub();
      const auth = configure(keycloak);
      auth.accountManagement();
      expect(keycloak.accountManagement).toHaveBeenCalledTimes(1);
    });
  });
});
