import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { provideFakeMessageTagActions } from '../../testing/fake-message-tag-actions';
import { provideFakeMessageEditShortcut } from '../../testing/fake-message-edit-shortcut';
import type { Message } from '../../models/message.model';
import type { SortBy, SortOrder } from '../../models/messages-filter-params.model';
import { MessagesTableView } from './messages-table-view';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  provideFakeAuthFacade,
  provideFakeDomainFacade,
} from '../../../../shared/testing/fake-auth';
import { afterFirstPaint } from '../../../../shared/testing/paint-testing';

interface FakeFacade {
  filter: {
    sortBy: ReturnType<typeof signal<SortBy>>;
    sortOrder: ReturnType<typeof signal<SortOrder>>;
    toggleSort: ReturnType<typeof vi.fn>;
    setSortBy: ReturnType<typeof vi.fn>;
  };
  list: {
    messages: ReturnType<typeof signal<readonly Message[]>>;
    total: ReturnType<typeof signal<number>>;
    loading: ReturnType<typeof signal<boolean>>;
    hasMore: ReturnType<typeof signal<boolean>>;
    error: ReturnType<typeof signal<unknown>>;
    groupedMessages: ReturnType<
      typeof signal<readonly { message: Message; areaHeading?: unknown }[]>
    >;
    loadMore: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
  };
  selection: {
    isEmpty: ReturnType<typeof signal<boolean>>;
    isSelected: (id: string) => boolean;
    toggleAllLoaded: ReturnType<typeof vi.fn>;
  };
  shell: {
    maxPageSize: ReturnType<typeof signal<number>>;
  };
}

function makeFacade(): FakeFacade {
  const sortBy = signal<SortBy>('AREA');
  return {
    filter: {
      sortBy,
      sortOrder: signal<SortOrder>('ASC'),
      toggleSort: vi.fn(),
      setSortBy: vi.fn((value: SortBy) => sortBy.set(value)),
    },
    list: {
      messages: signal<readonly Message[]>([]),
      total: signal(0),
      loading: signal(false),
      hasMore: signal(false),
      error: signal(null as unknown),
      groupedMessages: signal<readonly { message: Message; areaHeading?: unknown }[]>([]),
      loadMore: vi.fn(),
      retry: vi.fn(),
    },
    selection: {
      isEmpty: signal(true),
      isSelected: () => false,
      toggleAllLoaded: vi.fn(),
    },
    shell: {
      maxPageSize: signal(100),
    },
  };
}

function stubMatchMedia(): void {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => void 0,
      removeListener: () => void 0,
      addEventListener: () => void 0,
      removeEventListener: () => void 0,
      dispatchEvent: () => false,
    }),
  });
}

function setup(facade: FakeFacade) {
  const dialogOpen = vi.fn();

  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: () => 'en', activeLocale: () => 'en-GB' },
      },
      {
        provide: NotificationService,
        useValue: {
          success: vi.fn(),
          info: vi.fn(),
          warn: vi.fn(),
          error: vi.fn(),
          notify: vi.fn(),
        },
      },
      { provide: MessagesFacade, useValue: facade },
      provideFakeAuthFacade(),
      provideFakeDomainFacade(),
      { provide: MessageDetailsDialogService, useValue: { open: dialogOpen } },
      provideFakeMessageTagActions(),
      provideFakeMessageEditShortcut(),
    ],
  });

  const fixture = TestBed.createComponent(MessagesTableView);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, dialogOpen };
}

describe('MessagesTableView', () => {
  beforeEach(() => {
    localStorage.clear();
    stubMatchMedia();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('dateColumnType initializes to DATE when localStorage is missing', () => {
    const facade = makeFacade();
    const { component } = setup(facade);
    expect(component.dateColumnType()).toBe('DATE');
  });

  it('dateColumnType initializes from a valid localStorage value', () => {
    localStorage.setItem('dateColumnType', 'PUBLISH_DATE');
    const facade = makeFacade();
    const { component } = setup(facade);
    expect(component.dateColumnType()).toBe('PUBLISH_DATE');
  });

  it('dateColumnType falls back to DATE for invalid localStorage values', () => {
    localStorage.setItem('dateColumnType', 'NOT_A_TYPE');
    const facade = makeFacade();
    const { component } = setup(facade);
    expect(component.dateColumnType()).toBe('DATE');
  });

  it('onDateTypeChange writes the new type to localStorage', () => {
    const facade = makeFacade();
    const { component } = setup(facade);
    component.onDateTypeChange('FOLLOW_UP_DATE');
    expect(component.dateColumnType()).toBe('FOLLOW_UP_DATE');
    expect(localStorage.getItem('dateColumnType')).toBe('FOLLOW_UP_DATE');
  });

  it('onSortById calls filter.toggleSort with "ID"', () => {
    const facade = makeFacade();
    const { component } = setup(facade);
    component.onSortById();
    expect(facade.filter.toggleSort).toHaveBeenCalledWith('ID');
  });

  it('onSortByArea calls filter.toggleSort with "AREA"', () => {
    const facade = makeFacade();
    const { component } = setup(facade);
    component.onSortByArea();
    expect(facade.filter.toggleSort).toHaveBeenCalledWith('AREA');
  });

  it('onSortByDateColumn calls filter.toggleSort with the current date column type', () => {
    const facade = makeFacade();
    const { component } = setup(facade);
    component.onDateTypeChange('PUBLISH_DATE');
    component.onSortByDateColumn();
    expect(facade.filter.toggleSort).toHaveBeenCalledWith('PUBLISH_DATE');
  });

  it('dateColumnType syncs to a date-type sort field chosen elsewhere (e.g. action menu)', () => {
    const facade = makeFacade();
    const { component, fixture } = setup(facade);
    expect(component.dateColumnType()).toBe('DATE');

    facade.filter.sortBy.set('FOLLOW_UP_DATE');
    fixture.detectChanges();

    expect(component.dateColumnType()).toBe('FOLLOW_UP_DATE');
    expect(localStorage.getItem('dateColumnType')).toBe('FOLLOW_UP_DATE');
  });

  it('dateColumnType is unchanged when sortBy switches to a non-date field', () => {
    const facade = makeFacade();
    const { component, fixture } = setup(facade);
    component.onDateTypeChange('PUBLISH_DATE');

    facade.filter.sortBy.set('ID');
    fixture.detectChanges();

    expect(component.dateColumnType()).toBe('PUBLISH_DATE');
  });

  it('onDateTypeChange wins over a date-type sort already in effect', () => {
    const facade = makeFacade();
    facade.filter.sortBy.set('PUBLISH_DATE');
    const { component, fixture } = setup(facade);
    expect(component.dateColumnType()).toBe('PUBLISH_DATE');

    component.onDateTypeChange('DATE');
    fixture.detectChanges();

    expect(component.dateColumnType()).toBe('DATE');
  });

  it('onDateTypeChange retargets an active date-type sort to the newly picked field', () => {
    const facade = makeFacade();
    facade.filter.sortBy.set('PUBLISH_DATE');
    const { component } = setup(facade);

    component.onDateTypeChange('FOLLOW_UP_DATE');

    expect(facade.filter.setSortBy).toHaveBeenCalledWith('FOLLOW_UP_DATE');
    expect(facade.filter.sortBy()).toBe('FOLLOW_UP_DATE');
  });

  it('onDateTypeChange does not change sort when sortBy is a non-date field', () => {
    const facade = makeFacade();
    facade.filter.sortBy.set('AREA');
    const { component } = setup(facade);

    component.onDateTypeChange('PUBLISH_DATE');

    expect(facade.filter.setSortBy).not.toHaveBeenCalled();
    expect(facade.filter.sortBy()).toBe('AREA');
  });

  it('onToggleSelectAll calls selection.toggleAllLoaded with the current messages', () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }, { id: 'b' }]);
    const { component } = setup(facade);
    component.onToggleSelectAll();
    expect(facade.selection.toggleAllLoaded).toHaveBeenCalledWith([{ id: 'a' }, { id: 'b' }]);
  });

  it('renders area-heading row only for entries with an areaHeading', async () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }, { id: 'b' }]);
    facade.list.groupedMessages.set([
      { message: { id: 'a' }, areaHeading: { id: 1 } },
      { message: { id: 'b' } },
    ]);
    const { fixture } = setup(facade);
    await afterFirstPaint(fixture);
    const headings = fixture.nativeElement.querySelectorAll('app-message-area-heading');
    expect(headings.length).toBe(1);
  });

  it('clicking a body row calls dialog.open with the message id and the current id snapshot', async () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }, { id: 'b' }]);
    facade.list.groupedMessages.set([{ message: { id: 'a' } }, { message: { id: 'b' } }]);
    const { fixture, dialogOpen } = setup(facade);
    await afterFirstPaint(fixture);

    const bodyRows = fixture.nativeElement.querySelectorAll('tr.row-interactive');
    expect(bodyRows.length).toBe(2);
    (bodyRows[1] as HTMLElement).click();

    expect(dialogOpen).toHaveBeenCalledTimes(1);
    expect(dialogOpen).toHaveBeenCalledWith('b', ['a', 'b']);
  });

  /**
   * The row leads to an address — the dialog's `?messageId=` on the current
   * search — so the title is a real link. Without it, ctrl-click and middle
   * click could only ever open the row in the tab it was already in.
   */
  it('gives the title an href addressing the message the row opens', async () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }]);
    facade.list.groupedMessages.set([{ message: { id: 'a' } }]);
    const { fixture } = setup(facade);
    await afterFirstPaint(fixture);

    const link = fixture.nativeElement.querySelector(
      'tr.row-interactive a.row-link',
    ) as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toContain('messageId=a');
  });

  it('clicking the title link opens the dialog once, and navigates nothing on its own', async () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }, { id: 'b' }]);
    facade.list.groupedMessages.set([{ message: { id: 'a' } }, { message: { id: 'b' } }]);
    const { fixture, dialogOpen } = setup(facade);
    await afterFirstPaint(fixture);

    const link = fixture.nativeElement.querySelectorAll(
      'tr.row-interactive a.row-link',
    )[1] as HTMLAnchorElement;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    expect(dialogOpen).toHaveBeenCalledTimes(1);
    expect(dialogOpen).toHaveBeenCalledWith('b', ['a', 'b']);
  });

  it('clicking the menu trigger button does not bubble to the row open handler', async () => {
    const facade = makeFacade();
    facade.list.messages.set([{ id: 'a' }]);
    facade.list.groupedMessages.set([{ message: { id: 'a' } }]);
    const { fixture, dialogOpen } = setup(facade);
    await afterFirstPaint(fixture);

    const menuButton = fixture.nativeElement.querySelector(
      'tr.row-interactive td app-message-row-menu button',
    ) as HTMLElement;
    expect(menuButton).not.toBeNull();
    menuButton.click();
    expect(dialogOpen).not.toHaveBeenCalled();
  });
});
