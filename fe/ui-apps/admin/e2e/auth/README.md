# E2E auth fixture

Skips the Keycloak login UI for Playwright tests by exchanging test-user credentials for a
real access/refresh/id-token bundle via the password grant, then seeding that bundle onto
`window.__NIORD_TEST_AUTH__` before navigation. The KEYCLOAK provider in `app.config.ts`
detects the bundle (non-prod builds only) and returns a pre-hydrated Keycloak stub instead
of the real SSO client, so `AuthService.init()` resolves authenticated without any redirect.

## Required environment variables

| Variable                  | Required | Default     | Notes                                                                                                 |
| ------------------------- | -------- | ----------- | ----------------------------------------------------------------------------------------------------- |
| `KEYCLOAK_URL`            | yes      | —           | The Keycloak the served application signs in with; the local stack's is `http://localhost:8080/auth`. |
| `KEYCLOAK_TEST_USERNAME`  | yes      | —           | Base test-realm username.                                                                             |
| `KEYCLOAK_TEST_PASSWORD`  | yes      | —           | Base plaintext password. Never commit this.                                                           |
| `KEYCLOAK_REALM`          | no       | `niord`     | Override per realm.                                                                                   |
| `KEYCLOAK_TEST_CLIENT_ID` | no       | `niord-web` | Must have "Direct Access Grants" enabled.                                                             |

The suite names no environment of its own: the runner gives the Keycloak every time, so a run
never reaches a deployment by default, and a test that needs a login without `KEYCLOAK_URL`
fails at once, naming it, before any request leaves. Run it against a dev server on the default
port, `localhost:4200`, which the specs that expect a URL name: `npm start` serves the base
product against the local stack (Keycloak `http://localhost:8080/auth`). Any other Keycloak is
the runner's to name, with the credentials of a user in its realm.

The base pair must be a user holding **all** roles: every role-scoped fixture falls back to
it, so a plain `user` account bounces the admin and sysadmin specs through the role guard.

### Per-role credentials (optional)

Some sections need a specific resource role (e.g. the sysadmin pages need `sysadmin`).
Supply role-scoped pairs and request the matching fixture; if a role pair is missing,
the fixture falls back to the base pair above (so a single base user with all roles still
works).

| Variable                          | Used by fixture | Falls back to            |
| --------------------------------- | --------------- | ------------------------ |
| `KEYCLOAK_TEST_USERNAME_SYSADMIN` | `sysadminPage`  | `KEYCLOAK_TEST_USERNAME` |
| `KEYCLOAK_TEST_PASSWORD_SYSADMIN` | `sysadminPage`  | `KEYCLOAK_TEST_PASSWORD` |
| `KEYCLOAK_TEST_USERNAME_ADMIN`    | `adminPage`     | `KEYCLOAK_TEST_USERNAME` |
| `KEYCLOAK_TEST_PASSWORD_ADMIN`    | `adminPage`     | `KEYCLOAK_TEST_PASSWORD` |
| `KEYCLOAK_TEST_USERNAME_EDITOR`   | `editorPage`    | `KEYCLOAK_TEST_USERNAME` |
| `KEYCLOAK_TEST_PASSWORD_EDITOR`   | `editorPage`    | `KEYCLOAK_TEST_PASSWORD` |
| `KEYCLOAK_TEST_USERNAME_USER`     | `userPage`      | `KEYCLOAK_TEST_USERNAME` |
| `KEYCLOAK_TEST_PASSWORD_USER`     | `userPage`      | `KEYCLOAK_TEST_PASSWORD` |

The original `authenticatedPage` fixture is unchanged — it always uses the base pair.

## Usage

```ts
import { expect, test } from '../auth/fixtures';

test('editor can see editor menu items', async ({ authenticatedPage }) => {
  await authenticatedPage.goto('/messages/table');
  await expect(authenticatedPage.getByRole('link', { name: 'Editor' })).toBeVisible();
});
```

The fixture is a drop-in for tests that need an authenticated session. Tests that don't need
auth keep using `@playwright/test`'s default `test` and `page`.

## Running tests locally

PowerShell:

```powershell
$env:KEYCLOAK_URL = 'http://localhost:8080/auth'
$env:KEYCLOAK_TEST_USERNAME = 'your-test-user'
$env:KEYCLOAK_TEST_PASSWORD = 'your-test-password'
npx playwright test
```

bash:

```bash
KEYCLOAK_URL=http://localhost:8080/auth \
  KEYCLOAK_TEST_USERNAME=your-test-user KEYCLOAK_TEST_PASSWORD=your-test-password \
  npx playwright test
```

CI: configure the credentials as masked secrets in the workflow and `KEYCLOAK_URL` beside them;
never echo the secrets.

### In a country workspace

The suite and these fixtures travel in the base product's source zip. A country's generated
workspace runs its own specs in `fe/e2e/` with `npm run e2e` (`playwright test
--pass-with-no-tests`, through a generated `playwright.config.ts` that takes the base product's
options); a spec imports the fixtures as `@niord/admin/e2e`, which the generated
`.niord/tsconfig.e2e.json` maps to this folder's `fixtures.ts` in `.niord/base/`:
`import { expect, test } from '@niord/admin/e2e';`. The same variables apply, `KEYCLOAK_URL`
included, against the country's own `npm run start:admin`.

## Under the Content-Security-Policy

Every test built on the fixtures' `test` (imported from `fixtures.ts`) is watched by a guard: a Content-Security-Policy
violation the browser logs, in any frame, fails the test and lists what was refused. The dev
server sends no policy, so the guard finds nothing there.

`csp.spec.ts` checks the policy itself and runs only with `E2E_CSP=1`, against the bundle the
backend serves rather than a dev server. That bundle is a non-production build such as
`--configuration local`, because a production build refuses the token stub the authenticated
tests use. Point `E2E_BASE_URL` at the backend, set `KEYCLOAK_URL=http://localhost:8080/auth`
(the spec refuses to start without it, since it reads the policy's Keycloak origin from it) and
the local realm's credentials. `E2E_CSP_LOGIN=1` adds a real login through the Keycloak form.

```bash
E2E_CSP=1 E2E_BASE_URL=http://localhost:8898 KEYCLOAK_URL=http://localhost:8080/auth \
  KEYCLOAK_TEST_USERNAME=sysadmin KEYCLOAK_TEST_PASSWORD=sysadmin \
  npx playwright test csp.spec.ts auth-smoke.spec.ts
```

A full run needs two things in the database the backend serves: a queued mail, for the mail
preview (the one frame that inherits the page's policy), and a domain in which the sysadmin can
create a message, for the editor. Without them those two tests skip by name ("no mail in the
queue", "the session domain offers no message to create"); a run that is to stand for the policy
has no skip. On a local stack, run the backend with `./start-local.sh --sample --info` (its root
does not redirect to a dev server, and the sample's domains are clients of the local realm):
[testing.md](../../../../docs/testing.md) says how.

## The duty-officer scenarios

`../scenarios/` holds scenarios that write data. They run only with `E2E_SCENARIOS=1`, one
worker and the sample deployment, and use these fixtures with the sample's four logins; their
README says how to run them.

## Server-side prerequisite

The chosen Keycloak client (default `niord-web`) MUST have **Direct Access Grants Enabled**
in the realm's client settings. Many prod-hardened realms disable this; either flip it on
for the test realm or create a dedicated test client (`niord-web-test`) and point
`KEYCLOAK_TEST_CLIENT_ID` at it. A 401 from the password grant call almost always means
this flag is off.

A dedicated test client must also be listed in the backend's accepted clients
(`niord.oidc.allowed-clients`; in the Helm chart `app.oidc.allowedClients`), next to
`niord-web`. The backend refuses a token issued to any client that is not listed, so without
it every authenticated API call of the run answers 401 although the login itself succeeded.

## How the bundle reaches the app

1. `fetchTestTokenBundle()` POSTs `grant_type=password` to Keycloak.
2. `seedAuthenticatedSession()` calls `page.addInitScript()` so each new document gets
   `window.__NIORD_TEST_AUTH__` set BEFORE Angular bootstraps.
3. `app.config.ts` checks `!production && readTestAuthBundle()` in the KEYCLOAK factory; on
   hit it returns `createTestKeycloak(bundle)` which exposes parsed tokens via getters and
   resolves `init()` synchronously.
4. `AuthService.init()` reads `tokenParsed.realm_access.roles` and friends as normal.

The production gate in step 3 is belt-and-suspenders: production builds use
`environment.production.ts` which sets `production: true`, so the test branch is dead code
in prod even if the window key were somehow set. A country's set-up holds its production
environment to `production: true` the same way (`country-kit/lib/environment-check.mjs`).
