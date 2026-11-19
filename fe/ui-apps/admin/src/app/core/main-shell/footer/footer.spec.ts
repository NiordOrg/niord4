import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { BrandingService } from '../../i18n/branding.service';
import { LanguageSettingsService } from '../../i18n/language-settings.service';
import { LanguageService } from '../services/language.service';
import { provideNiordIcons } from '../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { Footer } from './footer';

/** Every footer key a branding file may set. */
const FULL_BRANDING = [
  'footer.copyright',
  'footer.link',
  'footer.disclaimer',
  'footer.cookies',
  'footer.accessibility',
  'footer.accessibilityLink',
  'main.footer.links.center.url',
  'main.footer.links.center.ariaLabel',
  'main.footer.links.center.imageAlt',
  'main.footer.links.center.imageSrc',
  'main.footer.dialogs.disclaimer.title',
  'main.footer.dialogs.cookies.title',
];

function setup(branded: readonly string[]) {
  // The stub brands `branded` for English only, so a language switch shows the per-language rule.
  const brandedKeys = signal<ReadonlySet<string>>(new Set(branded));
  const selectedLanguage = signal('en');
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: BrandingService,
        useValue: {
          isBranded: (lang: string, key: string) => lang === 'en' && brandedKeys().has(key),
        },
      },
      {
        provide: LanguageService,
        useValue: { selectedLanguage, defaultLanguage: 'en' },
      },
      { provide: LanguageSettingsService, useValue: { brandingLanguages: ['en'] } },
    ],
  });
  const fixture = TestBed.createComponent(Footer);
  fixture.autoDetectChanges();
  return {
    fixture,
    element: fixture.nativeElement as HTMLElement,
    brandedKeys,
    selectedLanguage,
    http: TestBed.inject(HttpTestingController),
  };
}

describe('Footer', () => {
  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('renders no footer row when the build has no branding', async () => {
    const { fixture, element } = setup([]);
    await fixture.whenStable();
    expect(element.querySelector('footer')).toBeNull();
    expect(element.children.length).toBe(0);
  });

  it('renders the branded copyright link, logo and legal-document buttons', async () => {
    const { fixture, element } = setup(FULL_BRANDING);
    await fixture.whenStable();
    // With the stub loader, translations render as their keys.
    const copyright = element.querySelector('a[href="footer.link"]');
    expect(copyright?.textContent?.trim()).toBe('footer.copyright');
    const logo = element.querySelector('img');
    expect(logo?.getAttribute('src')).toBe('main.footer.links.center.imageSrc');
    expect(logo?.closest('a')?.getAttribute('href')).toBe('main.footer.links.center.url');
    const statement = element.querySelector('a[href="footer.accessibilityLink"]');
    expect(statement?.textContent?.trim()).toBe('footer.accessibility');
    expect(statement?.getAttribute('target')).toBeNull();
    const buttons = [...element.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['footer.disclaimer', 'footer.cookies']);
  });

  it('renders the footer with the accessibility statement link when only it is branded', async () => {
    const { fixture, element } = setup(['footer.accessibility', 'footer.accessibilityLink']);
    await fixture.whenStable();
    expect(element.querySelector('footer')).not.toBeNull();
    const links = [...element.querySelectorAll('a')];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['footer.accessibilityLink']);
    expect(links[0].textContent?.trim()).toBe('footer.accessibility');
    expect(element.querySelector('button')).toBeNull();
  });

  it('renders no statement link for a label without its address', async () => {
    const { fixture, element } = setup(['footer.accessibility']);
    await fixture.whenStable();
    expect(element.querySelector('footer')).toBeNull();
    expect(element.querySelector('a')).toBeNull();
  });

  it('shows only what the branding sets, ignoring same-named keys from elsewhere', async () => {
    const { fixture, element } = setup(['footer.copyright', 'footer.cookies']);
    await fixture.whenStable();
    expect(element.querySelector('a')).toBeNull();
    expect(element.querySelector('footer')?.textContent).toContain('footer.copyright');
    expect(element.querySelector('img')).toBeNull();
    const buttons = [...element.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['footer.cookies']);
  });

  it('follows the branding as it loads', async () => {
    const { fixture, element, brandedKeys } = setup([]);
    await fixture.whenStable();
    expect(element.querySelector('footer')).toBeNull();
    brandedKeys.set(new Set(FULL_BRANDING));
    await fixture.whenStable();
    expect(element.querySelector('footer')).not.toBeNull();
  });

  it('asks the branding of the active language', async () => {
    const { fixture, element, selectedLanguage } = setup(FULL_BRANDING);
    await fixture.whenStable();
    expect(element.querySelector('footer')).not.toBeNull();
    selectedLanguage.set('da');
    await fixture.whenStable();
    expect(element.querySelector('footer')).toBeNull();
  });

  it('opens the legal-document dialog for the button pressed', async () => {
    const { fixture, element, http } = setup(FULL_BRANDING);
    await fixture.whenStable();
    const cookies = [...element.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('footer.cookies'),
    )!;
    cookies.click();
    await fixture.whenStable();
    http.expectOne('assets/legal/cookies.en.html').flush('<h3>Cookies</h3>');
    await fixture.whenStable();
    const panel = document.querySelector('.dialog-panel');
    expect(panel?.textContent).toContain('main.footer.dialogs.cookies.title');
    expect(panel?.querySelector('.legal-document h3')?.textContent).toBe('Cookies');
    http.verify();
  });
});
