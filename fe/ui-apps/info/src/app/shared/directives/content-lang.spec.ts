import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ContentLang } from './content-lang';
import { LanguageService } from '../../core/services/language.service';

@Component({
  selector: 'app-content-lang-host',
  imports: [ContentLang],
  template: `<span [appContentLang]="textLang()">text</span>`,
})
class ContentLangHost {
  readonly textLang = signal<string | undefined>('da');
}

describe('ContentLang', () => {
  const pageLanguage = signal('kl');

  function render() {
    TestBed.configureTestingModule({
      providers: [{ provide: LanguageService, useValue: { language: pageLanguage } }],
    });
    const fixture = TestBed.createComponent(ContentLangHost);
    fixture.detectChanges();
    const span = (fixture.nativeElement as HTMLElement).querySelector('span')!;
    return { fixture, span };
  }

  beforeEach(() => pageLanguage.set('kl'));

  it('marks text in another language than the page with the language it is in', () => {
    const { span } = render();
    expect(span.getAttribute('lang')).toBe('da');
  });

  // <html lang> already says it; a second lang on every title would only be noise.
  it('leaves text in the page language unmarked', () => {
    pageLanguage.set('da');
    const { span } = render();
    expect(span.hasAttribute('lang')).toBe(false);
  });

  it('leaves text whose language is not known unmarked', () => {
    const { fixture, span } = render();
    fixture.componentInstance.textLang.set(undefined);
    fixture.detectChanges();
    expect(span.hasAttribute('lang')).toBe(false);
  });

  it('follows the page when the reader switches language', () => {
    const { fixture, span } = render();
    pageLanguage.set('da');
    fixture.detectChanges();
    expect(span.hasAttribute('lang')).toBe(false);

    pageLanguage.set('en');
    fixture.detectChanges();
    expect(span.getAttribute('lang')).toBe('da');
  });
});
