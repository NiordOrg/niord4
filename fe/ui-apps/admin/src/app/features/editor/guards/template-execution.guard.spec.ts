import { TestBed } from '@angular/core/testing';
import type { RouterStateSnapshot } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { DiscardConfirmService } from '../../../shared/services/discard-confirm.service';
import { EditorFacade } from '../services/editor-facade';
import { templateExecutionGuard } from './template-execution.guard';

// The guard reads the view's `isDirty()` and `nextState.url`; pass throwaways for the rest and
// drive the facade flags and the discard-confirm stub.
function run(nextUrl: string, viewDirty: boolean): boolean | Promise<boolean> {
  return TestBed.runInInjectionContext(() =>
    templateExecutionGuard(
      { isDirty: () => viewDirty },
      undefined as never,
      undefined as never,
      { url: nextUrl } as RouterStateSnapshot,
    ),
  ) as boolean | Promise<boolean>;
}

function setup(opts: {
  bypass?: boolean;
  /** The facade holds something the database does not (`hasUnsavedChanges`). */
  unsaved?: boolean;
  /** The facade's message is stored (`created` set); default true. False: a new, copy or template message. */
  stored?: boolean;
  confirmResult?: boolean;
}) {
  const resetWorkingState = vi.fn();
  const facade = {
    consumeGuardBypass: vi.fn(() => opts.bypass ?? false),
    resetWorkingState,
    hasUnsavedChanges: () => opts.unsaved ?? false,
    message: () => ({
      id: 'm-1',
      created: (opts.stored ?? true) ? '2026-11-01T10:00:00Z' : undefined,
    }),
  } as unknown as EditorFacade;
  const confirmDiscard = vi.fn(() => Promise.resolve(opts.confirmResult ?? true));
  TestBed.configureTestingModule({
    providers: [
      { provide: EditorFacade, useValue: facade },
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
    ],
  });
  return { confirmDiscard, resetWorkingState };
}

describe('templateExecutionGuard (the Start screen)', () => {
  it('asks before the Start screen while the editor holds a message the database does not, and drops it on Yes', async () => {
    // Execute made a new message, Back returned here, and the view (its seed used up) goes to Start.
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: true, stored: false });

    await expect(run('/editor', false)).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).toHaveBeenCalledTimes(1);
  });

  it('keeps the unstored message on No', async () => {
    const { confirmDiscard, resetWorkingState } = setup({
      unsaved: true,
      stored: false,
      confirmResult: false,
    });

    await expect(run('/editor?domain=d-1', false)).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('asks once, not twice, when the view has its own changes as well', async () => {
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: true, stored: false });

    await expect(run('/editor', true)).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).toHaveBeenCalledTimes(1);
  });

  it('lets a stored message with edits go to Start silently while the view is unchanged', () => {
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: true, stored: true });

    expect(run('/editor', false)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it("still asks for the view's own changes on the way to Start with a stored message", async () => {
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: true, stored: true });

    await expect(run('/editor', true)).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('passes an allow-listed navigation (Execute, Cancel) without asking', () => {
    const { confirmDiscard, resetWorkingState } = setup({
      bypass: true,
      unsaved: true,
      stored: false,
    });

    expect(run('/editor', true)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
    expect(resetWorkingState).not.toHaveBeenCalled();
  });
});

describe('templateExecutionGuard (a route that replaces the working copy)', () => {
  // "Run template…" on a copy records /editor/copy/m-9 as the return URL; Back goes there, and
  // the copy route fetches a fresh copy over the working copy.
  it('asks before Back to the copy route while the editor holds an unstored copy, and keeps it on No', async () => {
    const { confirmDiscard, resetWorkingState } = setup({
      unsaved: true,
      stored: false,
      confirmResult: false,
    });

    await expect(run('/editor/copy/m-9', false)).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('lets the copy route replace the working copy on Yes, without dropping the state itself', async () => {
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: true, stored: false });

    await expect(run('/editor/copy/m-9', false)).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });

  it('asks once for the copy route, not twice, when the view has its own changes as well', async () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: false, confirmResult: false });

    await expect(run('/editor/copy/m-9', true)).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
  });

  it('asks before the Edit tab of another message while the working copy has unsaved edits', async () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: true, confirmResult: false });

    await expect(run('/editor/edit/m-2', false)).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
  });

  it('lets the Edit tab of the same message pass: its load dedupes onto the working copy', () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: true });

    expect(run('/editor/edit/m-1', false)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('lets the bare /editor/edit pass, with or without a query: the seed guard re-seeds the draft', () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: false });

    expect(run('/editor/edit', false)).toBe(true);
    expect(run('/editor/edit?domain=d-1', false)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('lets the copy route pass without a prompt when nothing is unsaved', () => {
    const { confirmDiscard } = setup({ unsaved: false });

    expect(run('/editor/copy/m-9', false)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });
});

describe('templateExecutionGuard (other targets)', () => {
  it('lets Back to the draft it was opened from pass while the view is unchanged', () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: false });

    expect(run('/editor/edit', false)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('defers to the shell guard when leaving the editor with unsaved work in the facade', () => {
    const { confirmDiscard } = setup({ unsaved: true, stored: false });

    expect(run('/messages/table', true)).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it("asks for the view's own changes when leaving the editor with a clean facade", async () => {
    const { confirmDiscard, resetWorkingState } = setup({ unsaved: false });

    await expect(run('/messages/table', true)).resolves.toBe(true);
    expect(confirmDiscard).toHaveBeenCalledTimes(1);
    expect(resetWorkingState).not.toHaveBeenCalled();
  });
});
