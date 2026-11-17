import type { KeycloakConfig } from '../../../core/config/app-config.interface';

/**
 * The Keycloak admin-console "clients" view for the deployment's realm —
 * `{url}/admin/{realm}/console/#/{realm}/clients`: the realm's own console, which a
 * realm admin of that realm can open. The master console would need a master-realm
 * account, which a country's sysadmins do not have on a shared Keycloak. The join
 * tolerates a base URL with or without a trailing slash, and the realm always comes
 * from the deployment config (never a hardcoded realm name).
 */
export function keycloakConsoleUrl(keycloak: Pick<KeycloakConfig, 'url' | 'realm'>): string {
  const base = keycloak.url.endsWith('/') ? keycloak.url : `${keycloak.url}/`;
  return `${base}admin/${keycloak.realm}/console/#/${keycloak.realm}/clients`;
}
