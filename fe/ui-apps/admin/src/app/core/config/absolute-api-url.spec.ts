import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { ApiUrlResolver } from './absolute-api-url';
import type { AppConfig } from './app-config.interface';
import { APP_CONFIG } from './app-config.token';

/** The host the app itself is served from — a dev server, in the split case. */
const PAGE_ORIGIN = 'http://localhost:4200';

function resolver(apiBaseUrl: string): ApiUrlResolver {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: APP_CONFIG,
        useValue: { apiBaseUrl, appName: 'Niord', helpUrls: {} } as AppConfig,
      },
      { provide: DOCUMENT, useValue: { location: { origin: PAGE_ORIGIN } } },
    ],
  });
  return TestBed.inject(ApiUrlResolver);
}

describe('ApiUrlResolver', () => {
  /**
   * The split-host case, which is the whole reason this exists.
   *
   * The API reports `/rest/repo/file/…` because that is the path on ITS host.
   * Followed from a page served by the dev server it reaches the dev server,
   * which answers with the app rather than the document.
   */
  it('resolves a backend path against the API’s origin when the base is absolute', () => {
    expect(
      resolver('https://niord.example.org/rest').absoluteApiUrl(
        '/rest/repo/file/publications/a/8e/EfS-36.pdf',
      ),
    ).toBe('https://niord.example.org/rest/repo/file/publications/a/8e/EfS-36.pdf');
  });

  /** In production app and API share an origin, and the page's is the right one. */
  it('resolves against the page’s own origin when the base is relative', () => {
    expect(resolver('/rest').absoluteApiUrl('/rest/repo/file/EfS-36.pdf')).toBe(
      `${PAGE_ORIGIN}/rest/repo/file/EfS-36.pdf`,
    );
  });

  /**
   * An external link belongs to whoever hosts it.
   *
   * A publication whose document lives on another authority's site carries that
   * address verbatim; rewriting its host would point every one of them here.
   */
  it('leaves an address that already names its own host alone', () => {
    const api = resolver('https://niord.example.org/rest');
    expect(api.absoluteApiUrl('https://other-authority.example.org/efs.pdf')).toBe(
      'https://other-authority.example.org/efs.pdf',
    );
    expect(api.absoluteApiUrl('//cdn.example/efs.pdf')).toBe('//cdn.example/efs.pdf');
  });

  /** Blank stays blank: an address that resolves to nothing is worse than none. */
  it('leaves an empty address empty', () => {
    expect(resolver('https://niord.example.org/rest').absoluteApiUrl('')).toBe('');
  });

  /** A base nobody can parse falls back to what the browser would have done. */
  it('falls back to the page origin for an unparseable base', () => {
    expect(resolver('https://').absoluteApiUrl('/rest/repo/file/x.pdf')).toBe(
      `${PAGE_ORIGIN}/rest/repo/file/x.pdf`,
    );
  });
});
