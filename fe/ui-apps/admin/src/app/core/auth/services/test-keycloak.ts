import type Keycloak from 'keycloak-js';

// Must match TEST_AUTH_WINDOW_KEY in e2e/auth/inject-auth.ts — the Playwright
// fixture writes a token bundle onto window under this key before navigation,
// and the KEYCLOAK provider in app.config.ts reads it back via readTestAuthBundle.
export const TEST_AUTH_WINDOW_KEY = '__NIORD_TEST_AUTH__';

export interface TestTokenBundle {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly idToken: string;
  readonly expiresIn: number;
  readonly refreshExpiresIn: number;
  readonly obtainedAtMs: number;
}

function base64UrlDecodeToString(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder('utf-8').decode(bytes);
}

function decodeJwtPayload(token: string): Record<string, unknown> {
  const part = token.split('.')[1];
  if (!part) throw new Error('Malformed JWT — expected three dot-separated segments.');
  return JSON.parse(base64UrlDecodeToString(part)) as Record<string, unknown>;
}

export function readTestAuthBundle(): TestTokenBundle | undefined {
  if (typeof window === 'undefined') return undefined;
  const value = (window as unknown as Record<string, unknown>)[TEST_AUTH_WINDOW_KEY];
  if (!value || typeof value !== 'object') return undefined;
  const bundle = value as Partial<TestTokenBundle>;
  if (!bundle.accessToken || !bundle.refreshToken || !bundle.idToken) return undefined;
  return bundle as TestTokenBundle;
}

/**
 * Build a Keycloak-shaped stub pre-hydrated from a password-grant bundle that
 * an e2e fixture wrote onto `window`. Only the surface AuthService touches is
 * implemented; login() throws so a stray redirect attempt during a test fails
 * loudly instead of leaving the suite hanging on a real Keycloak login page.
 */
export function createTestKeycloak(bundle: TestTokenBundle): Keycloak {
  const tokenParsed = decodeJwtPayload(bundle.accessToken);
  const idTokenParsed = decodeJwtPayload(bundle.idToken);

  const stub = {
    token: bundle.accessToken,
    refreshToken: bundle.refreshToken,
    idToken: bundle.idToken,
    tokenParsed,
    idTokenParsed,
    authenticated: true,
    init(): Promise<boolean> {
      return Promise.resolve(true);
    },
    updateToken(): Promise<boolean> {
      // No-op: the bundle is good for the duration of the run. If a test outlives
      // expiresIn, the fixture should fetch a fresh bundle (one per worker process).
      return Promise.resolve(false);
    },
    login(): Promise<void> {
      throw new Error(
        'test-keycloak: login() called — the test should have started authenticated.',
      );
    },
    logout(): Promise<void> {
      return Promise.resolve();
    },
    accountManagement(): Promise<void> {
      return Promise.resolve();
    },
  };

  return stub as unknown as Keycloak;
}
