import { DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, convertToParamMap, type ParamMap } from '@angular/router';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { BehaviorSubject, EMPTY, of } from 'rxjs';

// jsdom lacks ResizeObserver; ol.Map references it during mount.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

// jsdom lacks matchMedia; the embedded details menu binds a listener
// on init. Stub it at file load so this spec is self-sufficient and doesn't depend on a sibling
// spec's stub leaking into the shared single-fork test window.
if (typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  });
}

import { MessageDetailsDialog } from './message-details-dialog';
import { MessageDetailsDialogService } from '../services/message-details-dialog.service';
import { MessageSelectionService } from '../services/message-selection.service';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { MessagePrintService } from '../services/message-print.service';
import { provideFakeMessageTagActions } from '../testing/fake-message-tag-actions';
import { provideCorePromulgationRenderers } from '../promulgation/core-renderers.providers';
import { provideCountry } from '../../../country-specific/default/country.providers';
import type { Message } from '../models/message.model';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade, provideFakeDomainFacade } from '../../../shared/testing/fake-auth';

async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

interface SetupOptions {
  readonly initialMessageId?: string;
  readonly messageResponse?: Message | null;
  readonly messageError?: boolean;
  /** The HTTP status the failing lookup answers with; 500 when not set. */
  readonly messageErrorStatus?: number;
}

/**
 * Flush the message-details httpResource GET (issued whenever the dialog has a current
 * message id). Mirrors the old getMessage stub: resolves to the configured response, or
 * errors when messageError is set. Matched by path so the embedded menu's /tags/message
 * GET is left untouched.
 */
function flushMessage(httpMock: HttpTestingController, opts: SetupOptions): void {
  for (const req of httpMock.match(
    (r) => r.method === 'GET' && r.url.includes('/rest/messages/message/'),
  )) {
    // A prior navigation (open → selectReference) supersedes its in-flight request; the
    // resource aborts it, but it stays in the controller's match set. Flushing a cancelled
    // request throws, so only resolve the live one.
    if (req.cancelled) continue;
    if (opts.messageError) {
      const status = opts.messageErrorStatus ?? 500;
      req.flush('Boom', { status, statusText: status === 500 ? 'Server Error' : 'Refused' });
    } else {
      req.flush(opts.messageResponse ?? null);
    }
  }
}

function setup(opts: SetupOptions = {}) {
  const initialParams = opts.initialMessageId ? { messageId: opts.initialMessageId } : {};
  const queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap(initialParams));

  const navigate = vi.fn(
    (
      _commands: unknown[],
      extras: { queryParams: Record<string, string | null> },
    ): Promise<boolean> => {
      const current: Record<string, string> = {};
      queryParamMap.value.keys.forEach((k) => {
        const v = queryParamMap.value.get(k);
        if (v !== null) current[k] = v;
      });
      const merged: Record<string, string> = { ...current };
      for (const [k, v] of Object.entries(extras.queryParams)) {
        if (v === null) delete merged[k];
        else merged[k] = v;
      }
      queryParamMap.next(convertToParamMap(merged));
      return Promise.resolve(true);
    },
  );
  const router = {
    navigate,
    url: '/',
    // HelpAnchor (mounted in the dialog footer) subscribes to router.events
    // and reads routerState.{root,snapshot}. Defensive stubs for both: the
    // dialog has section="message-details" explicit, so the snapshot
    // fallback path isn't exercised, but Angular evaluates the computed
    // eagerly and would crash on missing properties.
    events: EMPTY,
    routerState: {
      root: { queryParamMap },
      snapshot: { root: { data: {}, firstChild: null } },
    },
  } as unknown as Router;

  const notifyMock = vi.fn();
  const notifications = { notify: notifyMock } as unknown as NotificationService;

  const language = {
    selectedLanguage: () => 'en',
  } as unknown as LanguageService;

  TestBed.configureTestingModule({
    // The map now sits behind @defer. Manual keeps jsdom from constructing OpenLayers when the
    // block would otherwise play through; these tests assert on the showMap() computed, not DOM.
    deferBlockBehavior: DeferBlockBehavior.Manual,
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideCorePromulgationRenderers(),
      provideCountry(),
      { provide: Router, useValue: router },
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          documentationUrl: 'https://docs.example',
          keycloak: { url: '/auth', realm: 'niord', clientId: 'niord-web' },
        },
      },
      { provide: NotificationService, useValue: notifications },
      { provide: MessagePrintService, useValue: { printMessage: vi.fn(() => of(undefined)) } },
      provideFakeMessageTagActions(),
      { provide: LanguageService, useValue: language },
      provideFakeAuthFacade(),
      provideFakeDomainFacade(),
    ],
  });

  const dialogService = TestBed.inject(MessageDetailsDialogService);
  const selection = TestBed.inject(MessageSelectionService);
  const fixture = TestBed.createComponent(MessageDetailsDialog);
  fixture.detectChanges();
  const httpMock = TestBed.inject(HttpTestingController);

  return {
    fixture,
    comp: fixture.componentInstance,
    dialogService,
    selection,
    notifyMock,
    navigate,
    httpMock,
    flush: () => flushMessage(httpMock, opts),
  };
}

const baseMessage: Message = {
  id: 'NM-1-26',
  parts: [],
  attachments: [],
  promulgations: [],
} as Message;

describe('MessageDetailsDialog — open/close + body', () => {
  it('starts hidden when no ?messageId is present', () => {
    const { dialogService } = setup();
    expect(dialogService.isOpen()).toBe(false);
  });

  it('renders the message body when the resource resolves with a message', async () => {
    const { comp, flush } = setup({
      initialMessageId: 'NM-1-26',
      messageResponse: { ...baseMessage, id: 'NM-1-26' },
    });
    await settle();
    flush();
    await settle();
    // The component instance exposes the resolved message via its computed
    const c = comp as unknown as { message: () => Message | null; warning: () => string | null };
    expect(c.message()).not.toBeNull();
    expect(c.message()!.id).toBe('NM-1-26');
    expect(c.warning()).toBeNull();
  });

  // The backend stamps a Jackson `@class` polymorphic discriminator onto each promulgation;
  // the details resource must carry it through opaquely (the value round-trips on any later
  // save/adjust, and the promulgation renderers key off it). This coverage moved here from the
  // removed getMessage() API method when the details read became an httpResource.
  it('preserves @class on promulgations returned by the details resource', async () => {
    const { comp, flush } = setup({
      initialMessageId: 'NM-1-26',
      messageResponse: {
        ...baseMessage,
        id: 'NM-1-26',
        promulgations: [
          {
            '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
            type: { serviceId: 'navtex' },
            promulgate: true,
          },
        ] as unknown as Message['promulgations'],
      },
    });
    await settle();
    flush();
    await settle();
    const c = comp as unknown as { message: () => Message | null };
    const promulgations = c.message()!.promulgations as unknown as readonly Record<
      string,
      unknown
    >[];
    expect(promulgations[0]['@class']).toBe(
      'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
    );
  });

  it('renders the not-found warning when the resource resolves to null', async () => {
    const { comp, flush } = setup({ initialMessageId: 'NM-missing', messageResponse: null });
    await settle();
    flush();
    await settle();
    const c = comp as unknown as { message: () => Message | null; warning: () => string | null };
    expect(c.message()).toBeNull();
    expect(c.warning()).not.toBeNull();
  });

  it('fires an error toast when the fetch errors and keeps the dialog open', async () => {
    const { comp, notifyMock, flush } = setup({ initialMessageId: 'NM-err', messageError: true });
    await settle();
    flush();
    await settle();
    expect(notifyMock).toHaveBeenCalledTimes(1);
    expect(notifyMock.mock.calls[0][0]).toEqual({
      severity: 'error',
      summaryKey: 'messages.dialog.errorToast.summary',
      detailKey: 'messages.dialog.errorToast.detail',
    });
    // Component reports errored state; dialog is still open
    const c = comp as unknown as { errored: () => boolean };
    expect(c.errored()).toBe(true);
  });

  // A guest's link to a message that is not public: the backend refuses the lookup. To the
  // guest the message is not there, so the drawer says so, and a toast asking to try again
  // (which can never succeed) is not shown.
  it.each([403, 401])(
    'shows the not-found line and no toast when the lookup is refused with %i',
    async (status) => {
      const { comp, notifyMock, flush } = setup({
        initialMessageId: 'NM-hidden',
        messageError: true,
        messageErrorStatus: status,
      });
      await settle();
      flush();
      await settle();
      const c = comp as unknown as {
        message: () => Message | null;
        warning: () => string | null;
        errored: () => boolean;
      };
      expect(notifyMock).not.toHaveBeenCalled();
      expect(c.errored()).toBe(false);
      expect(c.message()).toBeNull();
      expect(c.warning()).toBe('messages.dialog.notFound');
    },
  );

  it('keeps the toast and shows no not-found line when the server fails', async () => {
    const { comp, notifyMock, flush } = setup({
      initialMessageId: 'NM-err',
      messageError: true,
      messageErrorStatus: 503,
    });
    await settle();
    flush();
    await settle();
    expect(notifyMock).toHaveBeenCalledTimes(1);
    expect((comp as unknown as { warning: () => string | null }).warning()).toBeNull();
  });
});

describe('MessageDetailsDialog — navigation buttons', () => {
  beforeEach(() => undefined);

  it('prev/next visible when stack depth === 1 and root is in context', async () => {
    const { dialogService, comp } = setup();
    dialogService.open('NM-2', ['NM-1', 'NM-2', 'NM-3']);
    await settle();
    const c = comp as unknown as {
      showNavigation: () => boolean;
      showBack: () => boolean;
      prevDisabled: () => boolean;
      nextDisabled: () => boolean;
    };
    expect(c.showNavigation()).toBe(true);
    expect(c.showBack()).toBe(false);
    expect(c.prevDisabled()).toBe(false);
    expect(c.nextDisabled()).toBe(false);
  });

  it('prevDisabled at index 0; nextDisabled at last index', async () => {
    const { dialogService, comp } = setup();
    dialogService.open('NM-1', ['NM-1', 'NM-2']);
    await settle();
    const c = comp as unknown as {
      prevDisabled: () => boolean;
      nextDisabled: () => boolean;
    };
    expect(c.prevDisabled()).toBe(true);
    expect(c.nextDisabled()).toBe(false);

    dialogService.selectNext();
    await settle();
    expect(c.prevDisabled()).toBe(false);
    expect(c.nextDisabled()).toBe(true);
  });

  it('back visible when stack depth > 1; prev/next then hidden', async () => {
    const { dialogService, comp } = setup();
    dialogService.open('NM-1', ['NM-1', 'NM-2']);
    dialogService.selectReference('REF-A');
    await settle();
    const c = comp as unknown as {
      showNavigation: () => boolean;
      showBack: () => boolean;
    };
    expect(c.showBack()).toBe(true);
    expect(c.showNavigation()).toBe(false);
  });

  it('prev/next hidden when the root is not in the context (deep-link case)', async () => {
    const { dialogService, comp } = setup();
    dialogService.open('NM-ORPHAN', []);
    await settle();
    const c = comp as unknown as { showNavigation: () => boolean; showBack: () => boolean };
    expect(c.showNavigation()).toBe(false);
    expect(c.showBack()).toBe(false);
  });
});

describe('MessageDetailsDialog — showMap rules', () => {
  it('showMap is false when message has no geometry', async () => {
    const { dialogService, comp, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: { ...baseMessage, parts: [] },
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();
    expect((comp as unknown as { showMap: () => boolean }).showMap()).toBe(false);
  });

  it('showMap is false when an attachment has display === "ABOVE"', async () => {
    const { dialogService, comp, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: {
        ...baseMessage,
        parts: [{ geometry: { type: 'FeatureCollection', features: [] } }] as Message['parts'],
        attachments: [{ display: 'ABOVE' }] as Message['attachments'],
      },
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();
    expect((comp as unknown as { showMap: () => boolean }).showMap()).toBe(false);
  });

  it('showMap is true when geometry exists and no attachment-above claims the slot', async () => {
    const { dialogService, comp, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: {
        ...baseMessage,
        parts: [{ geometry: { type: 'FeatureCollection', features: [] } }] as Message['parts'],
        attachments: [],
      },
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();
    expect((comp as unknown as { showMap: () => boolean }).showMap()).toBe(true);
  });
});

describe('MessageDetailsDialog — deferred map placeholder + error', () => {
  const withGeometry = {
    ...baseMessage,
    parts: [{ geometry: { type: 'FeatureCollection', features: [] } }] as Message['parts'],
    attachments: [] as Message['attachments'],
  };

  /**
   * The map box is the FIRST child of the drawer body's column, so if it only appeared once the
   * OpenLayers chunk resolved it would be spliced in above the message text and shove it down.
   * The placeholder reserves the identical box up front — but only when a map is actually coming.
   */
  it('reserves the map box while the chunk is still loading', async () => {
    const { dialogService, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: withGeometry,
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();

    // Manual defer behaviour holds the block in its placeholder state, so the only .min-h-64
    // box in the document is the reserved one — the map itself has not rendered.
    expect(document.querySelectorAll('.min-h-64')).toHaveLength(1);
  });

  it('reserves NOTHING for a message that will never show a map', async () => {
    const { dialogService, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: { ...baseMessage, parts: [] },
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();

    // Without the showMap() guard on @placeholder this would be 1, reserving 16rem of blank
    // space above the body of every geometry-less message.
    expect(document.querySelectorAll('.min-h-64')).toHaveLength(0);
  });

  it('explains itself when the chunk fails instead of leaving a permanently blank box', async () => {
    const { fixture, dialogService, flush } = setup({
      initialMessageId: 'NM-1',
      messageResponse: withGeometry,
    });
    dialogService.open('NM-1', ['NM-1']);
    await settle();
    flush();
    await settle();

    const [mapBlock] = await fixture.getDeferBlocks();
    await mapBlock.render(DeferBlockState.Error);
    await settle();

    // Angular never retries a failed defer block, so without @error the placeholder would
    // simply stay forever with nothing telling the user why.
    const alert = document.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('common.mapLoadError');
  });
});

describe('MessageDetailsDialog — selection toggle', () => {
  it('toggles the CURRENT message (drilled, not root)', async () => {
    const { dialogService, selection, comp, flush } = setup({
      initialMessageId: 'NM-ROOT',
      messageResponse: { ...baseMessage, id: 'NM-DRILLED' },
    });
    dialogService.open('NM-ROOT', ['NM-ROOT']);
    dialogService.selectReference('NM-DRILLED');
    await settle();
    flush();
    await settle();

    // Service knows currentMessageId is drilled; component's resource loads NM-DRILLED.
    // Switch the response and let the resource refetch (the fake API returns the same fixture).
    const c = comp as unknown as { toggleSelectMessage: () => void };
    c.toggleSelectMessage();
    expect(selection.isSelected('NM-DRILLED')).toBe(true);
    expect(selection.isSelected('NM-ROOT')).toBe(false);

    c.toggleSelectMessage();
    expect(selection.isSelected('NM-DRILLED')).toBe(false);
  });

  it('no-op when no message is loaded yet', async () => {
    const { selection, comp } = setup();
    const c = comp as unknown as { toggleSelectMessage: () => void };
    c.toggleSelectMessage();
    expect(selection.selectedCount()).toBe(0);
  });

  // Regression: the star previously read isSelected against
  // `dialog.currentMessageId()` (the navigation key), which is the reference's
  // shortId after a drill — but the selection set is keyed by the backend
  // UUID. The result was a star that toggled the selection correctly but
  // never reflected as "selected" on the dialog after drilling via a
  // reference link.
  it('isSelected() tracks the loaded UUID even when the drill navigation key is a shortId', async () => {
    const { dialogService, comp, flush } = setup({
      initialMessageId: 'NM-ROOT',
      // The reference targets a shortId — but the backend returns a Message
      // with a different (UUID) `.id`. This mirrors real behaviour: references
      // carry shortIds, getMessage() returns the full message with its UUID.
      messageResponse: { ...baseMessage, id: 'uuid-of-NM-268-19', shortId: 'NM-268-19' },
    });
    dialogService.open('NM-ROOT', ['NM-ROOT']);
    dialogService.selectReference('NM-268-19');
    await settle();
    flush();
    await settle();

    const c = comp as unknown as { toggleSelectMessage: () => void; isSelected: () => boolean };
    expect(c.isSelected()).toBe(false);
    c.toggleSelectMessage();
    expect(c.isSelected()).toBe(true);
    c.toggleSelectMessage();
    expect(c.isSelected()).toBe(false);
  });
});
