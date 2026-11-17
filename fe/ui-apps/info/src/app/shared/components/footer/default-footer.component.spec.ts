import { existsSync, readFileSync } from 'node:fs';
import { Dialog } from '@angular/cdk/dialog';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { NiordInfoConfig } from '../../../niord-info.config';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_TRANSLATIONS,
} from '../../../testing/test-config';
import { LegalDialogComponent } from '../legal-dialog/legal-dialog.component';
import { DefaultFooterComponent } from './default-footer.component';

// Branding values no bundle holds, so a word on screen can only have come from the branding.
const DISCLAIMER = '[Ðíšçłáíméŕ]';
const COOKIES = '[Çóóķíéš]';
const ACCESSIBILITY = '[Áççéššíbíłíťý]';
const LOGO = 'assets/branding/logo-gray.png';
const LOGO_ALT = '[Ťhé áúťhóríťý]';
const STATEMENT = 'https://statement.example/site';

// The branding contract: the base product's kit, or the kit a country's set-up unpacks, where
// this spec runs too when the country overrides the footer.
const BRANDING_SCHEMA = [
  '../country-kit/branding.schema.json',
  '../.niord/kit/branding.schema.json',
];

/** Every label the footer reads, as a country's branding sets them. */
const EVERY_LABEL = {
  FOOTER_DISCLAIMER: DISCLAIMER,
  FOOTER_COOKIES: COOKIES,
  FOOTER_ACCESSIBILITY: ACCESSIBILITY,
  FOOTER_LOGO: LOGO,
  FOOTER_LOGO_ALT: LOGO_ALT,
};

/** What the footer renders today with no branding: the product's copyright line alone. */
const COPYRIGHT_ONLY =
  '<footer class="app-shell-gutter bg-chrome py-2 text-center text-chrome-muted"><span>Niord © 2031</span></footer>';

describe('DefaultFooterComponent', () => {
  afterEach(() => vi.useRealTimers());

  // The product's name is the same in every language, and it is still a key: every word the
  // site shows comes from a bundle, so no text on screen is missed by the translation files.
  // The clock stands in a year no bundle could have been written in, so a year typed into the
  // bundle fails here on every day of the year.
  it('names the product and the deployment’s year from the bundle', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2031-06-15T12:00:00Z'));
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [provideTestNiordConfig()],
    });
    const fixture = TestBed.createComponent(DefaultFooterComponent);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent!.trim();
    expect(text).toBe('Niord © 2031');
  });

  describe('with the branding’s labels', () => {
    let open: ReturnType<typeof vi.fn>;

    function configure(imports: unknown[], config: Partial<NiordInfoConfig>): void {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2031-06-15T12:00:00Z'));
      open = vi.fn(() => ({ closed: { subscribe: vi.fn() } }));
      TestBed.configureTestingModule({
        imports,
        providers: [provideTestNiordConfig(config), { provide: Dialog, useValue: { open } }],
      });
    }

    function render(branding: Record<string, string> = {}, config: Partial<NiordInfoConfig> = {}) {
      configure([getTranslocoTestingModule({ en: branding })], config);
      const fixture = TestBed.createComponent(DefaultFooterComponent);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    /**
     * The rendered markup without the comment nodes Angular anchors its blocks with: they are
     * no part of what a reader or a screen reader gets.
     */
    function markup(host: HTMLElement): string {
      const copy = host.cloneNode(true) as HTMLElement;
      const walker = document.createTreeWalker(copy, NodeFilter.SHOW_COMMENT);
      const comments: Node[] = [];
      while (walker.nextNode()) comments.push(walker.currentNode);
      comments.forEach((comment) => comment.parentNode!.removeChild(comment));
      return copy.innerHTML;
    }

    function buttons(host: HTMLElement): HTMLButtonElement[] {
      return [...host.querySelectorAll('button')];
    }

    // A deployment that labels nothing gets the footer it always had, element for element; a
    // configured statement or an alternative text with no logo to describe shows nothing alone.
    it.each([
      ['no branding', {}, {}],
      [
        'a statement and an alternative text alone',
        { FOOTER_LOGO_ALT: LOGO_ALT },
        { accessibilityStatement: STATEMENT },
      ],
    ])('renders the copyright line alone with %s', (_, branding, config) => {
      expect(markup(render(branding, config))).toBe(COPYRIGHT_ONLY);
    });

    it.each([
      ['disclaimer', 'FOOTER_DISCLAIMER', DISCLAIMER],
      ['cookies', 'FOOTER_COOKIES', COOKIES],
    ])(
      'offers the %s document under its label, and no document it has no label for',
      (_, key, label) => {
        const offered = buttons(render({ [key]: label }));

        expect(offered).toHaveLength(1);
        expect(offered[0].getAttribute('type')).toBe('button');
        expect(offered[0].classList).toContain('link-button');
        expect(offered[0].textContent!.trim()).toBe(label);
      },
    );

    it.each([
      ['disclaimer', 'FOOTER_DISCLAIMER', DISCLAIMER],
      ['cookies', 'FOOTER_COOKIES', COOKIES],
    ])('opens the legal dialog on the %s document', async (documentId, key, label) => {
      buttons(render({ [key]: label }))[0].click();

      await vi.waitFor(() => expect(open).toHaveBeenCalledOnce());
      const [component, config] = open.mock.calls[0];
      expect(component).toBe(LegalDialogComponent);
      expect(config).toMatchObject({ data: { documentId } });
    });

    // The statement is a page of its own at the address the build configures, not a document
    // the legal dialog renders.
    it('links the accessibility statement under its label', () => {
      const links = render(
        { FOOTER_ACCESSIBILITY: ACCESSIBILITY },
        { accessibilityStatement: STATEMENT },
      ).querySelectorAll('a');

      expect(links).toHaveLength(1);
      expect(links[0].getAttribute('href')).toBe(STATEMENT);
      expect(links[0].textContent!.trim()).toBe(ACCESSIBILITY);
    });

    it('links no accessibility statement the build does not configure', () => {
      const footer = render({ FOOTER_ACCESSIBILITY: ACCESSIBILITY });

      expect(footer.querySelector('a')).toBeNull();
      expect(footer.textContent).not.toContain(ACCESSIBILITY);
    });

    it('shows the logo the branding names, at the height it is drawn, described by its text', () => {
      const logos = render({ FOOTER_LOGO: LOGO, FOOTER_LOGO_ALT: LOGO_ALT }).querySelectorAll(
        'img',
      );

      expect(logos).toHaveLength(1);
      expect(logos[0].getAttribute('src')).toBe(LOGO);
      expect(logos[0].getAttribute('height')).toBe('22');
      expect(logos[0].getAttribute('alt')).toBe(LOGO_ALT);
    });

    // With no text to describe it the logo is decoration beside the copyright line, never an
    // image announced by its key's name.
    it('shows a logo with no alternative text as decoration', () => {
      const logo = render({ FOOTER_LOGO: LOGO }).querySelector('img')!;

      expect(logo).not.toBeNull();
      expect(logo.getAttribute('alt')).toBe('');
    });

    it('shows no logo the branding does not name', () => {
      expect(render({ FOOTER_LOGO_ALT: LOGO_ALT }).querySelector('img')).toBeNull();
    });

    // Until the branding has arrived each key reads as its own name: a file by that name would
    // be requested and the name shown as a link.
    it('offers nothing before the branding has arrived, and what it labels once it has', () => {
      configure([getEmptyTranslocoTestingModule()], { accessibilityStatement: STATEMENT });
      const fixture = TestBed.createComponent(DefaultFooterComponent);
      fixture.detectChanges();
      const footer = fixture.nativeElement as HTMLElement;

      expect(footer.querySelector('img, button, a')).toBeNull();

      TestBed.inject(TranslocoService).setTranslation(
        { ...TEST_TRANSLATIONS['en'], FOOTER_DISCLAIMER: DISCLAIMER, FOOTER_LOGO: LOGO },
        'en',
      );
      fixture.detectChanges();

      expect(footer.querySelector('img')!.getAttribute('src')).toBe(LOGO);
      expect(buttons(footer).map((button) => button.textContent!.trim())).toEqual([DISCLAIMER]);
    });

    // Every word in the footer is the bundle's or the branding's: no separator, no caption.
    it('shows no text but the copyright line and the labels, in that order', () => {
      const footer = render(EVERY_LABEL, { accessibilityStatement: STATEMENT });

      const items = [...footer.querySelector('footer')!.children].map((item) =>
        item.textContent!.trim(),
      );
      expect(items).toEqual(['Niord © 2031', '', ACCESSIBILITY, DISCLAIMER, COOKIES]);
      expect(footer.textContent!.replace(/\s+/g, '')).toBe(
        ['Niord © 2031', ACCESSIBILITY, DISCLAIMER, COOKIES].join('').replace(/\s+/g, ''),
      );
    });

    // The footer offers exactly the public site's legal documents of the branding contract, so a
    // document the contract adds or drops is a change here too.
    it('offers the legal documents the branding contract names for the public site', async () => {
      const schema = JSON.parse(
        readFileSync(BRANDING_SCHEMA.find((path) => existsSync(path))!, 'utf8'),
      ) as {
        apps: { info: { legal: { documents: Record<string, unknown> } } };
      };
      const footer = render(EVERY_LABEL, { accessibilityStatement: STATEMENT });

      for (const [index, button] of buttons(footer).entries()) {
        button.click();
        await vi.waitFor(() => expect(open).toHaveBeenCalledTimes(index + 1));
      }
      const offered = open.mock.calls.map(([, config]) => config.data.documentId as string);
      expect(offered.sort()).toEqual(Object.keys(schema.apps.info.legal.documents).sort());
    });
  });
});
