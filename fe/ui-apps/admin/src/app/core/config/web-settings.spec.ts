import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AppConfig } from './app-config.interface';
import { APP_CONFIG } from './app-config.token';
import {
  applyKeycloakConfig,
  createRuntimeConfig,
  fetchKeycloakConfig,
  mapTileSources,
  mergeWebSettings,
  setRuntimeKeycloakConfig,
  webSettingsInitializer,
} from './web-settings';

function baseConfig(): AppConfig {
  return createRuntimeConfig({
    apiBaseUrl: '/rest',
    appName: 'Niord',
    documentationUrl: 'https://docs.example',
    // A deprecated block a country's environment file may still carry: ignored, never overlaid.
    helpUrls: { home: '/manual.html#home' },
    keycloak: { url: 'https://auth.example', realm: 'niord', clientId: 'niord-web' },
    map: {
      openSeaMapLayerEnabled: true,
      wmsLayerEnabled: true,
      wmsProtected: true,
      mapDefaultLatitude: 56,
      mapDefaultLongitude: 11,
      mapDefaultZoomLevel: 6,
    },
    mode: 'test',
    production: false,
  });
}

describe('createRuntimeConfig', () => {
  it('returns a deep clone — merging never mutates the source environment object', () => {
    const env: AppConfig = {
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      map: { wmsLayerEnabled: true },
    };
    const clone = createRuntimeConfig(env);
    mergeWebSettings(clone, { wmsLayerEnabled: false, appName: 'Other' });
    expect(env.map?.wmsLayerEnabled).toBe(true);
    expect(env.appName).toBe('Niord');
    expect(clone.map?.wmsLayerEnabled).toBe(false);
    expect(clone.appName).toBe('Other');
  });
});

describe('mergeWebSettings', () => {
  it('overrides same-named leaves anywhere in the config tree', () => {
    const config = baseConfig();
    const applied = mergeWebSettings(config, {
      wmsLayerEnabled: false,
      openSeaMapLayerEnabled: false,
      mapDefaultZoomLevel: 8,
      mode: 'staging',
    });
    expect(config.map?.wmsLayerEnabled).toBe(false);
    expect(config.map?.openSeaMapLayerEnabled).toBe(false);
    expect(config.map?.mapDefaultZoomLevel).toBe(8);
    expect(config.mode).toBe('staging');
    expect(applied).toEqual(
      expect.arrayContaining([
        'map.wmsLayerEnabled',
        'map.openSeaMapLayerEnabled',
        'map.mapDefaultZoomLevel',
        'mode',
      ]),
    );
  });

  it('coerces backend string booleans and numeric strings ("wmsProtected": "true")', () => {
    const config = baseConfig();
    mergeWebSettings(config, { wmsProtected: 'false', mapDefaultZoomLevel: '9' });
    expect(config.map?.wmsProtected).toBe(false);
    expect(config.map?.mapDefaultZoomLevel).toBe(9);
  });

  it('keeps the env value when a setting cannot represent the leaf type', () => {
    const config = baseConfig();
    const applied = mergeWebSettings(config, {
      wmsLayerEnabled: 'not-a-bool',
      mapDefaultZoomLevel: 'NaN-ish',
    });
    expect(config.map?.wmsLayerEnabled).toBe(true);
    expect(config.map?.mapDefaultZoomLevel).toBe(6);
    expect(applied).toEqual([]);
  });

  it('never touches the excluded roots (apiBaseUrl, keycloak, production, the deprecated helpUrls)', () => {
    const config = baseConfig();
    mergeWebSettings(config, {
      apiBaseUrl: 'https://evil.example/rest',
      url: 'https://evil.example/auth',
      realm: 'evil',
      production: true,
      home: 'https://evil.example/manual',
      mode: 'production',
    });
    expect(config.apiBaseUrl).toBe('/rest');
    expect(config.keycloak.url).toBe('https://auth.example');
    expect(config.keycloak.realm).toBe('niord');
    expect(config.production).toBe(false);
    expect(config.helpUrls?.['home']).toBe('/manual.html#home');
    // ...while the same payload still applies to a legitimate leaf.
    expect(config.mode).toBe('production');
  });

  it('supplies registered optional leaves a thin env file omits (production template)', () => {
    const config = createRuntimeConfig({
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      map: { openSeaMapLayerEnabled: true },
      production: true,
    });
    const applied = mergeWebSettings(config, {
      mode: 'production',
      documentationUrl: 'https://docs.niord.org',
      wmsLayerEnabled: true,
      mapDefaultLatitude: 56,
    });
    expect(config.mode).toBe('production');
    expect(config.documentationUrl).toBe('https://docs.niord.org');
    expect(config.map?.wmsLayerEnabled).toBe(true);
    expect(config.map?.mapDefaultLatitude).toBe(56);
    expect(applied).toHaveLength(4);
  });

  it('supplies the computed s124Enabled, which no environment file sets', () => {
    const config = baseConfig();
    expect(config.s124Enabled).toBeUndefined();
    const applied = mergeWebSettings(config, { s124Enabled: false, secomEnabled: false });
    expect(config.s124Enabled).toBe(false);
    expect(applied).toContain('s124Enabled');
    // secomEnabled is read by the section gate, not the overlay: no config leaf takes it.
    expect(applied).not.toContain('secomEnabled');
  });

  it('supplies the tile addresses a thin env file omits', () => {
    const config = createRuntimeConfig({
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      map: { openSeaMapLayerEnabled: true },
      production: true,
    });
    const applied = mergeWebSettings(config, {
      osmUrl: 'https://tiles.example/osm/{z}/{x}/{y}.png',
      openSeaMapUrl: 'https://tiles.example/seamark/{z}/{x}/{y}.png',
    });
    expect(config.map?.osmUrl).toBe('https://tiles.example/osm/{z}/{x}/{y}.png');
    expect(config.map?.openSeaMapUrl).toBe('https://tiles.example/seamark/{z}/{x}/{y}.png');
    expect(applied).toEqual(['map.osmUrl', 'map.openSeaMapUrl']);
  });

  it('never applies a blank tile address — the env value or the kit default stands', () => {
    const thin = createRuntimeConfig({
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      map: { openSeaMapLayerEnabled: true },
    });
    expect(mergeWebSettings(thin, { osmUrl: '', openSeaMapUrl: '   ' })).toEqual([]);
    expect(thin.map?.osmUrl).toBeUndefined();
    expect(thin.map?.openSeaMapUrl).toBeUndefined();

    const withEnvAddress = createRuntimeConfig({
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      map: { openSeaMapUrl: 'https://env.example/{z}/{x}/{y}.png' },
    });
    expect(mergeWebSettings(withEnvAddress, { openSeaMapUrl: '' })).toEqual([]);
    expect(withEnvAddress.map?.openSeaMapUrl).toBe('https://env.example/{z}/{x}/{y}.png');
  });

  it('trims a tile address', () => {
    const config = baseConfig();
    mergeWebSettings(config, { osmUrl: '  https://tiles.example/{z}/{x}/{y}.png ' });
    expect(config.map?.osmUrl).toBe('https://tiles.example/{z}/{x}/{y}.png');
  });

  it('ignores backend-internal keys with no config counterpart (wmsProvider, …)', () => {
    const config = baseConfig();
    const applied = mergeWebSettings(config, {
      wmsProvider: 'https://upstream.example/WMSServer',
      mapImageSize: 256,
      editorFieldsBase: { type: true },
      frontPageMessageSeries: ['a'],
    });
    expect(applied).toEqual([]);
    expect(config.map?.wmsUrl).toBeUndefined();
  });

  it('coerces number/boolean setting values onto string leaves', () => {
    const config = baseConfig();
    mergeWebSettings(config, { documentationUrl: 42, mode: true });
    expect(config.documentationUrl).toBe('42');
    expect(config.mode).toBe('true');
  });

  it('skips an optional leaf whose parent branch is not an object (never throws)', () => {
    const config = createRuntimeConfig({
      apiBaseUrl: '/rest',
      appName: 'Niord',
      keycloak: { url: '', realm: '', clientId: '' },
      // Deliberately broken shape: `map` is a primitive, so the registry's
      // ['map', 'wmsLayerEnabled'] path has a non-record parent.
      map: 'broken' as unknown as AppConfig['map'],
    });
    const applied = mergeWebSettings(config, { wmsLayerEnabled: true });
    expect(applied).toEqual([]);
    expect(config.map as unknown).toBe('broken');
  });

  it('tolerates a non-object payload', () => {
    const config = baseConfig();
    expect(mergeWebSettings(config, null)).toEqual([]);
    expect(mergeWebSettings(config, [1, 2])).toEqual([]);
    expect(mergeWebSettings(config, 'nope')).toEqual([]);
    expect(config.map?.wmsLayerEnabled).toBe(true);
  });
});

describe('mapTileSources', () => {
  it('reads the addresses the overlay wrote after it was created', () => {
    const config = baseConfig();
    const sources = mapTileSources(config);
    expect(sources.osmUrl).toBeUndefined();
    expect(sources.openSeaMapUrl).toBeUndefined();

    mergeWebSettings(config, {
      osmUrl: 'https://tiles.example/osm/{z}/{x}/{y}.png',
      openSeaMapUrl: 'https://tiles.example/seamark/{z}/{x}/{y}.png',
    });
    expect(sources.osmUrl).toBe('https://tiles.example/osm/{z}/{x}/{y}.png');
    expect(sources.openSeaMapUrl).toBe('https://tiles.example/seamark/{z}/{x}/{y}.png');
  });

  it('has no addresses for a config without a map section', () => {
    const sources = mapTileSources(
      createRuntimeConfig({
        apiBaseUrl: '/rest',
        appName: 'Niord',
        keycloak: { url: '', realm: '', clientId: '' },
      }),
    );
    expect(sources.osmUrl).toBeUndefined();
    expect(sources.openSeaMapUrl).toBeUndefined();
  });
});

describe('webSettingsInitializer', () => {
  function setup() {
    const config = baseConfig();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: config },
      ],
    });
    return { config, httpMock: TestBed.inject(HttpTestingController) };
  }

  it('fetches {apiBaseUrl}/settings/web and overlays the payload onto APP_CONFIG', async () => {
    const { config, httpMock } = setup();
    const done = TestBed.runInInjectionContext(() => webSettingsInitializer());
    const req = httpMock.expectOne('/rest/settings/web');
    expect(req.request.method).toBe('GET');
    req.flush({ wmsLayerEnabled: false, wmsProtected: 'true', mode: 'test' });
    await done;
    expect(config.map?.wmsLayerEnabled).toBe(false);
    expect(config.map?.wmsProtected).toBe(true);
    httpMock.verify();
  });

  it('never fails boot: on an HTTP error the config keeps its env values', async () => {
    const { config, httpMock } = setup();
    const done = TestBed.runInInjectionContext(() => webSettingsInitializer());
    httpMock
      .expectOne('/rest/settings/web')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await expect(done).resolves.toBeUndefined();
    expect(config.map?.wmsLayerEnabled).toBe(true);
    expect(config.mode).toBe('test');
    httpMock.verify();
  });
});

describe("the deployment's Keycloak", () => {
  afterEach(() => {
    setRuntimeKeycloakConfig(undefined);
    vi.unstubAllGlobals();
  });

  it('applyKeycloakConfig replaces only the values the backend names, trimmed', () => {
    const config = baseConfig();
    applyKeycloakConfig(config, {
      url: ' https://login.example/auth ',
      realm: 'niord-dk',
      clientId: null,
    });
    expect(config.keycloak).toEqual({
      url: 'https://login.example/auth',
      realm: 'niord-dk',
      clientId: 'niord-web',
    });
  });

  it("a null or empty url keeps the build's: the backend does not know the browser's Keycloak", () => {
    const config = baseConfig();
    applyKeycloakConfig(config, { url: null, realm: '' });
    expect(config.keycloak).toEqual({
      url: 'https://auth.example',
      realm: 'niord',
      clientId: 'niord-web',
    });
  });

  it('createRuntimeConfig applies what main.ts fetched, and never touches the environment', () => {
    const env = baseConfig();
    setRuntimeKeycloakConfig({ url: 'https://login.example/auth', realm: 'niord-gl' });
    const config = createRuntimeConfig(env);
    expect(config.keycloak.url).toBe('https://login.example/auth');
    expect(config.keycloak.realm).toBe('niord-gl');
    expect(env.keycloak.realm).toBe('niord');
  });

  it('fetchKeycloakConfig reads GET {apiBaseUrl}/settings/keycloak', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ url: 'https://login.example/auth', realm: 'niord-dk' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const answer = await fetchKeycloakConfig('/rest');
    expect(fetchMock.mock.calls[0][0]).toBe('/rest/settings/keycloak');
    expect(answer).toEqual({ url: 'https://login.example/auth', realm: 'niord-dk' });
  });

  it("fetchKeycloakConfig keeps the build's values on 404 at once: an old backend", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 404 }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await fetchKeycloakConfig('/rest')).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('fetchKeycloakConfig retries a failure, and takes the answer that then comes', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 502 }))
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ url: 'https://login.example/auth', realm: 'niord-dk' }), {
          status: 200,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);
    expect(await fetchKeycloakConfig('/rest', 2000, 3, 0)).toEqual({
      url: 'https://login.example/auth',
      realm: 'niord-dk',
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('fetchKeycloakConfig gives up after the attempts, with a warning', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>', { status: 200 })));
    expect(await fetchKeycloakConfig('/rest', 2000, 2, 0)).toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
