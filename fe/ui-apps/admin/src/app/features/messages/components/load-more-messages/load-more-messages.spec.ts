import { TestBed } from '@angular/core/testing';
import { Injectable, signal } from '@angular/core';
import {
  provideTransloco,
  Translation,
  TranslocoLoader,
  TranslocoService,
} from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LoadMoreMessages } from './load-more-messages';
import { MessagesFacade } from '../../services/messages-facade.service';
import type { Message } from '../../models/message.model';
import { provideTestLanguages } from '../../../../shared/testing/language-testing';

@Injectable({ providedIn: 'root' })
class TranslationLoader implements TranslocoLoader {
  getTranslation(lang: string): Observable<Translation> {
    const word = lang === 'da' ? 'af' : 'of';
    return of({
      'msg.list.load_more': `{{from}} - {{to}} ${word} {{total}}`,
    } as unknown as Translation);
  }
}

function setup(opts: { messagesLength: number; total: number; maxPage?: number; error?: string }) {
  const messages = signal<readonly Message[]>(
    Array.from({ length: opts.messagesLength }, (_, i) => ({ id: `m-${i}` }) as Message),
  );
  const total = signal(opts.total);
  const hasMore = signal(opts.messagesLength < opts.total);
  const error = signal<string | null>(opts.error ?? null);
  const maxPageSize = signal(opts.maxPage ?? 200);
  const loadMore = vi.fn();
  const facade = {
    list: { messages, total, hasMore, error, loadMore },
    shell: { maxPageSize },
  } as unknown as MessagesFacade;

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

  const fixture = TestBed.createComponent(LoadMoreMessages);
  fixture.detectChanges();
  TestBed.tick();
  return { fixture, loadMore, hasMore, language: TestBed.inject(LanguageService) };
}

describe('LoadMoreMessages', () => {
  it('hides the button when hasMore is false', () => {
    const { fixture } = setup({ messagesLength: 50, total: 50 });
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('renders the button when hasMore is true', () => {
    const { fixture } = setup({ messagesLength: 50, total: 200 });
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
  });

  it('hides the button while a load error is showing (retry banner is the recovery path)', () => {
    const { fixture } = setup({ messagesLength: 50, total: 200, error: 'boom' });
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('interpolates from/to/total with locale-formatted numbers (en)', () => {
    const { fixture } = setup({ messagesLength: 0, total: 1234 });
    expect(fixture.componentInstance.label()).toBe('0 - 200 of 1,234');
  });

  it('interpolates from/to/total with locale-formatted numbers (da)', () => {
    const { fixture, language } = setup({ messagesLength: 0, total: 1234 });

    language.selectLanguage('da');
    TestBed.tick();

    expect(fixture.componentInstance.label()).toBe('0 - 200 af 1.234');
  });

  it('caps "to" at total when remaining is less than maxPageSize', () => {
    const { fixture } = setup({ messagesLength: 100, total: 150 });
    expect(fixture.componentInstance.label()).toBe('100 - 150 of 150');
  });

  it('invokes facade.list.loadMore on click', () => {
    const { fixture, loadMore } = setup({ messagesLength: 0, total: 200 });
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click();
    expect(loadMore).toHaveBeenCalledTimes(1);
  });
});
