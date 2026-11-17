import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { signal, SecurityContext } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { LegalDialogComponent } from './legal-dialog.component';
import { LanguageService } from '../../../core/services/language.service';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../../testing/test-config';

/** The public site's assets, relative to the workspace the spec runs in. */
const ASSETS = 'ui-apps/info/public/assets';

/**
 * The legal folders of any asset overlay in this tree, `assets/<cc>/legal/`: none in the base
 * product (its default overlay has branding icons only), and none in a country workspace, whose
 * own documents its set-up checks.
 */
function legalFolders(): string[] {
  if (!existsSync(ASSETS)) return [];
  return readdirSync(ASSETS, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && existsSync(`${ASSETS}/${entry.name}/legal`))
    .map((entry) => `${ASSETS}/${entry.name}/legal`);
}

function shippedDocuments(folders: string[]): { file: string; html: string }[] {
  return folders.flatMap((folder) =>
    readdirSync(folder, { withFileTypes: true })
      .filter((entry) => !entry.isDirectory() && entry.name.endsWith('.html'))
      .map((entry) => ({
        file: `${folder}/${entry.name}`,
        html: readFileSync(`${folder}/${entry.name}`, 'utf8'),
      })),
  );
}

/** A document with every element and attribute the documents use, checked wherever the spec runs. */
const FIXTURE = {
  file: 'the inline fixture',
  html: [
    '<h3 class="lead">Disclaimer</h3>',
    '<p>See <a href="https://authority.example/" target="_blank" rel="noopener">the authority</a>.</p>',
    '<dl><dt>Term</dt><dd>Meaning</dd></dl>',
  ].join('\n'),
};

/** Every element name a fragment uses. */
function tags(html: string): Set<string> {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return new Set([...doc.body.querySelectorAll('*')].map((el) => el.tagName.toLowerCase()));
}

/** Every (element, attribute) pair a fragment uses, as `tag@attribute`. */
function attributes(html: string): Set<string> {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const pairs = new Set<string>();
  for (const el of doc.body.querySelectorAll('*')) {
    for (const name of el.getAttributeNames()) pairs.add(`${el.tagName.toLowerCase()}@${name}`);
  }
  return pairs;
}

function setUp(
  language: string,
  documentId = 'disclaimer',
  config?: { languages: string[]; defaultLanguage: string },
) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [LegalDialogComponent, getTranslocoTestingModule()],
    providers: [
      provideTestNiordConfig(config),
      provideHttpClient(),
      provideHttpClientTesting(),
      // `DialogRef` and the data come from an actual `Dialog.open()`; a component test stands
      // the dialog up on its own and supplies both.
      { provide: DialogRef, useValue: { close: vi.fn() } },
      { provide: DIALOG_DATA, useValue: { documentId } },
      { provide: LanguageService, useValue: { language: signal(language) } },
    ],
  });
  const fixture: ComponentFixture<LegalDialogComponent> =
    TestBed.createComponent(LegalDialogComponent);
  return { fixture, component: fixture.componentInstance };
}

describe('LegalDialogComponent', () => {
  let httpTesting: HttpTestingController;

  afterEach(() => httpTesting.verify());

  it('asks for the document of the active language and renders it', () => {
    const { fixture, component } = setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();

    const req = httpTesting.expectOne('assets/legal/disclaimer.zz.html');
    expect(req.request.method).toBe('GET');
    req.flush('<p>[Díscláímér]</p>');

    expect(component.content()).toBe('<p>[Díscláímér]</p>');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.legal-document').innerHTML).toContain(
      '[Díscláímér]',
    );
  });

  it('falls back to the site default and then to the remaining site languages, in order', () => {
    const { fixture, component } = setUp('kl', 'cookies', {
      languages: ['kl', 'da', 'en'],
      defaultLanguage: 'kl',
    });
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();

    httpTesting
      .expectOne('assets/legal/cookies.kl.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    httpTesting
      .expectOne('assets/legal/cookies.da.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    httpTesting.expectOne('assets/legal/cookies.en.html').flush('<p>Cookies</p>');

    expect(component.content()).toBe('<p>Cookies</p>');
  });

  // The default language comes before English: the country writes its documents in its own
  // language first.
  it('gives a reader in a language without the document the site default, not English', () => {
    const { fixture, component } = setUp('kl', 'cookies', {
      languages: ['da', 'en', 'kl'],
      defaultLanguage: 'da',
    });
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();

    httpTesting
      .expectOne('assets/legal/cookies.kl.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    httpTesting.expectOne('assets/legal/cookies.da.html').flush('<p>Cookies</p>');

    expect(component.contentLang()).toBe('da');
  });

  // A document read aloud by the rules of the page's language when it is written in another is
  // unintelligible, and the reader is given no sign that the language changed.
  it('marks a document found in another language than the page with the language it is in', () => {
    const { fixture } = setUp('kl', 'cookies', { languages: ['kl', 'da'], defaultLanguage: 'kl' });
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();
    httpTesting
      .expectOne('assets/legal/cookies.kl.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    httpTesting.expectOne('assets/legal/cookies.da.html').flush('<p>Cookies</p>');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.legal-document').getAttribute('lang')).toBe('da');
  });

  it('leaves a document in the language of the page unmarked', () => {
    const { fixture } = setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();
    httpTesting.expectOne('assets/legal/disclaimer.zz.html').flush('<p>[Díscláímér]</p>');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.legal-document').hasAttribute('lang')).toBe(false);
  });

  it('treats the SPA fallback page as a miss and tries the next candidate', () => {
    const { fixture, component } = setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();

    httpTesting
      .expectOne('assets/legal/disclaimer.zz.html')
      .flush('<!doctype html><html><body><app-root></app-root></body></html>');
    httpTesting.expectOne('assets/legal/disclaimer.en.html').flush('<p>Disclaimer</p>');

    expect(component.content()).toBe('<p>Disclaimer</p>');
  });

  // A title over an empty body tells a reader nothing about why the document is not there.
  it('says the document could not be read when no candidate answers, and still closes', () => {
    const { fixture, component } = setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);
    const dialogRef = TestBed.inject(DialogRef);

    fixture.detectChanges();
    const alert = () => fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    // The live region is there before the notice, so the notice is announced when it lands.
    expect(alert().textContent!.trim()).toBe('');

    httpTesting
      .expectOne('assets/legal/disclaimer.zz.html')
      .flush('', { status: 404, statusText: 'Not Found' });
    httpTesting
      .expectOne('assets/legal/disclaimer.en.html')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

    expect(component.content()).toBe('');
    fixture.detectChanges();
    expect(alert().textContent!.trim()).toBe('Failed to load document');
    expect(fixture.nativeElement.querySelector('.legal-document').innerHTML).toBe('');

    fixture.nativeElement.querySelector('.dialog-header .icon-button').click();
    expect(dialogRef.close).toHaveBeenCalled();
  });

  it('says nothing of a failure when a later candidate answers', () => {
    const { fixture } = setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);

    fixture.detectChanges();
    httpTesting
      .expectOne('assets/legal/disclaimer.zz.html')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    httpTesting.expectOne('assets/legal/disclaimer.en.html').flush('<p>Disclaimer</p>');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent.trim()).toBe('');
  });

  it('derives the title key from the document it shows', () => {
    const disclaimer = setUp('zz', 'disclaimer');
    httpTesting = TestBed.inject(HttpTestingController);
    expect(disclaimer.component.titleKey()).toBe('LEGAL_TITLE_DISCLAIMER');
    httpTesting.verify();

    const cookies = setUp('zz', 'cookies');
    httpTesting = TestBed.inject(HttpTestingController);
    expect(cookies.component.titleKey()).toBe('LEGAL_TITLE_COOKIES');
  });

  it('ships no legal document that Angular sanitises anything out of', () => {
    setUp('zz');
    httpTesting = TestBed.inject(HttpTestingController);
    const sanitizer = TestBed.inject(DomSanitizer);

    // Each overlay that is here ships documents; the fixture is checked in any tree.
    const folders = legalFolders();
    const documents = shippedDocuments(folders);
    expect(documents.length, 'a legal folder of an overlay is empty').toBeGreaterThanOrEqual(
      folders.length,
    );

    for (const { file, html } of [FIXTURE, ...documents]) {
      const sanitized = sanitizer.sanitize(SecurityContext.HTML, html) ?? '';
      // Sets, not strings: the sanitiser normalises quoting, whitespace and attribute order.
      expect([...tags(sanitized)].sort(), file).toEqual([...tags(html)].sort());
      expect([...attributes(sanitized)].sort(), file).toEqual([...attributes(html)].sort());
    }
  });
});
