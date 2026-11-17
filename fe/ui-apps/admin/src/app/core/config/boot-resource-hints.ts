import type { AppConfig } from './app-config.interface';

/**
 * A cross-origin the boot path will talk to before the app can render, expressed as a
 * `<link rel="preconnect">`.
 *
 * `anonymous` selects the connection pool the hint warms, and it has to match the request that
 * follows or the browser opens a second socket and the hint is wasted:
 * - Keycloak boots by loading two IFRAME DOCUMENTS (a third-party-cookie probe, then the silent
 *   SSO check). Those use the CREDENTIALED pool, so the hint must carry no `crossorigin`.
 * - The Niord API is CORS XHR with no cookies (`provideHttpClient` sets neither `withFetch` nor
 *   credentials), which uses the ANONYMOUS pool, so that hint needs `crossorigin="anonymous"`.
 */
export interface BootResourceHint {
  readonly href: string;
  readonly anonymous: boolean;
}

/**
 * The origins worth pre-connecting, identity provider first — its two iframe loads are a strictly
 * serial chain and therefore the longest pole in the boot, whereas the API's requests all start
 * together (so a hint there only takes DNS off the critical path).
 *
 * Both values are safe to read from the build-time `environment`: `apiBaseUrl` and `keycloak` are
 * in `EXCLUDED_ROOTS` in `web-settings.ts` and can never be overridden at runtime, because the
 * overlay is itself fetched over `apiBaseUrl`.
 *
 * Same-origin and unparseable entries are dropped — a deployment that serves the app from the
 * Niord server already has that connection, so a hint for it would be noise.
 */
export function bootResourceHints(config: AppConfig, documentUrl: string): BootResourceHint[] {
  const candidates: readonly (readonly [string | undefined, boolean])[] = [
    [config.keycloak?.url, false],
    [config.apiBaseUrl, true],
  ];

  let documentOrigin: string | undefined;
  try {
    documentOrigin = new URL(documentUrl).origin;
  } catch {
    documentOrigin = undefined;
  }

  const hints: BootResourceHint[] = [];
  for (const [raw, anonymous] of candidates) {
    if (!raw) {
      continue;
    }
    let origin: string;
    try {
      origin = new URL(raw, documentUrl).origin;
    } catch {
      continue;
    }
    if (origin === documentOrigin || hints.some((hint) => hint.href === origin)) {
      continue;
    }
    hints.push({ href: origin, anonymous });
  }
  return hints;
}

/** Append the preconnect links to `<head>`. Call as early as possible — see the install module. */
export function installBootResourceHints(doc: Document, config: AppConfig): void {
  for (const hint of bootResourceHints(config, doc.baseURI)) {
    const link = doc.createElement('link');
    link.rel = 'preconnect';
    link.href = hint.href;
    if (hint.anonymous) {
      link.crossOrigin = 'anonymous';
    }
    doc.head.appendChild(link);
  }
}
