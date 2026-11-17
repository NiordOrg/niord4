import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import type { Editor } from '@tiptap/core';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UserDirectoryApiService } from '../../../../core/auth/services/users-api.service';
import { EmailAddressField } from '../../../../shared/forms/email-address-field/email-address-field';
import { RichTextEditor } from '../../../../shared/forms/rich-text-editor/rich-text-editor';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageComment } from '../../../messages/models/message-comment.model';
import { CommentEditor, type CommentDraft } from './comment-editor';

interface EditorHandle {
  saveLabel(): string;
  saveDisabled(): boolean;
  showAcknowledge(): boolean;
}

/** Wait for the lazy TipTap import + mount (a floating promise `whenStable` doesn't track). */
async function waitForEditor(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 200; i++) {
    if (fixture.nativeElement.querySelector('.ProseMirror')) {
      await fixture.whenStable();
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error('editor never mounted');
}

function rteOf(fixture: ComponentFixture<unknown>): RichTextEditor {
  return fixture.debugElement.query(By.directive(RichTextEditor))
    .componentInstance as RichTextEditor;
}

function editorOf(rte: RichTextEditor): Editor {
  const handle = (rte as unknown as { handle?: { editor: Editor } }).handle;
  if (!handle) {
    throw new Error('editor handle not mounted');
  }
  return handle.editor;
}

async function setup(
  comment: MessageComment,
  extra?: { newComment?: boolean; draft?: CommentDraft },
) {
  // Reset first so a test may build more than one editor instance (each `setup` is self-contained).
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: UserDirectoryApiService, useValue: { searchEmails: vi.fn(() => of([])) } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  // A text of its own, so a placeholder translated before it reaches the field shows in a test.
  TestBed.inject(TranslocoService).setTranslationKey(
    'editor.comments.emailPlaceholder',
    'Add e-mail address…',
    { lang: 'en' },
  );

  const fixture = TestBed.createComponent(CommentEditor);
  fixture.componentRef.setInput('comment', comment);
  if (extra?.newComment !== undefined) {
    fixture.componentRef.setInput('newComment', extra.newComment);
  }
  if (extra?.draft) {
    fixture.componentRef.setInput('draft', extra.draft);
  }
  const drafts: CommentDraft[] = [];
  fixture.componentInstance.draftChange.subscribe((draft) => drafts.push(draft));
  fixture.autoDetectChanges();
  await fixture.whenStable();
  await waitForEditor(fixture);
  const handle = fixture.componentInstance as unknown as EditorHandle;
  const rte = () => rteOf(fixture);
  const rebind = async (next: MessageComment) => {
    fixture.componentRef.setInput('comment', next);
    await fixture.whenStable();
  };
  return { fixture, handle, rte, rebind, drafts };
}

interface FormInternals {
  form: CommentEditor['form'];
  formModel(): { comment: string; emailAddresses: readonly string[] };
}

describe('CommentEditor', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('labels Save when there are no recipients and Save-and-Send when there are', async () => {
    const noEmails = await setup({ comment: 'x' });
    expect(noEmails.handle.saveLabel()).toBe('editor.comments.save');

    const withEmails = await setup({ comment: 'x', emailAddresses: ['a@b.c'] });
    expect(withEmails.handle.saveLabel()).toBe('editor.comments.saveAndSend');
  });

  it('reseeds the Save label when a different comment is bound (no stale Save-and-Send)', async () => {
    const { handle, rebind } = await setup({ id: 'a', comment: 'x', emailAddresses: ['a@b.c'] });
    expect(handle.saveLabel()).toBe('editor.comments.saveAndSend');

    // Reselect a recipient-less comment in the SAME component instance (repopulation uses
    // emitEvent:false, which previously left the label stuck on saveAndSend).
    await rebind({ comment: '' });
    expect(handle.saveLabel()).toBe('editor.comments.save');
  });

  it('disables Save while pristine and shows Acknowledge only for a saved, un-acked comment', async () => {
    const { handle } = await setup({ id: 'a', comment: 'persisted body' });
    expect(handle.saveDisabled()).toBe(true);
    expect(handle.showAcknowledge()).toBe(true);

    const acked = await setup({ id: 'a', comment: 'x', acknowledgeDate: '2026-01-01' });
    expect(acked.handle.showAcknowledge()).toBe(false);

    const fresh = await setup({ comment: 'x' });
    expect(fresh.handle.showAcknowledge()).toBe(false);
  });

  it('emits the edited body + recipients on Save once a change exists', async () => {
    const { fixture, handle } = await setup({ id: 'a', comment: 'orig', emailAddresses: [] });
    const comp = fixture.componentInstance;
    const internals = comp as unknown as { form: CommentEditor['form']; onSave: () => void };
    let emitted: MessageComment | undefined;
    comp.save.subscribe((c) => (emitted = c));

    expect(handle.saveDisabled()).toBe(true); // pristine after (re)population
    internals.form.comment().value.set('edited body');
    expect(handle.saveDisabled()).toBe(false);

    internals.onSave();
    expect(emitted).toMatchObject({ id: 'a', comment: 'edited body', emailAddresses: [] });
  });

  it('emits the rich HTML body on Save after a genuine content edit', async () => {
    const { fixture, handle, rte } = await setup({ id: 'a', comment: '', emailAddresses: [] });
    let emitted: MessageComment | undefined;
    fixture.componentInstance.save.subscribe((c) => (emitted = c));

    expect(handle.saveDisabled()).toBe(true); // empty body — required blocks Save

    rte().insertContentAtCursor('Reef sighted');
    await fixture.whenStable();

    expect(handle.saveDisabled()).toBe(false);
    (fixture.componentInstance as unknown as { onSave(): void }).onSave();
    expect(emitted?.comment).toContain('Reef sighted');
    expect(emitted?.comment).toContain('<p>'); // rich HTML, not a bare string
  });

  it('blocks Save when the editor is emptied (required sees the normalized empty document)', async () => {
    const { fixture, handle, rte } = await setup({ id: 'a', comment: '<p>orig body</p>' });
    expect(handle.saveDisabled()).toBe(true); // pristine

    editorOf(rte()).commands.clearContent(true);
    await fixture.whenStable();

    // The body now differs from the baseline (changed) but is empty, so `required` keeps Save off.
    expect(handle.saveDisabled()).toBe(true);
  });

  it('passes the e-mail field its placeholder as a key, which the field translates itself', async () => {
    const { fixture } = await setup({ comment: 'x' });
    const field = fixture.debugElement.query(By.directive(EmailAddressField))
      .componentInstance as EmailAddressField;
    // A translated text here would be looked up again as a key: a missing translation logged.
    expect(field.placeholder()).toBe('editor.comments.emailPlaceholder');
  });

  it('fills a new comment from a kept draft, and the draft can be posted at once', async () => {
    const { fixture, handle } = await setup(
      { comment: '' },
      { newComment: true, draft: { comment: '<p>half typed</p>', emailAddresses: ['a@b.c'] } },
    );
    const internals = fixture.componentInstance as unknown as FormInternals;

    expect(internals.formModel()).toEqual({
      comment: '<p>half typed</p>',
      emailAddresses: ['a@b.c'],
    });
    // The baseline is the bound (empty) comment, so the restored draft is a change.
    expect(handle.saveDisabled()).toBe(false);
    expect(handle.saveLabel()).toBe('editor.comments.sendComment');
  });

  it('reports the typed text and recipients of a new comment as its draft', async () => {
    const { fixture, drafts } = await setup({ comment: '' }, { newComment: true });
    const internals = fixture.componentInstance as unknown as FormInternals;

    internals.form.comment().value.set('<p>typed</p>');
    await fixture.whenStable();

    expect(drafts.at(-1)).toEqual({ comment: '<p>typed</p>', emailAddresses: [] });
  });

  it('does not report the edit of an existing comment as a draft', async () => {
    const { fixture, drafts } = await setup({ id: 'a', comment: 'orig' });
    const internals = fixture.componentInstance as unknown as FormInternals;

    internals.form.comment().value.set('edited');
    await fixture.whenStable();

    expect(drafts).toEqual([]);
  });

  it('fills an existing comment from its own values, never from a draft', async () => {
    const { fixture, handle } = await setup(
      { id: 'a', comment: 'stored body' },
      { newComment: false, draft: { comment: 'stray', emailAddresses: [] } },
    );
    const internals = fixture.componentInstance as unknown as FormInternals;

    expect(internals.formModel().comment).toBe('stored body');
    expect(handle.saveDisabled()).toBe(true);
  });

  it('keeps Save disabled after populating a bare-text body TipTap re-wraps (baseline reads the field value)', async () => {
    const { handle, rte } = await setup({ id: 'a', comment: 'legacy plain body' });

    // TipTap wraps the bare text in <p> on render, but the field keeps the raw stored value, so the
    // baseline comparison sees no change.
    expect(rte().value()).toBe('legacy plain body');
    expect(handle.saveDisabled()).toBe(true);
  });
});
