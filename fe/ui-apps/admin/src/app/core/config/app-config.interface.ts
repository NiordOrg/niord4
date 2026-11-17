import { LanguageOption } from '../../shared/models/main.models';

export interface KeycloakConfig {
  readonly url: string;
  readonly realm: string;
  readonly clientId: string;
}

/**
 * Deployment defaults and language metadata. Backend settings override the enabled
 * language lists and default at bootstrap; metadata stays in the country build.
 */
export interface LanguageConfig {
  /**
   * Every language the build can name as content: its label, its locale and, when the build
   * ships one, its flag. A language without a flag is shown as its code.
   */
  readonly availableLanguages: readonly LanguageOption[];
  /** The languages whose UI bundle (`assets/i18n/<lang>.json`) this build ships. */
  readonly uiLanguages: readonly string[];
  /**
   * The languages this build ships branding for (`assets/branding/<lang>.json`). Absent means
   * none, and the build requests no branding file.
   */
  readonly brandingLanguages?: readonly string[];
  readonly defaultLanguage: string;
  readonly siteLanguages?: readonly string[];
  readonly modelLanguages?: readonly string[];
}

/**
 * Field names deliberately mirror the backend web-setting keys (the `Setting`
 * DB table rows with `web: true`) so the runtime web-settings merge
 * (`core/config/web-settings.ts`) can override any env value by plain name
 * match — a DB setting named like a config leaf wins over the env default.
 * The tile addresses `osmUrl` and `openSeaMapUrl` are such settings. FE-only
 * fields with no backend counterpart (`wmsUrl`, `highlightEnabled`) keep their
 * own names; the backend-internal `wmsProvider` maps onto nothing here.
 */
export interface MapConfig {
  /** When true, the OpenSeaMap overlay is offered in the map view's layer switcher. */
  readonly openSeaMapLayerEnabled?: boolean;
  /**
   * OpenSeaMap tile-source URL template, the backend `openSeaMapUrl` web setting.
   * Unset or blank leaves the map kit's default, the public tile server over
   * https. Override to point at an in-house proxy if the deployment needs
   * same-origin tiles (avoiding the upstream's lack of CORS headers in
   * canvas-export scenarios). Reaches every map through `MAP_TILE_SOURCES`.
   */
  readonly openSeaMapUrl?: string;
  /**
   * OpenStreetMap tile-source URL template, the backend `osmUrl` web setting.
   * Unset or blank leaves OL's built-in single-host URL
   * (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`). Override to point at a
   * CDN or a caching proxy; the host must send CORS headers, because the OSM
   * source loads its tiles in CORS mode and the thumbnail capture reads the
   * canvas back. Reaches every map through `MAP_TILE_SOURCES`.
   */
  readonly osmUrl?: string;
  /**
   * When true, the WMS overlay is offered in the map view's layer switcher.
   */
  readonly wmsLayerEnabled?: boolean;
  /**
   * When true, the WMS overlay starts switched on (visible) whenever a map view
   * opens — the message map view and the message-details map. Only takes effect
   * when the layer is actually offered (`wmsLayerEnabled`, plus auth for a
   * protected WMS); otherwise there is nothing to switch on. Default `false`
   * leaves the layer added but unchecked.
   */
  readonly wmsLayerEnabledByDefault?: boolean;
  /**
   * WMS GetMap base URL (e.g. `/wms/`). When set the WMS layer is built against
   * this endpoint with `{LAYERS: 'cells', TRANSPARENT: 'TRUE'}` params (the
   * `cells` layer serves IHO S-57 chart cells). Must be same-origin in dev/prod
   * for CORS; route via the dev proxy or platform-rewrite path.
   */
  readonly wmsUrl?: string;
  /**
   * When true, WMS tiles are fetched through HttpClient (so auth interceptors
   * inject the Keycloak Bearer token + NiordDomain header) and the layer's
   * maxResolution is capped to avoid excessive tile counts at low zoom.
   * Default `false` for a public WMS.
   */
  readonly wmsProtected?: boolean;
  /**
   * Deployment-level default map center latitude (EPSG:4326). Last fallback
   * after URL state and per-domain `lat` — see `computeInitialMapView`.
   * `56`, the base default (FrontendSettings).
   */
  readonly mapDefaultLatitude?: number;
  /**
   * Deployment-level default map center longitude (EPSG:4326). Last fallback
   * after URL state and per-domain `lon`. `11`, the base default.
   */
  readonly mapDefaultLongitude?: number;
  /**
   * Deployment-level default zoom level. Last fallback after URL state and
   * per-domain `zoomLevel`. `6`, the base default.
   */
  readonly mapDefaultZoomLevel?: number;
  /**
   * When true (default), message features show the niord selection (orange) and hover
   * (blue) highlight halos — a niord interaction enhancement layered on top of the
   * S-124 portrayal. Set `false` for strictly-standard S-124 output (no halos).
   * Treated as `true` when omitted.
   */
  readonly highlightEnabled?: boolean;
}

export interface AppConfig {
  readonly apiBaseUrl: string;
  readonly appName: string;
  /**
   * Base URL of the online documentation site (no trailing slash). Prepended to
   * the help paths of `HELP_URLS` (`core/main-shell/help/help-urls.json`) by
   * `HelpUrlService`; without it no help link shows. Mirrors the backend
   * `documentationUrl` web setting, so the DB value overrides this at runtime.
   */
  readonly documentationUrl?: string;
  /**
   * Deprecated: ignored. The help paths are code now (`HELP_URLS`, beside
   * `HelpUrlService`), the same in every build; only `documentationUrl` is
   * configuration. Kept, optional, so that a country's environment file that still
   * carries the block compiles; delete the block. It goes in a later version, once
   * DK and GL have taken one without it. Not a JSDoc deprecation tag, which the lint
   * would turn into an error in every file that still sets it.
   */
  readonly helpUrls?: Readonly<Record<string, string>>;
  readonly keycloak: KeycloakConfig;
  readonly map?: MapConfig;
  /**
   * Deployment mode label, mirroring the backend `mode` web setting (the DB
   * value overrides this at runtime). Any value other than `'production'`
   * shows the header deployment badge with the mode uppercased (TEST, …).
   * When absent, the badge falls back to the `production` flag below.
   */
  readonly mode?: string;
  /**
   * Marks this build as a production deployment — a BUILD-TIME flag (gates the
   * dev routes and the e2e Keycloak stub at module scope, which no runtime
   * setting can reach). The header badge prefers the runtime `mode` above and
   * only falls back to this flag. Only an explicit `true` (environment.
   * production.ts) suppresses the badge in the absence of `mode`.
   */
  readonly production?: boolean;
  /**
   * Whether this deployment does S-124. Computed by the backend from the boot property
   * `niord.s124.enabled` and supplied by `/rest/settings/web`; an environment file never
   * sets it. Absent means unknown (no backend answered), read as on.
   */
  readonly s124Enabled?: boolean;
}
