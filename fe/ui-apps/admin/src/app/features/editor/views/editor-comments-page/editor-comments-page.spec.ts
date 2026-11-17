import { signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { DomainService } from '../../../../core/domain/services/domain.service';
import type { MessageComment } from '../../../messages/models/message-comment.model';
import type { Message } from '../../../messages/models/message.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { CommentEditor } from '../../components/comment-editor/comment-editor';
import { TemplateMessageStore } from '../../services/template-message-store';
import { EditorCommentsStore } from '../../services/editor-comments.store';
import { EditorFacade } from '../../services/editor-facade';
import { EditorCommentsPage } from './editor-comments-page';

interface CommentsHandle {
  save(comment: MessageComment, options?: { resetCompose?: boolean }): void;
  saveCompose(comment: MessageComment): void;
  acknowledge(comment: MessageComment): void;
  canEdit(comment: MessageComment): boolean;
  composeSeed(): MessageComment;
}

const message: Message = {
  id: 'm-1',
  status: 'DRAFT',
  mainType: 'NW',
  type: 'COASTAL_WARNING',
  messageSeries: { seriesId: 's' },
  parts: [{ type: 'DETAILS', indexNo: 0 }],
  created: 'x',
};

/**
 * Drain the effect scheduler + microtask queue so the comments httpResource resolves (zoneless).
 * Several rounds are needed: the resource's params depend on the message that the load effect sets
 * in the same change-detection pass, so the post-load refetch is one extra signal hop away.
 */
async function settle(): Promise<void> {
  for (let round = 0; round < 5; round++) {
    TestBed.tick();
    await Promise.resolve();
  }
}

async function setup(opts?: { admin?: boolean; comments?: MessageComment[]; loadError?: number }) {
  const api = {
    editableDetails: vi.fn(() =>
      opts?.loadError
        ? throwError(() => new HttpErrorResponse({ status: opts.loadError }))
        : of(message),
    ),
    createComment: vi.fn((_id: string, comment: MessageComment) => of(comment)),
    updateComment: vi.fn((_id: string, comment: MessageComment) => of(comment)),
    acknowledgeComment: vi.fn(() => of({ id: 'c', comment: '' } as MessageComment)),
  };
  const notify = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [{ seriesId: 's', mainType: 'NW' }] }),
    supportsMainType: () => signal(true),
  };
  const route = {
    snapshot: { paramMap: convertToParamMap({ id: 'm-1' }) },
    paramMap: of(convertToParamMap({ id: 'm-1' })),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideFakeAuthFacade({ isAuthenticated: true, editor: true, admin: opts?.admin ?? false }),
      EditorFacade,
      EditorCommentsStore,
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      { provide: NotificationService, useValue: notify },
      {
        provide: TemplateMessageStore,
        useValue: { consume: vi.fn(), set: vi.fn(), clear: vi.fn() },
      },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), activeLocale: signal('en-GB') },
      },
      { provide: ActivatedRoute, useValue: route },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(EditorCommentsPage);
  fixture.detectChanges();
  await settle();
  const httpMock = TestBed.inject(HttpTestingController);
  // The comments httpResource fires its GET once the message resolves (created draft). Flush it
  // with the scenario's comment list so the thread renders one card per comment.
  for (const req of httpMock.match(
    (r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments',
  )) {
    req.flush(opts?.comments ?? []);
  }
  await settle();
  const view = fixture.componentInstance as unknown as CommentsHandle;
  return {
    fixture,
    view,
    api,
    facade: TestBed.inject(EditorFacade),
    store: TestBed.inject(EditorCommentsStore),
    httpMock,
  };
}

describe('EditorCommentsPage edit-rights matrix', () => {
  it('a new comment (no id) is editable', async () => {
    const { view } = await setup();
    expect(view.canEdit({ comment: '' })).toBe(true);
  });

  it("the user's own comment is editable", async () => {
    const { view } = await setup();
    expect(view.canEdit({ id: 'c1', comment: 'mine', ownComment: true })).toBe(true);
  });

  it("another user's comment is NOT editable for a non-admin", async () => {
    const { view } = await setup();
    expect(view.canEdit({ id: 'c2', comment: 'theirs', ownComment: false })).toBe(false);
  });

  it("an admin can edit another user's comment", async () => {
    const { view } = await setup({ admin: true });
    expect(view.canEdit({ id: 'c2', comment: 'theirs', ownComment: false })).toBe(true);
  });
});

describe('EditorCommentsPage comment count (drives the tab badge)', () => {
  it('counts EVERY comment, not just the unacknowledged ones', async () => {
    const { store } = await setup({
      comments: [
        { id: 'a', comment: '', acknowledgeDate: undefined },
        { id: 'b', comment: '', acknowledgeDate: '2026-01-01' },
        { id: 'c', comment: '' },
      ],
    });
    expect(store.count()).toBe(3);
  });

  it('is 0 for an empty thread', async () => {
    const { store } = await setup({ comments: [] });
    expect(store.count()).toBe(0);
  });
});

describe('EditorCommentsPage save / acknowledge routing', () => {
  it('routes a new comment through createComment with the active language', async () => {
    const { view, api } = await setup();
    view.save({ comment: 'hello' });
    expect(api.createComment).toHaveBeenCalledWith('m-1', { comment: 'hello' }, { language: 'en' });
    expect(api.updateComment).not.toHaveBeenCalled();
  });

  it('routes an existing comment through updateComment', async () => {
    const { view, api } = await setup();
    view.save({ id: 'c1', comment: 'edited' });
    expect(api.updateComment).toHaveBeenCalledWith(
      'm-1',
      { id: 'c1', comment: 'edited' },
      { language: 'en' },
    );
    expect(api.createComment).not.toHaveBeenCalled();
  });

  it('acknowledges a comment by id', async () => {
    const { view, api } = await setup();
    view.acknowledge({ id: 'c1', comment: 'x' });
    expect(api.acknowledgeComment).toHaveBeenCalledWith('m-1', 'c1');
  });

  it('ignores an acknowledge for an id-less comment', async () => {
    const { view, api } = await setup();
    view.acknowledge({ comment: 'x' });
    expect(api.acknowledgeComment).not.toHaveBeenCalled();
  });
});

describe('EditorCommentsPage comment rendering', () => {
  it("renders another user's comment body as sanitized HTML (bold kept, script dropped)", async () => {
    const { fixture } = await setup({
      comments: [
        {
          id: 'c2',
          comment: '<b>bold note</b><script>alert(1)</script>',
          user: 'Someone Else',
          created: '2026-01-01',
          ownComment: false,
        },
      ],
    });

    const html = fixture.nativeElement.innerHTML as string;
    expect(html).toContain('<b>bold note</b>');
    expect(html).not.toContain('<script');
  });

  it('renders a plain-text comment verbatim (plain text is valid HTML)', async () => {
    const { fixture } = await setup({
      comments: [{ id: 'c3', comment: 'plain legacy note', user: 'Someone Else', created: 'x' }],
    });
    expect(fixture.nativeElement.textContent).toContain('plain legacy note');
  });
});

describe('EditorCommentsPage compose-draft survival', () => {
  it('a reply/edit save from a card reloads the thread WITHOUT wiping the compose draft', async () => {
    const { view, fixture, httpMock } = await setup({
      comments: [{ id: 'a', comment: 'x', user: 'U', created: 't' }],
    });
    const draftBefore = view.composeSeed();

    // A reply emitted by a card (id-less ⇒ create), NOT the compose card.
    view.save({ comment: 'reply text' });
    await settle();
    httpMock
      .match((r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments')
      .forEach((req) => req.flush([{ id: 'a', comment: 'x', user: 'U', created: 't' }]));
    await settle();

    // Same seed reference ⇒ the compose editor was not repopulated (draft preserved).
    expect(view.composeSeed()).toBe(draftBefore);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('posting from the compose card clears its draft', async () => {
    const { view, httpMock } = await setup({ comments: [] });
    const draftBefore = view.composeSeed();

    view.saveCompose({ comment: 'new comment' });
    await settle();
    httpMock
      .match((r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments')
      .forEach((req) => req.flush([]));
    await settle();

    // A FRESH seed object ⇒ the compose editor repopulates blank.
    expect(view.composeSeed()).not.toBe(draftBefore);
    expect(view.composeSeed()).toEqual({ comment: '' });
  });
});

describe('EditorCommentsPage compose draft kept on the store', () => {
  interface ComposeInternals {
    form: CommentEditor['form'];
    formModel(): { comment: string; emailAddresses: readonly string[] };
  }

  /** The bottom "Add comment" editor (the only one: the scenarios' cards are not expanded). */
  function composeEditor(fixture: ComponentFixture<unknown>): ComposeInternals {
    const editors = fixture.debugElement.queryAll(By.directive(CommentEditor));
    return editors[editors.length - 1].componentInstance as ComposeInternals;
  }

  it('keeps a half-typed comment over a tab switch: a new page is filled from the store', async () => {
    const { fixture, store } = await setup({ comments: [] });
    composeEditor(fixture).form.comment().value.set('<p>half typed</p>');
    await settle();
    expect(store.composeDraft()?.comment).toBe('<p>half typed</p>');

    // A switch to another tab destroys the page; the route's store stays.
    fixture.destroy();
    const again = TestBed.createComponent(EditorCommentsPage);
    again.detectChanges();
    await settle();

    expect(composeEditor(again).formModel().comment).toBe('<p>half typed</p>');
    expect(store.hasComposeDraft()).toBe(true);
  });

  it('a reply saved from a card keeps the kept draft', async () => {
    const { view, store, httpMock } = await setup({ comments: [] });
    store.setComposeDraft({ comment: 'draft', emailAddresses: [] });

    view.save({ comment: 'reply text' });
    await settle();
    httpMock
      .match((r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments')
      .forEach((req) => req.flush([]));
    await settle();

    expect(store.composeDraft()?.comment).toBe('draft');
  });

  it("the compose card's own post clears the kept draft", async () => {
    const { view, store, httpMock } = await setup({ comments: [] });
    store.setComposeDraft({ comment: 'new comment', emailAddresses: [] });

    view.saveCompose({ comment: 'new comment' });
    await settle();
    httpMock
      .match((r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments')
      .forEach((req) => req.flush([]));
    await settle();

    expect(store.hasComposeDraft()).toBe(false);
  });

  it('a failed post keeps the kept draft', async () => {
    const { view, store, api } = await setup({ comments: [] });
    store.setComposeDraft({ comment: 'new comment', emailAddresses: [] });
    api.createComment.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));

    view.saveCompose({ comment: 'new comment' });
    await settle();

    expect(store.composeDraft()?.comment).toBe('new comment');
  });
});

describe('EditorCommentsPage reload', () => {
  it('refetches the comment thread when the message is reloaded (same id)', async () => {
    const { fixture, facade, httpMock } = await setup({
      comments: [{ id: 'a', comment: 'first', user: 'X', created: 'x' }],
    });
    expect(fixture.nativeElement.textContent).toContain('first');

    // "Reload message" resolves the SAME id — the comments httpResource must still re-fire (it keys
    // on the facade's loadSeq, which bumps on every backend (re)load).
    facade.reload();
    await settle();
    const reqs = httpMock.match(
      (r) => r.method === 'GET' && r.url === '/rest/messages/message/m-1/comments',
    );
    expect(reqs.length).toBeGreaterThan(0);
    reqs.forEach((req) => req.flush([{ id: 'a', comment: 'updated', user: 'X', created: 'x' }]));
    await settle();
    expect(fixture.nativeElement.textContent).toContain('updated');
  });
});

describe('EditorCommentsPage load error panel', () => {
  function query(fixture: { nativeElement: unknown }, testId: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      `[data-testid="${testId}"]`,
    );
  }

  it('a 403 names the cause and offers the way to Start, not Retry', async () => {
    const { fixture } = await setup({ loadError: 403 });
    fixture.detectChanges();

    expect(query(fixture, 'editor-load-error')?.textContent).toContain(
      'editor.load.error.otherDomain',
    );
    expect(query(fixture, 'editor-load-retry')).toBeNull();
    expect(query(fixture, 'editor-load-to-start')).not.toBeNull();
  });

  it('another load failure keeps Retry', async () => {
    const { fixture } = await setup({ loadError: 500 });
    fixture.detectChanges();

    expect(query(fixture, 'editor-load-error')?.textContent).toContain('editor.load.errorPanel');
    expect(query(fixture, 'editor-load-retry')).not.toBeNull();
    expect(query(fixture, 'editor-load-to-start')).toBeNull();
  });
});
