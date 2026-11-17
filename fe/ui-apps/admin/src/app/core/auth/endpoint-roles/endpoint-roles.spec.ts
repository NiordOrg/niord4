/* eslint-disable no-restricted-imports -- this spec renders the gated components of every feature
   against the backend's endpoint table: a test of the whole application that sits beside the auth
   code it exercises, not core code depending on a feature. */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, type EnvironmentProviders, type Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { TagPicker } from '../../../features/messages/components/tag-picker/tag-picker';
import { MessageDetailsMenu } from '../../../features/messages/message-details-menu/message-details-menu';
import { MessagesActionMenu } from '../../../features/messages/messages-action-menu/messages-action-menu';
import { MessageCopyDialogService } from '../../../features/messages/dialogs/message-copy-dialog/message-copy-dialog.service';
import { MessageTagsDialogService } from '../../../features/messages/dialogs/message-tags-dialog/message-tags-dialog.service';
import type { Message } from '../../../features/messages/models/message.model';
import { TagsApiService } from '../../../features/messages/services/api/tags-api.service';
import { MessageExportService } from '../../../features/messages/services/message-export.service';
import { MessageListFacade } from '../../../features/messages/services/message-list.facade';
import { MessageMailService } from '../../../features/messages/services/message-mail.service';
import { MessagePrintService } from '../../../features/messages/services/message-print.service';
import { MessageSelectionService } from '../../../features/messages/services/message-selection.service';
import { MessageTagActionsService } from '../../../features/messages/services/message-tag-actions.service';
import { MessagesFilterFacade } from '../../../features/messages/services/messages-filter.facade';
import type { MenuItem } from '../../../shared/models/menu-item.model';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { APP_CONFIG } from '../../config/app-config.token';
import { TAG_SELECTION_STORE } from '../../domain/services/tag-selection-store.token';
import { MenuService } from '../../main-shell/menu.service';
import { NotificationService } from '../../notifications/notification.service';
import { UserField } from '../components/user-field/user-field';
import { AuthService } from '../services/auth.service';
import { IDENTITIES, provideIdentity, SERIES_OF, type IdentityName } from './fake-auth-service';
import offers from './role-offers.json';

/**
 * What each gated component of the administration app shows each identity of the backend's
 * endpoint table, held against `role-offers.json`.
 *
 * Each component is rendered once per identity with the real `AuthService` and `AuthFacade`
 * over that identity's token claims (`fake-auth-service.ts`), in the state that shows the most
 * items (a selection of two messages, a message of the session domain, a remembered tag). The
 * item keys it shows (the label keys: the test translations answer each key with itself) are
 * recorded per identity and must equal the `shownTo` of `role-offers.json`: an item shown that the
 * file does not name fails, so a new action, however gated, has to be mapped to the endpoints it
 * calls. The endpoint-role guard (`fe/scripts/endpoint-roles.test.mjs`) then holds every identity
 * an item is shown to against the backend's answer for each of those endpoints.
 */

type Shown = Record<string, IdentityName[]>;

const APP_CONFIG_STUB = { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} };

/** The providers every rendering shares: the identity, HTTP testing, translations, a router. */
function base(identity: IdentityName): (Provider | EnvironmentProviders)[] {
  return [
    ...provideIdentity(identity),
    { provide: APP_CONFIG, useValue: APP_CONFIG_STUB },
    {
      provide: NotificationService,
      useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
    },
    provideHttpClient(),
    provideHttpClientTesting(),
    provideTestTransloco(),
    provideRouter([]),
  ];
}

async function signIn(
  identity: IdentityName,
  providers: (Provider | EnvironmentProviders)[],
): Promise<void> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...base(identity), ...providers] });
  await TestBed.inject(AuthService).init();
}

async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

/** The label keys of a menu model, separators left out (a submenu's own label included). */
function keys(items: readonly MenuItem[]): string[] {
  return items.filter((i) => !i.separator && i.label).map((i) => i.label as string);
}

const MESSAGE = {
  id: 'sweep-frontend-message',
  shortId: 'NW-001-26',
  mainType: 'NW',
  type: 'LOCAL_WARNING',
  status: 'PUBLISHED',
  messageSeries: { seriesId: SERIES_OF['niord-nw'], mainType: 'NW' },
} as unknown as Message;

const noop = () => of(undefined);

/** The tag filter panel's picker, as `tag-filter-panel.html` hosts it (filter mode, Manage on). */
@Component({
  imports: [TagPicker],
  template: `<app-tag-picker mode="filter" [selectedIds]="[]" />`,
})
class TagFilterPickerHost {}

/** Each rendered component: its key in role-offers.json and the item keys it shows. */
const RENDERERS: Record<string, (identity: IdentityName) => Promise<string[]>> = {
  /** The main navigation: one item per area the identity may open. */
  MenuService: async (identity) => {
    await signIn(identity, []);
    return keys(TestBed.inject(MenuService).menuItems());
  },

  /** The messages list's Action menu, with two messages selected. */
  MessagesActionMenu: async (identity) => {
    await signIn(identity, [
      {
        provide: MessageSelectionService,
        useValue: {
          selectedCount: () => 2,
          selectedIds: () => ['m-1', 'm-2'],
          selectedMessages: () => [],
          selectLoaded: vi.fn(),
          clear: vi.fn(),
        },
      },
      {
        provide: MessagesFilterFacade,
        useValue: {
          sortBy: () => 'AREA',
          sortOrder: () => 'ASC',
          requestParams: () => '',
          effectiveStatusFilter: () => [],
          toggleSort: vi.fn(),
        },
      },
      { provide: MessageListFacade, useValue: { messages: () => [], total: () => 2 } },
      {
        provide: MessageTagActionsService,
        useValue: { addMessagesToTag: noop, removeMessagesFromTag: noop },
      },
      {
        provide: TagsApiService,
        useValue: { searchTags: () => of([]), tagsForMessage: () => of([]) },
      },
      {
        provide: MessagePrintService,
        useValue: { printList: noop, printSelection: noop, printMessage: noop },
      },
      { provide: MessageMailService, useValue: { mailSelection: noop } },
      { provide: MessageExportService, useValue: { export: noop, exportSelection: noop } },
    ]);
    const fixture = TestBed.createComponent(MessagesActionMenu);
    await settle();
    const shown = keys(fixture.componentInstance.items());
    fixture.destroy();
    return shown;
  },

  /**
   * A message's cog menu, on a message of the session domain's series, with a tag remembered
   * and the message in another tag (so "add to <last tag>" and "remove from" both show).
   */
  MessageDetailsMenu: async (identity) => {
    await signIn(identity, [
      {
        provide: MessageTagActionsService,
        useValue: { addMessagesToLastSelectedTag: noop, removeMessagesFromTag: noop },
      },
      { provide: MessagePrintService, useValue: { printMessage: noop } },
      { provide: MessageCopyDialogService, useValue: { open: () => of(undefined) } },
      {
        provide: TAG_SELECTION_STORE,
        useValue: {
          getLastSelection: () => () => ({ tagId: 'sweep-frontend-tag-1', name: 'last' }),
          setLastSelection: vi.fn(),
          clearLastSelection: vi.fn(),
        },
      },
    ]);
    const fixture = TestBed.createComponent(MessageDetailsMenu);
    fixture.componentRef.setInput('messageId', MESSAGE.id);
    fixture.componentRef.setInput('message', MESSAGE);
    await settle();
    (fixture.componentInstance as unknown as { onOpened(): void }).onOpened();
    await settle();
    for (const req of TestBed.inject(HttpTestingController).match(() => true)) {
      req.flush([{ tagId: 'sweep-frontend-tag-2', name: 'sweep-frontend-tag-2' }]);
    }
    await settle();
    const shown = keys(fixture.componentInstance.items());
    fixture.destroy();
    return shown;
  },

  /**
   * The tag picker of the Tag filter, which anyone opens: its footer's Manage link (the label
   * key `messages.tagPicker.manage`) opens the tag manager. The footer renders the link when
   * `canManage()` is true, so that is read rather than opening the popover.
   */
  TagPicker: async (identity) => {
    await signIn(identity, [
      { provide: TagsApiService, useValue: { searchTags: () => of([]) } },
      { provide: MessageTagActionsService, useValue: {} },
      {
        provide: TAG_SELECTION_STORE,
        useValue: {
          getLastSelection: () => signal(undefined),
          saveLastSelection: vi.fn(),
          clearLastSelection: vi.fn(),
        },
      },
      { provide: MessageTagsDialogService, useValue: { open: noop, visible: signal(false) } },
    ]);
    const fixture = TestBed.createComponent(TagFilterPickerHost);
    await settle();
    const picker = fixture.debugElement.query(By.directive(TagPicker))
      .componentInstance as unknown as { canManage(): boolean };
    const shown = picker.canManage() ? ['messages.tagPicker.manage'] : [];
    fixture.destroy();
    return shown;
  },

  /**
   * The user field of the messages' User filter (the filter menu offers the filter to every
   * signed-in account): the field searches the user directory while its search is enabled. Its
   * item is its placeholder key, shown when the search is not disabled (Me, which calls nothing,
   * is offered apart).
   */
  UserField: async (identity) => {
    await signIn(identity, []);
    const fixture = TestBed.createComponent(UserField);
    await settle();
    const field = fixture.componentInstance;
    const shown = field.searchDisabled() ? [] : [field.placeholder()];
    fixture.destroy();
    return shown;
  },
};

/** For each item a component shows to anyone, the identities it is shown to, in table order. */
async function record(component: string): Promise<Shown> {
  const shown: Shown = {};
  for (const identity of IDENTITIES) {
    for (const item of await RENDERERS[component](identity)) {
      (shown[item] ??= []).push(identity);
    }
  }
  return shown;
}

/** The file's view of a component: item → shownTo. */
function expected(component: string): Shown {
  const def = (
    offers.components as Record<string, { items: Record<string, { shownTo: string[] }> }>
  )[component];
  return Object.fromEntries(
    Object.entries(def?.items ?? {}).map(([item, spec]) => [item, spec.shownTo as IdentityName[]]),
  );
}

describe('endpoint roles: what each identity is offered (role-offers.json)', () => {
  it('names every rendered component, and renders every component it names', () => {
    expect(Object.keys(offers.components).sort()).toEqual(Object.keys(RENDERERS).sort());
  });

  for (const component of Object.keys(RENDERERS)) {
    it(`${component} shows each identity the items role-offers.json says`, async () => {
      const shown = await record(component);
      const want = expected(component);
      // One sentence per item that differs, naming the item and both lists.
      const differences = [...new Set([...Object.keys(shown), ...Object.keys(want)])]
        .filter((item) => JSON.stringify(shown[item] ?? []) !== JSON.stringify(want[item] ?? []))
        .map(
          (item) =>
            `${component} shows "${item}" to [${(shown[item] ?? []).join(', ')}], ` +
            `role-offers.json says [${(want[item] ?? []).join(', ')}]`,
        );
      // Printed so that a new item's line can be copied into role-offers.json.
      if (differences.length) console.log(`${component} shows: ${JSON.stringify(shown)}`);
      expect(differences).toEqual([]);
      expect(shown).toEqual(want);
    });
  }
});
