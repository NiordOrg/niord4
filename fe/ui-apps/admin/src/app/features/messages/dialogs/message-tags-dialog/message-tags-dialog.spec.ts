import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { LastTagSelectionService } from '../../services/last-tag-selection.service';
import { TagsApiService } from '../../services/api/tags-api.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { MessageTagsDialog } from './message-tags-dialog';
import { MessageTagsDialogService } from './message-tags-dialog.service';
import type { MessageTag } from '../../../../shared/models/message-tag.model';

function mkTag(opts: Partial<MessageTag> = {}): MessageTag {
  return {
    tagId: 't1',
    name: 'Tag 1',
    type: 'PRIVATE',
    messageCount: 0,
    locked: false,
    ...opts,
  };
}

interface SetupOptions {
  readonly isLoggedIn?: boolean;
  readonly admin?: boolean;
  readonly sysadmin?: boolean;
  readonly tags?: MessageTag[];
  readonly searchError?: boolean;
  readonly lastSelection?: MessageTag;
}

function setup(opts: SetupOptions = {}) {
  const getTagsByIds = vi.fn(() => of([] as MessageTag[]));
  const createMessageTag = vi.fn(() => of(mkTag()));
  const updateMessageTag = vi.fn(() => of(mkTag()));
  const deleteMessageTag = vi.fn(() => of(undefined));
  const clearMessageTagMessages = vi.fn(() => of(undefined));
  const unlockMessageTag = vi.fn(() => of(mkTag()));
  // searchTags now runs through the dialog's httpResource → HttpClient, so it is exercised via
  // HttpTestingController (not this mock). Only getTagsByIds + the mutations stay on the api.
  const api = {
    getTagsByIds,
    createMessageTag,
    updateMessageTag,
    deleteMessageTag,
    clearMessageTagMessages,
    unlockMessageTag,
  } as unknown as TagsApiService;

  const saveLastSelection = vi.fn();
  const clearLastSelection = vi.fn();
  const lastTag = {
    saveLastSelection,
    getLastSelection: () => () => opts.lastSelection,
    clearLastSelection,
  } as unknown as LastTagSelectionService;

  const error = vi.fn();
  const notifications = { error } as unknown as NotificationService;

  const confirmAccept = vi.fn();

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideFakeAuthFacade({
        isAuthenticated: opts.isLoggedIn ?? true,
        editor: opts.isLoggedIn ?? true,
        admin: opts.admin ?? false,
        sysadmin: opts.sysadmin ?? false,
      }),
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          documentationUrl: 'https://docs.example',
          keycloak: { url: '/auth', realm: 'niord', clientId: 'niord-web' },
        },
      },
      { provide: TagsApiService, useValue: api },
      { provide: LastTagSelectionService, useValue: lastTag },
      { provide: NotificationService, useValue: notifications },
    ],
  });

  const dialogService = TestBed.inject(MessageTagsDialogService);
  const fixture = TestBed.createComponent(MessageTagsDialog);
  // The component declares `providers: [ConfirmService]` to scope its confirm to
  // its own <app-confirm> mount. Grab the resolved instance and auto-accept both
  // intents so `deleteTag`/`unlockTag` resolve synchronously without rendering the
  // overlay (the request-bus is driven directly).
  const confirmService = fixture.debugElement.injector.get(ConfirmService);
  vi.spyOn(confirmService, 'confirmDestructive').mockImplementation((opts) => {
    confirmAccept();
    opts.accept?.();
  });
  vi.spyOn(confirmService, 'confirmNeutral').mockImplementation((opts) => {
    confirmAccept();
    opts.accept?.();
  });
  fixture.detectChanges();
  const httpMock = TestBed.inject(HttpTestingController);
  return {
    fixture,
    comp: fixture.componentInstance,
    dialogService,
    api,
    httpMock,
    createMessageTag,
    updateMessageTag,
    deleteMessageTag,
    clearMessageTagMessages,
    unlockMessageTag,
    saveLastSelection,
    clearLastSelection,
    notifyError: error,
    confirmAccept,
  };
}

async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

interface DraftAccess {
  draft: () => { mode: 'new' | 'edit'; tagId: string | null } | null;
  dirty: () => boolean;
  addTag: () => void;
  editTag: (t: MessageTag) => void;
  copyTag: (t: MessageTag) => void;
  cancelEdit: () => void;
  isEditingRow: (t: MessageTag) => boolean;
  form: MessageTagsDialog['form'];
  setTagType: (t: MessageTag['type']) => void;
  saveTag: () => void;
  newRowId: string;
}

describe('MessageTagsDialog — inline-row editing', () => {
  beforeEach(() => undefined);

  it('starts with no draft (clean)', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess;
    expect(c.draft()).toBeNull();
    expect(c.dirty()).toBe(false);
  });

  it('addTag() opens a new draft (dirty) editing the sentinel top row', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess;
    c.addTag();
    expect(c.draft()?.mode).toBe('new');
    expect(c.dirty()).toBe(true);
    expect(c.form.name().disabled()).toBe(false);
    expect(c.isEditingRow(mkTag({ tagId: c.newRowId }))).toBe(true);
  });

  it('editTag() edits the row in place and disables the (identity) name control', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess;
    c.editTag(mkTag({ tagId: 'X', name: 'Foo' }));
    expect(c.draft()).toEqual({ mode: 'edit', tagId: 'X' });
    expect(c.form.name().disabled()).toBe(true);
    expect(c.form.name().value()).toBe('Foo');
    expect(c.isEditingRow(mkTag({ tagId: 'X' }))).toBe(true);
    expect(c.isEditingRow(mkTag({ tagId: 'other' }))).toBe(false);
  });

  it('copyTag() opens a NEW draft (blank id) prefilled, name editable, lock cleared', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess;
    c.copyTag(mkTag({ tagId: 'src', name: 'Foo', locked: true }));
    expect(c.draft()).toEqual({ mode: 'new', tagId: null });
    expect(c.form.name().disabled()).toBe(false);
    expect(c.form.locked().value()).toBe(false);
  });

  it('cancelEdit() drops the draft (clean again)', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess;
    c.addTag();
    c.cancelEdit();
    expect(c.draft()).toBeNull();
    expect(c.dirty()).toBe(false);
  });

  it('sorting is frozen while editing so the open editor row cannot be filtered away', () => {
    const { comp } = setup();
    const c = comp as unknown as DraftAccess & {
      toggleSort: (f: string) => void;
      sortBy: () => string;
    };
    const before = c.sortBy();
    c.editTag(mkTag({ tagId: 'X', name: 'Foo' }));
    c.toggleSort('TYPE');
    expect(c.sortBy()).toBe(before);
  });
});

describe('MessageTagsDialog — list', () => {
  it('fetches tags when the drawer is opened', async () => {
    const { dialogService, httpMock } = setup({ tags: [mkTag()] });
    dialogService.open();
    await settle();
    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === '/rest/tags/search');
    expect(req.request.params.get('sortBy')).toBe('NAME');
    expect(req.request.params.get('locked')).toBe('false');
    req.flush([mkTag()]);
  });

  it('saveTag() creates a new tag then clears the draft', async () => {
    const { dialogService, comp, createMessageTag } = setup({ tags: [mkTag()] });
    dialogService.open();
    await settle();
    const c = comp as unknown as DraftAccess;
    c.addTag();
    c.form.name().value.set('Fresh');
    c.saveTag();
    expect(createMessageTag).toHaveBeenCalled();
    expect(c.draft()).toBeNull();
  });
});

describe('MessageTagsDialog — CRUD', () => {
  it('deleteTag invokes API + reloads', async () => {
    const { dialogService, comp, deleteMessageTag } = setup({ tags: [mkTag()] });
    dialogService.open();
    await settle();
    (comp as unknown as { deleteTag: (t: MessageTag) => void }).deleteTag(mkTag({ tagId: 'x' }));
    expect(deleteMessageTag).toHaveBeenCalledWith('x');
  });

  it('deleteTag clears the last-selection when the deleted tag was last-selected', async () => {
    const last = mkTag({ tagId: 'last-1' });
    const { dialogService, comp, clearLastSelection } = setup({
      tags: [last],
      lastSelection: last,
    });
    dialogService.open();
    await settle();
    (comp as unknown as { deleteTag: (t: MessageTag) => void }).deleteTag(last);
    expect(clearLastSelection).toHaveBeenCalledTimes(1);
  });

  it('deleteTag leaves last-selection untouched when a different tag is deleted', async () => {
    const last = mkTag({ tagId: 'last-1' });
    const other = mkTag({ tagId: 'other' });
    const { dialogService, comp, clearLastSelection } = setup({
      tags: [last, other],
      lastSelection: last,
    });
    dialogService.open();
    await settle();
    (comp as unknown as { deleteTag: (t: MessageTag) => void }).deleteTag(other);
    expect(clearLastSelection).not.toHaveBeenCalled();
  });

  it('clearTagMessages invokes API', async () => {
    const { dialogService, comp, clearMessageTagMessages } = setup({ tags: [mkTag()] });
    dialogService.open();
    await settle();
    (comp as unknown as { clearTagMessages: (t: MessageTag) => void }).clearTagMessages(
      mkTag({ tagId: 'x' }),
    );
    expect(clearMessageTagMessages).toHaveBeenCalledWith('x');
  });

  it('unlockTag asks for confirmation and unlocks on accept', async () => {
    const { dialogService, comp, unlockMessageTag, confirmAccept } = setup({ tags: [mkTag()] });
    dialogService.open();
    await settle();
    (comp as unknown as { unlockTag: (t: MessageTag) => void }).unlockTag(
      mkTag({ tagId: 'x', locked: true }),
    );
    expect(confirmAccept).toHaveBeenCalled();
    expect(unlockMessageTag).toHaveBeenCalledWith('x');
  });
});

describe('MessageTagsDialog — errors', () => {
  it('fetch error fires the single CRUD error toast', async () => {
    const { dialogService, notifyError, httpMock } = setup({ searchError: true });
    dialogService.open();
    await settle();
    httpMock
      .expectOne((r) => r.method === 'GET' && r.url === '/rest/tags/search')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(notifyError).toHaveBeenCalledWith('messages.tags.dialog.error');
  });
});

interface TypeAccess extends DraftAccess {
  canChangeType: () => boolean;
  editingType: () => MessageTag['type'];
}

/** Open the drawer, list `tags` and render; the edit row then shows its type control. */
async function openWith(env: ReturnType<typeof setup>, tags: MessageTag[]): Promise<void> {
  env.dialogService.open();
  await settle();
  env.httpMock.expectOne((r) => r.method === 'GET' && r.url === '/rest/tags/search').flush(tags);
  await settle();
  env.fixture.detectChanges();
}

// The drawer's body is portaled to the CDK overlay container, so the edit row is queried there.
function typeGroup(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-testid="tag-edit-type"]');
}

function typeButtons(): HTMLButtonElement[] {
  return Array.from(typeGroup()?.querySelectorAll<HTMLButtonElement>('button') ?? []);
}

function typeHint(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-testid="tag-edit-type-hint"]');
}

describe('MessageTagsDialog — who may change a tag type (TAG4)', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  for (const type of ['DOMAIN', 'PUBLIC'] as const) {
    it(`a user who is not an administrator cannot change the type of a ${type.toLowerCase()} tag`, async () => {
      const env = setup();
      const tag = mkTag({ tagId: 'shared', name: 'Shared', type });
      await openWith(env, [tag]);
      const c = env.comp as unknown as TypeAccess;
      c.editTag(tag);
      env.fixture.detectChanges();

      expect(c.canChangeType()).toBe(false);
      const buttons = typeButtons();
      expect(buttons.length).toBe(3);
      expect(buttons.every((b) => b.disabled)).toBe(true);
      const hint = typeHint();
      expect(hint?.textContent?.trim()).toBe('messages.tags.dialog.edit.typeAdminOnly');
      expect(hint?.id).toBeTruthy();
      expect(typeGroup()?.getAttribute('aria-describedby')).toBe(hint?.id);

      // A call that gets past the disabled buttons changes nothing either.
      c.setTagType('PRIVATE');
      expect(c.editingType()).toBe(type);
      env.httpMock.match(() => true);
    });
  }

  it('the owner of a private tag can still change its type', async () => {
    const env = setup();
    const tag = mkTag({ tagId: 'mine', name: 'Mine', type: 'PRIVATE' });
    await openWith(env, [tag]);
    const c = env.comp as unknown as TypeAccess;
    c.editTag(tag);
    env.fixture.detectChanges();

    expect(c.canChangeType()).toBe(true);
    expect(typeButtons().length).toBe(3);
    expect(typeButtons().some((b) => b.disabled)).toBe(false);
    expect(typeHint()).toBeNull();
    expect(typeGroup()?.hasAttribute('aria-describedby')).toBe(false);
    c.setTagType('DOMAIN');
    expect(c.editingType()).toBe('DOMAIN');
    env.httpMock.match(() => true);
  });

  for (const role of ['admin', 'sysadmin'] as const) {
    it(`${role === 'admin' ? 'an admin' : 'a sysadmin'} can change the type of a shared tag`, async () => {
      const env = setup({ [role]: true });
      const tag = mkTag({ tagId: 'shared', name: 'Shared', type: 'PUBLIC' });
      await openWith(env, [tag]);
      const c = env.comp as unknown as TypeAccess;
      c.editTag(tag);
      env.fixture.detectChanges();

      expect(c.canChangeType()).toBe(true);
      expect(typeButtons().length).toBe(3);
      expect(typeButtons().some((b) => b.disabled)).toBe(false);
      expect(typeHint()).toBeNull();
      c.setTagType('DOMAIN');
      expect(c.editingType()).toBe('DOMAIN');
      env.httpMock.match(() => true);
    });
  }

  it('a new tag, a duplicate of a shared one included, takes any type', () => {
    const env = setup();
    const c = env.comp as unknown as TypeAccess;
    c.addTag();
    expect(c.canChangeType()).toBe(true);
    c.copyTag(mkTag({ tagId: 'src', name: 'Shared', type: 'PUBLIC' }));
    expect(c.canChangeType()).toBe(true);
    c.setTagType('PRIVATE');
    expect(c.editingType()).toBe('PRIVATE');
  });

  it('saving a shared tag as a user who is not an administrator sends its stored type', () => {
    const env = setup();
    const c = env.comp as unknown as TypeAccess;
    c.editTag(mkTag({ tagId: 'shared', name: 'Shared', type: 'DOMAIN' }));
    c.setTagType('PRIVATE');
    c.form.locked().value.set(true);
    c.saveTag();
    expect(env.updateMessageTag).toHaveBeenCalledWith(
      expect.objectContaining({ tagId: 'shared', type: 'DOMAIN', locked: true }),
    );
  });
});

describe('MessageTagsDialog — a refused save', () => {
  it("shows the backend's reason and keeps the row open", () => {
    const env = setup();
    env.updateMessageTag.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            statusText: 'Bad Request',
            error: { status: 400, detail: 'A domain tag needs a domain' },
          }),
      ),
    );
    const c = env.comp as unknown as TypeAccess;
    c.editTag(mkTag({ tagId: 'mine', name: 'Mine', type: 'PRIVATE' }));
    c.setTagType('DOMAIN');
    c.saveTag();
    expect(env.notifyError).toHaveBeenCalledWith('messages.tags.dialog.refused', {
      detail: 'A domain tag needs a domain',
    });
    expect(c.draft()).toEqual({ mode: 'edit', tagId: 'mine' });
  });

  it('a 403 with a reason shows that reason too', () => {
    const env = setup();
    env.createMessageTag.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            statusText: 'Forbidden',
            error: { status: 403, detail: 'The tag belongs to another domain' },
          }),
      ),
    );
    const c = env.comp as unknown as TypeAccess;
    c.addTag();
    c.form.name().value.set('Fresh');
    c.saveTag();
    expect(env.notifyError).toHaveBeenCalledWith('messages.tags.dialog.refused', {
      detail: 'The tag belongs to another domain',
    });
  });

  it('an error without a reason keeps the general error text', () => {
    const env = setup();
    env.createMessageTag.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    const c = env.comp as unknown as TypeAccess;
    c.addTag();
    c.form.name().value.set('Fresh');
    c.saveTag();
    expect(env.notifyError).toHaveBeenCalledWith('messages.tags.dialog.error');
    expect(c.draft()).toEqual({ mode: 'new', tagId: null });
  });
});
