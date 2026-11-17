import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { MailingList } from '../../../services/api/mailing-lists-api.service';
import type { Group, User } from '../../../services/api/users-api.service';
import { UserEditor, UserEditorModel } from './user-editor';

// jsdom lacks ResizeObserver, which the tab list observes.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  imports: [UserEditor],
  template: `
    <app-user-editor
      [model]="model()"
      [groups]="groups()"
      [mailingLists]="mailingLists()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<UserEditorModel | undefined>(undefined);
  readonly groups = signal<readonly Group[]>([]);
  readonly mailingLists = signal<readonly MailingList[]>([]);
  savedCount = 0;
}

function setup() {
  const toasts: { kind: 'success' | 'error'; key: string; params?: Record<string, unknown> }[] = [];
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: NotificationService,
        useValue: {
          success: (key: string) => toasts.push({ kind: 'success', key }),
          error: (key: string, params?: Record<string, unknown>) =>
            toasts.push({ kind: 'error', key, params }),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
    toasts,
  };
}

interface EditorInternals {
  form: UserEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  memberIds: () => ReadonlySet<string> | undefined;
  onMembershipToggle: (toggle: { list: MailingList; recipient: boolean }) => void;
  userGroups: WritableSignal<readonly Group[] | undefined>;
  availableGroups: () => readonly Group[];
  leaveGroup: (group: Group) => void;
  onGroupSelected: (group: Group) => void;
  joinSelectedGroup: () => void;
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

const ADD_MODEL: UserEditorModel = {
  mode: 'add',
  user: { keycloakId: '', username: '', email: '' },
};

const PERSISTED: User = {
  keycloakId: 'kc-1',
  username: 'alice',
  email: 'alice@x.dk',
  language: 'da',
};

/** Flush the two edit-mode opening fetches: the user's groups + the membership lookup. */
function flushEditOpens(
  httpTesting: HttpTestingController,
  memberOf: readonly string[] = [],
): void {
  httpTesting.expectOne('/rest/users/kc-user/kc-1/kc-groups').flush([]);
  httpTesting
    .expectOne('/rest/mailing-lists/search?username=alice')
    .flush(memberOf.map((id) => ({ mailingListId: id })));
}

describe('UserEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('add mode requires BOTH username and email before Save enables', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(!editor.form.username().disabled()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.form.username().value.set('bob');
    expect(editor.saveDisabled()).toBe(true); // email still empty

    editor.form.email().value.set('bob@x.dk');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('add POSTs with truthy required-actions flattened; blank language/password OMITTED', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.username().value.set('bob');
    editor.form.email().value.set('bob@x.dk');
    editor.form.requiredActions.updateProfile().value.set(true);
    editor.form.requiredActions.verifyEmail().value.set(true);
    editor.save();

    const req = httpTesting.expectOne('/rest/users/kc-user/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      keycloakId: '',
      username: 'bob',
      email: 'bob@x.dk',
      firstName: undefined,
      lastName: undefined,
      language: undefined,
      keycloakPassword: undefined,
      keycloakActions: ['UPDATE_PROFILE', 'VERIFY_EMAIL'],
    });
    req.flush({});
    expect(fixture.componentInstance.savedCount).toBe(1);
  });

  it('edit mode disables the immutable username and PUTs WITHOUT keycloakActions', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', user: PERSISTED });
    fixture.detectChanges();
    flushEditOpens(httpTesting);
    const editor = editorOf(fixture);

    expect(editor.form.username().disabled()).toBe(true);
    expect(editor.saveDisabled()).toBe(true); // unchanged baseline

    editor.form.firstName().value.set('Alice');
    // Even with the checkbox ticked, edit never sends required-actions.
    editor.form.requiredActions.updatePassword().value.set(true);
    editor.save();

    const req = httpTesting.expectOne('/rest/users/kc-user/kc-1');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as User;
    expect(body.username).toBe('alice'); // disabled PK still rides the payload (stays in formModel)
    expect(body.keycloakActions).toBeUndefined();
    expect(body.language).toBe('da');
    req.flush({});
  });

  it('clearing the language omits it from the payload', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', user: PERSISTED });
    fixture.detectChanges();
    flushEditOpens(httpTesting);
    const editor = editorOf(fixture);

    editor.form.language().value.set('');
    editor.save();

    const req = httpTesting.expectOne('/rest/users/kc-user/kc-1');
    expect((req.request.body as User).language).toBeUndefined();
    req.flush({});
  });

  it('memberIds stays UNDEFINED until the membership lookup resolves (the two-source race guard)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', user: PERSISTED });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.memberIds()).toBeUndefined(); // in flight — flags must not paint yet
    flushEditOpens(httpTesting, ['list-a']);
    expect(editor.memberIds()).toEqual(new Set(['list-a']));
  });

  it('a membership toggle PUTs the FULL persisted user + the desired state, stays toast-quiet, and re-resolves the flags', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', user: PERSISTED });
    fixture.detectChanges();
    flushEditOpens(httpTesting);
    const editor = editorOf(fixture);

    editor.onMembershipToggle({ list: { mailingListId: 'list-a' }, recipient: true });

    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/list-a/update-status');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ user: PERSISTED, recipient: true });
    req.flush(true);

    // Success re-resolves the flags (no toast).
    httpTesting
      .expectOne('/rest/mailing-lists/search?username=alice')
      .flush([{ mailingListId: 'list-a' }]);
    expect(editor.memberIds()).toEqual(new Set(['list-a']));
  });

  it('multi-open reuse leaks nothing across users', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', user: PERSISTED });
    fixture.detectChanges();
    flushEditOpens(httpTesting, ['list-a']);

    host.model.set({
      mode: 'edit',
      user: { keycloakId: 'kc-2', username: 'bob', email: 'bob@x.dk' },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(editor.memberIds()).toBeUndefined(); // reset, not the previous user's
    httpTesting.expectOne('/rest/users/kc-user/kc-2/kc-groups').flush([]);
    httpTesting.expectOne('/rest/mailing-lists/search?username=bob').flush([]);

    expect(editor.form.username().value()).toBe('bob');
    expect(editor.form.language().value()).toBe('');
    expect(editor.saveDisabled()).toBe(true); // fresh baseline
  });

  it('a failed save keeps the drawer open and re-saveable', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.username().value.set('bob');
    editor.form.email().value.set('bob@x.dk');
    editor.save();
    httpTesting
      .expectOne('/rest/users/kc-user/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined(); // still open
    expect(fixture.componentInstance.savedCount).toBe(0);
    expect(editor.saving()).toBe(false);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.username().value.set('bob');
    editor.form.email().value.set('bob@x.dk');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/users/kc-user/').flush({});
    expect(fixture.componentInstance.savedCount).toBe(1);
  });

  // ── Group join and leave: a change Keycloak did not make is never shown as done ──
  describe('a group join or leave Keycloak did not carry out', () => {
    const GROUP: Group = { id: 'g-1', active: true };
    const problem = (status: number, detail: string) => ({
      body: { type: 'about:blank', title: 'x', status, detail },
      opts: { status, statusText: 'x', headers: { 'Content-Type': 'application/problem+json' } },
    });

    it('a refused leave toasts the backend sentence, no success, and reloads nothing', () => {
      const { fixture, host, httpTesting, toasts } = setup();
      host.model.set({ mode: 'edit', user: PERSISTED });
      fixture.detectChanges();
      flushEditOpens(httpTesting);
      const editor = editorOf(fixture);

      editor.leaveGroup(GROUP);
      const refused = problem(
        403,
        "Keycloak refused to remove the user from the group, so the user's groups are unchanged",
      );
      const req = httpTesting.expectOne('/rest/users/kc-user/kc-1/kc-groups/g-1');
      expect(req.request.method).toBe('DELETE');
      req.flush(refused.body, refused.opts);

      expect(toasts).toEqual([
        {
          kind: 'error',
          key: 'admin.users.toast.groupRefused',
          params: { detail: refused.body.detail },
        },
      ]);
      // No reload of the user's groups follows (afterEach verifies no request is left)
    });

    it('a join Keycloak could not be reached for toasts its sentence', () => {
      const { fixture, host, httpTesting, toasts } = setup();
      host.model.set({ mode: 'edit', user: PERSISTED });
      fixture.detectChanges();
      flushEditOpens(httpTesting);
      const editor = editorOf(fixture);

      editor.onGroupSelected(GROUP);
      editor.joinSelectedGroup();
      const failed = problem(502, 'Keycloak could not be reached to add the user to the group');
      const req = httpTesting.expectOne('/rest/users/kc-user/kc-1/kc-groups/g-1');
      expect(req.request.method).toBe('PUT');
      req.flush(failed.body, failed.opts);

      expect(toasts).toEqual([
        {
          kind: 'error',
          key: 'admin.users.toast.groupRefused',
          params: { detail: failed.body.detail },
        },
      ]);
    });

    it('a failure without a sentence keeps the generic error toast', () => {
      const { fixture, host, httpTesting, toasts } = setup();
      host.model.set({ mode: 'edit', user: PERSISTED });
      fixture.detectChanges();
      flushEditOpens(httpTesting);
      const editor = editorOf(fixture);

      editor.leaveGroup(GROUP);
      httpTesting
        .expectOne('/rest/users/kc-user/kc-1/kc-groups/g-1')
        .flush('boom', { status: 500, statusText: 'Server Error' });

      expect(toasts).toEqual([
        { kind: 'error', key: 'admin.users.toast.groupError', params: undefined },
      ]);
    });
  });

  // ── availableGroups / pruneJoinedGroups ──────────────────────────────────
  // Driven through the public `availableGroups` computed: set the group tree
  // (host input) and the joined set (the userGroups signal), then read the
  // pruned tree the Join target picker is offered.
  describe('availableGroups prunes the joined groups from the tree', () => {
    /** Build a leaf/branch Group; only `id`/`children`/`active` matter to the prune. */
    const grp = (id: string, children: Group[] = []): Group => ({
      id,
      active: true,
      children,
    });

    /**
     * Wire a fully-loaded edit-mode editor with `groups` + a resolved
     * `userGroups`, so `availableGroups` runs over real signal inputs.
     */
    function withGroups(
      tree: readonly Group[],
      joined: readonly Group[],
    ): { editor: EditorInternals; httpTesting: HttpTestingController } {
      const { fixture, host, httpTesting } = setup();
      host.groups.set(tree);
      host.model.set({ mode: 'edit', user: PERSISTED });
      fixture.detectChanges();
      flushEditOpens(httpTesting); // resolves the opening fetches → no pending reqs
      const editor = editorOf(fixture);
      editor.userGroups.set(joined); // the joined set the prune subtracts
      fixture.detectChanges();
      return { editor, httpTesting };
    }

    it('drops a top-level group the user already joined', () => {
      const a = grp('a');
      const b = grp('b');
      const { editor } = withGroups([a, b], [a]);

      const ids = editor.availableGroups().map((g) => g.id);
      expect(ids).toEqual(['b']); // joined `a` gone; `b` still joinable
    });

    it('drops a joined PARENT but surfaces its still-unjoined children in its place', () => {
      const child1 = grp('p-child-1');
      const child2 = grp('p-child-2');
      const parent = grp('parent', [child1, child2]);
      const sibling = grp('sibling');
      // Parent is joined; neither child is.
      const { editor } = withGroups([parent, sibling], [parent]);

      const available = editor.availableGroups();
      // The parent itself is gone, replaced by its two unjoined children,
      // followed by the untouched sibling — order is depth-first.
      expect(available.map((g) => g.id)).toEqual(['p-child-1', 'p-child-2', 'sibling']);
      // The surfaced children are flattened to the top level (no parent wrapper).
      expect(available.every((g) => (g.children ?? []).length === 0)).toBe(true);
    });

    it('returns a fully-unjoined tree intact (parent kept with its children)', () => {
      const child = grp('c');
      const parent = grp('p', [child]);
      const { editor } = withGroups([parent], []);

      const available = editor.availableGroups();
      expect(available.map((g) => g.id)).toEqual(['p']);
      expect(available[0].children?.map((g) => g.id)).toEqual(['c']);
    });
  });
});
