import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { LanguageSettingsService } from '../../i18n/language-settings.service';
import { LanguageService } from '../services/language.service';
import { provideNiordIcons } from '../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { LegalDocument, LegalDocumentDialog } from './legal-document-dialog';

@Component({
  imports: [LegalDocumentDialog],
  template: `<app-legal-document-dialog [document]="document()" [(visible)]="visible" />`,
})
class Host {
  readonly document = signal<LegalDocument>('disclaimer');
  readonly visible = signal(false);
}

interface Deployment {
  readonly selected?: string;
  readonly defaultLanguage?: string;
  readonly branded?: readonly string[];
}

function setup({
  selected = 'kl',
  defaultLanguage = 'da',
  branded = ['da', 'en'],
}: Deployment = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal(selected), defaultLanguage },
      },
      { provide: LanguageSettingsService, useValue: { brandingLanguages: branded } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  return { fixture, host: fixture.componentInstance, http: TestBed.inject(HttpTestingController) };
}

const panel = () => document.querySelector('.dialog-panel');
const body = () => panel()?.querySelector('.legal-document');

describe('LegalDocumentDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('reads nothing until it is opened', async () => {
    const { fixture, http } = setup();
    await fixture.whenStable();
    http.expectNone(() => true);
    expect(panel()).toBeNull();
  });

  it('renders the reader-language document, with no lang of its own', async () => {
    const { fixture, host, http } = setup({ selected: 'en' });
    host.visible.set(true);
    await fixture.whenStable();
    http.expectOne('assets/legal/disclaimer.en.html').flush('<p>English text</p>');
    await fixture.whenStable();
    expect(body()?.innerHTML).toBe('<p>English text</p>');
    expect(body()?.hasAttribute('lang')).toBe(false);
    expect(panel()?.textContent).toContain('main.footer.dialogs.disclaimer.title');
    http.verify();
  });

  it('falls back to the deployment default, then English, then the other branding languages', async () => {
    const { fixture, host, http } = setup({
      selected: 'da',
      defaultLanguage: 'en',
      branded: ['da', 'en', 'fo'],
    });
    host.visible.set(true);
    await fixture.whenStable();
    http
      .expectOne('assets/legal/disclaimer.da.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    // The SPA fallback answers a missing file with index.html and a 200.
    http
      .expectOne('assets/legal/disclaimer.en.html')
      .flush('<!doctype html><html><body><app-root></app-root></body></html>');
    await fixture.whenStable();
    http.expectOne('assets/legal/disclaimer.fo.html').flush('<p>Føroyskt</p>');
    await fixture.whenStable();
    expect(body()?.innerHTML).toBe('<p>Føroyskt</p>');
    expect(body()?.getAttribute('lang')).toBe('fo');
    http.verify();
  });

  it("asks only the build's branding languages, the reader's language skipped when it has none", async () => {
    const { fixture, host, http } = setup({ selected: 'kl', defaultLanguage: 'da' });
    host.visible.set(true);
    await fixture.whenStable();
    http.expectOne('assets/legal/disclaimer.da.html').flush('<p>Dansk</p>');
    await fixture.whenStable();
    expect(body()?.getAttribute('lang')).toBe('da');
    http.verify();
  });

  it('says so when no language has the document', async () => {
    const { fixture, host, http } = setup({
      selected: 'en',
      defaultLanguage: 'en',
      branded: ['en'],
    });
    host.document.set('cookies');
    host.visible.set(true);
    await fixture.whenStable();
    http
      .expectOne('assets/legal/cookies.en.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(panel()?.querySelector('[role="alert"]')?.textContent).toContain('common.fetchError');
    http.verify();
  });

  it("renders the document through Angular's sanitiser: markup kept, script and handlers gone", async () => {
    const { fixture, host, http } = setup({ selected: 'en' });
    host.visible.set(true);
    await fixture.whenStable();
    http
      .expectOne('assets/legal/disclaimer.en.html')
      .flush(
        '<h3>About</h3><p onclick="alert(1)">Text</p><dl><dt>Term</dt><dd>Def</dd></dl>' +
          '<script>alert(2)</script><img src="x" onerror="alert(3)">',
      );
    await fixture.whenStable();
    const rendered = body()!;
    expect(rendered.querySelector('h3')?.textContent).toBe('About');
    expect(rendered.querySelector('dl dt')?.textContent).toBe('Term');
    expect(rendered.querySelector('script')).toBeNull();
    expect(rendered.querySelector('[onclick]')).toBeNull();
    expect(rendered.querySelector('[onerror]')).toBeNull();
    http.verify();
  });

  it('closes on its Close button', async () => {
    const { fixture, host, http } = setup({ selected: 'en' });
    host.visible.set(true);
    await fixture.whenStable();
    http.expectOne('assets/legal/disclaimer.en.html').flush('<p>Text</p>');
    await fixture.whenStable();
    (panel()!.querySelector('button.btn-secondary') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(panel()).toBeNull();
    expect(host.visible()).toBe(false);
  });
});
