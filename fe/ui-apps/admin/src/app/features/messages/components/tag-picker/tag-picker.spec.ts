import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of } from 'rxjs';

import { TAG_SELECTION_STORE } from '../../../../core/domain/services/tag-selection-store.token';
import type { MessageTag } from '../../../../shared/models/message-tag.model';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessageTagsDialogService } from '../../dialogs/message-tags-dialog/message-tags-dialog.service';
import { MessageTagActionsService } from '../../services/message-tag-actions.service';
import { TagsApiService } from '../../services/api/tags-api.service';
import { TagPicker, type TagPickerMode, type TagPickerToggle } from './tag-picker';

function mkTag(tagId: string, opts: Partial<MessageTag> = {}): MessageTag {
  return {
    tagId,
    name: `Tag ${tagId}`,
    type: 'DOMAIN',
    messageCount: 0,
    locked: false,
    ...opts,
  };
}

@Component({
  imports: [TagPicker],
  template: `
    <app-tag-picker [mode]="mode()" [selectedIds]="selected()" (toggled)="events.push($event)" />
  `,
})
class Host {
  readonly mode = signal<TagPickerMode>('add');
  readonly selected = signal<readonly string[]>([]);
  readonly events: TagPickerToggle[] = [];
}

interface SetupOptions {
  readonly mode?: TagPickerMode;
  readonly isLoggedIn?: boolean;
  readonly searchResult?: readonly MessageTag[];
  /** messageId → tags the message carries (membership pre-check). */
  readonly tagsByMessage?: Record<string, readonly MessageTag[]>;
  /** What the actions-service stubs resolve to; defaults to echoing the tag. */
  readonly mutationFails?: boolean;
}

function setup(opts: SetupOptions = {}) {
  const searchTags = vi.fn(() => of(opts.searchResult ?? []));
  // Mirrors the real aggregation contract: counts across the queried ids plus
  // the distinct tags, name-sorted.
  const membershipForMessages = vi.fn((ids: readonly string[]) => {
    const counts = new Map<string, number>();
    const byId = new Map<string, MessageTag>();
    for (const id of ids) {
      for (const tag of opts.tagsByMessage?.[id] ?? []) {
        counts.set(tag.tagId, (counts.get(tag.tagId) ?? 0) + 1);
        byId.set(tag.tagId, tag);
      }
    }
    return of({
      counts,
      tags: [...byId.values()].sort((a, b) => a.name.localeCompare(b.name)),
    });
  });
  const api = { searchTags, membershipForMessages } as unknown as TagsApiService;

  const mutationResult = (tag: MessageTag) => of(opts.mutationFails ? undefined : tag);
  const addMessagesToTag = vi.fn((_ids: readonly string[], tag: MessageTag) => mutationResult(tag));
  const removeMessagesFromTag = vi.fn((_ids: readonly string[], tag: MessageTag) =>
    mutationResult(tag),
  );
  const tagActions = {
    addMessagesToTag,
    removeMessagesFromTag,
  } as unknown as MessageTagActionsService;

  const saveLastSelection = vi.fn();
  const store = {
    getLastSelection: () => signal<MessageTag | undefined>(undefined),
    saveLastSelection,
    clearLastSelection: vi.fn(),
  };

  const managerOpen = vi.fn(() => of(undefined));

  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({ isAuthenticated: opts.isLoggedIn ?? true, editor: true }),
      { provide: TagsApiService, useValue: api },
      { provide: MessageTagActionsService, useValue: tagActions },
      { provide: TAG_SELECTION_STORE, useValue: store },
      {
        provide: MessageTagsDialogService,
        useValue: { open: managerOpen, visible: signal(false) },
      },
      provideTestTransloco(),
    ],
  });

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.mode.set(opts.mode ?? 'add');
  fixture.detectChanges();
  const picker = fixture.debugElement.query(By.directive(TagPicker)).componentInstance as TagPicker;

  const anchor = document.createElement('button');
  document.body.appendChild(anchor);

  return {
    fixture,
    host: fixture.componentInstance,
    picker,
    anchor,
    searchTags,
    membershipForMessages,
    addMessagesToTag,
    removeMessagesFromTag,
    saveLastSelection,
    managerOpen,
  };
}

/** Drain effects + the timer(0) macrotask the tag list stream starts with. */
async function settle(): Promise<void> {
  TestBed.tick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  TestBed.tick();
  await Promise.resolve();
}

interface PickerInternals {
  isChecked(tag: MessageTag): boolean;
  isIndeterminate(tag: MessageTag): boolean;
  togglesPending(): boolean;
  onToggle(tag: MessageTag): void;
  openManager(): void;
  selectedScopes: ReturnType<typeof signal<readonly MessageTag['type'][]>>;
  tags(): readonly MessageTag[];
  canManage(): boolean;
  headerKey(): string;
}

function internals(picker: TagPicker): PickerInternals {
  return picker as unknown as PickerInternals;
}

describe('TagPicker add mode — membership pre-check', () => {
  it('pre-checks full membership, marks partial membership indeterminate', () => {
    const tagA = mkTag('a');
    const tagB = mkTag('b');
    const { picker, anchor } = setup({
      tagsByMessage: { m1: [tagA, tagB], m2: [tagA] },
    });

    picker.open(anchor, { messageIds: ['m1', 'm2'] });

    const p = internals(picker);
    expect(p.isChecked(tagA)).toBe(true);
    expect(p.isIndeterminate(tagA)).toBe(false);
    expect(p.isChecked(tagB)).toBe(false);
    expect(p.isIndeterminate(tagB)).toBe(true);
    expect(p.isChecked(mkTag('c'))).toBe(false);
    expect(p.isIndeterminate(mkTag('c'))).toBe(false);
    anchor.remove();
  });

  it('membership comes from the service-layer aggregation, once per open', () => {
    const { picker, anchor, membershipForMessages } = setup({});
    picker.open(anchor, { messageIds: ['m1', 'm2'] });
    expect(membershipForMessages).toHaveBeenCalledTimes(1);
    expect(membershipForMessages).toHaveBeenCalledWith(['m1', 'm2']);
    anchor.remove();
  });
});

describe('TagPicker add mode — toggling', () => {
  it('toggling an unchecked tag adds all targets and saves the last selection', () => {
    const tag = mkTag('x');
    const { picker, anchor, addMessagesToTag, saveLastSelection } = setup({});
    picker.open(anchor, { messageIds: ['m1', 'm2'] });

    internals(picker).onToggle(tag);

    expect(addMessagesToTag).toHaveBeenCalledWith(['m1', 'm2'], tag);
    expect(saveLastSelection).toHaveBeenCalledWith(tag);
    expect(internals(picker).isChecked(tag)).toBe(true);
    anchor.remove();
  });

  it('toggling a fully-checked tag removes all targets and does NOT save last selection', () => {
    const tag = mkTag('x');
    const { picker, anchor, removeMessagesFromTag, saveLastSelection } = setup({
      tagsByMessage: { m1: [tag], m2: [tag] },
    });
    picker.open(anchor, { messageIds: ['m1', 'm2'] });

    internals(picker).onToggle(tag);

    expect(removeMessagesFromTag).toHaveBeenCalledWith(['m1', 'm2'], tag);
    expect(saveLastSelection).not.toHaveBeenCalled();
    expect(internals(picker).isChecked(tag)).toBe(false);
    anchor.remove();
  });

  it('toggling an indeterminate tag promotes it to all targets (add call)', () => {
    const tag = mkTag('x');
    const { picker, anchor, addMessagesToTag } = setup({
      tagsByMessage: { m1: [tag], m2: [] },
    });
    picker.open(anchor, { messageIds: ['m1', 'm2'] });

    internals(picker).onToggle(tag);

    expect(addMessagesToTag).toHaveBeenCalledWith(['m1', 'm2'], tag);
    expect(internals(picker).isChecked(tag)).toBe(true);
    anchor.remove();
  });

  it('reverts the optimistic flip when the mutation fails', () => {
    const tag = mkTag('x');
    const { picker, anchor, saveLastSelection } = setup({ mutationFails: true });
    picker.open(anchor, { messageIds: ['m1'] });

    internals(picker).onToggle(tag);

    expect(internals(picker).isChecked(tag)).toBe(false);
    expect(saveLastSelection).not.toHaveBeenCalled();
    anchor.remove();
  });

  it('a failed toggle reverts only its own tag, not concurrent sibling toggles', () => {
    const tagX = mkTag('x');
    const tagY = mkTag('y');
    const { picker, anchor, addMessagesToTag } = setup({});
    const xResult = new Subject<MessageTag | undefined>();
    const yResult = new Subject<MessageTag | undefined>();
    addMessagesToTag
      .mockImplementationOnce(() => xResult.asObservable())
      .mockImplementationOnce(() => yResult.asObservable());
    picker.open(anchor, { messageIds: ['m1'] });

    const p = internals(picker);
    p.onToggle(tagX);
    p.onToggle(tagY);
    expect(p.isChecked(tagX)).toBe(true);
    expect(p.isChecked(tagY)).toBe(true);

    xResult.next(undefined);
    xResult.complete();
    expect(p.isChecked(tagX)).toBe(false);
    expect(p.isChecked(tagY)).toBe(true);

    yResult.next(tagY);
    yResult.complete();
    expect(p.isChecked(tagY)).toBe(true);
    anchor.remove();
  });

  it('membershipOnly lists exactly the tags the targets are in, without a server search', async () => {
    const tagA = mkTag('a', { name: 'Alpha' });
    const tagB = mkTag('b', { name: 'Beta' });
    const { picker, anchor, searchTags } = setup({
      tagsByMessage: { m1: [tagB], m2: [tagA, tagB] },
    });

    picker.open(anchor, { messageIds: ['m1', 'm2'], membershipOnly: true });
    await settle();

    const p = internals(picker);
    expect(p.tags().map((t) => t.name)).toEqual(['Alpha', 'Beta']);
    expect(p.headerKey()).toBe('messages.tagPicker.headerRemove');
    expect(searchTags).not.toHaveBeenCalled();
    // Unchecking still routes through the same membership-editor semantics.
    expect(p.isChecked(tagB)).toBe(true);
    expect(p.isIndeterminate(tagA)).toBe(true);
    anchor.remove();
  });
});

describe('TagPicker filter mode', () => {
  it('locked tags ARE selectable and toggling emits without API calls', () => {
    const locked = mkTag('l', { locked: true });
    const { picker, host, anchor, addMessagesToTag, membershipForMessages } = setup({
      mode: 'filter',
    });
    picker.open(anchor);

    const p = internals(picker);
    p.onToggle(locked);

    expect(host.events).toEqual([{ tag: locked, selected: true }]);
    expect(addMessagesToTag).not.toHaveBeenCalled();
    expect(membershipForMessages).not.toHaveBeenCalled();
    anchor.remove();
  });

  it('checked state mirrors selectedIds and toggling a selected tag emits selected=false', () => {
    const tag = mkTag('x');
    const { picker, host, anchor, fixture } = setup({ mode: 'filter' });
    host.selected.set(['x']);
    fixture.detectChanges();
    picker.open(anchor);

    const p = internals(picker);
    expect(p.isChecked(tag)).toBe(true);
    p.onToggle(tag);
    expect(host.events).toEqual([{ tag, selected: false }]);
    anchor.remove();
  });
});

describe('TagPicker tag list', () => {
  it('add mode opens with NO scope filter (all scopes) and excludes locked tags', async () => {
    const { picker, anchor, searchTags } = setup({});
    picker.open(anchor, { messageIds: ['m1'] });
    await settle();

    expect(searchTags).toHaveBeenCalledWith({
      name: undefined,
      types: undefined,
      sortBy: 'NAME',
      sortOrder: 'ASC',
      locked: false,
    });
    anchor.remove();
  });

  it('scope chips multi-select; selecting narrows, deselecting back to none shows all', async () => {
    const { picker, anchor, searchTags } = setup({});
    picker.open(anchor, { messageIds: ['m1'] });
    await settle();

    const p = internals(picker);
    p.selectedScopes.set(['PUBLIC']);
    await settle();
    expect(searchTags).toHaveBeenLastCalledWith(expect.objectContaining({ types: ['PUBLIC'] }));

    p.selectedScopes.set(['PUBLIC', 'DOMAIN']);
    await settle();
    expect(searchTags).toHaveBeenLastCalledWith(
      expect.objectContaining({ types: ['PUBLIC', 'DOMAIN'] }),
    );

    p.selectedScopes.set([]);
    await settle();
    expect(searchTags).toHaveBeenLastCalledWith(expect.objectContaining({ types: undefined }));
    anchor.remove();
  });

  it('filter mode keeps locked tags in the fetch', async () => {
    const { picker, anchor, searchTags } = setup({ mode: 'filter' });
    picker.open(anchor);
    await settle();

    expect(searchTags).toHaveBeenCalledWith(expect.objectContaining({ locked: undefined }));
    anchor.remove();
  });

  it('anonymous users get no Manage link', () => {
    const { picker } = setup({ mode: 'filter', isLoggedIn: false });
    expect(internals(picker).canManage()).toBe(false);
  });

  it('logged-in users get the Manage link', () => {
    const { picker } = setup({ mode: 'filter', isLoggedIn: true });
    expect(internals(picker).canManage()).toBe(true);
  });
});

describe('TagPicker add mode — stale membership response guard', () => {
  interface MembershipResult {
    counts: Map<string, number>;
    tags: MessageTag[];
  }

  it('discards a late membership response from a superseded open (stale-tick guard)', async () => {
    const tagA = mkTag('a');
    const tagB = mkTag('b');
    const call1 = new Subject<MembershipResult>();
    const call2 = new Subject<MembershipResult>();
    const { picker, anchor, fixture, membershipForMessages } = setup({});
    membershipForMessages
      .mockReturnValueOnce(call1.asObservable())
      .mockReturnValueOnce(call2.asObservable());

    // Open (call 1, tick 1), close, reopen (call 2, tick 2) before either resolves.
    picker.open(anchor, { messageIds: ['m1'] });
    picker.close();
    picker.open(anchor, { messageIds: ['m2'] });
    await fixture.whenStable();

    const p = internals(picker);

    // Call 2 resolves first: tag B is the live membership.
    call2.next({ counts: new Map([[tagB.tagId, 1]]), tags: [tagB] });
    call2.complete();
    expect(p.isChecked(tagB)).toBe(true);
    expect(p.isChecked(tagA)).toBe(false);
    expect(p.togglesPending()).toBe(false);

    // Superseded call 1 resolves LATE — the stale-tick guard drops it, so tag A is
    // never adopted and the call-2 state stays intact.
    call1.next({ counts: new Map([[tagA.tagId, 1]]), tags: [tagA] });
    call1.complete();
    expect(p.isChecked(tagA)).toBe(false);
    expect(p.isChecked(tagB)).toBe(true);
    anchor.remove();
  });

  it('a late membership ERROR from a superseded open does not clear the current pending state', async () => {
    const tagA = mkTag('a');
    const call1 = new Subject<MembershipResult>();
    const call2 = new Subject<MembershipResult>();
    const { picker, anchor, fixture, membershipForMessages } = setup({});
    membershipForMessages
      .mockReturnValueOnce(call1.asObservable())
      .mockReturnValueOnce(call2.asObservable());

    picker.open(anchor, { messageIds: ['m1'] });
    picker.close();
    picker.open(anchor, { messageIds: ['m2'] });
    await fixture.whenStable();

    const p = internals(picker);
    // Call 2 is still in flight → the picker is pending.
    expect(p.togglesPending()).toBe(true);

    // The superseded call 1 errors late; the guard drops it, so call 2 stays pending
    // instead of the stale error flipping the spinner off prematurely.
    call1.error(new Error('stale'));
    expect(p.togglesPending()).toBe(true);

    // Call 2 resolves and clears the pending state with its own membership.
    call2.next({ counts: new Map([[tagA.tagId, 1]]), tags: [tagA] });
    call2.complete();
    expect(p.togglesPending()).toBe(false);
    expect(p.isChecked(tagA)).toBe(true);
    anchor.remove();
  });
});

describe('TagPicker manager hand-off', () => {
  it('Manage closes the picker and opens the manager dialog with locked tags visible', () => {
    const { picker, anchor, managerOpen } = setup({});
    picker.open(anchor, { messageIds: ['m1'] });

    internals(picker).openManager();

    expect(managerOpen).toHaveBeenCalledWith({ includeLocked: true });
    anchor.remove();
  });
});
