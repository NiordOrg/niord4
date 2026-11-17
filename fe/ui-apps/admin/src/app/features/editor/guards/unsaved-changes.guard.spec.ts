import { TestBed } from '@angular/core/testing';
import type { RouterStateSnapshot } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { DiscardConfirmService } from '../../../shared/services/discard-confirm.service';
import { EditorCommentsStore } from '../services/editor-comments.store';
import { EditorFacade } from '../services/editor-facade';
import { editorExitGuard, unsavedChangesGuard } from './unsaved-changes.guard';

// The guards only read `nextState.url` from their (component, route, state, nextState) args, so
// pass throwaways plus a URL stub and drive the facade flags + the discard-confirm stub.
function runChild(nextUrl: string): boolean | Promise<boolean> {
  return TestBed.runInInjectionContext(() =>
    unsavedChangesGuard(
      undefined,
      undefined as never,
      undefined as never,
      {
        url: nextUrl,
      } as RouterStateSnapshot,
    ),
  ) as boolean | Promise<boolean>;
}

function runExit(): boolean | Promise<boolean> {
  return TestBed.runInInjectionContext(() =>
    editorExitGuard(
      undefined,
      undefined as never,
      undefined as never,
      {
        url: '/messages/table',
      } as RouterStateSnapshot,
    ),
  ) as boolean | Promise<boolean>;
}

function setup(opts: {
  bypass?: boolean;
  touched?: boolean;
  messageId?: string;
  /** The message is stored (`created` set); default true. False: a copy, a new or a template message. */
  stored?: boolean;
  confirmResult?: boolean;
  /** A half-typed comment is held on the Comments tab. */
  commentDraft?: boolean;
}) {
  const consumeGuardBypass = vi.fn(() => opts.bypass ?? false);
  const resetWorkingState = vi.fn();
  const facade = {
    consumeGuardBypass,
    resetWorkingState,
    hasUnsavedChanges: () => opts.touched ?? false,
    message: () => ({
      id: opts.messageId ?? 'm-1',
      created: (opts.stored ?? true) ? '2026-11-01T10:00:00Z' : undefined,
    }),
  } as unknown as EditorFacade;
  const confirmDiscard = vi.fn(() => Promise.resolve(opts.confirmResult ?? true));

  TestBed.configureTestingModule({
    providers: [
      { provide: EditorFacade, useValue: facade },
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
      {
        provide: EditorCommentsStore,
        useValue: { hasComposeDraft: () => opts.commentDraft ?? false },
      },
    ],
  });
  return { consumeGuardBypass, confirmDiscard, resetWorkingState };
}

describe('unsavedChangesGuard (editor child routes)', () => {
  it('allows navigation and consumes the bypass when a programmatic bypass is armed (no prompt)', () => {
    const { consumeGuardBypass, confirmDiscard } = setup({ bypass: true, touched: true });

    expect(runChild('/editor/edit')).toBe(true);
    expect(consumeGuardBypass).toHaveBeenCalled();
    // Bypass short-circuits before userTouched / the prompt.
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('allows navigation without prompting when there are no user edits', () => {
    const { confirmDiscard } = setup({ bypass: false, touched: false });

    expect(runChild('/editor/edit/other-id')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('switches to the status / comments / history tabs silently — the working copy survives', () => {
    const { confirmDiscard } = setup({ bypass: false, touched: true });

    expect(runChild('/editor/status/m-1')).toBe(true);
    expect(runChild('/editor/comments/m-1')).toBe(true);
    expect(runChild('/editor/history/m-1')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('goes to the Start screen silently with edits on a stored message — opening it again keeps them', () => {
    const { confirmDiscard, resetWorkingState } = setup({ bypass: false, touched: true });

    expect(runChild('/editor')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('asks before the Start screen while the message is not stored, and drops it on Yes', async () => {
    const { confirmDiscard, resetWorkingState } = setup({
      bypass: false,
      touched: true,
      messageId: '',
      stored: false,
      confirmResult: true,
    });

    await expect(runChild('/editor')).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    // Dropped, so the shell's leave guard and a later Forward do not ask again for work that is gone.
    expect(resetWorkingState).toHaveBeenCalledTimes(1);
  });

  it('keeps the unstored message on No at the Start-screen prompt (the browser Back is cancelled)', async () => {
    const { confirmDiscard, resetWorkingState } = setup({
      bypass: false,
      touched: true,
      stored: false,
      confirmResult: false,
    });

    await expect(runChild('/editor')).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('takes the bare /editor with a query or a fragment as the Start screen too', async () => {
    const { confirmDiscard } = setup({ bypass: false, touched: true, stored: false });

    await expect(runChild('/editor?domain=d-1')).resolves.toBe(true);
    await expect(runChild('/editor/#top')).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(2);
  });

  it('lets an unstored message go to Start silently when nothing is unsaved', () => {
    const { confirmDiscard } = setup({ bypass: false, touched: false, stored: false });

    expect(runChild('/editor')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('returns to the Edit tab of the SAME message silently — the same-id load keeps the copy', () => {
    const { confirmDiscard } = setup({ bypass: false, touched: true, messageId: 'm-1' });

    expect(runChild('/editor/edit/m-1')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('leaves editor exits to the parent guard (no double prompt)', () => {
    const { confirmDiscard } = setup({ bypass: false, touched: true });

    expect(runChild('/messages/table')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('prompts before a navigation that REPLACES the working copy and allows on confirm', async () => {
    const { confirmDiscard } = setup({ bypass: false, touched: true, confirmResult: true });

    await expect(runChild('/editor/edit/other-id')).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalled();
  });

  it('blocks a replacing navigation when the user rejects the prompt', async () => {
    setup({ bypass: false, touched: true, confirmResult: false });

    await expect(runChild('/editor/copy/other-id')).resolves.toBe(false);
  });
});

describe('unsavedChangesGuard with a half-typed comment (no message edits)', () => {
  it('asks before a navigation that replaces the working copy, which drops the draft', async () => {
    const { confirmDiscard } = setup({ touched: false, commentDraft: true, confirmResult: false });

    await expect(runChild('/editor/edit/other-id')).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
  });

  it('switches tabs, goes to Start and back to the same Edit tab silently: the draft is kept', () => {
    const { confirmDiscard } = setup({ touched: false, commentDraft: true });

    expect(runChild('/editor/edit/m-1')).toBe(true);
    expect(runChild('/editor/status/m-1')).toBe(true);
    expect(runChild('/editor')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('leaves an editor exit to the parent guard (no double prompt)', () => {
    const { confirmDiscard } = setup({ touched: false, commentDraft: true });

    expect(runChild('/messages/table')).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });
});

describe('editorExitGuard (editor parent route)', () => {
  it('allows leaving without prompting when there are no user edits', () => {
    const { confirmDiscard } = setup({ touched: false });

    expect(runExit()).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('prompts on exit with user edits and allows when confirmed', async () => {
    const { confirmDiscard } = setup({ touched: true, confirmResult: true });

    await expect(runExit()).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalled();
  });

  it('blocks the exit when the user rejects the prompt', async () => {
    setup({ touched: true, confirmResult: false });

    await expect(runExit()).resolves.toBe(false);
  });

  it('asks before leaving with a half-typed comment and no message edits', async () => {
    const { confirmDiscard } = setup({ touched: false, commentDraft: true, confirmResult: false });

    await expect(runExit()).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
  });
});
