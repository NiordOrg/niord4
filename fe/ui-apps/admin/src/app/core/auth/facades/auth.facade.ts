import { computed, inject, Injectable, Signal } from '@angular/core';

import { DomainService } from '../../domain/services/domain.service';
import { AuthService } from '../services/auth.service';

export type AuthRole = 'user' | 'editor' | 'admin' | 'sysadmin';

/**
 * The Keycloak client whose roles the backend applies in every domain: the default of its
 * `niord.oidc.base-client` (`SecurityConfig.jwtAuthConverter`). A role held there counts wherever
 * the session sits, beside the role on the session domain's own client. A deployment that names
 * another base client is not read here (the base chart names none).
 */
export const BASE_CLIENT_ID = 'niord-client-master';

@Injectable({ providedIn: 'root' })
export class AuthFacade {
  private readonly authService = inject(AuthService);
  // Injecting DomainService (not DomainFacade) avoids a circular dependency —
  // DomainFacade itself reads from AuthFacade for editableDomains / searchPublicMessages.
  // The sessionDomain signal we need lives on DomainService either way.
  private readonly domainService = inject(DomainService);

  readonly isAuthenticated = this.authService.isAuthenticated;
  readonly preferredUsername = this.authService.preferredUsername;
  readonly userName = this.authService.userName;
  /** Set when the app fell back to anonymous browsing — Keycloak unreachable at startup
   *  ('unreachable') or the session lost mid-use ('expired'). Drives the shell's guest banner. */
  readonly authFallback = this.authService.authFallback;

  private readonly roleSignals = new Map<AuthRole, Signal<boolean>>();

  hasRole(role: AuthRole): Signal<boolean> {
    const cached = this.roleSignals.get(role);
    if (cached) return cached;
    const created = computed(() => {
      // Project convention: hasRole('user') is treated as a synonym for isAuthenticated()
      // — every signed-in account is assumed to hold 'user'; the backend still checks it.
      if (role === 'user') return this.isAuthenticated();
      return this.holdsInSession(role);
    });
    this.roleSignals.set(role, created);
    return created;
  }

  /**
   * True when the user holds the backend's `user` role where the session sits: the client role
   * `user` on the session domain's client or on the base client. It is what the backend asks of
   * the writes every signed-in user is offered (tags, the temporary tag behind a selection's print
   * or export, the mailing-list reports) and of a search for the non-public statuses. Unlike
   * `hasRole('user')`, which stays a synonym for signed in, a signed-in account with no role in the
   * session domain gets false here. An editor, admin or sysadmin holds `user` too wherever the
   * realm makes those roles composite of it, as Niord's realms do on every domain client.
   */
  readonly hasDomainUserRole = computed(() => this.holdsInSession('user'));

  /**
   * A role the backend grants on a request in the session domain: the client role on the session
   * domain's client (what `NiordDomainFilter` adds for the `NiordDomain` header) or on the base
   * client (`SecurityConfig.jwtAuthConverter`, every request). A Keycloak realm role named like a
   * Niord role grants nothing: the backend maps no realm roles, so offering what it would refuse
   * only turns a hidden action into a 403. `hasResourceRole` returns false for an unknown or
   * empty resource, so no session domain leaves the base client alone to answer.
   */
  private holdsInSession(role: string): boolean {
    return (
      this.authService.hasResourceRole(role, this.domainService.sessionDomain()?.domainId ?? '') ||
      this.authService.hasResourceRole(role, BASE_CLIENT_ID)
    );
  }

  /**
   * True when the user holds any of the supplied roles. Convenience over
   * `roles.some(r => hasRole(r)())` — used by both the route `roleGuard`
   * and `MenuService` so the two stay aligned through a single helper.
   */
  hasAnyRole(roles: readonly AuthRole[]): boolean {
    return roles.some((role) => this.hasRole(role)());
  }

  login(returnUrl?: string): void {
    this.authService.login(returnUrl);
  }

  toggleAuthentication(): void {
    if (this.isAuthenticated()) {
      this.authService.logout();
      return;
    }
    this.authService.login(window.location.href);
  }

  accountManagement(): void {
    this.authService.accountManagement();
  }

  /**
   * True when the authenticated user has ANY role granted for the given domain
   * (editor / admin / sysadmin scoped to that resource). Used by the domain
   * switcher to draw a pencil-vs-eye icon per entry showing edit-vs-view-only
   * access.
   */
  hasRolesFor(domainId: string): boolean {
    return this.authService.hasRolesFor(domainId);
  }

  /**
   * True when the user administers the NAMED domain, which is not the session one.
   *
   * `hasRole('admin')` answers about the desk the user is sitting at, and that is
   * the wrong question wherever an action names a second domain — moving a
   * publication to another desk needs the right in the domain it is going TO, and
   * the switcher is not the way to find out whether that right exists.
   *
   * Sysadmin counts; a realm role does not, on the same reading `hasRole` takes.
   * Only the client role on the named domain grants anything, not one on the base
   * client: the backend's check for a domain other than the header's reads that
   * domain's client alone (`PublicationSeriesRestService.isAdminIn`). This decides what
   * to OFFER — the server checks both sides again and names which one is missing.
   */
  adminIn(domainId: string): boolean {
    if (!domainId) {
      return false;
    }
    return (
      this.authService.hasResourceRole('admin', domainId) ||
      this.authService.hasResourceRole('sysadmin', domainId)
    );
  }

  /**
   * True when the user may manage Keycloak users: the `manage-users` client
   * role on Keycloak's own `realm-management` client. A VERBATIM client-role
   * check — deliberately NO realm-role fallback and NO session-domain fallback
   * (unlike `hasRole`), reading `hasResourceRole` directly. Gates the admin
   * Users tab.
   */
  readonly canManageUsers = computed(() =>
    this.authService.hasResourceRole('manage-users', 'realm-management'),
  );
}
