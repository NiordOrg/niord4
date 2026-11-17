import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { Observable, of } from 'rxjs';

import { By } from '@angular/platform-browser';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { TAG_SELECTION_STORE } from '../../../core/domain/services/tag-selection-store.token';
import { TagPicker } from '../components/tag-picker/tag-picker';
import { MessageCopyDialogService } from '../dialogs/message-copy-dialog/message-copy-dialog.service';
import { MessageDetailsMenu } from './message-details-menu';
import { TagsApiService } from '../services/api/tags-api.service';
import { MessagePrintService } from '../services/message-print.service';
import { MessageTagActionsService } from '../services/message-tag-actions.service';
import type { Message } from '../models/message.model';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade, provideFakeDomainFacade } from '../../../shared/testing/fake-auth';

interface ScenarioInputs {
  readonly isLoggedIn: boolean;
  readonly isEditor: boolean;
  readonly canEditSeries: boolean;
  readonly hasSeries?: boolean;
  /** The backend's `user` role in the session domain; defaults to isLoggedIn. */
  readonly domainUserRole?: boolean;
}

function mkTag(tagId: string, name: string): MessageTag {
  return { tagId, name, type: 'PRIVATE', messageCount: 0, locked: false };
}

interface SetupOptions {
  readonly fetchedTags?: MessageTag[];
  readonly lastTag?: MessageTag;
}

/** URL of the per-message tag membership GET the menu's httpResource issues once opened. */
const TAG_FETCH_URL = '/rest/tags/message/NM-1-26';

/** Flush the pending tag-membership GET with the given list (mirrors the old tagsForMessage stub). */
function flushTagFetch(httpMock: HttpTestingController, tags: MessageTag[]): void {
  const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === TAG_FETCH_URL);
  req.flush(tags);
}

function setup(inputs: ScenarioInputs, optsOrTags: SetupOptions | MessageTag[] = {}) {
  const opts: SetupOptions = Array.isArray(optsOrTags) ? { fetchedTags: optsOrTags } : optsOrTags;

  type TagOp = (ids: readonly string[], tag?: MessageTag) => Observable<MessageTag | undefined>;
  type LastTagOp = (ids: readonly string[]) => Observable<MessageTag | undefined>;
  const addMessagesToTag = vi.fn<TagOp>(() => of(mkTag('returned', 'Returned')));
  const removeMessagesFromTag = vi.fn<TagOp>(() => of(mkTag('returned', 'Returned')));
  const addMessagesToLastSelectedTag = vi.fn<LastTagOp>(() => of(opts.lastTag));
  const tagActions = {
    addMessagesToTag,
    removeMessagesFromTag,
    addMessagesToLastSelectedTag,
  } as unknown as MessageTagActionsService;

  const lastTagSignal = signal<MessageTag | undefined>(opts.lastTag);
  const store = {
    getLastSelection: () => lastTagSignal,
    saveLastSelection: vi.fn(),
    clearLastSelection: vi.fn(),
  };

  const copyDialog = {
    open: vi.fn((id: string) => of<string[] | undefined>(['/editor/copy', id])),
  };

  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({
        isAuthenticated: inputs.isLoggedIn,
        editor: inputs.isEditor,
        domainUserRole: inputs.domainUserRole,
      }),
      provideFakeDomainFacade({ canEditSeries: inputs.canEditSeries }),
      { provide: MessageTagActionsService, useValue: tagActions },
      { provide: MessagePrintService, useValue: { printMessage: vi.fn(() => of(undefined)) } },
      { provide: MessageCopyDialogService, useValue: copyDialog },
      { provide: TAG_SELECTION_STORE, useValue: store },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideRouter([]),
    ],
  });

  const fixture = TestBed.createComponent(MessageDetailsMenu);
  fixture.componentRef.setInput('messageId', 'NM-1-26');
  fixture.componentRef.setInput('message', {
    id: 'NM-1-26',
    mainType: 'NM',
    messageSeries: inputs.hasSeries === false ? undefined : { seriesId: 'series-1' },
  } as Message);
  fixture.detectChanges();

  return {
    fixture,
    comp: fixture.componentInstance,
    httpMock: TestBed.inject(HttpTestingController),
    addMessagesToTag,
    removeMessagesFromTag,
    addMessagesToLastSelectedTag,
    lastTagSignal,
    copyDialog,
  };
}

/**
 * Drain Angular's effect scheduler and the JS microtask queue so httpResource's
 * request → fetch → value transition completes. Project is zoneless.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

function labels(component: MessageDetailsMenu): string[] {
  return component
    .items()
    .filter((i) => i.label)
    .map((i) => i.label as string);
}

function findItem(component: MessageDetailsMenu, label: string) {
  return component.items().find((i) => i.label === label);
}

describe('MessageDetailsMenu permission visibility', () => {
  it('anonymous: only Print is visible', () => {
    const { comp } = setup({
      isLoggedIn: false,
      isEditor: false,
      canEditSeries: false,
    });
    expect(labels(comp)).toEqual(['messages.detailsMenu.print']);
  });

  it('editor on a domain-editable series: Edit + Copy + Print + Add to tag', () => {
    const { comp } = setup({
      isLoggedIn: true,
      isEditor: true,
      canEditSeries: true,
    });
    const ls = labels(comp);
    expect(ls).toContain('menu.action.edit');
    expect(ls).toContain('menu.action.copy');
    expect(ls).toContain('messages.detailsMenu.print');
    expect(ls).toContain('menu.action.tag.add');
  });

  it('editor on a non-domain series: no Edit / Copy but still Print + Add to tag', () => {
    const { comp } = setup({
      isLoggedIn: true,
      isEditor: true,
      canEditSeries: false,
    });
    const ls = labels(comp);
    expect(ls).not.toContain('menu.action.edit');
    expect(ls).not.toContain('menu.action.copy');
    expect(ls).toContain('messages.detailsMenu.print');
    expect(ls).toContain('menu.action.tag.add');
  });

  it('editor with no messageSeries on the message: no Edit / Copy', () => {
    const { comp } = setup({
      isLoggedIn: true,
      isEditor: true,
      canEditSeries: true,
      hasSeries: false,
    });
    expect(labels(comp)).not.toContain('menu.action.edit');
  });

  it('logged-in non-editor on a non-domain series: Print + tag actions, no Edit', () => {
    const { comp } = setup({
      isLoggedIn: true,
      isEditor: false,
      canEditSeries: false,
    });
    const ls = labels(comp);
    expect(ls).toContain('messages.detailsMenu.print');
    expect(ls).toContain('menu.action.tag.add');
    expect(ls).not.toContain('menu.action.edit');
  });

  it('signed in without a role in the session domain: no tag items, Print stays', async () => {
    const { fixture, comp, httpMock } = setup(
      { isLoggedIn: true, isEditor: false, canEditSeries: false, domainUserRole: false },
      { lastTag: mkTag('last', 'Last') },
    );
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, [mkTag('t1', 'Alpha')]);
    await settle();

    const ls = labels(comp);
    expect(ls).toContain('messages.detailsMenu.print');
    expect(ls).not.toContain('menu.action.tag.add');
    expect(ls).not.toContain('menu.action.tag.add_named');
    expect(ls).not.toContain('menu.action.tag.remove_named');
  });

  it('logged-in non-editor on a domain-editable series: Edit is shown (to comment) but NOT Copy', () => {
    const { comp } = setup({
      isLoggedIn: true,
      isEditor: false,
      canEditSeries: true,
    });
    const ls = labels(comp);
    expect(ls).toContain('menu.action.edit');
    expect(ls).not.toContain('menu.action.copy');
    expect(ls).toContain('messages.detailsMenu.print');
  });
});

describe('MessageDetailsMenu items enabled', () => {
  it('edit / copy are enabled', () => {
    const { comp } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    for (const label of ['menu.action.edit', 'menu.action.copy']) {
      const item = findItem(comp, label);
      expect(item, `expected ${label} present`).toBeDefined();
      expect(item?.disabled, `expected ${label} enabled`).toBeFalsy();
      expect(item?.title, `expected no coming-soon tooltip on ${label}`).toBeUndefined();
    }
  });

  it('tag items + print are NOT disabled', () => {
    const { comp } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    const addToTag = findItem(comp, 'menu.action.tag.add');
    expect(addToTag?.disabled).toBeFalsy();
    const print = findItem(comp, 'messages.detailsMenu.print');
    expect(print?.disabled).toBeFalsy();
  });
});

describe('MessageDetailsMenu lazy tag fetch', () => {
  it('does not fetch this message tags on init', () => {
    const { httpMock } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true }, [
      mkTag('t1', 'Tag 1'),
    ]);
    httpMock.expectNone((r) => r.url === TAG_FETCH_URL);
  });

  it('fetches tags once the menu opens and nests one Remove child per tag', async () => {
    const { fixture, comp, httpMock } = setup(
      { isLoggedIn: true, isEditor: true, canEditSeries: true },
      [mkTag('t1', 'Alpha'), mkTag('t2', 'Beta')],
    );

    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();

    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === TAG_FETCH_URL);
    expect(req.request.params.get('locked')).toBe('false');
    req.flush([mkTag('t1', 'Alpha'), mkTag('t2', 'Beta')]);
    await settle();

    // The remove shortcuts live under a single "Remove from…" submenu — one
    // child per (unlocked) tag the message is in, labelled with the tag name.
    const removeParent = findItem(comp, 'menu.action.tag.remove_named');
    expect(removeParent).toBeDefined();
    expect(removeParent?.command).toBeUndefined();
    expect(removeParent?.items?.map((c) => c.label)).toEqual(['Alpha', 'Beta']);
  });

  it('a Remove submenu child calls removeMessagesFromTag with its tag and reloads', async () => {
    const { fixture, comp, removeMessagesFromTag, httpMock } = setup(
      { isLoggedIn: true, isEditor: true, canEditSeries: true },
      [mkTag('t42', 'Gamma')],
    );
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, [mkTag('t42', 'Gamma')]);
    await settle();

    const child = findItem(comp, 'menu.action.tag.remove_named')?.items?.[0];
    child?.command?.({ originalEvent: new MouseEvent('click'), item: child });
    expect(removeMessagesFromTag).toHaveBeenCalledTimes(1);
    expect(removeMessagesFromTag.mock.calls[0][0]).toEqual(['NM-1-26']);
    expect(removeMessagesFromTag.mock.calls[0][1]).toEqual(mkTag('t42', 'Gamma'));
    await settle();
    // Success reloads the membership list backing the menu → a second GET is issued.
    httpMock.expectOne((r) => r.method === 'GET' && r.url === TAG_FETCH_URL);
  });

  it('shows a disabled loading placeholder in the Remove submenu while tags load', async () => {
    // Leaving the GET unflushed pins the resource in its loading state.
    const { fixture, comp } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();

    const removeParent = findItem(comp, 'menu.action.tag.remove_named');
    expect(removeParent?.items).toHaveLength(1);
    expect(removeParent?.items?.[0].disabled).toBe(true);
    expect(removeParent?.items?.[0].label).toBe('common.loading');
  });

  it('omits the Remove submenu entirely when the message is in no tags', async () => {
    const { fixture, comp, httpMock } = setup({
      isLoggedIn: true,
      isEditor: true,
      canEditSeries: true,
    });
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, []);
    await settle();

    expect(findItem(comp, 'menu.action.tag.remove_named')).toBeUndefined();
  });

  it('refetches this message tags each time the menu opens (fresh Remove-from rows)', async () => {
    const { fixture, comp, httpMock } = setup(
      { isLoggedIn: true, isEditor: true, canEditSeries: true },
      [mkTag('t1', 'Alpha')],
    );

    // The trigger's (opened) handler bumps the fetch tick (CDK owns the open).
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, [mkTag('t1', 'Alpha')]);
    await settle();

    // A second open bumps the tick, recomputing the request into a fresh object and forcing
    // another GET even though the URL is unchanged — so the "Remove from…" rows stay fresh.
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    httpMock.expectOne((r) => r.method === 'GET' && r.url === TAG_FETCH_URL);
  });

  it('anonymous user never gets tag items rendered', () => {
    const { fixture, comp } = setup({ isLoggedIn: false, isEditor: false, canEditSeries: false }, [
      mkTag('t1', 'Alpha'),
    ]);

    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    TestBed.tick();

    const ls = labels(comp);
    expect(ls).not.toContain('menu.action.tag.add');
    expect(ls).not.toContain('menu.action.tag.remove_named');
  });
});

describe('MessageDetailsMenu edit / copy navigation (in-component)', () => {
  it('edit navigates into the editor by message id', () => {
    const { comp } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const editItem = findItem(comp, 'menu.action.edit');
    editItem?.command?.({ originalEvent: new MouseEvent('click'), item: editItem });
    expect(navigate).toHaveBeenCalledWith(['/editor/edit', 'NM-1-26']);
  });

  it('copy routes through the copy dialog and navigates its resolved copy route', () => {
    const { comp, copyDialog } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const copyItem = findItem(comp, 'menu.action.copy');
    copyItem?.command?.({ originalEvent: new MouseEvent('click'), item: copyItem });

    expect(copyDialog.open).toHaveBeenCalledWith('NM-1-26', 'NM');
    expect(navigate).toHaveBeenCalledWith(['/editor/copy', 'NM-1-26']);
  });

  it('add-to-tag opens the tag picker for this message (no direct service call)', () => {
    const { fixture, comp, addMessagesToTag } = setup({
      isLoggedIn: true,
      isEditor: true,
      canEditSeries: true,
    });
    const picker = fixture.debugElement.query(By.directive(TagPicker))
      .componentInstance as TagPicker;
    const openSpy = vi.spyOn(picker, 'open').mockImplementation(() => undefined);

    // The picker anchors at the rendered trigger button (re-sourced from the
    // trigger's element, not a click event, since the menu row is gone on close).
    const triggerBtn = fixture.nativeElement.querySelector(
      'app-menu-trigger button',
    ) as HTMLButtonElement;
    const tagItem = findItem(comp, 'menu.action.tag.add');
    tagItem?.command?.({ originalEvent: new MouseEvent('click'), item: tagItem });
    expect(openSpy).toHaveBeenCalledWith(triggerBtn, {
      messageIds: ['NM-1-26'],
      displayId: undefined,
    });
    expect(addMessagesToTag).not.toHaveBeenCalled();
  });

  it('print calls MessagePrintService.printMessage directly', () => {
    TestBed.resetTestingModule();
    const printMessage = vi.fn(() => of(undefined));
    const printSvc = { printMessage };
    // Re-set up minimal scaffolding similar to the menu spec setup but with a
    // captured printService stub so we can assert the command routes through.
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestTransloco(),
        provideFakeAuthFacade({ isAuthenticated: true, editor: true }),
        provideFakeDomainFacade({ canEditSeries: true }),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        { provide: TagsApiService, useValue: { tagsForMessage: vi.fn(() => of([])) } },
        {
          provide: MessageTagActionsService,
          useValue: { addMessagesToTag: vi.fn() },
        },
        { provide: MessagePrintService, useValue: printSvc },
        { provide: TAG_SELECTION_STORE, useValue: { getLastSelection: () => signal(undefined) } },
        provideRouter([]),
      ],
    });
    const fixture = TestBed.createComponent(MessageDetailsMenu);
    fixture.componentRef.setInput('messageId', 'NM-99');
    fixture.componentRef.setInput('message', { id: 'NM-99', messageSeries: { seriesId: 's1' } });
    fixture.detectChanges();
    const printItem = fixture.componentInstance
      .items()
      .find((i) => i.label === 'messages.detailsMenu.print');
    printItem?.command?.({ originalEvent: new MouseEvent('click'), item: printItem });
    expect(printMessage).toHaveBeenCalledWith('NM-99');
  });
});

describe('MessageDetailsMenu last-tag routing', () => {
  it('add-to-last-tag is hidden when no last selection is stored', () => {
    const { comp } = setup({ isLoggedIn: true, isEditor: true, canEditSeries: true });
    const labelsList = labels(comp);
    expect(labelsList).not.toContain('menu.action.tag.add_named');
  });

  it('add-to-last-tag is visible when a last selection is stored AND not already in the message', async () => {
    const { fixture, comp, httpMock, addMessagesToLastSelectedTag } = setup(
      { isLoggedIn: true, isEditor: true, canEditSeries: true },
      { lastTag: mkTag('last', 'Last') },
    );
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, []);
    await settle();

    const item = findItem(comp, 'menu.action.tag.add_named');
    expect(item).toBeDefined();

    item?.command?.({ originalEvent: new MouseEvent('click'), item });
    expect(addMessagesToLastSelectedTag).toHaveBeenCalledWith(['NM-1-26']);
  });

  it('add-to-last-tag is hidden when the message already has the last-selected tag', async () => {
    const last = mkTag('t42', 'Gamma');
    const { fixture, comp, httpMock } = setup(
      { isLoggedIn: true, isEditor: true, canEditSeries: true },
      { lastTag: last },
    );
    (comp as unknown as { onOpened: () => void }).onOpened();
    fixture.detectChanges();
    await settle();
    flushTagFetch(httpMock, [last]);
    await settle();

    expect(findItem(comp, 'menu.action.tag.add_named')).toBeUndefined();
  });
});
