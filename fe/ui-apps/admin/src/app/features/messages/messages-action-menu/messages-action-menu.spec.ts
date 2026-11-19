import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';

import type { MenuItem } from '../../../shared/models/menu-item.model';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../core/notifications/notification.service';
import { TagPicker } from '../components/tag-picker/tag-picker';
import { MessageExportService } from '../services/message-export.service';
import { MessageListFacade } from '../services/message-list.facade';
import { TagsApiService } from '../services/api/tags-api.service';
import { MessageMailService } from '../services/message-mail.service';
import { MessagePrintService } from '../services/message-print.service';
import { MessageSelectionService } from '../services/message-selection.service';
import { MessageTagActionsService } from '../services/message-tag-actions.service';
import { MessageComparisonDialogService } from '../dialogs/message-comparison-dialog/message-comparison-dialog.service';
import { MessagesActionMenu } from './messages-action-menu';
import { MessagesFilterFacade } from '../services/messages-filter.facade';
import type { Message } from '../models/message.model';
import type { SortBy, SortOrder } from '../models/messages-filter-params.model';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade } from '../../../shared/testing/fake-auth';
import { ConfirmService } from '../../../shared/services/confirm.service';
import { of } from 'rxjs';

interface FakeFilter {
  sortBy: ReturnType<typeof signal<SortBy>>;
  sortOrder: ReturnType<typeof signal<SortOrder>>;
  setSortBy: (v: SortBy) => void;
  setSortOrder: (v: SortOrder) => void;
  toggleSort: ReturnType<typeof vi.fn>;
  requestParams: () => string;
}

interface ScenarioInputs {
  isLoggedIn: boolean;
  isEditor: boolean;
  isAdmin: boolean;
  selectionSize: number;
  /** The backend's `user` role in the session domain; defaults to isLoggedIn. */
  domainUserRole?: boolean;
  /** The list on screen was answered while the search index was being rebuilt. */
  indexIncomplete?: boolean;
}

function setup(
  inputs: ScenarioInputs,
  loadedMessages: readonly Message[] = [],
): {
  filter: FakeFilter;
  selection: {
    selectedCount: ReturnType<typeof signal<number>>;
    selectedIds: ReturnType<typeof signal<readonly string[]>>;
    selectLoaded: ReturnType<typeof vi.fn>;
    clear: ReturnType<typeof vi.fn>;
  };
  component: MessagesActionMenu;
  fixture: ComponentFixture<MessagesActionMenu>;
} {
  const ids = Array.from({ length: inputs.selectionSize }, (_, i) => `m${i + 1}`);
  const selection = {
    selectedCount: signal(inputs.selectionSize),
    selectedIds: signal<readonly string[]>(ids),
    selectLoaded: vi.fn(),
    clear: vi.fn(),
  };

  const addMessagesToTag = vi.fn(() => of(undefined));
  const removeMessagesFromTag = vi.fn(() => of(undefined));
  const tagActions = { addMessagesToTag, removeMessagesFromTag };

  const sortBy = signal<SortBy>('AREA');
  const sortOrder = signal<SortOrder>('ASC');
  const filter: FakeFilter = {
    sortBy,
    sortOrder,
    setSortBy: vi.fn((v: SortBy) => sortBy.set(v)),
    setSortOrder: vi.fn((v: SortOrder) => sortOrder.set(v)),
    toggleSort: vi.fn((field: SortBy) => {
      if (sortBy() === field) {
        sortOrder.set(sortOrder() === 'ASC' ? 'DESC' : 'ASC');
      } else {
        sortBy.set(field);
        sortOrder.set('ASC');
      }
    }),
    requestParams: () => 'query=wreck',
  };

  const list = {
    messages: signal<readonly Message[]>(loadedMessages),
    indexIncomplete: signal(inputs.indexIncomplete ?? false),
  } as unknown as MessageListFacade;

  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({
        isAuthenticated: inputs.isLoggedIn,
        editor: inputs.isEditor,
        admin: inputs.isAdmin,
        domainUserRole: inputs.domainUserRole,
      }),
      { provide: MessageSelectionService, useValue: selection },
      { provide: MessagesFilterFacade, useValue: filter },
      { provide: MessageListFacade, useValue: list },
      { provide: MessageTagActionsService, useValue: tagActions },
      // The embedded TagPicker injects the tags API — stub it so the root
      // service (which needs APP_CONFIG + HttpClient) never instantiates.
      {
        provide: TagsApiService,
        useValue: { searchTags: vi.fn(() => of([])), tagsForMessage: vi.fn(() => of([])) },
      },
      {
        provide: MessagePrintService,
        useValue: {
          printList: vi.fn(() => of(undefined)),
          printSelection: vi.fn(() => of(undefined)),
          printMessage: vi.fn(() => of(undefined)),
        },
      },
      {
        provide: MessageMailService,
        useValue: { mailSelection: vi.fn(() => of(undefined)) },
      },
      {
        provide: MessageExportService,
        useValue: {
          export: vi.fn(() => of(undefined)),
          exportSelection: vi.fn(() => of(undefined)),
        },
      },
      // The embedded import/status dialogs pull in these — provide the minimum so
      // the menu (and its child dialogs) construct.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: DomainFacade, useValue: { domainMessageSeriesIds: () => [] } },
      {
        provide: NotificationService,
        useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      ConfirmService,
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });

  const fixture = TestBed.createComponent(MessagesActionMenu);
  return { filter, selection, component: fixture.componentInstance, fixture };
}

function labels(component: MessagesActionMenu): string[] {
  return component
    .items()
    .filter((i) => i.label)
    .map((i) => i.label as string);
}

function findItem(component: MessagesActionMenu, label: string): MenuItem | undefined {
  return component.items().find((i) => i.label === label);
}

describe('MessagesActionMenu permission visibility', () => {
  it('logged out, no selection: only Print, Select all, sort items', () => {
    const { component } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    const ls = labels(component);
    expect(ls).toContain('menu.action.print');
    expect(ls).toContain('menu.action.select.all');
    expect(ls).toContain('messages.shell.action.sortBy.area');
    expect(ls).not.toContain('menu.action.print.selection');
    expect(ls).not.toContain('menu.action.report');
    expect(ls).not.toContain('menu.action.mail');
    expect(ls).not.toContain('menu.action.export');
    expect(ls).not.toContain('menu.action.import');
    expect(ls).not.toContain('menu.action.sort_area');
  });

  it('logged in, zero selection: Print, mailing list, Select all, Export, sort items', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    const ls = labels(component);
    expect(ls).toContain('menu.action.report');
    expect(ls).toContain('menu.action.export');
    expect(ls).not.toContain('menu.action.print.selection');
    expect(ls).not.toContain('menu.action.export_selection');
    expect(ls).not.toContain('menu.action.tag.add_selection');
    expect(ls).not.toContain('menu.action.import');
  });

  it('logged in, selection 1: includes selection-gated items but no compare and no Mail', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 1,
    });
    const ls = labels(component);
    expect(ls).toContain('menu.action.print.selection');
    // Sending the mail asks for the editor role on the backend.
    expect(ls).not.toContain('menu.action.mail');
    expect(ls).toContain('menu.action.tag.add_selection');
    expect(ls).toContain('menu.action.select.none');
    expect(ls).toContain('menu.action.export_selection');
    expect(ls).not.toContain('menu.action.message.compare');
    expect(ls).not.toContain('menu.action.update_status');
  });

  it('editor, selection 1: Mail is offered', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: true,
      isAdmin: false,
      selectionSize: 1,
    });
    const item = findItem(component, 'menu.action.mail');
    expect(item).toBeDefined();
    expect(typeof item?.command).toBe('function');
  });

  it('editor without the user role in the domain: no Mail, whose temporary tag would be refused', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: true,
      isAdmin: false,
      selectionSize: 1,
      domainUserRole: false,
    });
    expect(labels(component)).not.toContain('menu.action.mail');
  });

  it('signed in without a role in the domain: no Print selection, Report, tag actions or Export selection', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
      domainUserRole: false,
    });
    const ls = labels(component);
    expect(ls).not.toContain('menu.action.print.selection');
    expect(ls).not.toContain('menu.action.report');
    expect(ls).not.toContain('menu.action.mail');
    expect(ls).not.toContain('menu.action.tag.add_selection');
    expect(ls).not.toContain('menu.action.tag.remove_selection');
    expect(ls).not.toContain('messages.shell.action.tagManage');
    expect(ls).not.toContain('menu.action.export_selection');
    // What the backend serves to any caller stays.
    expect(ls).toContain('menu.action.print');
    expect(ls).toContain('menu.action.export');
    expect(ls).toContain('menu.action.message.compare');
  });

  it('a role in the domain offers the tag manager and the report with no selection', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    const ls = labels(component);
    expect(ls).toContain('messages.shell.action.tagManage');
    expect(ls).toContain('menu.action.report');
  });

  it('a role in the domain offers My mails, with or without a selection, and it opens the dialog', () => {
    const { component, fixture } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    fixture.detectChanges();
    const item = findItem(component, 'menu.action.my_mails');
    expect(item).toBeDefined();
    item?.command?.({ item });
    fixture.detectChanges();
    // The dialog reads the user's own mails as it opens.
    TestBed.inject(HttpTestingController).expectOne('/rest/scheduled-mails/mine?days=7').flush([]);
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('signed in without a role in the domain, or signed out: no My mails, whose read would be refused', () => {
    expect(
      labels(
        setup({
          isLoggedIn: true,
          isEditor: true,
          isAdmin: false,
          selectionSize: 1,
          domainUserRole: false,
        }).component,
      ),
    ).not.toContain('menu.action.my_mails');
    TestBed.resetTestingModule();
    expect(
      labels(
        setup({ isLoggedIn: false, isEditor: false, isAdmin: false, selectionSize: 0 }).component,
      ),
    ).not.toContain('menu.action.my_mails');
  });

  it('logged in, selection 2: Compare is enabled with a command handler', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });
    const item = findItem(component, 'menu.action.message.compare');
    expect(item?.disabled).toBeFalsy();
    expect(typeof item?.command).toBe('function');
  });

  it('clicking Compare opens the comparison dialog with both selected ids in order', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });
    const comparison = TestBed.inject(MessageComparisonDialogService);
    const openSpy = vi.spyOn(comparison, 'open').mockReturnValue(of(undefined));

    const item = findItem(component, 'menu.action.message.compare');
    item?.command?.({ originalEvent: new MouseEvent('click'), item });

    expect(openSpy).toHaveBeenCalledWith('m1', 'm2');
  });

  it('editor, selection 1: includes Update status and Sort area', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: true,
      isAdmin: false,
      selectionSize: 1,
    });
    const ls = labels(component);
    expect(ls).toContain('menu.action.update_status');
    expect(ls).toContain('menu.action.sort_area');
  });

  it('admin, zero selection: includes Import', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: true,
      selectionSize: 0,
    });
    expect(labels(component)).toContain('menu.action.import');
  });

  it('sort-by-field items are enabled and have command handlers', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: true,
      isAdmin: true,
      selectionSize: 0,
    });
    const sortItems = component
      .items()
      .filter((i) =>
        [
          'messages.shell.action.sortBy.area',
          'messages.shell.action.sortBy.id',
          'messages.shell.action.sortBy.date',
          'messages.shell.action.sortBy.publishDate',
          'messages.shell.action.sortBy.followUpDate',
        ].includes(i.label as string),
      );
    expect(sortItems.length).toBe(5);
    expect(sortItems.every((i) => !i.disabled && typeof i.command === 'function')).toBe(true);
  });

  it('sortArea is a live, enabled action for editors', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: true,
      isAdmin: false,
      selectionSize: 0,
    });
    const sortAreaItem = findItem(component, 'menu.action.sort_area');
    expect(sortAreaItem).toBeDefined();
    expect(sortAreaItem?.disabled).toBeFalsy();
    expect(typeof sortAreaItem?.command).toBe('function');
  });

  it('sortArea is hidden for non-editors', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    expect(findItem(component, 'menu.action.sort_area')).toBeUndefined();
  });
});

describe('MessagesActionMenu sort behavior', () => {
  it('clicking sort-by-id delegates to filter.toggleSort with ID', () => {
    const { component, filter } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });

    filter.sortBy.set('AREA');
    filter.sortOrder.set('DESC');

    const item = findItem(component, 'messages.shell.action.sortBy.id');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });

    expect(filter.toggleSort).toHaveBeenCalledWith('ID');
    expect(filter.sortBy()).toBe('ID');
    expect(filter.sortOrder()).toBe('ASC');
  });

  it('clicking sort-by-area with same field flips ASC to DESC', () => {
    const { component, filter } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });

    filter.sortBy.set('AREA');
    filter.sortOrder.set('ASC');

    const item = findItem(component, 'messages.shell.action.sortBy.area');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });

    expect(filter.toggleSort).toHaveBeenCalledWith('AREA');
    expect(filter.sortBy()).toBe('AREA');
    expect(filter.sortOrder()).toBe('DESC');
  });

  it('clicking sort-by-area with same field flips DESC to ASC', () => {
    const { component, filter } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });

    filter.sortBy.set('AREA');
    filter.sortOrder.set('DESC');

    const item = findItem(component, 'messages.shell.action.sortBy.area');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });

    expect(filter.toggleSort).toHaveBeenCalledWith('AREA');
    expect(filter.sortOrder()).toBe('ASC');
  });
});

describe('MessagesActionMenu selection actions', () => {
  it('Select all is enabled and has a command handler', () => {
    const { component } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    const item = findItem(component, 'menu.action.select.all');
    expect(item?.disabled).toBeFalsy();
    expect(typeof item?.command).toBe('function');
  });

  it('Clear selection is hidden when selection is empty', () => {
    const { component } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 0,
    });
    expect(findItem(component, 'menu.action.select.none')).toBeUndefined();
  });

  it('Clear selection is enabled and has a command handler when selection > 0', () => {
    const { component } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 1,
    });
    const item = findItem(component, 'menu.action.select.none');
    expect(item?.disabled).toBeFalsy();
    expect(typeof item?.command).toBe('function');
  });

  it('clicking Select all calls selection.selectLoaded with the loaded messages', () => {
    const loaded: Message[] = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    const { component, selection } = setup(
      { isLoggedIn: false, isEditor: false, isAdmin: false, selectionSize: 0 },
      loaded,
    );

    const item = findItem(component, 'menu.action.select.all');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });

    expect(selection.selectLoaded).toHaveBeenCalledTimes(1);
    expect(selection.selectLoaded.mock.calls[0][0]).toEqual(loaded);
  });

  it('clicking Clear selection calls selection.clear', () => {
    const { component, selection } = setup({
      isLoggedIn: false,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });

    const item = findItem(component, 'menu.action.select.none');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });

    expect(selection.clear).toHaveBeenCalledTimes(1);
  });
});

describe('MessagesActionMenu bulk tag membership', () => {
  it('tagAdd and tagRemove are both present and enabled for any logged-in user with a selection', () => {
    const { component } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });
    for (const label of ['menu.action.tag.add_selection', 'menu.action.tag.remove_selection']) {
      const item = findItem(component, label);
      expect(item?.disabled, label).toBeFalsy();
      expect(typeof item?.command, label).toBe('function');
    }
  });

  it('clicking tagRemove opens the picker narrowed to the tags the selection is in', () => {
    const { component, fixture } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });
    fixture.detectChanges();
    const picker = fixture.debugElement.query(By.directive(TagPicker))
      .componentInstance as TagPicker;
    const openSpy = vi.spyOn(picker, 'open').mockImplementation(() => undefined);

    // The picker anchors at the rendered Action trigger button (re-sourced from
    // the trigger element, since the menu row is gone once the menu closes).
    const triggerBtn = fixture.nativeElement.querySelector(
      'app-menu-trigger button',
    ) as HTMLButtonElement;
    const item = findItem(component, 'menu.action.tag.remove_selection');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });
    expect(openSpy).toHaveBeenCalledWith(triggerBtn, {
      messageIds: ['m1', 'm2'],
      membershipOnly: true,
    });
  });

  it('clicking tagAdd opens the tag picker anchored at the Action trigger with selectedIds', () => {
    const { component, fixture } = setup({
      isLoggedIn: true,
      isEditor: false,
      isAdmin: false,
      selectionSize: 2,
    });
    fixture.detectChanges();
    const picker = fixture.debugElement.query(By.directive(TagPicker))
      .componentInstance as TagPicker;
    const openSpy = vi.spyOn(picker, 'open').mockImplementation(() => undefined);

    const triggerBtn = fixture.nativeElement.querySelector(
      'app-menu-trigger button',
    ) as HTMLButtonElement;
    const item = findItem(component, 'menu.action.tag.add_selection');
    item?.command?.({ originalEvent: new MouseEvent('click'), item: item });
    expect(openSpy).toHaveBeenCalledWith(triggerBtn, { messageIds: ['m1', 'm2'] });
  });
});

describe('MessagesActionMenu while the search index is being rebuilt', () => {
  const editor = { isLoggedIn: true, isEditor: true, isAdmin: false, selectionSize: 2 };

  function run(component: MessagesActionMenu, label: string): void {
    const item = findItem(component, label);
    item?.command?.({ originalEvent: new MouseEvent('click'), item });
  }

  it('exports at once when the list is complete', () => {
    const { component } = setup(editor);
    const exports = TestBed.inject(MessageExportService);

    run(component, 'menu.action.export');
    run(component, 'menu.action.export_selection');

    expect(TestBed.inject(ConfirmService).active()).toBeUndefined();
    expect(exports.export).toHaveBeenCalledTimes(1);
    expect(exports.exportSelection).toHaveBeenCalledTimes(1);
  });

  it("shows the list's warning before the export, and exports only when it is confirmed", () => {
    const { component } = setup({ ...editor, indexIncomplete: true });
    const exports = TestBed.inject(MessageExportService);
    const confirm = TestBed.inject(ConfirmService);

    run(component, 'menu.action.export');

    expect(exports.export).not.toHaveBeenCalled();
    const asked = confirm.active();
    expect(asked?.message).toBe('messages.list.indexIncomplete');
    expect(asked?.acceptLabel).toBe('menu.action.export');
    asked?.accept();
    expect(exports.export).toHaveBeenCalledWith({ filterParams: 'query=wreck' });
  });

  it("shows the list's warning before the export of the selection too", () => {
    const { component } = setup({ ...editor, indexIncomplete: true });
    const exports = TestBed.inject(MessageExportService);
    const confirm = TestBed.inject(ConfirmService);

    run(component, 'menu.action.export_selection');

    expect(exports.exportSelection).not.toHaveBeenCalled();
    expect(confirm.active()?.message).toBe('messages.list.indexIncomplete');
    confirm.active()?.accept();
    expect(exports.exportSelection).toHaveBeenCalledWith(['m1', 'm2']);
  });

  it("hands the list's state to the mail dialog, which shows the warning before the mail is sent", () => {
    const mails = () => TestBed.inject(MessageMailService);

    run(setup({ ...editor, indexIncomplete: true }).component, 'menu.action.mail');
    expect(mails().mailSelection).toHaveBeenCalledWith(['m1', 'm2'], true);
    TestBed.resetTestingModule();

    run(setup(editor).component, 'menu.action.mail');
    expect(mails().mailSelection).toHaveBeenCalledWith(['m1', 'm2'], false);
  });
});
