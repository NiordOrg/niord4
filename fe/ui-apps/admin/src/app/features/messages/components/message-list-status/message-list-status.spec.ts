import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';

import { MessageListStatus } from './message-list-status';
import { MessagesFacade } from '../../services/messages-facade.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(opts: { loading: boolean; error: unknown | null }) {
  const loading = signal(opts.loading);
  const error = signal<unknown | null>(opts.error);
  const retry = vi.fn();
  const facade = {
    list: { loading, error, retry },
  } as unknown as MessagesFacade;

  try {
    localStorage.removeItem('niord.language');
  } catch {
    // ignore
  }

  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), { provide: MessagesFacade, useValue: facade }],
  });

  const fixture = TestBed.createComponent(MessageListStatus);
  fixture.detectChanges();
  return { fixture, loading, error, retry };
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
});
