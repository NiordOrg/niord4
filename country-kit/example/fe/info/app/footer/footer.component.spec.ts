import { readFileSync } from 'node:fs';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { getTranslocoTestingModule, provideTestNiordConfig } from '@niord/info/api/testing';
import { COUNTRY_INFO } from '../info.config';
import { FooterComponent } from './footer.component';

/** The example's branding, relative to the workspace folder the runner starts in. */
function branding(lang: string): Record<string, string> {
  return JSON.parse(readFileSync(`info/assets/branding/${lang}.json`, 'utf8')) as Record<
    string,
    string
  >;
}

function render(lang: string, withBranding = true): HTMLElement {
  TestBed.configureTestingModule({
    imports: [getTranslocoTestingModule({ [lang]: withBranding ? branding(lang) : {} })],
    providers: [
      provideTestNiordConfig({
        languages: [lang],
        defaultLanguage: lang,
        timeZone: COUNTRY_INFO.timeZone,
      }),
    ],
  });
  TestBed.inject(TranslocoService).setActiveLang(lang);
  const fixture = TestBed.createComponent(FooterComponent);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('FooterComponent (example country)', () => {
  it('shows the copyright line, the motto and the logo its branding names', () => {
    const words = branding('zz');
    const footer = render('zz');

    expect(footer.textContent).toContain(words['country.motto']);
    expect(footer.textContent).toContain(words['FOOTER_DISCLAIMER']);
    const logo = footer.querySelector('img')!;
    expect(logo.getAttribute('src')).toBe(words['FOOTER_LOGO']);
    expect(logo.getAttribute('alt')).toBe(words['FOOTER_LOGO_ALT']);
  });

  it('puts the current year into the copyright line', () => {
    const footer = render('zz');

    expect(footer.querySelector('p')!.textContent).toMatch(/© \d{4} /);
  });

  it('asks for no logo before the branding has arrived', () => {
    expect(render('zz', false).querySelector('img')).toBeNull();
  });

  it('splits the footer with the country-only utility', () => {
    expect(render('zz').querySelector('footer')!.className).toContain('sm:grid-cols-[3fr_2fr]');
  });
});
