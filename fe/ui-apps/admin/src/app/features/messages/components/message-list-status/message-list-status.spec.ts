import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { MessageListStatus } from './message-list-status';
import { MessagesFacade } from '../../services/messages-facade.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import en from '../../../../../../../../languages/en/i18n/admin/en.json';

function setup(opts: { loading: boolean; error: unknown | null; indexIncomplete?: boolean }) {
  const loading = signal(opts.loading);
  const error = signal<unknown | null>(opts.error);
  const indexIncomplete = signal(opts.indexIncomplete ?? false);
  const retry = vi.fn();
  const facade = {
    list: { loading, error, retry, indexIncomplete },
  } as unknown as MessagesFacade;

  try {
    localStorage.removeItem('niord.language');
  } catch {
    // ignore
  }

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: MessagesFacade, useValue: facade },
    ],
  });
  TestBed.inject(TranslocoService).setTranslation(en, 'en');

  const fixture = TestBed.createComponent(MessageListStatus);
  fixture.detectChanges();
  return { fixture, loading, error, retry, indexIncomplete };
}

describe('MessageListStatus', () => {
  it('renders the progress bar when loading is true', () => {
    const { fixture } = setup({ loading: true, error: null });
    expect(fixture.nativeElement.querySelector('.progress')).not.toBeNull();
  });

  it('does not render the progress bar when loading is false', () => {
    const { fixture } = setup({ loading: false, error: null });
    expect(fixture.nativeElement.querySelector('.progress')).toBeNull();
  });

  it('renders the progress bar while its view has rows still to build', () => {
    const { fixture } = setup({ loading: false, error: null });
    fixture.componentRef.setInput('pending', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.progress')).not.toBeNull();

    fixture.componentRef.setInput('pending', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.progress')).toBeNull();
  });

  it('renders the error banner when error is non-null', () => {
    const { fixture } = setup({ loading: false, error: new Error('boom') });
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('does not render the error banner when error is null', () => {
    const { fixture } = setup({ loading: false, error: null });
    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
  });

  it('invokes facade.list.retry when the retry button is clicked', () => {
    const { fixture, retry } = setup({ loading: false, error: new Error('boom') });
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement | null;
    expect(button).not.toBeNull();
    button!.click();
    expect(retry).toHaveBeenCalledTimes(1);
  });

  describe('the notice of a search answered while the index is being filled', () => {
    const notice = (root: HTMLElement) =>
      root.querySelector('[data-testid="index-incomplete-notice"]') as HTMLElement | null;
    const liveRegion = (root: HTMLElement) =>
      root.querySelector('[data-testid="index-incomplete-status"]') as HTMLElement | null;

    it('shows nothing for an ordinary answer, inside a live region that is always there', () => {
      const { fixture } = setup({ loading: false, error: null });
      const root = fixture.nativeElement as HTMLElement;
      expect(notice(root)).toBeNull();
      expect(liveRegion(root)?.getAttribute('role')).toBe('status');
      expect(liveRegion(root)?.textContent?.trim()).toBe('');
    });

    it('shows the warning, in the warning style, inside the live region when the answer is marked', () => {
      const { fixture } = setup({ loading: false, error: null, indexIncomplete: true });
      const root = fixture.nativeElement as HTMLElement;
      const shown = notice(root);
      expect(shown).not.toBeNull();
      expect(shown!.classList).toContain('message-warn');
      expect(liveRegion(root)!.contains(shown)).toBe(true);
      expect(shown!.textContent?.trim()).toBe(en.messages.list.indexIncomplete);
    });

    it('goes with the next answer without the mark', () => {
      const { fixture, indexIncomplete } = setup({
        loading: false,
        error: null,
        indexIncomplete: true,
      });
      const root = fixture.nativeElement as HTMLElement;
      expect(notice(root)).not.toBeNull();

      indexIncomplete.set(false);
      fixture.detectChanges();
      expect(notice(root)).toBeNull();
      expect(liveRegion(root)).not.toBeNull();
    });
  });
});
