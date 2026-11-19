import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { TAG_SELECTION_STORE } from '../../../../core/domain/services/tag-selection-store.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { TagPicker } from '../../../messages/components/tag-picker/tag-picker';
import { MessagePrintService } from '../../../messages/services/message-print.service';
import { MessageTagActionsService } from '../../../messages/services/message-tag-actions.service';
import type { MessageTag } from '../../../../shared/models/message-tag.model';
import { EditorCommentsStore } from '../../services/editor-comments.store';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { EditorActionMenu } from './editor-action-menu';

/**
 * The editor cog's tag surface is the app-wide `TagPicker` in `mode="add"` —
 * a membership editor that pre-loads the message's current (unlocked) tags checked and removes on
 * uncheck. The picker's own specs cover the load/toggle mechanics; this spec's contract is narrower:
 * the EDITOR mount is add-mode and, when "Add to tag" is invoked, opens the picker seeded with the
 * loaded message's id + display id (so it edits THIS message's membership).
 */
function setup(opts: { lastTag?: MessageTag } = {}) {
  const message = signal<Record<string, unknown> | undefined>({
    id: 'ed-1',
    created: true,
    shortId: 'NM-042',
    messageSeries: { seriesId: 's' },
  });
  const facade = {
    message,
    isEditor: () => true,
  };
  const removeMessagesFromTag = vi.fn(() => of(undefined));

  TestBed.configureTestingModule({
    providers: [
      { provide: EditorFacade, useValue: facade },
      // The badge count comes from the route-provided store; this spec is about tag membership.
      {
        provide: EditorCommentsStore,
        useValue: { comments: () => [], count: () => 0, reload: () => undefined },
      },
      { provide: EditorFieldsService, useValue: { expandAll: vi.fn(), collapseAll: vi.fn() } },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), modelLanguages: [{ value: 'en' }] },
      },
      { provide: MessagePrintService, useValue: { printMessage: vi.fn(() => of(undefined)) } },
      {
        provide: MessageTagActionsService,
        useValue: {
          addMessagesToTag: vi.fn(() => of(undefined)),
          removeMessagesFromTag,
          addMessagesToLastSelectedTag: vi.fn(() => of(undefined)),
        },
      },
      {
        provide: TAG_SELECTION_STORE,
        useValue: {
          getLastSelection: () => signal(opts.lastTag),
          saveLastSelection: vi.fn(),
          clearLastSelection: vi.fn(),
        },
      },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideFakeAuthFacade({ isAuthenticated: true, editor: true }),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([]),
    ],
  });

  const fixture = TestBed.createComponent(EditorActionMenu);
  fixture.detectChanges();
  const picker = fixture.debugElement.query(By.directive(TagPicker)).componentInstance as TagPicker;
  interface Item {
    label?: string;
    command?: (event: unknown) => void;
    items?: { label?: string; command?: (event: unknown) => void }[];
  }
  // `items` / `onOpened` are protected on the component — read them through a narrow cast.
  const comp = fixture.componentInstance as unknown as {
    items: () => Item[];
    onOpened: () => void;
  };
  const httpMock = TestBed.inject(HttpTestingController);
  return { fixture, comp, picker, httpMock, removeMessagesFromTag };
}

describe('EditorActionMenu tag membership', () => {
  it('mounts the tag surface as a mode="add" membership editor', () => {
    const { picker } = setup();
    expect(picker.mode()).toBe('add');
  });

  it('"Add to tag" opens the picker seeded with the loaded message id + display id', () => {
    const { fixture, comp, picker } = setup();
    const openSpy = vi.spyOn(picker, 'open').mockImplementation(() => undefined);
    const cogBtn = fixture.nativeElement.querySelector(
      'button[aria-label="editor.action.menu"]',
    ) as HTMLButtonElement;

    const addToTag = comp.items().find((i) => i.label === 'editor.action.addToTag');
    expect(addToTag).toBeDefined();
    addToTag?.command?.({ originalEvent: new MouseEvent('click'), item: addToTag });

    expect(openSpy).toHaveBeenCalledWith(cogBtn, {
      messageIds: ['ed-1'],
      displayId: 'NM-042',
    });
  });

  it('drops "Add to last tag" once the message is found in that tag, and offers "Remove from…"', async () => {
    const { fixture, comp, httpMock } = setup({
      lastTag: { tagId: 't1', name: 'My Tag' } as MessageTag,
    });

    // Before the menu opens membership is unknown — the add-to-last row shows optimistically.
    expect(comp.items().map((i) => i.label)).toContain('editor.action.addToLastTag');

    comp.onOpened();
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/rest/tags/message/ed-1')
      .flush([{ tagId: 't1', name: 'My Tag' }]);
    await Promise.resolve();

    const items = comp.items();
    // Already in t1 → add-to-last removed; the "Remove from…" submenu lists the tag.
    expect(items.map((i) => i.label)).not.toContain('editor.action.addToLastTag');
    const removeParent = items.find((i) => i.label === 'menu.action.tag.remove_named');
    expect(removeParent?.items?.map((c) => c.label)).toEqual(['My Tag']);
  });

  it('keeps "Add to last tag" when the message is only in a DIFFERENT tag; remove targets that tag', async () => {
    const { fixture, comp, httpMock, removeMessagesFromTag } = setup({
      lastTag: { tagId: 't1', name: 'Last Tag' } as MessageTag,
    });

    comp.onOpened();
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/rest/tags/message/ed-1')
      .flush([{ tagId: 't2', name: 'Other' }]);
    await Promise.resolve();

    const items = comp.items();
    // Not in t1 → add-to-last still offered.
    expect(items.map((i) => i.label)).toContain('editor.action.addToLastTag');
    const removeChild = items
      .find((i) => i.label === 'menu.action.tag.remove_named')
      ?.items?.find((c) => c.label === 'Other');
    removeChild?.command?.({});
    expect(removeMessagesFromTag).toHaveBeenCalledWith(['ed-1'], { tagId: 't2', name: 'Other' });
  });

  it('says so in "Remove from…" when the tags could not be read, with a retry', async () => {
    const { fixture, comp, httpMock } = setup();
    comp.onOpened();
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/rest/tags/message/ed-1')
      .flush('down', { status: 503, statusText: 'Service Unavailable' });
    await Promise.resolve();

    const removeParent = comp.items().find((i) => i.label === 'menu.action.tag.remove_named');
    expect(removeParent?.items?.map((c) => c.label)).toEqual(['common.fetchError', 'common.retry']);
    removeParent?.items?.[1].command?.({});
    fixture.detectChanges();
    await Promise.resolve();
    httpMock.expectOne((r) => r.url === '/rest/tags/message/ed-1');
  });
});
