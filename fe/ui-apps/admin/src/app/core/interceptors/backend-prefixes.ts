import { DOCUMENT } from '@angular/common';
import { inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config.token';

/**
 * True when `url` falls within the boundary of `prefix` — it either equals the
 * prefix exactly or extends it at a path/query/fragment boundary. A raw
 * `startsWith` is suffix-confusable: a bare-origin prefix such as
 * `https://api.example.invalid` would otherwise also match
 * `https://api.example.invalid.evil.com/…`, leaking the bearer token to an
 * attacker-controlled host. Requiring the character after the prefix to be `/`,
 * `?` or `#` (or the prefix itself to already end in `/`) closes that gap while
 * still matching every legitimate sub-path.
 */
export function urlMatchesPrefix(url: string, prefix: string): boolean {
  if (!url.startsWith(prefix)) {
    return false;
  }
  if (url.length === prefix.length || prefix.endsWith('/')) {
    return true;
  }
  const next = url[prefix.length];
  return next === '/' || next === '?' || next === '#';
}

/**
 * The origin of `url` resolved against `base`, or `null` when it cannot be
 * parsed or has an opaque origin — an address nobody can place shares an
 * origin with nothing.
 */
function originOf(url: string, base: string): string | null {
  try {
    const origin = new URL(url, base).origin;
    return origin === 'null' ? null : origin;
  } catch {
    return null;
  }
}

/**
 * Returns true when the URL targets the Niord backend — either the JSON API
 * (matched against `AppConfig.apiBaseUrl`) or the WMS tile service (matched
 * against `AppConfig.map.wmsUrl`). Interceptors use this to decide whether to
 * inject auth + NiordDomain headers; anything not matching is treated as
 * third-party and passes through untouched.
 *
 * The WMS prefix counts only while it is on the API's own origin. Unlike
 * `apiBaseUrl`, `map.wmsUrl` is a leaf the runtime web-settings overlay can
 * replace from a setting, and the bearer token must not follow a setting to
 * another host: a WMS prefix on any other origin is treated as third-party, so
 * its tiles are requested without credentials.
 *
 * Must be called inside an injection context (e.g. from an `HttpInterceptorFn`).
 */
export function isNiordBackendRequest(url: string): boolean {
  const config = inject(APP_CONFIG);
  if (urlMatchesPrefix(url, config.apiBaseUrl)) {
    return true;
  }
  const wmsUrl = config.map?.wmsUrl;
  if (!wmsUrl || !urlMatchesPrefix(url, wmsUrl)) {
    return false;
  }
  const base = inject(DOCUMENT).baseURI;
  const apiOrigin = originOf(config.apiBaseUrl, base);
  return apiOrigin !== null && apiOrigin === originOf(wmsUrl, base);
}
