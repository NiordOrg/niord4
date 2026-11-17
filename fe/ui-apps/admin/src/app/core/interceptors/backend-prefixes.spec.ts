import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../config/app-config.token';
import type { AppConfig } from '../config/app-config.interface';
import { createRuntimeConfig, mergeWebSettings } from '../config/web-settings';
import { isNiordBackendRequest, urlMatchesPrefix } from './backend-prefixes';

describe('urlMatchesPrefix', () => {
  const PREFIX = 'https://api.example.invalid';

  it('matches an exact URL', () => {
    expect(urlMatchesPrefix(PREFIX, PREFIX)).toBe(true);
  });

  it('matches a path continuation', () => {
    expect(urlMatchesPrefix(`${PREFIX}/rest/messages`, PREFIX)).toBe(true);
  });

  it('matches a query continuation', () => {
    expect(urlMatchesPrefix(`${PREFIX}?lang=en`, PREFIX)).toBe(true);
  });

  it('matches a fragment continuation', () => {
    expect(urlMatchesPrefix(`${PREFIX}#section`, PREFIX)).toBe(true);
  });

  it('matches any sub-path when the prefix already ends in a slash', () => {
    expect(urlMatchesPrefix('/wms/GetMap?LAYERS=cells', '/wms/')).toBe(true);
    expect(urlMatchesPrefix('/wms/', '/wms/')).toBe(true);
  });

  it('rejects a suffix-confusable host (the evil-suffix attack)', () => {
    expect(urlMatchesPrefix(`${PREFIX}.evil.com/rest/messages`, PREFIX)).toBe(false);
  });

  it('rejects a non-boundary path continuation', () => {
    // `https://niord.example.org/restaurant` must not match the `/rest` API prefix.
    expect(
      urlMatchesPrefix('https://niord.example.org/restaurant', 'https://niord.example.org/rest'),
    ).toBe(false);
  });

  it('rejects a URL that does not start with the prefix at all', () => {
    expect(urlMatchesPrefix('https://other.host/rest', PREFIX)).toBe(false);
  });
});

interface ConfigureOptions {
  readonly apiBaseUrl: string;
  readonly wmsUrl?: string;
}

function match(url: string, { apiBaseUrl, wmsUrl }: ConfigureOptions): boolean {
  const appConfig = {
    apiBaseUrl,
    appName: 'Niord',
    helpUrls: {},
    keycloak: { url: '', realm: '', clientId: '' },
    map: wmsUrl ? { wmsUrl } : {},
  } as unknown as AppConfig;

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: APP_CONFIG, useValue: appConfig }],
  });

  return TestBed.runInInjectionContext(() => isNiordBackendRequest(url));
}

describe('isNiordBackendRequest', () => {
  describe('apiBaseUrl (bare-origin, suffix-confusable)', () => {
    const apiBaseUrl = 'https://api.example.invalid';

    it('matches the exact origin', () => {
      expect(match(apiBaseUrl, { apiBaseUrl })).toBe(true);
    });

    it('matches a REST sub-path', () => {
      expect(match(`${apiBaseUrl}/rest/messages`, { apiBaseUrl })).toBe(true);
    });

    it('matches a query continuation', () => {
      expect(match(`${apiBaseUrl}?lang=en`, { apiBaseUrl })).toBe(true);
    });

    it('does NOT match an attacker-controlled suffix host', () => {
      expect(match(`${apiBaseUrl}.evil.com/rest/messages`, { apiBaseUrl })).toBe(false);
    });
  });

  describe('apiBaseUrl (path-bearing)', () => {
    const apiBaseUrl = 'https://niord.example.org/rest';

    it('matches the exact prefix and sub-paths', () => {
      expect(match(apiBaseUrl, { apiBaseUrl })).toBe(true);
      expect(match(`${apiBaseUrl}/messages`, { apiBaseUrl })).toBe(true);
    });

    it('does NOT match a suffix host', () => {
      expect(match('https://niord.example.org.evil.com/rest/messages', { apiBaseUrl })).toBe(false);
    });

    it('does NOT match a non-boundary continuation', () => {
      expect(match('https://niord.example.org/restaurant', { apiBaseUrl })).toBe(false);
    });
  });

  describe('wmsUrl on the API origin', () => {
    const apiBaseUrl = 'https://niord.example.invalid/rest';
    const wmsUrl = 'https://niord.example.invalid/wms';

    it('matches a WMS GetMap request', () => {
      expect(match(`${wmsUrl}?SERVICE=WMS&REQUEST=GetMap`, { apiBaseUrl, wmsUrl })).toBe(true);
      expect(match(`${wmsUrl}/GetMap`, { apiBaseUrl, wmsUrl })).toBe(true);
    });

    it('does NOT match an attacker-controlled WMS suffix host', () => {
      expect(match(`${wmsUrl}.evil.com/GetMap`, { apiBaseUrl, wmsUrl })).toBe(false);
    });

    it('matches any sub-path when wmsUrl ends in a slash', () => {
      expect(match('/wms/GetMap?LAYERS=cells', { apiBaseUrl: '/rest', wmsUrl: '/wms/' })).toBe(
        true,
      );
    });
  });

  // The WMS prefix is the one token boundary the runtime web-settings overlay can move, so it
  // only counts while it shares the API's origin.
  describe('wmsUrl on another origin', () => {
    it('does NOT match a WMS prefix on another host', () => {
      const wmsUrl = 'https://tiles.example.invalid/wms/';
      expect(
        match(`${wmsUrl}GetMap`, { apiBaseUrl: 'https://niord.example.invalid/rest', wmsUrl }),
      ).toBe(false);
    });

    it('does NOT match a WMS prefix on another scheme or port of the API host', () => {
      const apiBaseUrl = 'https://niord.example.invalid/rest';
      expect(
        match('http://niord.example.invalid/wms/x', {
          apiBaseUrl,
          wmsUrl: 'http://niord.example.invalid/wms/',
        }),
      ).toBe(false);
      expect(
        match('https://niord.example.invalid:8443/wms/x', {
          apiBaseUrl,
          wmsUrl: 'https://niord.example.invalid:8443/wms/',
        }),
      ).toBe(false);
    });

    it('does NOT match a page-relative WMS prefix when the API is on another origin', () => {
      expect(
        match('/wms/GetMap', { apiBaseUrl: 'https://niord.example.invalid/rest', wmsUrl: '/wms/' }),
      ).toBe(false);
    });

    it('does NOT match an absolute WMS prefix when the API is page-relative and elsewhere', () => {
      const wmsUrl = 'https://tiles.example.invalid/wms/';
      expect(match(`${wmsUrl}GetMap`, { apiBaseUrl: '/rest', wmsUrl })).toBe(false);
    });

    it('still matches the API itself', () => {
      const apiBaseUrl = 'https://niord.example.invalid/rest';
      expect(
        match(`${apiBaseUrl}/messages`, {
          apiBaseUrl,
          wmsUrl: 'https://tiles.example.invalid/wms/',
        }),
      ).toBe(true);
    });
  });

  it('does NOT follow a wmsUrl the web-settings overlay moved to another host', () => {
    const config = createRuntimeConfig({
      apiBaseUrl: 'https://niord.example.invalid/rest',
      appName: 'Niord',
      helpUrls: {},
      keycloak: { url: '', realm: '', clientId: '' },
      map: { wmsUrl: 'https://niord.example.invalid/wms/', wmsProtected: true },
    });
    mergeWebSettings(config, { wmsUrl: 'https://tiles.example.invalid/wms/', wmsProtected: true });
    expect(config.map?.wmsUrl).toBe('https://tiles.example.invalid/wms/');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: APP_CONFIG, useValue: config }] });
    expect(
      TestBed.runInInjectionContext(() =>
        isNiordBackendRequest('https://tiles.example.invalid/wms/?REQUEST=GetMap'),
      ),
    ).toBe(false);
  });

  it('returns false for third-party URLs when no wmsUrl is configured', () => {
    expect(
      match('https://tile.openstreetmap.org/1/2/3.png', {
        apiBaseUrl: 'https://api.example.invalid',
      }),
    ).toBe(false);
  });
});
