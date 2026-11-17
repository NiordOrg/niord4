import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BrandingService } from './branding.service';
import { LanguageSettingsService } from './language-settings.service';

function setup(brandingLanguages: readonly string[] = ['da', 'en']) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: LanguageSettingsService, useValue: { brandingLanguages } },
    ],
  });
  return {
    branding: TestBed.inject(BrandingService),
    http: TestBed.inject(HttpTestingController),
  };
}

describe('BrandingService', () => {
  it('loads a branded language as flat text and records its keys for that language', () => {
    const { branding, http } = setup();
    let result: unknown;
    branding.load('da', 'da').subscribe((value) => (result = value));
    http.expectOne('assets/branding/da.json').flush({
      'footer.copyright': '© Myndighed',
      main: { footer: { links: { center: { url: 'u' } } } },
    });
    expect(result).toEqual({
      'footer.copyright': '© Myndighed',
      'main.footer.links.center.url': 'u',
    });
    expect(branding.isBranded('da', 'footer.copyright')).toBe(true);
    expect(branding.isBranded('da', 'main.footer.links.center.url')).toBe(true);
    expect(branding.isBranded('da', 'footer.cookies')).toBe(false);
    // Another language has its own branding, not yet loaded.
    expect(branding.isBranded('en', 'footer.copyright')).toBe(false);
    http.verify();
  });

  it('requests nothing for a language the build has no branding for', () => {
    const { branding, http } = setup([]);
    let result: unknown;
    branding.load('da', 'da').subscribe((value) => (result = value));
    http.expectNone((request) => request.url.startsWith('assets/branding/'));
    expect(result).toEqual({});
    expect(branding.isBranded('da', 'footer.copyright')).toBe(false);
  });

  it('records the branding under the requested language when another bundle serves it', () => {
    const { branding, http } = setup();
    branding.load('kl', 'da').subscribe();
    http.expectOne('assets/branding/da.json').flush({ 'footer.link': 'https://x.test/' });
    expect(branding.isBranded('kl', 'footer.link')).toBe(true);
    expect(branding.isBranded('da', 'footer.link')).toBe(false);
  });

  it('gives no text and brands nothing when the file is missing', () => {
    const { branding, http } = setup();
    let result: unknown;
    branding.load('da', 'da').subscribe((value) => (result = value));
    http.expectOne('assets/branding/da.json').flush('', { status: 404, statusText: 'Not Found' });
    expect(result).toEqual({});
    expect(branding.isBranded('da', 'footer.copyright')).toBe(false);
  });
});
