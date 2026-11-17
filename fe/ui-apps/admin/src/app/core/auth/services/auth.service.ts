import { computed, inject, Injectable, signal } from '@angular/core';

import type { AuthSession } from '../models/auth-session.model';
import type { KeycloakTokenClaims } from '../models/keycloak-token-claims.model';
import { KEYCLOAK } from './keycloak.token';

const TOKEN_REFRESH_LEEWAY_SECONDS = 60;

/**
 * Caps how long bootstrap blocks on `keycloak.init()`. A slow/unstable Keycloak makes the
 * silent-SSO flow take many seconds (the 3p-cookie `step1.html` probe alone blocks up to 10s,
 * and the silent-check iframe has no built-in timeout at all); past this we proceed as an
 * anonymous guest rather than holding the blank first paint.
 */
const INIT_TIMEOUT_MS = 8000;

/** Why the app fell back to anonymous browsing — drives the shell's guest banner message. */
export type AuthFallbackReason = 'unreachable' | 'expired';

const asString = (value: unknown): string | undefined =>
  typeof value === 'string' ? value : undefined;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(KEYCLOAK);

  // Single source of truth. Set on successful init() and cleared on logout().
  private readonly session = signal<AuthSession | undefined>(undefined);

  // Why the app dropped to anonymous browsing, if it did: 'unreachable' = Keycloak couldn't be
  // reached at startup; 'expired' = the session was lost mid-use (token refresh / 401). Drives
  // the shell's guest banner; undefined = normal (authenticated, or a clean not-logged-in).
  private readonly authFallbackState = signal<AuthFallbackReason | undefined>(undefined);
  readonly authFallback = this.authFallbackState.asReadonly();

  readonly isAuthenticated = computed(() => this.session() !== undefined);
  readonly idTokenClaims = computed(() => this.session()?.identity);
  readonly preferredUsername = computed(() => this.session()?.identity.preferred_username);
  readonly userName = computed(
    () => this.session()?.identity.name ?? this.session()?.identity.preferred_username,
  );
  async init(): Promise<void> {
    try {
      const authenticated = await this.initKeycloak();
      this.session.set(authenticated ? this.captureSession() : undefined);
    } catch (err) {
      // Keycloak unreachable / slow / unstable: fall back to anonymous browsing instead of
      // reloading the page. The Niord API is public, so the app stays usable as a guest and
      // the user can retry from the header's Login. Reloading on failure must be avoided: when
      // Keycloak is slow/unstable — e.g. the 3p-cookie `step1.html` probe timing out — it loops
      // blank reloads until init happens to succeed.
      console.error('[AuthService] Keycloak init failed; continuing as guest', err);
      this.session.set(undefined);
      this.authFallbackState.set('unreachable');
    }
  }

  /**
   * Runs `keycloak.init()` (silent check-sso) but gives up after {@link INIT_TIMEOUT_MS} so a
   * hung/slow Keycloak can't stall the (render-blocking) first paint indefinitely. A
   * late-settling init after the timeout is harmless — it's left unawaited, so it can't
   * clobber the guest session.
   */
  private initKeycloak(): Promise<boolean> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<boolean>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error('Keycloak init timed out')), INIT_TIMEOUT_MS);
    });
    const init = this.keycloak.init({
      onLoad: 'check-sso',
      // The backend lets this one address be framed, by its own origin only, and refuses framing
      // everywhere else: a rename or a move must be made in its SecurityConfig too.
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
      checkLoginIframe: false,
    });
    return Promise.race([init, timeout]).finally(() => clearTimeout(timer));
  }

  // The three redirect actions are fire-and-forget by design (the page navigates away on
  // success), but an adapter failure must not vanish silently — surface it in the console
  // so a broken IdP config is diagnosable (matching the init-failure logging).
  login(returnUrl?: string): void {
    this.keycloak
      .login(returnUrl ? { redirectUri: returnUrl } : undefined)
      .catch((err: unknown) => console.error('[AuthService] Keycloak login redirect failed', err));
  }

  logout(): void {
    this.keycloak
      .logout()
      .catch((err: unknown) => console.error('[AuthService] Keycloak logout failed', err));
    this.session.set(undefined);
  }

  /**
   * The session became invalid mid-flight (token refresh failed, or the backend returned 401).
   * Drop to anonymous LOCALLY — deliberately NOT `keycloak.logout()`, whose full-page redirect
   * loops when triggered from an on-load request (it bounces through the IdP and re-bootstraps).
   * Signals coalesce equal values, so a burst of 401s settling here is harmless. The user
   * re-authenticates via the header / the guest banner.
   */
  sessionExpired(): void {
    this.session.set(undefined);
    this.authFallbackState.set('expired');
  }

  accountManagement(): void {
    this.keycloak
      .accountManagement()
      .catch((err: unknown) =>
        console.error('[AuthService] Keycloak account-management redirect failed', err),
      );
  }

  async ensureFreshToken(): Promise<string | undefined> {
    if (!this.keycloak.token) return undefined;
    try {
      const refreshed = await this.keycloak.updateToken(TOKEN_REFRESH_LEEWAY_SECONDS);
      // A refreshed token can carry CHANGED role grants. Because role checks read a captured
      // session snapshot rather than the live token, the snapshot must be re-captured after a
      // refresh — otherwise a mid-session role change stays invisible until full reload.
      if (refreshed && this.session()) {
        this.session.set(this.captureSession());
      }
    } catch (err) {
      // Refresh failed (network error, invalid/expired refresh token). Drop to guest locally
      // rather than firing keycloak.logout()'s full-page redirect — when this happens on an
      // on-load request the redirect re-bootstraps and loops. The user re-authenticates fresh
      // on the next interaction.
      this.sessionExpired();
      throw err;
    }
    return this.keycloak.token;
  }

  /**
   * Whether the token carries the realm role. No Niord role check reads it: the backend grants
   * nothing from realm roles, so `AuthFacade` decides on client roles alone.
   */
  hasRealmRole(role: string): boolean {
    return this.session()?.realmRoles.has(role) ?? false;
  }

  hasResourceRole(role: string, resource: string): boolean {
    return this.session()?.resourceRoles.get(resource)?.has(role) ?? false;
  }

  hasRolesFor(domainId: string): boolean {
    return (this.session()?.resourceRoles.get(domainId)?.size ?? 0) > 0;
  }

  private captureSession(): AuthSession {
    const tokenParsed = this.keycloak.tokenParsed;
    const idTokenParsed = this.keycloak.idTokenParsed;

    // KeycloakTokenParsed types only the standard JWT claims (iss/sub/aud/exp/...) and
    // declares `[key: string]: any` for the rest. Narrow each user-identity claim to
    // `string | undefined` explicitly so non-string values (or missing fields) can't
    // leak through as `any`.
    const identity: KeycloakTokenClaims = {
      sub: idTokenParsed?.sub ?? tokenParsed?.sub ?? '',
      preferred_username: asString(idTokenParsed?.['preferred_username']),
      name: asString(idTokenParsed?.['name']),
      email: asString(idTokenParsed?.['email']),
      given_name: asString(idTokenParsed?.['given_name']),
      family_name: asString(idTokenParsed?.['family_name']),
    };

    const resourceRoles = new Map<string, ReadonlySet<string>>();
    const resourceAccess = tokenParsed?.resource_access;
    if (resourceAccess) {
      for (const [resource, value] of Object.entries(resourceAccess)) {
        resourceRoles.set(resource, new Set(value.roles));
      }
    }

    return {
      identity,
      realmRoles: new Set(tokenParsed?.realm_access?.roles ?? []),
      resourceRoles,
    };
  }
}
