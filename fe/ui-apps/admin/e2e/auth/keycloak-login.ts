/* Password-grant login against Keycloak — fetches an access/refresh/id-token
   bundle that the inject-auth helper hydrates into the app before navigation.

   Credentials come from env vars (never committed):
     KEYCLOAK_TEST_USERNAME   (required base pair)
     KEYCLOAK_TEST_PASSWORD   (required base pair)

   Per-role overrides (optional — fall back to the base pair above):
     KEYCLOAK_TEST_USERNAME_<ROLE> / KEYCLOAK_TEST_PASSWORD_<ROLE>
     where <ROLE> ∈ { SYSADMIN, ADMIN, EDITOR, USER }. A test needing a
     specific role (e.g. sysadmin pages) requests fetchTestTokenBundle('sysadmin');
     the un-suffixed fetch (no role) keeps using the base pair.

   The Keycloak is always named by whoever runs the suite — the suite names no
   environment of its own, so a base product run and a country's `npm run e2e`
   never reach a deployment by default:
     KEYCLOAK_URL             (required; the local stack's is http://localhost:8080/auth)
   Realm and clientId default to Niord's own names; override per run:
     KEYCLOAK_REALM           (default: niord)
     KEYCLOAK_TEST_CLIENT_ID  (default: niord-web)

   The chosen client MUST have "Direct Access Grants Enabled" in Keycloak. */

// Local declaration of the slice of Node's `process` global we need — the e2e
// tsconfig sets `types: []` to keep app TS isolated, and @types/node is not a
// dependency. Adding @types/node just for `process.env` would broaden the
// global type surface unnecessarily.
declare const process: { readonly env: Record<string, string | undefined> };

export interface TestTokenBundle {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly idToken: string;
  readonly expiresIn: number;
  readonly refreshExpiresIn: number;
  readonly obtainedAtMs: number;
}

const DEFAULT_KC_REALM = 'niord';
const DEFAULT_KC_CLIENT_ID = 'niord-web';

/** Role-scoped Keycloak test users. The base (un-suffixed) pair serves any role-less fetch. */
export type TestRole = 'sysadmin' | 'admin' | 'editor' | 'user';

/** What to set for each required variable, said in the error a test fails with without it. */
const REQUIRED_HINT: Record<string, string> = {
  KEYCLOAK_URL:
    'set KEYCLOAK_URL to the Keycloak the served application signs in with ' +
    '(the local stack: http://localhost:8080/auth); the suite has no default',
  KEYCLOAK_TEST_USERNAME: 'set KEYCLOAK_TEST_USERNAME and KEYCLOAK_TEST_PASSWORD',
  KEYCLOAK_TEST_PASSWORD: 'set KEYCLOAK_TEST_USERNAME and KEYCLOAK_TEST_PASSWORD',
};

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `Missing required env var ${name}, which tests that use the authenticated fixtures ` +
        `need: ${REQUIRED_HINT[name] ?? `set ${name}`}. See e2e/auth/README.md of the base ` +
        `product, or country-kit/README.md, End-to-end specs, in a country workspace.`,
    );
  }
  return value;
}

/**
 * Credentials for a role: prefer the per-role pair (…_<ROLE>) when BOTH are set,
 * otherwise fall back to the base pair. A role-less call always uses the base pair.
 */
function credentialsForRole(role?: TestRole): { username: string; password: string } {
  if (role) {
    const suffix = `_${role.toUpperCase()}`;
    const roleUser = process.env[`KEYCLOAK_TEST_USERNAME${suffix}`];
    const rolePass = process.env[`KEYCLOAK_TEST_PASSWORD${suffix}`];
    if (roleUser && rolePass) {
      return { username: roleUser, password: rolePass };
    }
  }
  return {
    username: requireEnv('KEYCLOAK_TEST_USERNAME'),
    password: requireEnv('KEYCLOAK_TEST_PASSWORD'),
  };
}

export async function fetchTestTokenBundle(role?: TestRole): Promise<TestTokenBundle> {
  const url = requireEnv('KEYCLOAK_URL');
  const realm = process.env['KEYCLOAK_REALM'] ?? DEFAULT_KC_REALM;
  const clientId = process.env['KEYCLOAK_TEST_CLIENT_ID'] ?? DEFAULT_KC_CLIENT_ID;
  const { username, password } = credentialsForRole(role);

  const tokenUrl = `${url}/realms/${realm}/protocol/openid-connect/token`;
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: clientId,
    username,
    password,
    scope: 'openid',
  });

  const response = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(
      `Keycloak password-grant failed (${response.status} ${response.statusText}) at ${tokenUrl}. ` +
        `Verify the '${clientId}' client has Direct Access Grants enabled and that the test ` +
        `user '${username}' exists with the supplied password. Response body: ${detail}`,
    );
  }

  const raw = (await response.json()) as {
    access_token?: string;
    refresh_token?: string;
    id_token?: string;
    expires_in?: number;
    refresh_expires_in?: number;
  };

  if (!raw.access_token || !raw.refresh_token || !raw.id_token) {
    throw new Error(
      `Keycloak returned an incomplete bundle (missing access/refresh/id token). The client ` +
        `must include the 'openid' scope so id_token is issued.`,
    );
  }

  return {
    accessToken: raw.access_token,
    refreshToken: raw.refresh_token,
    idToken: raw.id_token,
    expiresIn: raw.expires_in ?? 0,
    refreshExpiresIn: raw.refresh_expires_in ?? 0,
    obtainedAtMs: Date.now(),
  };
}
