import { TestBed } from '@angular/core/testing';
import { Router, type ActivatedRouteSnapshot, type RouterStateSnapshot } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import type { Message } from '../../messages/models/message.model';
import { EditorFacade } from '../services/editor-facade';
import { TemplateMessageStore } from '../services/template-message-store';
import { templateSeedGuard } from './template-seed.guard';

// The guard reads only the injected store, the facade and the Router (its `url` is the page being
// left while the guards run); pass throwaway route/state args. A UrlTree return is stubbed as a
// sentinel so we can assert the redirect target.
function run(): boolean | ReturnType<Router['parseUrl']> {
  return TestBed.runInInjectionContext(() =>
    templateSeedGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
  ) as boolean | ReturnType<Router['parseUrl']>;
}

function setup(opts: {
  hasTemplate: boolean;
  /** The facade's working message; undefined: an empty facade (a reload, or after a discard). */
  working?: Message;
  /** The facade holds something the database does not; default: the message has no `created`. */
  unsaved?: boolean;
  /** The page being left. */
  fromUrl?: string;
}) {
  const parseUrl = vi.fn((url: string) => ({ url }) as unknown as ReturnType<Router['parseUrl']>);
  const resetWorkingState = vi.fn();
  const facade = {
    message: () => opts.working,
    hasUnsavedChanges: () => opts.unsaved ?? (opts.working !== undefined && !opts.working.created),
    resetWorkingState,
  } as unknown as EditorFacade;
  TestBed.configureTestingModule({
    providers: [
      TemplateMessageStore,
      { provide: Router, useValue: { parseUrl, url: opts.fromUrl ?? '/editor' } },
      { provide: EditorFacade, useValue: facade },
    ],
  });
  const store = TestBed.inject(TemplateMessageStore);
  if (opts.hasTemplate) store.set({} as Message);
  return { parseUrl, store, resetWorkingState };
}

const DRAFT: Message = { id: 'draft-uuid', mainType: 'NW', title: 'Typed by the user' } as Message;
const STORED: Message = { id: 'm-1', created: '2026-11-01T10:00:00Z' } as unknown as Message;

describe('templateSeedGuard', () => {
  it('activates when the transient template store holds a seed', () => {
    const { parseUrl } = setup({ hasTemplate: true });
    expect(run()).toBe(true);
    expect(parseUrl).not.toHaveBeenCalled();
  });

  it('redirects to the Start screen when the store is empty (reload / deep-link)', () => {
    const { parseUrl } = setup({ hasTemplate: false });
    expect(run()).toEqual({ url: '/editor' });
    expect(parseUrl).toHaveBeenCalledWith('/editor');
  });

  it('returns to the draft on Back from the template execution page: re-seeds the store from the facade', () => {
    // "Run template..." on a new draft at /editor/edit, then Back: the edit form used the store up.
    const { parseUrl, store, resetWorkingState } = setup({
      hasTemplate: false,
      working: DRAFT,
      fromUrl: '/editor/execute-template',
    });

    expect(run()).toBe(true);
    expect(parseUrl).not.toHaveBeenCalled();
    expect(store.template()).toBe(DRAFT);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('redirects to Start from the template execution page when the facade holds a stored message', () => {
    const { parseUrl, store } = setup({
      hasTemplate: false,
      working: STORED,
      unsaved: true,
      fromUrl: '/editor/execute-template',
    });

    expect(run()).toEqual({ url: '/editor' });
    expect(parseUrl).toHaveBeenCalledWith('/editor');
    expect(store.template()).toBeUndefined();
  });

  it('redirects to Start from the template execution page after a discard (an empty facade)', () => {
    const { parseUrl, store } = setup({
      hasTemplate: false,
      fromUrl: '/editor/execute-template',
    });

    expect(run()).toEqual({ url: '/editor' });
    expect(parseUrl).toHaveBeenCalledWith('/editor');
    expect(store.template()).toBeUndefined();
  });

  it("does not re-seed another page's unstored message: it redirects and drops it, the page's guard has already asked", () => {
    // Back from a copy to an older /editor/edit entry: unsavedChangesGuard asked (a replacing URL)
    // and the user said Yes. Without the drop, the redirect would ask a second time on the way to Start.
    const { parseUrl, store, resetWorkingState } = setup({
      hasTemplate: false,
      working: DRAFT,
      fromUrl: '/editor/copy/m-9',
    });

    expect(run()).toEqual({ url: '/editor' });
    expect(parseUrl).toHaveBeenCalledWith('/editor');
    expect(store.template()).toBeUndefined();
    expect(resetWorkingState).toHaveBeenCalledTimes(1);
  });
});
