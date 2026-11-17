import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import type { Message } from '../../messages/models/message.model';
import { EditorCommentsStore } from './editor-comments.store';
import { EditorFacade } from './editor-facade';

function stored(id: string): Message {
  return {
    id,
    status: 'DRAFT',
    mainType: 'NW',
    type: 'COASTAL_WARNING',
    messageSeries: { seriesId: 's' },
    parts: [],
    created: '2026-11-01T10:00:00Z',
  };
}

function setup() {
  const message = signal<Message | undefined>(stored('m-1'));
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: EditorFacade, useValue: { message, loadSeq: signal(0) } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      EditorCommentsStore,
    ],
  });
  return { message, store: TestBed.inject(EditorCommentsStore) };
}

describe('EditorCommentsStore compose draft', () => {
  it('keeps the typed text and recipients beyond the comments page', () => {
    const { store } = setup();
    expect(store.hasComposeDraft()).toBe(false);

    store.setComposeDraft({ comment: '<p>half typed</p>', emailAddresses: ['a@b.c'] });

    expect(store.hasComposeDraft()).toBe(true);
    expect(store.composeDraft()).toEqual({
      comment: '<p>half typed</p>',
      emailAddresses: ['a@b.c'],
    });
  });

  it('holds no draft for an empty text without recipients', () => {
    const { store } = setup();
    store.setComposeDraft({ comment: 'x', emailAddresses: [] });

    store.setComposeDraft({ comment: '', emailAddresses: [] });

    expect(store.hasComposeDraft()).toBe(false);
    expect(store.composeDraft()).toBeUndefined();
  });

  it('counts recipients alone as a draft', () => {
    const { store } = setup();
    store.setComposeDraft({ comment: '', emailAddresses: ['a@b.c'] });
    expect(store.hasComposeDraft()).toBe(true);
  });

  it('drops the draft when another message takes the place of the one it was typed against', () => {
    const { store, message } = setup();
    store.setComposeDraft({ comment: 'for m-1', emailAddresses: [] });

    message.set(stored('m-2'));

    expect(store.hasComposeDraft()).toBe(false);
  });

  it('drops the draft when the working state is dropped (no message)', () => {
    const { store, message } = setup();
    store.setComposeDraft({ comment: 'for m-1', emailAddresses: [] });

    message.set(undefined);

    expect(store.hasComposeDraft()).toBe(false);
  });

  it('keeps the draft over a reload of the same message (a new object, the same id)', () => {
    const { store, message } = setup();
    store.setComposeDraft({ comment: 'for m-1', emailAddresses: [] });

    message.set({ ...stored('m-1'), status: 'VERIFIED' });

    expect(store.composeDraft()?.comment).toBe('for m-1');
  });

  it('clears the draft on request (its post succeeded, or the editor was left)', () => {
    const { store } = setup();
    store.setComposeDraft({ comment: 'x', emailAddresses: [] });

    store.clearComposeDraft();

    expect(store.hasComposeDraft()).toBe(false);
  });
});
