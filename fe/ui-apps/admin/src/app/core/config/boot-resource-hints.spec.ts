import { describe, expect, it } from 'vitest';

import type { AppConfig } from './app-config.interface';
import { bootResourceHints } from './boot-resource-hints';

const configWith = (apiBaseUrl: string, keycloakUrl: string): AppConfig =>
  ({
    apiBaseUrl,
    keycloak: { url: keycloakUrl, realm: 'niord', clientId: 'niord-web' },
  }) as AppConfig;

const DOC = 'https://niord-app.example.org/';

describe('bootResourceHints', () => {
  it('warms the identity provider FIRST — its iframe chain is serial and the longest pole', () => {
    const hints = bootResourceHints(
      configWith('https://niord.example.org/rest', 'https://login.example.org/auth'),
      DOC,
    );

    expect(hints.map((hint) => hint.href)).toEqual([
      'https://login.example.org',
      'https://niord.example.org',
    ]);
  });

  it('matches each origin to the connection pool its first request will use', () => {
    const hints = bootResourceHints(
      configWith('https://niord.example.org/rest', 'https://login.example.org/auth'),
      DOC,
    );

    // Keycloak boots via iframe DOCUMENT loads -> credentialed pool -> no crossorigin.
    expect(hints[0].anonymous).toBe(false);
    // The API is cookieless CORS XHR -> anonymous pool -> crossorigin="anonymous".
    expect(hints[1].anonymous).toBe(true);
  });

  it('drops a same-origin backend — that connection already exists', () => {
    const hints = bootResourceHints(
      configWith('https://niord-app.example.org/rest', 'https://login.example.org/auth'),
      DOC,
    );

    expect(hints.map((hint) => hint.href)).toEqual(['https://login.example.org']);
  });

  it('collapses a shared origin to one hint rather than emitting a duplicate', () => {
    const hints = bootResourceHints(
      configWith('https://sso.example.org/rest', 'https://sso.example.org/auth'),
      DOC,
    );

    expect(hints).toEqual([{ href: 'https://sso.example.org', anonymous: false }]);
  });

  it('resolves a relative apiBaseUrl against the document', () => {
    const hints = bootResourceHints(configWith('/rest', 'https://login.example.org/auth'), DOC);

    // Relative -> same origin as the document -> dropped.
    expect(hints.map((hint) => hint.href)).toEqual(['https://login.example.org']);
  });

  it('skips unparseable or absent values instead of throwing during boot', () => {
    expect(bootResourceHints(configWith('', ':::not a url'), DOC)).toEqual([]);
    expect(bootResourceHints({} as AppConfig, DOC)).toEqual([]);
  });
});
