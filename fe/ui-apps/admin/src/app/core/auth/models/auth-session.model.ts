import type { KeycloakTokenClaims } from './keycloak-token-claims.model';

export interface AuthSession {
  readonly identity: KeycloakTokenClaims;
  readonly realmRoles: ReadonlySet<string>;
  /** Keyed by Keycloak resource id (e.g. a Niord domainId for per-domain roles). */
  readonly resourceRoles: ReadonlyMap<string, ReadonlySet<string>>;
}
