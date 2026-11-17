import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';
import type { MapTileSources } from '@niord/map/tile-sources';

import { APP_CONFIG } from './app-config.token';
import type { AppConfig } from './app-config.interface';
import { LanguageSettingsService } from '../i18n/language-settings.service';

/**
 * Runtime web-settings overlay — the config counterpart of the dictionary
 * override merge (`core/i18n/dictionary-overrides.service.ts`).
 *
 * At bootstrap the app fetches the backend's `web: true` settings from the
 * anonymous `GET {apiBaseUrl}/settings/web` endpoint (a flat `{key: value}`
 * map) and overlays them onto the APP_CONFIG object by KEY NAME: any config leaf
 * whose name matches a payload key takes the backend value; everything else
 * keeps its `environment.*.ts` default. The DB is the source of truth, env is
 * the fallback — so a deployment can retune e.g. `wmsLayerEnabled` in the
 * sysadmin Settings page without a rebuild, and a web setting added to the DB
 * later starts working against a same-named config key with no code change.
 *
 * Mechanics and guardrails:
 * - APP_CONFIG is provided as a CLONE of the environment (`createRuntimeConfig`)
 *   so the merge never mutates the imported environment module object.
 * - The merge walks the config object's own leaves, so any (current or future)
 *   env key is overridable by default. Keys a thin env file may omit entirely
 *   (the sanitized production template) are declared in `OPTIONAL_LEAVES` so
 *   the DB can still supply them.
 * - `EXCLUDED_ROOTS` are never overridden: bootstrap-circular values
 *   (`apiBaseUrl`, `keycloak` — already consumed by the time settings arrive),
 *   build-time flags (`production` gates module-scope code no runtime value
 *   can reach), and the deprecated, ignored `helpUrls` a country's environment
 *   file may still carry (its leaf names are help section ids that could collide
 *   with unrelated setting keys; the help paths are code, `HELP_URLS`, and help is
 *   runtime-tuned via `documentationUrl` instead).
 * - Values are type-coerced against the env leaf's type — the backend emits
 *   some booleans as the strings "true"/"false" (e.g. `wmsProtected`), and
 *   consumers test strict `=== true`.
 * - Backend-internal payload keys (`wmsProvider`, `editorFields*`, …) match no
 *   config leaf and are ignored by construction.
 * - The payload also carries values the backend computes from its running
 *   properties rather than reads from a setting: `s124Enabled` is an optional
 *   leaf the overlay writes, `secomEnabled` matches no leaf and is read by the
 *   admin section gate (`SectionGatesService`) instead.
 * - A blank tile address (`osmUrl`, `openSeaMapUrl`) is no address: it is not
 *   applied, so the env value stands and, without one, the map kit's default.
 *   A layer given an empty template would load no tiles at all.
 * - The fetch is anonymous, capped at 5s and swallows every error: with no
 *   backend the app boots on pure env config, exactly as before this overlay.
 *
 * Settings edited in the sysadmin Settings page apply on the next page load —
 * the web settings are read once per load, not polled.
 */

/** Top-level AppConfig keys the runtime overlay must never touch. */
const EXCLUDED_ROOTS: ReadonlySet<string> = new Set([
  'apiBaseUrl',
  'keycloak',
  'production',
  // Deprecated and ignored; excluded so a country file's leftover block stays inert.
  'helpUrls',
]);

type LeafType = 'string' | 'number' | 'boolean';

/**
 * Overridable leaves a deployment's env file may legitimately omit (the
 * production template is deliberately thin). Listed here so an absent env
 * default still accepts a DB value; leaves present in env are picked up by
 * the generic walk and need no entry.
 */
const OPTIONAL_LEAVES: readonly { path: readonly string[]; type: LeafType }[] = [
  { path: ['documentationUrl'], type: 'string' },
  { path: ['mode'], type: 'string' },
  { path: ['map', 'openSeaMapLayerEnabled'], type: 'boolean' },
  { path: ['map', 'wmsLayerEnabled'], type: 'boolean' },
  { path: ['map', 'wmsLayerEnabledByDefault'], type: 'boolean' },
  { path: ['map', 'wmsProtected'], type: 'boolean' },
  { path: ['map', 'mapDefaultLatitude'], type: 'number' },
  { path: ['map', 'mapDefaultLongitude'], type: 'number' },
  { path: ['map', 'mapDefaultZoomLevel'], type: 'number' },
  { path: ['map', 'highlightEnabled'], type: 'boolean' },
  { path: ['map', 'osmUrl'], type: 'string' },
  { path: ['map', 'openSeaMapUrl'], type: 'string' },
  // Computed by the backend from niord.s124.enabled, never in an environment file.
  { path: ['s124Enabled'], type: 'boolean' },
];

/** Leaves whose blank value means "not set" rather than an empty string. */
const BLANK_IS_UNSET: ReadonlySet<string> = new Set(['map.osmUrl', 'map.openSeaMapUrl']);

/**
 * The APP_CONFIG factory value: a deep clone of the build's environment, so
 * the web-settings overlay mutates a runtime copy and never the module object.
 */
export function createRuntimeConfig(env: AppConfig): AppConfig {
  const config = structuredClone(env);
  if (runtimeKeycloak) applyKeycloakConfig(config, runtimeKeycloak);
  return config;
}

/**
 * What the deployment answers at `GET {apiBaseUrl}/settings/keycloak`: the Keycloak
 * the browser reaches, the realm and the web app's client id. A deployment whose
 * Keycloak is not at `/auth` of its own origin (the cluster's shared Keycloak, with a
 * realm per country) says so here. `url` is null when the backend does not know it.
 */
export interface KeycloakRuntimeConfig {
  readonly url?: string | null;
  readonly realm?: string | null;
  readonly clientId?: string | null;
}

/** The answer `main.ts` fetched before bootstrap, applied by `createRuntimeConfig`. */
let runtimeKeycloak: KeycloakRuntimeConfig | undefined;

/**
 * Hands `createRuntimeConfig` the deployment's Keycloak settings. Called once from
 * `main.ts`, before bootstrap: the `KEYCLOAK` factory reads `config.keycloak` when the
 * injector is built, long before the web-settings overlay runs, which is why `keycloak`
 * is in `EXCLUDED_ROOTS` and takes this path instead. `undefined` clears it (specs).
 */
export function setRuntimeKeycloakConfig(fetched: KeycloakRuntimeConfig | undefined): void {
  runtimeKeycloak = fetched;
}

/**
 * Writes the deployment's Keycloak values over the build's. Only a non-empty string
 * replaces a value: a null `url` means the backend does not know the browser's
 * Keycloak, and the build's `/auth` stands.
 */
export function applyKeycloakConfig(config: AppConfig, fetched: KeycloakRuntimeConfig): AppConfig {
  const keycloak = { ...config.keycloak };
  for (const key of ['url', 'realm', 'clientId'] as const) {
    const value = fetched[key];
    if (typeof value === 'string' && value.trim() !== '') keycloak[key] = value.trim();
  }
  (config as { keycloak: AppConfig['keycloak'] }).keycloak = keycloak;
  return config;
}

/**
 * Fetches the deployment's Keycloak settings before Angular boots. Plain `fetch`, since
 * no injector exists yet.
 *
 * Only a 404 means "keep the build's values": an old backend without the endpoint. Any
 * other failure (a 5xx while the backend restarts, a network error, `timeoutMs` passing)
 * is retried `attempts` times, `retryDelayMs` apart, because on a deployment whose
 * Keycloak is elsewhere the build's `/auth` leads nowhere and Login would open a 404 until
 * a reload. After the last attempt the build's values stand anyway, with a console
 * warning: the API is down too, and the app proceeds as a guest.
 */
export async function fetchKeycloakConfig(
  apiBaseUrl: string,
  timeoutMs = 2000,
  attempts = 3,
  retryDelayMs = 1000,
): Promise<KeycloakRuntimeConfig | undefined> {
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${apiBaseUrl}/settings/keycloak`, {
        signal: controller.signal,
        headers: { Accept: 'application/json' },
      });
      if (response.status === 404) return undefined;
      if (response.ok) {
        const body: unknown = await response.json();
        if (body && typeof body === 'object') return body as KeycloakRuntimeConfig;
      }
    } catch {
      // a network error, an aborted request or a non-JSON body: retried below
    } finally {
      clearTimeout(timer);
    }
    if (attempt < attempts) await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
  }
  console.warn("The Keycloak settings could not be read from the backend; using the build's.");
  return undefined;
}

/**
 * The map kit's `MAP_TILE_SOURCES` value: getters over the config's tile
 * addresses, not a copy of them, because the overlay below writes the backend's
 * addresses into the config after the providers are built, and a tile layer
 * reads them when it is created.
 */
export function mapTileSources(config: AppConfig): MapTileSources {
  return {
    get osmUrl() {
      return config.map?.osmUrl;
    },
    get openSeaMapUrl() {
      return config.map?.openSeaMapUrl;
    },
  };
}

/**
 * Coerce a raw backend setting value to the config leaf's type. Returns
 * `undefined` (= keep the env value) when the value cannot represent the type.
 */
function coerce(value: unknown, type: LeafType): string | number | boolean | undefined {
  switch (type) {
    case 'boolean': {
      if (typeof value === 'boolean') return value;
      if (typeof value === 'string') {
        const lowered = value.trim().toLowerCase();
        if (lowered === 'true') return true;
        if (lowered === 'false') return false;
      }
      return undefined;
    }
    case 'number': {
      if (typeof value === 'number' && Number.isFinite(value)) return value;
      if (typeof value === 'string' && value.trim() !== '') {
        const parsed = Number(value);
        if (Number.isFinite(parsed)) return parsed;
      }
      return undefined;
    }
    case 'string': {
      if (typeof value === 'string') return value;
      if (typeof value === 'number' || typeof value === 'boolean') return String(value);
      return undefined;
    }
  }
}

/**
 * The value a setting gives the leaf at `dotted`, or `undefined` (= keep the env
 * value) when it gives none. The one place a blank tile address becomes unset.
 */
function leafValue(
  dotted: string,
  value: unknown,
  type: LeafType,
): string | number | boolean | undefined {
  const coerced = coerce(value, type);
  if (typeof coerced === 'string' && BLANK_IS_UNSET.has(dotted)) {
    return coerced.trim() || undefined;
  }
  return coerced;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Overlay backend web settings onto the (cloned) config by key name.
 * Returns the dotted paths that were overridden — handy for dev logging/tests.
 */
export function mergeWebSettings(config: AppConfig, settings: unknown): string[] {
  if (!isRecord(settings)) return [];
  const applied: string[] = [];

  const walk = (node: Record<string, unknown>, path: readonly string[]): void => {
    for (const [key, current] of Object.entries(node)) {
      if (path.length === 0 && EXCLUDED_ROOTS.has(key)) continue;
      if (isRecord(current)) {
        walk(current, [...path, key]);
        continue;
      }
      const type = typeof current;
      if (type !== 'string' && type !== 'number' && type !== 'boolean') continue;
      if (!(key in settings)) continue;
      const dotted = [...path, key].join('.');
      const coerced = leafValue(dotted, settings[key], type);
      if (coerced !== undefined) {
        node[key] = coerced;
        applied.push(dotted);
      }
    }
  };
  walk(config as unknown as Record<string, unknown>, []);

  for (const { path, type } of OPTIONAL_LEAVES) {
    const dotted = path.join('.');
    if (applied.includes(dotted)) continue;
    const key = path[path.length - 1];
    if (!(key in settings)) continue;
    const coerced = leafValue(dotted, settings[key], type);
    if (coerced === undefined) continue;
    let node = config as unknown as Record<string, unknown>;
    let missingBranch = false;
    for (const segment of path.slice(0, -1)) {
      const next = node[segment];
      if (next === undefined) {
        node[segment] = {};
      } else if (!isRecord(next)) {
        missingBranch = true;
        break;
      }
      node = node[segment] as Record<string, unknown>;
    }
    if (missingBranch || node[key] !== undefined) continue;
    node[key] = coerced;
    applied.push(dotted);
  }

  return applied;
}

/**
 * App initializer: fetch the anonymous web settings and overlay them onto the
 * injected APP_CONFIG clone. Never blocks or fails boot — on any error or a
 * 5s stall the app proceeds on env config alone.
 */
export function webSettingsInitializer(): Promise<void> {
  const http = inject(HttpClient);
  const config = inject(APP_CONFIG);
  const languages = inject(LanguageSettingsService);
  return firstValueFrom(
    http.get<Record<string, unknown>>(`${config.apiBaseUrl}/settings/web`).pipe(
      timeout(5000),
      catchError(() => of({})),
    ),
    // defaultValue: a cancelled/torn-down request completes the stream EMPTY
    // (no error), which firstValueFrom would otherwise turn into an EmptyError
    // rejection — boot must survive that too.
    { defaultValue: {} },
  ).then((settings) => {
    mergeWebSettings(config, settings);
    languages.configure(settings);
  });
}
