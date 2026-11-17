import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from './app-config.token';

/** Anything already carrying a scheme, or a protocol-relative `//host/…` address. */
const ABSOLUTE = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i;

/**
 * Backend-relative addresses, resolved against the backend rather than the page.
 *
 * The API reports file addresses ROOT-RELATIVE — `/rest/repo/file/…` — because
 * that is what they are on the server that serves them. The browser resolves a
 * root-relative address against the page it is on, and the page is not always
 * the API: in development and on test the app runs on its own host and the
 * backend lives on another, so a link the server called `/rest/repo/file/x` is
 * followed to the dev server, which has no such path. What a reader gets is a
 * 404 — or worse, the app's own index page rendered where a PDF was promised —
 * and a copied link that behaves the same way for whoever it is handed to.
 *
 * So the origin comes from `apiBaseUrl`. In production it is relative (`/rest`),
 * app and API are one origin, and the page's own origin is the right and only
 * answer. Everywhere else it is absolute, and its origin is where the bytes are.
 *
 * An address that is already absolute is returned untouched. An external link is
 * a real one — it is somebody else's document, hosted somewhere else — and
 * rewriting its host would point every one of them at this installation.
 */
@Injectable({ providedIn: 'root' })
export class ApiUrlResolver {
  private readonly config = inject(APP_CONFIG);
  private readonly document = inject(DOCUMENT);

  /**
   * The origin backend-relative addresses hang off.
   *
   * Read once: `apiBaseUrl` is fixed for the life of the app — the runtime
   * settings overlay excludes it deliberately — and a per-call parse would be a
   * `new URL` on every row of every document card.
   */
  private readonly origin = this.resolveOrigin();

  /**
   * One backend address, made followable from this page.
   *
   * Use it for every href and every copied link that points at a file the API
   * serves. A blank stays blank: an address that resolves to nothing is worse
   * than the absence of one, and the screens above this treat the two alike.
   */
  absoluteApiUrl(path: string): string {
    if (path === '' || ABSOLUTE.test(path)) {
      return path;
    }
    return new URL(path, `${this.origin}/`).toString();
  }

  private resolveOrigin(): string {
    const base = this.config.apiBaseUrl ?? '';
    if (ABSOLUTE.test(base)) {
      try {
        return new URL(base, this.document.location.origin).origin;
      } catch {
        // A base nobody can parse is not a base to resolve against. The page's
        // own origin is the answer the browser would have used anyway, so the
        // links behave exactly as they did before this class existed.
        return this.document.location.origin;
      }
    }
    return this.document.location.origin;
  }
}
