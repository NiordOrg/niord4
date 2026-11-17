import { TestBed } from '@angular/core/testing';
import { Injectable, signal } from '@angular/core';
import {
  provideTransloco,
  Translation,
  TranslocoLoader,
  TranslocoService,
} from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { EmptySearchResult } from './empty-search-result';
import { MessagesFacade } from '../../services/messages-facade.service';
import { provideTestLanguages } from '../../../../shared/testing/language-testing';

const EN: Record<string, string> = {
  'messages.list.empty.prompt': 'Please update the search filter',
  'messages.list.empty.matches': '{{count}} matches',
  'messages.list.empty.noMatches': 'No matches',
};

const DA: Record<string, string> = {
  'messages.list.empty.prompt': 'Opdatér søgefiltret',
  'messages.list.empty.matches': '{{count}} resultater',
  'messages.list.empty.noMatches': 'Ingen resultater',
};

@Injectable({ providedIn: 'root' })
class TranslationLoader implements TranslocoLoader {
  getTranslation(lang: string): Observable<Translation> {
    return of((lang === 'da' ? DA : EN) as unknown as Translation);
  }
}

function setup(totalValue: number) {
  const total = signal(totalValue);
  const facade = { list: { total } } as unknown as MessagesFacade;

  try {
    localStorage.removeItem('niord.language');
  } catch {
    // ignore
  }

  TestBed.configureTestingModule({
    providers: [
      provideTestLanguages(),
      provideTransloco({
        config: { availableLangs: ['en', 'da'], defaultLang: 'en', flatten: { aot: false } },
        loader: TranslationLoader,
      }),
      { provide: MessagesFacade, useValue: facade },
    ],
  });

  const transloco = TestBed.inject(TranslocoService);
  transloco.load('en').subscribe();
  transloco.load('da').subscribe();

  const fixture = TestBed.createComponent(EmptySearchResult);
  fixture.detectChanges();
  TestBed.tick();
  return { fixture, language: TestBed.inject(LanguageService) };
}

describe('EmptySearchResult', () => {
  it('renders the prompt title', () => {
    const { fixture } = setup(0);
    expect(fixture.componentInstance.title()).toBe('Please update the search filter');
  });

  it('renders noMatches body when total is zero', () => {
    const { fixture } = setup(0);
    expect(fixture.componentInstance.body()).toBe('No matches');
  });

  it('renders matches body with locale-formatted count when total > 0', () => {
    const { fixture } = setup(1234);
    expect(fixture.componentInstance.body()).toBe('1,234 matches');
  });

  it('reactively re-renders on language switch', () => {
    const { fixture, language } = setup(1234);

    expect(fixture.componentInstance.body()).toBe('1,234 matches');

    language.selectLanguage('da');
    TestBed.tick();

    expect(fixture.componentInstance.title()).toBe('Opdatér søgefiltret');
    expect(fixture.componentInstance.body()).toBe('1.234 resultater');
  });
});
