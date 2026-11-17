import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../core/domain/models/domain.model';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { GeometryFeature } from '../../../shared/models/geometry.model';
import { wallClockIn } from '../../../shared/testing/zone-testing';
import type {
  BasePromulgation,
  Message,
  MessageAttachment,
  MessageArea,
  MessageGeometryFeatureCollection,
  PromulgationType,
} from '../../messages/models/message.model';
import type { AreaRef } from '../../messages/models/messages-filter-params.model';
import { AreasApiService } from '../../messages/services/api/areas-api.service';
import {
  ChartsApiService,
  type ComputedMessageChart,
} from '../../messages/services/api/charts-api.service';
import { MessagesApiService } from '../../messages/services/api/messages-api.service';
import { PromulgationsApiService } from '../../messages/services/api/promulgations-api.service';
import { MessageGeometryService } from '../../messages/services/message-geometry.service';
import { EditorFacade } from './editor-facade';
import { TemplateMessageStore } from './template-message-store';

/** A GeoJSON point feature carrying an `n` = readable-coordinate count for the reindex mock. */
function pointFeature(n: number): GeometryFeature {
  return { type: 'Feature', geometry: { type: 'Point', coordinates: [0, 0] }, properties: { n } };
}

/** The single-feature collection a mocked `areaToFeatureCollection` returns for a copied area. */
function areaLocationCollection(): MessageGeometryFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [10, 56] },
        properties: { areaId: 3, n: 1 },
      },
    ],
  };
}

function setup(opts?: {
  admin?: boolean;
  editor?: boolean;
  sessionDomain?: Domain;
  supportsMainType?: boolean;
  template?: Message;
  editableResult?: Message;
}) {
  const isAdmin = signal(opts?.admin ?? false);
  const isEditor = signal(opts?.editor ?? true);
  const auth = {
    hasRole: (role: string) =>
      role === 'admin' ? isAdmin : role === 'editor' ? isEditor : signal(false),
  };

  const sessionDomain = signal<Domain | undefined>(opts?.sessionDomain);
  const supports = signal(opts?.supportsMainType ?? true);
  const domain = { sessionDomain, supportsMainType: () => supports };

  const router = { navigate: vi.fn(() => Promise.resolve(true)) };

  const reloaded: Message = opts?.editableResult ?? { id: 'm-1', status: 'VERIFIED', created: 'x' };
  const api = {
    editableDetails: vi.fn((id: string) => of<Message | null>({ ...reloaded, id })),
    copyMessageTemplate: vi.fn((id: string) =>
      of<Message | null>({ id: `copy-of-${id}`, mainType: 'NW' } as Message),
    ),
    newMessageTemplate: vi.fn((mainType: string) =>
      of<Message | null>({ id: 'tmp', mainType } as Message),
    ),
    saveMessage: vi.fn((message: Message) =>
      of({ ...message, id: 'saved-id', created: '2026-01-02' }),
    ),
    adjustEditableMessage: vi.fn((message: Message) => of(message)),
    deleteRepoFile: vi.fn(() => of('ok')),
  };
  const promulgationsApi = {
    getPromulgationType: vi.fn((typeId: string) => of({ typeId } as PromulgationType)),
    generatePromulgation: vi.fn(),
    reformat: vi.fn(),
  };
  const notify = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() };
  const templateStore = {
    consume: vi.fn(() => opts?.template),
    set: vi.fn(),
    clear: vi.fn(),
  };
  const areasApi = {
    intersectingAreas: vi.fn(() => of([] as MessageArea[])),
    getByIds: vi.fn(() => of([] as AreaRef[])),
    areaToFeatureCollection: vi.fn(
      (): MessageGeometryFeatureCollection => ({ type: 'FeatureCollection', features: [] }),
    ),
  };
  const chartsApi = {
    intersectingCharts: vi.fn(() => of([] as ComputedMessageChart[])),
  };
  // The coordinate serializer is mocked so the reindex threads by each feature's `n` property —
  // the real serializer is unit-tested against the reindex util separately.
  const geometry = {
    getFeatures: vi.fn(() => [] as GeometryFeature[]),
    serializeReadableCoordinates: vi.fn((feature: GeometryFeature) =>
      new Array((feature.properties?.['n'] as number | undefined) ?? 0).fill(0),
    ),
  };
  // Two configured model languages — the facade only reads `availableLanguages[].value`.
  const language = {
    modelLanguages: [
      { value: 'en', label: 'English' },
      { value: 'da', label: 'Dansk' },
    ],
  };

  TestBed.configureTestingModule({
    providers: [
      EditorFacade,
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      { provide: PromulgationsApiService, useValue: promulgationsApi },
      { provide: NotificationService, useValue: notify },
      { provide: TemplateMessageStore, useValue: templateStore },
      { provide: LanguageService, useValue: language },
      { provide: AreasApiService, useValue: areasApi },
      { provide: ChartsApiService, useValue: chartsApi },
      { provide: MessageGeometryService, useValue: geometry },
      { provide: Router, useValue: router },
    ],
  });

  return {
    facade: TestBed.inject(EditorFacade),
    router,
    isAdmin,
    isEditor,
    sessionDomain,
    api,
    promulgationsApi,
    notify,
    templateStore,
    areasApi,
    chartsApi,
    geometry,
  };
}

const draft: Message = { id: 'm-1', status: 'DRAFT' };
const published: Message = { id: 'm-2', status: 'PUBLISHED' };

describe('EditorFacade', () => {
  describe('editable()', () => {
    it('is true for DRAFT and VERIFIED', () => {
      const { facade } = setup();
      facade.applyLoaded(draft);
      expect(facade.editable()).toBe(true);
      facade.applyLoaded({ id: 'm', status: 'VERIFIED' });
      expect(facade.editable()).toBe(true);
    });

    it('is false for PUBLISHED/CANCELLED unless the user is admin', () => {
      const { facade, isAdmin } = setup();
      facade.applyLoaded(published);
      expect(facade.editable()).toBe(false);
      isAdmin.set(true);
      expect(facade.editable()).toBe(true);
    });
  });

  describe('dirty + userTouched latches', () => {
    it('dirty trips on a user edit and stays true (sticky)', () => {
      const { facade } = setup();
      facade.applyLoaded(draft);
      expect(facade.dirty()).toBe(false);
      facade.markUserTouched();
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
      expect(facade.dirty()).toBe(true);
    });

    it('userTouched does NOT trip on a born-dirty programmatic load (copy/new)', () => {
      const { facade } = setup();
      facade.applyLoaded(draft, { dirty: true });
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(false);
    });

    it('setDirty trips dirty but not userTouched', () => {
      const { facade } = setup();
      facade.applyLoaded(draft);
      facade.setDirty();
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(false);
    });

    it('a fresh load resets both latches', () => {
      const { facade } = setup();
      facade.applyLoaded(draft);
      facade.markUserTouched();
      facade.applyLoaded(draft);
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
    });
  });

  describe('hasUnsavedChanges (an edit, or a load the database does not hold)', () => {
    /** A user edit the way the form makes one: patch the content, then mark the touch. */
    function editShortId(facade: EditorFacade, shortId: string): void {
      facade.patchMessage({ shortId });
      facade.markUserTouched();
    }

    it('a copy has unsaved changes before any field is touched', () => {
      const { facade } = setup();
      facade.loadCopy('src', 'REFERENCE');
      expect(facade.userTouched()).toBe(false);
      expect(facade.hasUnsavedChanges()).toBe(true);
    });

    it('a blank new message and a template seed have unsaved changes before any touch', () => {
      const { facade } = setup({ template: { id: '', mainType: 'NW', type: 'LOCAL_WARNING' } });
      facade.createMessage('NW');
      expect(facade.hasUnsavedChanges()).toBe(true);

      facade.loadFromTemplate();
      expect(facade.message()?.type).toBe('LOCAL_WARNING');
      expect(facade.hasUnsavedChanges()).toBe(true);
    });

    it("a template's result on a saved message has unsaved changes before any touch", () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm-5', status: 'DRAFT', created: 'x', shortId: 'OLD' });
      expect(facade.hasUnsavedChanges()).toBe(false);

      // The message keeps its `created`: only the load itself says the content is not stored.
      facade.templateExecuted({ id: 'm-5', status: 'DRAFT', created: 'x', shortId: 'NEW' });
      expect(facade.message()?.created).toBe('x');
      expect(facade.hasUnsavedChanges()).toBe(true);
    });

    it('undoing an edit on a born-dirty message keeps it unsaved', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'tmp', status: 'DRAFT', shortId: 'A' }, { dirty: true });
      editShortId(facade, 'B');
      expect(facade.hasUnsavedChanges()).toBe(true);
      editShortId(facade, 'A');
      expect(facade.changed()).toBe(false);
      expect(facade.hasUnsavedChanges()).toBe(true);
    });

    it('a save clears it (the post-save reload is an ordinary load)', () => {
      const { facade } = setup({
        editableResult: { id: 'saved-id', status: 'DRAFT', created: 'y' },
      });
      facade.applyLoaded(
        { id: 'tmp', mainType: 'NW', type: 'COASTAL_WARNING', messageSeries: { seriesId: 's' } },
        { dirty: true },
      );
      expect(facade.hasUnsavedChanges()).toBe(true);

      facade.save().subscribe();
      expect(facade.hasUnsavedChanges()).toBe(false);
    });

    it('a reload and a reset clear it', () => {
      const { facade } = setup();
      facade.templateExecuted({ id: 'm-5', status: 'DRAFT', created: 'x' });
      facade.reload();
      expect(facade.hasUnsavedChanges()).toBe(false);

      facade.templateExecuted({ id: 'm-5', status: 'DRAFT', created: 'x' });
      facade.resetWorkingState();
      expect(facade.hasUnsavedChanges()).toBe(false);
    });

    it('an ordinary load has none, an edit has, and undoing the edit clears it', () => {
      const { facade } = setup({
        editableResult: { id: 'm-9', status: 'DRAFT', created: 'x', shortId: 'A' },
      });
      facade.loadExisting('m-9');
      expect(facade.hasUnsavedChanges()).toBe(false);

      editShortId(facade, 'B');
      expect(facade.hasUnsavedChanges()).toBe(true);

      editShortId(facade, 'A');
      // The sticky latch stays; the unsaved state does not.
      expect(facade.dirty()).toBe(true);
      expect(facade.hasUnsavedChanges()).toBe(false);
    });
  });

  describe('messageSeries (null-guarded re-hydration)', () => {
    it('returns [] when there is no session domain', () => {
      const { facade } = setup();
      facade.applyLoaded(draft);
      expect(facade.messageSeries()).toEqual([]);
    });

    it('narrows to the active message mainType', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [
          { seriesId: 'nw', mainType: 'NW' },
          { seriesId: 'nm', mainType: 'NM' },
        ],
      };
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW' });
      expect(facade.messageSeries().map((s) => s.seriesId)).toEqual(['nw']);
    });
  });

  describe('isTypeValid (|= accumulation)', () => {
    const sessionDomain: Domain = {
      domainId: 'd',
      messageSeries: [
        { seriesId: 'restrictive', mainType: 'NW', types: ['LOCAL_WARNING'] },
        { seriesId: 'permissive', mainType: 'NW' },
      ],
    };

    it('a permissive series is not clobbered by a restrictive sibling', () => {
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW' });
      expect(facade.isTypeValid('LOCAL_WARNING')).toBe(true);
      expect(facade.isTypeValid('COASTAL_WARNING')).toBe(true);
    });

    it('only listed types are valid when every series is restrictive', () => {
      const restrictiveOnly: Domain = {
        domainId: 'd',
        messageSeries: [{ seriesId: 'r', mainType: 'NW', types: ['LOCAL_WARNING'] }],
      };
      const { facade } = setup({ sessionDomain: restrictiveOnly });
      facade.applyLoaded({ id: 'm', mainType: 'NW' });
      expect(facade.isTypeValid('LOCAL_WARNING')).toBe(true);
      expect(facade.isTypeValid('COASTAL_WARNING')).toBe(false);
    });
  });

  describe('short-id editability (re-hydrated numbering config)', () => {
    it('a MANUAL series without shortFormat is short-id editable, not message-number editable', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [{ seriesId: 'manual', mainType: 'NW', numberSequenceType: 'MANUAL' }],
      };
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW', messageSeries: { seriesId: 'manual' } });
      expect(facade.numberSequenceType()).toBe('MANUAL');
      expect(facade.shortIdEditable()).toBe(true);
      expect(facade.messageNumberEditable()).toBe(false);
    });

    it('a MANUAL series WITH shortFormat is message-number editable, not short-id editable', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [
          { seriesId: 'fmt', mainType: 'NW', numberSequenceType: 'MANUAL', shortFormat: 'NW-{}' },
        ],
      };
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW', messageSeries: { seriesId: 'fmt' } });
      expect(facade.messageNumberEditable()).toBe(true);
      expect(facade.shortIdEditable()).toBe(false);
    });

    it('a non-MANUAL series is neither', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [{ seriesId: 'auto', mainType: 'NW', numberSequenceType: 'YEAR' }],
      };
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW', messageSeries: { seriesId: 'auto' } });
      expect(facade.shortIdEditable()).toBe(false);
      expect(facade.messageNumberEditable()).toBe(false);
    });
  });

  describe('seriesAllowingType (type-select auto-deduce input)', () => {
    it('returns only the series whose type restriction permits the type', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [
          { seriesId: 'a', mainType: 'NW', types: ['LOCAL_WARNING'] },
          { seriesId: 'b', mainType: 'NW' },
        ],
      };
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', mainType: 'NW' });
      expect(facade.seriesAllowingType('COASTAL_WARNING').map((s) => s.seriesId)).toEqual(['b']);
      expect(facade.seriesAllowingType('LOCAL_WARNING').map((s) => s.seriesId)).toEqual(['a', 'b']);
    });
  });

  describe('resolveSeriesForPatch (D2 cross-domain preservation)', () => {
    const sessionDomain: Domain = {
      domainId: 'd',
      messageSeries: [{ seriesId: 's', mainType: 'NW' }],
    };

    it('resolves an in-domain series id off the active domain list', () => {
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW' });
      expect(facade.resolveSeriesForPatch('s')?.seriesId).toBe('s');
    });

    it('preserves the loaded foreign series when the id matches but it is not in the active domain', () => {
      const { facade } = setup({ sessionDomain });
      const foreign = { seriesId: 'foreign', mainType: 'NW' as const };
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW', messageSeries: foreign });
      expect(facade.resolveSeriesForPatch('foreign')?.seriesId).toBe('foreign');
    });

    it('returns undefined for an id matching neither the domain nor the loaded series', () => {
      const { facade } = setup({ sessionDomain });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        mainType: 'NW',
        messageSeries: { seriesId: 'foreign' },
      });
      expect(facade.resolveSeriesForPatch('nope')).toBeUndefined();
    });
  });

  describe('load paths', () => {
    it('Path A (loadExisting) fetches pristine and dedupes a re-load of the same saved id', () => {
      const { facade, api } = setup({
        editableResult: { id: 'm-9', status: 'DRAFT', created: 'x' },
      });
      facade.loadExisting('m-9');
      expect(api.editableDetails).toHaveBeenCalledWith('m-9');
      expect(facade.dirty()).toBe(false);
      api.editableDetails.mockClear();

      facade.loadExisting('m-9');
      expect(api.editableDetails).not.toHaveBeenCalled();

      facade.loadExisting('m-other');
      expect(api.editableDetails).toHaveBeenCalledWith('m-other');
    });

    it('a 403 on a first load stays on the editor address and shows the panel with its cause and no Retry', () => {
      const { facade, api, router, notify } = setup();
      api.editableDetails.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      facade.loadExisting('m-elsewhere');

      // A followed link (or a domain switch, which reloads the application at the same address)
      // must say why; choosing the message's domain in the header then opens it at this address.
      expect(router.navigate).not.toHaveBeenCalled();
      expect(facade.loadStatus()).toBe('error');
      expect(facade.loadErrorPanel()).toBe('editor.load.error.otherDomain');
      expect(facade.canRetryLoad()).toBe(false);
      expect(facade.message()).toBeUndefined();
      expect(facade.dirty()).toBe(false);
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.load');

      // Retry has nothing to replay: the same 403 cannot be fixed by asking again.
      api.editableDetails.mockClear();
      facade.retryLoad();
      expect(api.editableDetails).not.toHaveBeenCalled();
    });

    it('a 403 drops a working copy held from an earlier message', () => {
      const { facade, api } = setup();
      facade.loadExisting('m-9');
      facade.patchMessage({ shortId: 'NW-009' });
      facade.markUserTouched();
      expect(facade.hasUnsavedChanges()).toBe(true);
      api.editableDetails.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      facade.loadExisting('m-elsewhere');

      expect(facade.message()).toBeUndefined();
      expect(facade.hasUnsavedChanges()).toBe(false);
      expect(facade.loadStatus()).toBe('error');
      expect(facade.canRetryLoad()).toBe(false);
    });

    it('a non-403 load failure still shows the inline error panel with Retry', () => {
      const { facade, api, router, notify } = setup();
      api.editableDetails.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      facade.loadExisting('m-broken');

      expect(router.navigate).not.toHaveBeenCalled();
      expect(facade.loadStatus()).toBe('error');
      expect(facade.loadErrorPanel()).toBe('editor.load.errorPanel');
      expect(facade.canRetryLoad()).toBe(true);
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.load');
    });

    it("a copy's 403 says it cannot be copied in the current domain, without Retry", () => {
      const { facade, api, router, notify } = setup();
      api.copyMessageTemplate.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      facade.loadCopy('m-elsewhere', 'UPDATE');

      expect(router.navigate).not.toHaveBeenCalled();
      expect(facade.loadStatus()).toBe('error');
      expect(facade.loadErrorPanel()).toBe('editor.load.error.copyOtherDomain');
      expect(facade.canRetryLoad()).toBe(false);
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.copy');

      api.copyMessageTemplate.mockClear();
      facade.retryLoad();
      expect(api.copyMessageTemplate).not.toHaveBeenCalled();
    });

    it("a copy's other failure keeps Retry, which copies again", () => {
      const { facade, api } = setup();
      api.copyMessageTemplate.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      facade.loadCopy('m-src', 'UPDATE');
      expect(facade.loadErrorPanel()).toBe('editor.load.errorPanel');
      expect(facade.canRetryLoad()).toBe(true);

      facade.retryLoad();
      expect(api.copyMessageTemplate).toHaveBeenLastCalledWith('m-src', 'UPDATE');
      expect(facade.loadStatus()).toBe('loaded');
    });

    it('leaveToStart drops the working state and goes to the Start screen', () => {
      const { facade, api, router } = setup();
      api.editableDetails.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );
      facade.loadExisting('m-elsewhere');

      facade.leaveToStart();

      expect(router.navigate).toHaveBeenCalledWith(['/editor']);
      expect(facade.loadStatus()).toBe('idle');
      expect(facade.message()).toBeUndefined();
    });

    it('Path B (loadCopy) is born-dirty, passes a valid reference type, drops an invalid one', () => {
      const { facade, api } = setup();
      facade.loadCopy('src', 'CANCELLATION');
      expect(api.copyMessageTemplate).toHaveBeenCalledWith('src', 'CANCELLATION');
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(false);

      facade.loadCopy('src', 'NOT_A_TYPE');
      expect(api.copyMessageTemplate).toHaveBeenLastCalledWith('src', undefined);

      // 'REFERENCE' is what the copy dialog's "include reference" box sends — it travels to the
      // server, which adds the back-reference to the draft.
      facade.loadCopy('src', 'REFERENCE');
      expect(api.copyMessageTemplate).toHaveBeenLastCalledWith('src', 'REFERENCE');
    });

    it('createMessage overlays the template AFTER the server template (template wins)', () => {
      const { facade, api } = setup();
      api.newMessageTemplate.mockReturnValueOnce(
        of({ id: 'tmp', mainType: 'NW', type: 'COASTAL_WARNING' } as Message),
      );
      facade.createMessage('NW', { id: 't', mainType: 'NW', type: 'LOCAL_WARNING' });
      expect(facade.message()?.type).toBe('LOCAL_WARNING');
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(false);
    });

    it('createMessage keeps the server template UUID over the seed’s blank id', () => {
      const { facade, api } = setup();
      api.newMessageTemplate.mockReturnValueOnce(
        of({ id: 'server-uuid', mainType: 'NW' } as Message),
      );
      // The Start-screen seed only carries id:'' to satisfy the required Message.id type — the
      // server-minted UUID must survive so the create POST sends a real id.
      facade.createMessage('NW', { id: '', mainType: 'NW', type: 'LOCAL_WARNING' });
      expect(facade.message()?.id).toBe('server-uuid');
      expect(facade.message()?.type).toBe('LOCAL_WARNING');
    });

    it('createMessage surfaces a refused create as an inline error', () => {
      const { facade, api, notify } = setup({ editor: false });
      facade.createMessage('NW');
      expect(api.newMessageTemplate).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.cannotCreate');
      expect(facade.loadStatus()).toBe('error');
    });

    it('loadFromTemplate guards a missing template', () => {
      const { facade, notify } = setup({ template: undefined });
      facade.loadFromTemplate();
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.noTemplate');
      expect(facade.loadStatus()).toBe('error');
      // Nothing to replay: the panel offers no Retry.
      expect(facade.canRetryLoad()).toBe(false);
    });

    it('loadFromTemplate seeds a blank message from a present template (born dirty)', () => {
      const { facade } = setup({ template: { id: 't', mainType: 'NM' } });
      facade.loadFromTemplate();
      expect(facade.message()?.mainType).toBe('NM');
      expect(facade.dirty()).toBe(true);
    });

    it('templateExecuted re-entry is born dirty', () => {
      const { facade } = setup();
      facade.templateExecuted({ id: 'x', mainType: 'NW' });
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(false);
    });

    it('a load failure flips loadStatus to error and toasts', () => {
      const { facade, api, notify } = setup();
      api.editableDetails.mockReturnValueOnce(throwError(() => new Error('boom')));
      facade.loadExisting('bad');
      expect(facade.loadStatus()).toBe('error');
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.load');
    });

    it('a 200/null body for an unknown id is a load failure, not a hang', () => {
      // The backend answers an unknown id with 200 + null body rather than an HTTP error.
      const { facade, api, notify } = setup();
      api.editableDetails.mockReturnValueOnce(of(null));
      facade.loadExisting('unknown');
      expect(facade.loadStatus()).toBe('error');
      expect(facade.message()).toBeUndefined();
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.load');
    });

    it('a 200/null body on the copy path is a load failure', () => {
      const { facade, api, notify } = setup();
      api.copyMessageTemplate.mockReturnValueOnce(of(null));
      facade.loadCopy('unknown');
      expect(facade.loadStatus()).toBe('error');
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.copy');
    });

    it('resetWorkingState drops the working copy so re-entry refetches instead of resurfacing it', () => {
      const { facade, api } = setup();
      facade.loadExisting('m-9');
      facade.markUserTouched();
      expect(facade.message()).toBeDefined();

      // Leaving the editor destroys the shell, which resets — the route-provider injector (and
      // this facade instance) survives the visit, so without the reset the next same-id entry
      // would dedupe onto the discarded dirty working copy.
      facade.resetWorkingState();
      expect(facade.message()).toBeUndefined();
      expect(facade.loadedBaseline()).toBeUndefined();
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
      expect(facade.loadStatus()).toBe('idle');

      api.editableDetails.mockClear();
      facade.loadExisting('m-9');
      expect(api.editableDetails).toHaveBeenCalledWith('m-9');
      expect(facade.dirty()).toBe(false);
    });

    it('retryLoad replays the last (failed) load', () => {
      const { facade, api } = setup();
      api.editableDetails.mockReturnValueOnce(throwError(() => new Error('boom')));
      facade.loadExisting('m-7');
      expect(facade.loadStatus()).toBe('error');

      facade.retryLoad();
      expect(api.editableDetails).toHaveBeenLastCalledWith('m-7');
      expect(facade.loadStatus()).toBe('loaded');
    });
  });

  describe('validateForSave', () => {
    it('requires type + mainType', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm' });
      expect(facade.validateForSave()).toEqual({
        ok: false,
        errorKey: 'editor.validation.specifyType',
      });
    });

    it('requires a series', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm', mainType: 'NW', type: 'COASTAL_WARNING' });
      expect(facade.validateForSave().errorKey).toBe('editor.validation.specifySeries');
    });

    it('rejects an inverted interval only when BOTH dates are present', () => {
      const { facade } = setup();
      const base: Message = {
        id: 'm',
        mainType: 'NW',
        type: 'COASTAL_WARNING',
        messageSeries: { seriesId: 's' },
      };
      facade.applyLoaded({ ...base, publishDateFrom: '2026-02-01', publishDateTo: '2026-01-01' });
      expect(facade.validateForSave().errorKey).toBe('editor.validation.invalidInterval');

      facade.applyLoaded({ ...base, publishDateFrom: '2026-01-01' });
      expect(facade.validateForSave().ok).toBe(true);
    });
  });

  describe('loadedBaseline snapshot (edited-dot baseline)', () => {
    it('applyLoaded stores a deep, frozen snapshot equal by value to the loaded message', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm', status: 'DRAFT', descs: [{ lang: 'en', title: 'T' }] });
      const baseline = facade.loadedBaseline();
      expect(baseline?.descs?.[0]?.title).toBe('T');
      expect(Object.isFrozen(baseline)).toBe(true);
      expect(Object.isFrozen(baseline?.descs?.[0])).toBe(true);
    });

    it('is independent of later working-copy edits (a real deep copy, not a shared ref)', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm', status: 'DRAFT', shortId: 'ORIG' });
      facade.patchMessage({ shortId: 'EDITED' });
      expect(facade.message()?.shortId).toBe('EDITED');
      expect(facade.loadedBaseline()?.shortId).toBe('ORIG');
    });

    it('is re-set to the canonical state after a save-reload', () => {
      const { facade } = setup({
        editableResult: { id: 'saved-id', status: 'PUBLISHED', created: 'y' },
      });
      facade.applyLoaded(
        {
          id: 'tmp',
          status: 'DRAFT',
          mainType: 'NW',
          type: 'COASTAL_WARNING',
          messageSeries: { seriesId: 's' },
        },
        { dirty: true },
      );
      expect(facade.loadedBaseline()?.status).toBe('DRAFT');

      facade.save().subscribe();
      expect(facade.loadedBaseline()?.id).toBe('saved-id');
      expect(facade.loadedBaseline()?.status).toBe('PUBLISHED');
    });
  });

  describe('save()', () => {
    it('persists then reloads the canonical state by the (possibly new) id and resets to pristine', () => {
      const { facade, api } = setup({
        editableResult: { id: 'saved-id', status: 'DRAFT', created: 'y' },
      });
      facade.applyLoaded(
        { id: 'tmp', mainType: 'NW', type: 'COASTAL_WARNING', messageSeries: { seriesId: 's' } },
        { dirty: true },
      );

      let result: Message | undefined;
      facade.save().subscribe((message) => (result = message));

      expect(api.saveMessage).toHaveBeenCalled();
      expect(api.editableDetails).toHaveBeenCalledWith('saved-id');
      expect(result?.id).toBe('saved-id');
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
      expect(facade.saving()).toBe(false);
    });
  });

  describe('message parts', () => {
    it('normalizes an empty/undefined parts list to a single part on load (≥1 invariant)', () => {
      const { facade } = setup();
      facade.applyLoaded({ id: 'm', status: 'DRAFT' });
      expect(facade.message()?.parts?.length).toBe(1);
    });

    it('a structural part change fires the adjust round-trip', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });
      api.adjustEditableMessage.mockClear();
      facade.addPart(0);
      expect(api.adjustEditableMessage).toHaveBeenCalled();
    });

    it('a structural part op re-threads startCoordIndex across parts AND still adjusts', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        parts: [
          {
            type: 'DETAILS',
            indexNo: 0,
            geometry: { type: 'FeatureCollection', features: [pointFeature(2)] },
          },
        ],
      });
      api.adjustEditableMessage.mockClear();

      facade.addPart(0); // inserts an empty part after index 0 — the reindex must re-thread

      const parts = facade.message()?.parts ?? [];
      expect(parts[0].geometry?.features[0].properties?.['startCoordIndex']).toBe(1);
      expect(api.adjustEditableMessage).toHaveBeenCalled();
    });

    it('add inserts after the index and re-indexes; remove keeps ≥1 part', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });

      facade.addPart(0);
      expect(facade.message()?.parts?.length).toBe(2);
      expect(facade.message()?.parts?.map((p) => p.indexNo)).toEqual([0, 1]);
      expect(facade.userTouched()).toBe(true);

      facade.removePart(1);
      facade.removePart(0);
      expect(facade.message()?.parts?.length).toBe(1);
    });

    it('move swaps within bounds and re-indexes; out-of-bounds is a no-op', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        parts: [
          { type: 'A', indexNo: 0 },
          { type: 'B', indexNo: 1 },
        ],
      });

      facade.movePart(0, 'down');
      expect(facade.message()?.parts?.map((p) => p.type)).toEqual(['B', 'A']);
      expect(facade.message()?.parts?.map((p) => p.indexNo)).toEqual([0, 1]);

      facade.movePart(0, 'up');
      expect(facade.message()?.parts?.map((p) => p.type)).toEqual(['B', 'A']);
    });

    it('movePartTo splice-moves 0→2, re-indexes, and dirties (drag-reorder)', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        parts: [
          { type: 'A', indexNo: 0 },
          { type: 'B', indexNo: 1 },
          { type: 'C', indexNo: 2 },
        ],
      });

      facade.movePartTo(0, 2);
      expect(facade.message()?.parts?.map((p) => p.type)).toEqual(['B', 'C', 'A']);
      expect(facade.message()?.parts?.map((p) => p.indexNo)).toEqual([0, 1, 2]);
      expect(facade.userTouched()).toBe(true);
    });

    it('movePartTo is a no-op (nothing committed) for an out-of-bounds or same-index move', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        parts: [
          { type: 'A', indexNo: 0 },
          { type: 'B', indexNo: 1 },
        ],
      });
      const before = facade.message()?.parts;

      facade.movePartTo(1, 1); // same index
      facade.movePartTo(0, 5); // out of bounds

      expect(facade.message()?.parts).toBe(before);
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
    });

    it('part ops are no-ops for a non-editor', () => {
      const { facade } = setup({ editor: false });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });
      facade.addPart(0);
      facade.setPartType(0, 'TIME');
      expect(facade.message()?.parts?.length).toBe(1);
      expect(facade.message()?.parts?.[0].type).toBe('DETAILS');
    });

    it('D1: part ops are no-ops on a PUBLISHED message for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });
      facade.applyLoaded({
        id: 'm',
        status: 'PUBLISHED',
        parts: [{ type: 'DETAILS', indexNo: 0 }],
      });

      facade.addPart(0);
      facade.movePart(0, 'down');
      facade.setPartType(0, 'TIME');
      facade.removePart(0);

      expect(facade.message()?.parts?.length).toBe(1);
      expect(facade.message()?.parts?.[0].type).toBe('DETAILS');
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
    });

    it('D1: an admin may restructure parts of a PUBLISHED message (admin override)', () => {
      const { facade } = setup({ editor: true, admin: true });
      facade.applyLoaded({
        id: 'm',
        status: 'PUBLISHED',
        parts: [{ type: 'DETAILS', indexNo: 0 }],
      });

      facade.addPart(0);
      expect(facade.message()?.parts?.length).toBe(2);
      expect(facade.dirty()).toBe(true);
    });

    it('D1: parts stay mutable on DRAFT and VERIFIED for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });

      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });
      facade.addPart(0);
      expect(facade.message()?.parts?.length).toBe(2);

      facade.applyLoaded({ id: 'm', status: 'VERIFIED', parts: [{ type: 'DETAILS', indexNo: 0 }] });
      facade.addPart(0);
      expect(facade.message()?.parts?.length).toBe(2);
    });
  });

  describe('adjust round-trip (concurrency)', () => {
    const manualSeries = { seriesId: 's', numberSequenceType: 'MANUAL', shortFormat: 'NW-1' };

    it('merges the response into the CURRENT working copy, so an in-flight edit survives', () => {
      const response = new Subject<Message>();
      const { facade, api } = setup({ editor: true });
      api.adjustEditableMessage.mockReturnValue(response);
      facade.applyLoaded({ id: 'm', status: 'DRAFT', shortId: 'OLD', messageSeries: manualSeries });

      facade.adjust().subscribe();
      // A field edit lands while the adjust request is in flight.
      facade.patchMessage({ publishDateFrom: '2026-06-10' });
      // The (late) server response arrives.
      response.next({ id: 'm', shortId: 'NEW', messageSeries: manualSeries });
      response.complete();

      expect(facade.message()?.publishDateFrom).toBe('2026-06-10');
      expect(facade.message()?.shortId).toBe('NEW');
    });

    it('ignores a stale response superseded by a newer adjust', () => {
      const first = new Subject<Message>();
      const second = new Subject<Message>();
      const { facade, api } = setup({ editor: true });
      api.adjustEditableMessage.mockReturnValueOnce(first).mockReturnValueOnce(second);
      facade.applyLoaded({ id: 'm', status: 'DRAFT', messageSeries: manualSeries });

      facade.adjust().subscribe(); // seq 1
      facade.adjust().subscribe(); // seq 2 — supersedes seq 1
      second.next({ id: 'm', shortId: 'NEW', messageSeries: manualSeries });
      second.complete();
      first.next({ id: 'm', shortId: 'STALE', messageSeries: manualSeries }); // arrives late
      first.complete();

      expect(facade.message()?.shortId).toBe('NEW');
    });

    it('drops an in-flight adjust response once a fresh load supersedes it', () => {
      const response = new Subject<Message>();
      const { facade, api } = setup({ editor: true });
      api.adjustEditableMessage.mockReturnValue(response);
      facade.applyLoaded({ id: 'm', status: 'DRAFT', shortId: 'A', messageSeries: manualSeries });

      facade.adjust().subscribe();
      facade.applyLoaded({ id: 'm', status: 'DRAFT', shortId: 'B', messageSeries: manualSeries });
      response.next({ id: 'm', shortId: 'STALE', messageSeries: manualSeries });
      response.complete();

      expect(facade.message()?.shortId).toBe('B');
    });
  });

  describe('navigation-guard allow-list', () => {
    it('bypassGuardOnce is consumed exactly once', () => {
      const { facade } = setup();
      facade.bypassGuardOnce();
      expect(facade.consumeGuardBypass()).toBe(true);
      expect(facade.consumeGuardBypass()).toBe(false);
    });

    it('a fresh load clears a pending bypass (backstop for guard-less source routes)', () => {
      const { facade } = setup();
      facade.bypassGuardOnce();
      facade.applyLoaded(draft);
      expect(facade.consumeGuardBypass()).toBe(false);
    });
  });

  describe('reload()', () => {
    it('refetches the current message via editableDetails and resets the dirty latch', () => {
      const { facade, api } = setup({
        editableResult: { id: 'm-1', status: 'VERIFIED', created: 'z' },
      });
      facade.applyLoaded(draft);
      facade.markUserTouched();
      facade.reload();
      expect(api.editableDetails).toHaveBeenCalledWith('m-1');
      expect(facade.dirty()).toBe(false);
      expect(facade.message()?.status).toBe('VERIFIED');
    });

    it('is a no-op when no message is loaded', () => {
      const { facade, api } = setup();
      facade.reload();
      expect(api.editableDetails).not.toHaveBeenCalled();
    });

    it('error: toasts and preserves the prior message', () => {
      const { facade, api, notify } = setup();
      facade.applyLoaded({ id: 'm-1', status: 'DRAFT' });
      const before = facade.message();
      api.editableDetails = vi.fn(() => throwError(() => new Error('reload failed')));
      facade.reload();
      expect(notify.error).toHaveBeenCalledWith('editor.load.error.load');
      expect(facade.message()).toBe(before);
    });
  });

  describe('save() failure paths', () => {
    const editable: Message = {
      id: 'tmp',
      mainType: 'NW',
      type: 'COASTAL_WARNING',
      messageSeries: { seriesId: 's' },
    };

    it('saveMessage error: errors out, resets saving (finalize), keeps dirty/userTouched', () => {
      const { facade, api } = setup();
      api.saveMessage = vi.fn(() => throwError(() => new Error('save failed')));
      facade.applyLoaded(editable, { dirty: true });
      facade.markUserTouched();

      let errored = false;
      facade.save().subscribe({ next: () => undefined, error: () => (errored = true) });

      expect(errored).toBe(true);
      expect(facade.saving()).toBe(false);
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
    });

    it('reload-leg error (save ok, editableDetails fails): resets saving, leaves the working copy unreloaded and dirty', () => {
      const { facade, api } = setup();
      facade.applyLoaded(editable, { dirty: true });
      api.editableDetails = vi.fn(() => throwError(() => new Error('reload failed')));
      const before = facade.message();

      let errored = false;
      facade.save().subscribe({ next: () => undefined, error: () => (errored = true) });

      expect(api.saveMessage).toHaveBeenCalled();
      expect(errored).toBe(true);
      expect(facade.saving()).toBe(false);
      expect(facade.message()).toBe(before);
      expect(facade.dirty()).toBe(true);
    });
  });

  describe('fireAdjust error handling', () => {
    it('a failed adjust is swallowed and does not corrupt the working copy', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });
      api.adjustEditableMessage = vi.fn(() => throwError(() => new Error('adjust failed')));
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

      expect(() => facade.addPart(0)).not.toThrow();
      expect(facade.message()?.parts?.length).toBe(2);
      expect(errSpy).toHaveBeenCalled();
      errSpy.mockRestore();
    });
  });

  describe('reference mutators', () => {
    const editableDraft: Message = { id: 'm', status: 'DRAFT' };

    it('addReference seeds a blank ref (one desc per model language) and returns its index', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      const index = facade.addReference();
      expect(index).toBe(0);
      const reference = facade.message()?.references?.[0];
      expect(reference?.messageId).toBe('');
      expect(reference?.type).toBe('REFERENCE');
      expect(reference?.descs?.map((d) => d.lang)).toEqual(['en', 'da']);
      expect(facade.userTouched()).toBe(true);
    });

    it('addReference is a no-op (returns -1) for a non-editor', () => {
      const { facade } = setup({ editor: false });
      facade.applyLoaded(editableDraft);
      expect(facade.addReference()).toBe(-1);
      expect(facade.message()?.references ?? []).toEqual([]);
    });

    it('setReferenceField patches messageId/type; setReferenceDesc upserts a per-language description', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      facade.addReference();
      facade.setReferenceField(0, { messageId: 'NW-042', type: 'CANCELLATION' });
      facade.setReferenceDesc(0, 'en', 'see also');
      const reference = facade.message()?.references?.[0];
      expect(reference?.messageId).toBe('NW-042');
      expect(reference?.type).toBe('CANCELLATION');
      expect(reference?.descs?.find((d) => d.lang === 'en')?.description).toBe('see also');
    });

    it('moveReference reorders; removeReference drops the row', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      facade.addReference();
      facade.addReference();
      facade.setReferenceField(0, { messageId: 'A' });
      facade.setReferenceField(1, { messageId: 'B' });

      facade.moveReference(0, 1);
      expect(facade.message()?.references?.map((r) => r.messageId)).toEqual(['B', 'A']);

      facade.removeReference(0);
      expect(facade.message()?.references?.map((r) => r.messageId)).toEqual(['A']);
    });

    it('reference mutations are no-ops on a PUBLISHED message for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });
      facade.applyLoaded({ id: 'm', status: 'PUBLISHED' });
      expect(facade.addReference()).toBe(-1);
      facade.removeReference(0);
      expect(facade.message()?.references ?? []).toEqual([]);
      expect(facade.userTouched()).toBe(false);
    });
  });

  describe('area / category membership mutators', () => {
    const editableDraft: Message = { id: 'm', status: 'DRAFT', mainType: 'NW' };

    it('setAreas replaces the list, marks dirty + userTouched, and adjusts immediately', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      api.adjustEditableMessage.mockClear();

      facade.setAreas([{ id: 7, descs: [{ lang: 'en', name: 'Baltic' }] }]);

      expect(facade.message()?.areas?.[0]?.id).toBe(7);
      expect(facade.message()?.areas?.[0]?.descs?.[0]?.name).toBe('Baltic');
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
    });

    it('setCategories replaces the list and marks dirty WITHOUT adjusting', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      api.adjustEditableMessage.mockClear();

      facade.setCategories([{ id: 3, descs: [{ lang: 'en', name: 'Firing exercises' }] }]);

      const categories = facade.message()?.categories;
      expect(categories?.[0]?.id).toBe(3);
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('membership mutations are no-ops on a PUBLISHED message for a non-admin editor', () => {
      const { facade, api } = setup({ editor: true, admin: false });
      facade.applyLoaded({ id: 'm', status: 'PUBLISHED', mainType: 'NW' });
      api.adjustEditableMessage.mockClear();

      facade.setAreas([{ id: 1 }]);
      facade.setCategories([{ id: 2 }]);

      expect(facade.message()?.areas ?? []).toEqual([]);
      expect(facade.message()?.categories ?? []).toEqual([]);
      expect(facade.dirty()).toBe(false);
      expect(facade.userTouched()).toBe(false);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });
  });

  describe('chart membership mutator', () => {
    const editableDraft: Message = { id: 'm', status: 'DRAFT', mainType: 'NW' };

    it('setCharts replaces the list and marks dirty WITHOUT adjusting', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      api.adjustEditableMessage.mockClear();

      facade.setCharts([{ chartNumber: '101' }, { chartNumber: '102' }]);

      expect(facade.message()?.charts?.map((c) => c.chartNumber)).toEqual(['101', '102']);
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('setCharts is a no-op on a PUBLISHED message for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });
      facade.applyLoaded({ id: 'm', status: 'PUBLISHED', mainType: 'NW' });

      facade.setCharts([{ chartNumber: '101' }]);

      expect(facade.message()?.charts ?? []).toEqual([]);
      expect(facade.dirty()).toBe(false);
    });
  });

  describe('setPartGeometry (geometry commit — dirty + reindex, NO adjust)', () => {
    it('replaces the part geometry, re-threads startCoordIndex message-wide, dirties, no adjust', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        parts: [
          { type: 'DETAILS', indexNo: 0, geometry: { type: 'FeatureCollection', features: [] } },
          {
            type: 'DETAILS',
            indexNo: 1,
            geometry: { type: 'FeatureCollection', features: [pointFeature(2)] },
          },
        ],
      });
      api.adjustEditableMessage.mockClear();

      facade.setPartGeometry(0, { type: 'FeatureCollection', features: [pointFeature(3)] });

      const parts = facade.message()?.parts ?? [];
      // Part 0's new feature (3 coords) stamps at 1; part 1's existing feature shifts to 4.
      expect(parts[0].geometry?.features[0].properties?.['startCoordIndex']).toBe(1);
      expect(parts[1].geometry?.features[0].properties?.['startCoordIndex']).toBe(4);
      expect(facade.dirty()).toBe(true);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('is a no-op for an out-of-range index and for a non-editor', () => {
      const { facade } = setup({ editor: false });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', parts: [{ type: 'DETAILS', indexNo: 0 }] });

      facade.setPartGeometry(0, { type: 'FeatureCollection', features: [pointFeature(1)] });
      facade.setPartGeometry(5, { type: 'FeatureCollection', features: [pointFeature(1)] });

      expect(facade.message()?.parts?.[0].geometry).toBeUndefined();
      expect(facade.dirty()).toBe(false);
    });
  });

  describe('compute from locations', () => {
    const geoDraft = (): Message => ({
      id: 'm',
      status: 'DRAFT',
      mainType: 'NW',
      parts: [
        {
          type: 'DETAILS',
          indexNo: 0,
          geometry: { type: 'FeatureCollection', features: [pointFeature(1)] },
        },
      ],
    });

    it('computeAreas POSTs the affected-excluded geometry, sets areas, adjusts + dirties', () => {
      const { facade, api, areasApi, geometry } = setup({ editor: true });
      geometry.getFeatures.mockReturnValue([pointFeature(1)]);
      areasApi.intersectingAreas.mockReturnValue(
        of([{ id: 9, descs: [{ lang: 'en', name: 'Baltic' }] }] as MessageArea[]),
      );
      facade.applyLoaded(geoDraft());
      api.adjustEditableMessage.mockClear();

      facade.computeAreas();

      expect(geometry.getFeatures).toHaveBeenCalledWith(expect.anything(), {
        excludeAffectedAreas: true,
      });
      expect(facade.message()?.areas?.[0]?.id).toBe(9);
      expect(facade.dirty()).toBe(true);
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
    });

    it('computeAreas is a no-op when no part defines geometry (no POST)', () => {
      const { facade, areasApi, geometry } = setup({ editor: true });
      geometry.getFeatures.mockReturnValue([]);
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW' });

      facade.computeAreas();

      expect(areasApi.intersectingAreas).not.toHaveBeenCalled();
    });

    it('computeAreas toasts and leaves state untouched on an intersecting-areas failure', () => {
      const { facade, areasApi, notify, geometry } = setup({ editor: true });
      geometry.getFeatures.mockReturnValue([pointFeature(1)]);
      areasApi.intersectingAreas.mockReturnValue(throwError(() => new Error('nope')));
      facade.applyLoaded(geoDraft());

      facade.computeAreas();

      expect(notify.error).toHaveBeenCalledWith('editor.areas.computeError');
      expect(facade.message()?.areas ?? []).toEqual([]);
    });

    it('computeCharts POSTs the full geometry, sets the pruned charts, dirties WITHOUT adjusting', () => {
      const { facade, api, chartsApi, geometry } = setup({ editor: true });
      geometry.getFeatures.mockReturnValue([pointFeature(1)]);
      chartsApi.intersectingCharts.mockReturnValue(
        of([{ chartNumber: '101', scale: 50000 }] as ComputedMessageChart[]),
      );
      facade.applyLoaded(geoDraft());
      api.adjustEditableMessage.mockClear();

      facade.computeCharts();

      // Charts include affected features — getFeatures is called WITHOUT the exclude option.
      expect(geometry.getFeatures).toHaveBeenLastCalledWith(expect.anything());
      expect(facade.message()?.charts?.map((c) => c.chartNumber)).toEqual(['101']);
      expect(facade.dirty()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('copyAreaLocations appends the areas geometry to parts[0], reindexes, dirties, NO adjust', () => {
      const { facade, api, areasApi } = setup({ editor: true });
      areasApi.getByIds.mockReturnValue(of([{ id: 3 }] as AreaRef[]));
      areasApi.areaToFeatureCollection.mockReturnValue(areaLocationCollection());
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        mainType: 'NW',
        areas: [{ id: 3 }],
        parts: [
          { type: 'DETAILS', indexNo: 0, geometry: { type: 'FeatureCollection', features: [] } },
        ],
      });
      api.adjustEditableMessage.mockClear();

      facade.copyAreaLocations();

      const features = facade.message()?.parts?.[0].geometry?.features ?? [];
      expect(features).toHaveLength(1);
      expect(features[0].properties?.['areaId']).toBe(3);
      expect(features[0].properties?.['startCoordIndex']).toBe(1);
      expect(facade.dirty()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('copyAreaLocations is a no-op when no area is selected (no fetch)', () => {
      const { facade, areasApi } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW' });

      facade.copyAreaLocations();

      expect(areasApi.getByIds).not.toHaveBeenCalled();
    });
  });

  describe('part value mutators', () => {
    const partDraft: Message = {
      id: 'm',
      status: 'DRAFT',
      mainType: 'NW',
      parts: [{ type: 'DETAILS', indexNo: 0, descs: [], eventDates: [] }],
    };

    it('setPartSubject upserts the per-language subject; setPartHideSubject toggles the flag', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(partDraft);
      facade.setPartSubject(0, 'en', 'Buoy adrift');
      facade.setPartHideSubject(0, true);
      const part = facade.message()?.parts?.[0];
      expect(part?.descs?.find((d) => d.lang === 'en')?.subject).toBe('Buoy adrift');
      expect(part?.hideSubject).toBe(true);
      expect(facade.userTouched()).toBe(true);
    });

    it('setPartDetails upserts the per-language body, marks dirty, and never adjusts', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(partDraft);
      api.adjustEditableMessage.mockClear();

      facade.setPartDetails(0, 'da', '<p>Body DA</p>');

      const part = facade.message()?.parts?.[0];
      expect(part?.descs?.find((d) => d.lang === 'da')?.details).toBe('<p>Body DA</p>');
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('part value mutations are no-ops for a non-editor', () => {
      const { facade } = setup({ editor: false });
      facade.applyLoaded(partDraft);
      facade.setPartSubject(0, 'en', 'x');
      facade.setPartDetails(0, 'en', '<p>x</p>');
      expect(facade.message()?.parts?.[0].descs ?? []).toEqual([]);
    });
  });

  describe('setMessageDesc (message-level publication text)', () => {
    const editableDraft: Message = {
      id: 'm',
      status: 'DRAFT',
      descs: [{ lang: 'en', title: 'T' }],
    };

    it('upserts External/Internal per language, marks dirty, and never adjusts', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(editableDraft);
      api.adjustEditableMessage.mockClear();

      facade.setMessageDesc('en', { publication: '<span publication="7">Pub 7</span>' });
      facade.setMessageDesc('da', { internalPublication: '<span>Intern</span>' });

      const en = facade.message()?.descs?.find((d) => d.lang === 'en');
      const da = facade.message()?.descs?.find((d) => d.lang === 'da');
      // The existing title on the en desc survives the publication upsert (spread, not replace).
      expect(en?.title).toBe('T');
      expect(en?.publication).toBe('<span publication="7">Pub 7</span>');
      // A language with no entry yet is seeded rather than index-crashing.
      expect(da?.internalPublication).toBe('<span>Intern</span>');
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('is a no-op on a PUBLISHED message for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });
      facade.applyLoaded({ id: 'm', status: 'PUBLISHED', descs: [{ lang: 'en' }] });

      facade.setMessageDesc('en', { publication: '<span>x</span>' });

      expect(facade.message()?.descs?.[0]?.publication).toBeUndefined();
      expect(facade.userTouched()).toBe(false);
    });

    it('applyPublicationsUpdate upserts server-rendered publication HTML, creating missing languages', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        descs: [
          { lang: 'en', title: 'T' },
          { lang: 'da', title: 'DT' },
        ],
      });
      api.adjustEditableMessage.mockClear();

      facade.applyPublicationsUpdate({
        id: 'm',
        descs: [
          { lang: 'en', title: 'SERVER TITLE', publication: '<span publication="9">P</span>' },
          { lang: 'da', internalPublication: '<span>I</span>' },
          { lang: 'fr', publication: '<span>fresh</span>' },
        ],
      });

      const en = facade.message()?.descs?.find((d) => d.lang === 'en');
      const da = facade.message()?.descs?.find((d) => d.lang === 'da');
      const fr = facade.message()?.descs?.find((d) => d.lang === 'fr');
      // ONLY the publication fields are copied back — the working title is not overwritten.
      expect(en?.title).toBe('T');
      expect(en?.publication).toBe('<span publication="9">P</span>');
      expect(da?.internalPublication).toBe('<span>I</span>');
      // A language the working copy lacks is CREATED (upsert), not skipped — this is what lets a
      // brand-new blank message (which starts with no descs) receive the picked publication.
      expect(fr?.publication).toBe('<span>fresh</span>');
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });
  });

  describe('event-date mutators', () => {
    const base: Message = {
      id: 'm',
      status: 'DRAFT',
      mainType: 'NW',
      parts: [{ type: 'DETAILS', indexNo: 0, descs: [], eventDates: [] }],
    };

    it('addPartEventDate appends an interval; all-day defaults false for NW, true for NM', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      expect(facade.message()?.parts?.[0].eventDates?.[0].allDay).toBe(false);

      // Re-load the same facade as an NM message — the new interval defaults all-day for NM.
      facade.applyLoaded({ ...base, mainType: 'NM' });
      facade.addPartEventDate(0);
      expect(facade.message()?.parts?.[0].eventDates?.[0].allDay).toBe(true);
    });

    /** A session domain carrying nothing but the zone — all the date mutators read of it. */
    const inZone = (timeZone: string): Domain => ({ domainId: 'd', timeZone });

    /** The interval at index 0 after loading `base` and seeding one row. */
    function interval(facade: EditorFacade) {
      return facade.message()?.parts?.[0].eventDates?.[0];
    }

    it('setPartEventDate snaps an all-day interval to the whole day in UTC', () => {
      const { facade } = setup({ editor: true, sessionDomain: inZone('UTC') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.setPartEventDate(0, 0, {
        allDay: true,
        fromDate: '2026-06-10T09:30:00.000Z',
        toDate: '2026-06-10T15:45:00.000Z',
      });
      expect(interval(facade)?.fromDate).toBe('2026-06-10T00:00:00.000Z');
      // The last MILLISECOND of the day: an inclusive bound at .000 would drop the final second.
      expect(interval(facade)?.toDate).toBe('2026-06-10T23:59:59.999Z');
    });

    it('setPartEventDate takes the day from the domain zone, not the browser', () => {
      // A stable half-hour offset: the bounds land on :30, which no browser-local midnight
      // could produce, and 20:00Z is already the 11th there while it is still the 10th in
      // every zone the suite might run in.
      const { facade } = setup({ editor: true, sessionDomain: inZone('Asia/Kolkata') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.setPartEventDate(0, 0, {
        allDay: true,
        fromDate: '2026-06-10T20:00:00.000Z',
        toDate: '2026-06-10T20:00:00.000Z',
      });
      expect(interval(facade)?.fromDate).toBe('2026-06-10T18:30:00.000Z');
      expect(interval(facade)?.toDate).toBe('2026-06-11T18:29:59.999Z');
      expect(wallClockIn('Asia/Kolkata', new Date(interval(facade)!.fromDate!))).toBe(
        '2026-06-11 00:00:00',
      );
    });

    it('setPartEventDate spans the full 23 hours of a spring-forward day', () => {
      // Europe/Lisbon turns the clocks forward on 29 March, three weeks after the runner's own
      // zone, and sits hours ahead of it, so the bounds below can only have come from the
      // domain's calendar.
      const { facade } = setup({ editor: true, sessionDomain: inZone('Europe/Lisbon') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      // 2026-03-29 is the European spring-forward: the day is one hour short.
      facade.setPartEventDate(0, 0, {
        allDay: true,
        fromDate: '2026-03-29T12:00:00.000Z',
        toDate: '2026-03-29T12:00:00.000Z',
      });
      const from = new Date(interval(facade)!.fromDate!);
      const to = new Date(interval(facade)!.toDate!);
      expect(from.toISOString()).toBe('2026-03-29T00:00:00.000Z');
      expect(to.toISOString()).toBe('2026-03-29T22:59:59.999Z');
      expect(wallClockIn('Europe/Lisbon', from)).toBe('2026-03-29 00:00:00');
      expect(wallClockIn('Europe/Lisbon', to)).toBe('2026-03-29 23:59:59');
      expect(to.getTime() - from.getTime()).toBe(23 * 60 * 60 * 1000 - 1);
    });

    it('setPartEventDate leaves the instants alone and warns when no domain gives a zone', () => {
      const { facade, notify } = setup({ editor: true });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.setPartEventDate(0, 0, { fromDate: '2026-06-10T09:30:00.000Z' });
      facade.setPartEventDate(0, 0, { allDay: true });
      expect(interval(facade)?.allDay).toBe(true);
      expect(interval(facade)?.fromDate).toBe('2026-06-10T09:30:00.000Z');
      expect(notify.error).toHaveBeenCalledWith('common.dateTime.noTimeZone');
    });

    it('duplicatePartEventDate copies the interval as-is', () => {
      const { facade } = setup({ editor: true, sessionDomain: inZone('UTC') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.setPartEventDate(0, 0, { fromDate: '2026-06-10T10:00:00.000Z' });
      facade.duplicatePartEventDate(0, 0);
      const dates = facade.message()?.parts?.[0].eventDates ?? [];
      expect(dates.length).toBe(2);
      expect(dates[1].fromDate).toBe('2026-06-10T10:00:00.000Z');
    });

    it('duplicatePartEventDate keeps the domain wall clock across a DST transition', () => {
      const { facade } = setup({ editor: true, sessionDomain: inZone('Europe/Lisbon') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      // 28 March 11:00 in Lisbon; the next day the clocks go forward, so "same time
      // tomorrow" is 10:00Z, not the 11:00Z a flat 24-hour shift would produce.
      facade.setPartEventDate(0, 0, { fromDate: '2026-03-28T11:00:00.000Z' });
      facade.duplicatePartEventDate(0, 0, 1);
      const dates = facade.message()?.parts?.[0].eventDates ?? [];
      expect(dates[1].fromDate).toBe('2026-03-29T10:00:00.000Z');
      expect(wallClockIn('Europe/Lisbon', new Date(dates[1].fromDate!))).toBe(
        '2026-03-29 11:00:00',
      );
    });

    /**
     * A duplicated all-day interval closes on the same millisecond as the row it came from.
     *
     * The zoned day shift resolves to whole seconds, so a copy that lost the `.999` would end
     * a millisecond before the day it is meant to cover — and the interval would silently
     * exclude the last moment of its own last day.
     */
    it('duplicatePartEventDate keeps the sub-second part of an all-day end bound', () => {
      const { facade } = setup({ editor: true, sessionDomain: inZone('Asia/Kolkata') });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.setPartEventDate(0, 0, {
        allDay: true,
        fromDate: '2026-06-10T20:00:00.000Z',
        toDate: '2026-06-10T20:00:00.000Z',
      });
      expect(interval(facade)?.toDate).toBe('2026-06-11T18:29:59.999Z');

      facade.duplicatePartEventDate(0, 0, 1);
      const dates = facade.message()?.parts?.[0].eventDates ?? [];
      expect(dates[1].fromDate).toBe('2026-06-11T18:30:00.000Z');
      expect(dates[1].toDate).toBe('2026-06-12T18:29:59.999Z');
    });

    it('duplicatePartEventDate refuses a day shift with no zone to shift it in', () => {
      const { facade, notify } = setup({ editor: true });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.duplicatePartEventDate(0, 0, 1);
      expect(facade.message()?.parts?.[0].eventDates?.length).toBe(1);
      expect(notify.error).toHaveBeenCalledWith('common.dateTime.noTimeZone');
    });

    it('removePartEventDate drops the interval at the index', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(base);
      facade.addPartEventDate(0);
      facade.addPartEventDate(0);
      facade.removePartEventDate(0, 0);
      expect(facade.message()?.parts?.[0].eventDates?.length).toBe(1);
    });
  });

  describe('debounced adjust', () => {
    it('coalesces bursts into one trailing adjust after 500 ms', () => {
      vi.useFakeTimers();
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', messageSeries: { seriesId: 's' } });
      api.adjustEditableMessage.mockClear();

      facade.adjustDebounced();
      facade.adjustDebounced();
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
      vi.advanceTimersByTime(500);
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
    });

    it('flushAdjust fires a pending debounced adjust immediately (blur hook)', () => {
      vi.useFakeTimers();
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', messageSeries: { seriesId: 's' } });
      api.adjustEditableMessage.mockClear();

      facade.adjustDebounced();
      facade.flushAdjust();
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
      // The timer was cleared, so no second fire.
      vi.advanceTimersByTime(500);
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
    });

    it('a load cancels a pending debounced adjust', () => {
      vi.useFakeTimers();
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', messageSeries: { seriesId: 's' } });
      api.adjustEditableMessage.mockClear();

      facade.adjustDebounced();
      facade.applyLoaded({ id: 'm2', status: 'DRAFT' });
      vi.advanceTimersByTime(500);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
      vi.useRealTimers();
    });
  });

  describe('attachments', () => {
    const att = (over: Partial<MessageAttachment> = {}): MessageAttachment => ({
      path: 'messages/uid-1/3/photo.png',
      fileName: 'photo.png',
      type: 'image/png',
      ...over,
    });

    const loadedWithAttachments = (attachments: MessageAttachment[]): Message => ({
      id: 'm-1',
      status: 'DRAFT',
      editRepoPath: 'messages/uid-1',
      revision: 3,
      attachments,
    });

    it('addAttachments appends the uploaded VOs and marks touched', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(loadedWithAttachments([att({ fileName: 'a.png' })]));

      facade.addAttachments([att({ fileName: 'b.png' }), att({ fileName: 'c.png' })]);

      expect(facade.message()?.attachments?.map((a) => a.fileName)).toEqual([
        'a.png',
        'b.png',
        'c.png',
      ]);
      expect(facade.userTouched()).toBe(true);
    });

    it('addAttachments is a no-op for an empty list', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(loadedWithAttachments([att()]));

      facade.addAttachments([]);

      expect(facade.message()?.attachments).toHaveLength(1);
      expect(facade.userTouched()).toBe(false);
    });

    it('updateAttachment merges a caption/display patch into the addressed attachment', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(loadedWithAttachments([att(), att({ fileName: 'other.png' })]));

      facade.updateAttachment(0, {
        display: 'ABOVE',
        descs: [{ lang: 'en', caption: 'Wreck' }],
      });

      const updated = facade.message()?.attachments ?? [];
      expect(updated[0].display).toBe('ABOVE');
      expect(updated[0].descs).toEqual([{ lang: 'en', caption: 'Wreck' }]);
      // The sibling attachment is untouched.
      expect(updated[1].fileName).toBe('other.png');
    });

    it("updateAttachment with display '' clears display/width/height", () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(
        loadedWithAttachments([att({ display: 'BELOW', width: '50%', height: '200px' })]),
      );

      facade.updateAttachment(0, { display: '' });

      const cleared = facade.message()?.attachments?.[0] ?? {};
      expect('display' in cleared).toBe(false);
      expect('width' in cleared).toBe(false);
      expect('height' in cleared).toBe(false);
    });

    it('moveAttachment moves an attachment to another place and marks touched', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(
        loadedWithAttachments([
          att({ fileName: 'a.png' }),
          att({ fileName: 'b.png' }),
          att({ fileName: 'c.png' }),
        ]),
      );

      facade.moveAttachment(0, 2);
      expect(facade.message()?.attachments?.map((a) => a.fileName)).toEqual([
        'b.png',
        'c.png',
        'a.png',
      ]);
      expect(facade.userTouched()).toBe(true);

      facade.moveAttachment(2, 1);
      expect(facade.message()?.attachments?.map((a) => a.fileName)).toEqual([
        'b.png',
        'a.png',
        'c.png',
      ]);
    });

    it('moveAttachment refuses out-of-range and no-op moves and leaves the message untouched', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded(
        loadedWithAttachments([att({ fileName: 'a.png' }), att({ fileName: 'b.png' })]),
      );
      const before = facade.message();

      facade.moveAttachment(0, 0);
      facade.moveAttachment(-1, 0);
      facade.moveAttachment(0, 2);
      facade.moveAttachment(2, 0);

      expect(facade.message()).toBe(before);
      expect(facade.userTouched()).toBe(false);
    });

    it('removeAttachment drops the model entry AND deletes the file when it is the current revision', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(loadedWithAttachments([att({ fileName: 'photo file.png' })]));

      facade.removeAttachment(0);

      expect(facade.message()?.attachments).toHaveLength(0);
      // The DELETE target is the RE-BUILT encoded editRepoPath/revision/fileName path.
      expect(api.deleteRepoFile).toHaveBeenCalledTimes(1);
      expect(api.deleteRepoFile).toHaveBeenCalledWith('messages/uid-1/3/photo%20file.png');
    });

    it('removeAttachment on a prior-revision file is model-only (no physical delete)', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded(loadedWithAttachments([att({ path: 'messages/uid-1/2/photo.png' })]));

      facade.removeAttachment(0);

      expect(facade.message()?.attachments).toHaveLength(0);
      expect(api.deleteRepoFile).not.toHaveBeenCalled();
    });

    it('removeAttachment issues no delete when the message has no editRepoPath', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT', attachments: [att()] });

      facade.removeAttachment(0);

      expect(facade.message()?.attachments).toHaveLength(0);
      expect(api.deleteRepoFile).not.toHaveBeenCalled();
    });

    it('setThumbnail patches thumbnailPath and marks touched', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({ id: 'm', status: 'DRAFT' });

      facade.setThumbnail('messages/uid-1/3/custom_thumb_256.png');

      expect(facade.message()?.thumbnailPath).toBe('messages/uid-1/3/custom_thumb_256.png');
      expect(facade.userTouched()).toBe(true);
    });

    it('clearThumbnail on a current-revision file unsets the path AND deletes the file itself', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        editRepoPath: 'messages/uid-1',
        revision: 3,
        thumbnailPath: 'messages/uid-1/3/custom_thumb_256.png',
      });

      facade.clearThumbnail();

      expect(facade.message()?.thumbnailPath).toBeUndefined();
      expect(facade.userTouched()).toBe(true);
      // The thumbnail delete target is the thumbnailPath ITSELF (not a re-built path).
      expect(api.deleteRepoFile).toHaveBeenCalledWith('messages/uid-1/3/custom_thumb_256.png');
    });

    it('clearThumbnail on a prior-revision thumbnail is model-only (history preserved)', () => {
      const { facade, api } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        editRepoPath: 'messages/uid-1',
        revision: 3,
        thumbnailPath: 'messages/uid-1/2/custom_thumb_256.png',
      });

      facade.clearThumbnail();

      expect(facade.message()?.thumbnailPath).toBeUndefined();
      expect(api.deleteRepoFile).not.toHaveBeenCalled();
    });
  });

  describe('promulgation mutators', () => {
    // The three-entry promulgations array off a real editable message (SafetyNET MANDATORY+on,
    // Audio MANDATORY+off, NAVTEX OPTIONAL+off).
    const safetynet = (): BasePromulgation => ({
      '@class': 'org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo',
      id: 2063152,
      type: {
        typeId: 'safetynet',
        serviceId: 'safetynet',
        name: 'SafetyNET',
        requirement: 'MANDATORY',
      },
      promulgate: true,
    });
    const audio = (): BasePromulgation => ({
      '@class': 'org.niord.core.promulgation.vo.AudioMessagePromulgationVo',
      type: { typeId: 'audio', serviceId: 'audio', name: 'Audio', requirement: 'MANDATORY' },
      promulgate: false,
    });
    const navtex = (): BasePromulgation =>
      ({
        '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
        type: {
          typeId: 'navtex',
          serviceId: 'navtex',
          name: 'NAVTEX/S-53',
          requirement: 'OPTIONAL',
        },
        promulgate: false,
        priority: 'ROUTINE',
        transmitters: { Rogaland: false, Baltico: false, Aasiaat: false },
        preamble: '${publish-date}',
      }) as BasePromulgation;

    const generatedNavtex = (): BasePromulgation =>
      ({
        '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
        type: {
          typeId: 'navtex',
          serviceId: 'navtex',
          name: 'NAVTEX/S-53',
          requirement: 'OPTIONAL',
        },
        promulgate: true,
        priority: 'ROUTINE',
        transmitters: { Rogaland: false, Baltico: true, Aasiaat: false },
        text: 'ANCHORLAND. NORTH SOUND.',
      }) as BasePromulgation;

    it('patchPromulgation replaces one entry immutably, marks dirty, does NOT adjust', () => {
      const { facade, api } = setup({ editor: true });
      const [sn, a, n] = [safetynet(), audio(), navtex()];
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        type: 'COASTAL_WARNING',
        promulgations: [sn, a, n],
      });
      api.adjustEditableMessage.mockClear();

      facade.patchPromulgation('navtex', { text: 'edited' });

      const proms = facade.message()?.promulgations ?? [];
      expect((proms[2] as { text?: string }).text).toBe('edited');
      // Siblings are the SAME references (untouched).
      expect(proms[0]).toBe(sn);
      expect(proms[1]).toBe(a);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('setPromulgate refuses to uncheck a MANDATORY channel that is promulgating', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        type: 'COASTAL_WARNING',
        promulgations: [safetynet()],
      });

      facade.setPromulgate('safetynet', false);

      expect(facade.message()?.promulgations?.[0]?.promulgate).toBe(true);
      expect(facade.userTouched()).toBe(false);
    });

    it('setPromulgate can check a MANDATORY channel on and toggle an OPTIONAL one both ways', () => {
      const { facade } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        type: 'COASTAL_WARNING',
        promulgations: [audio(), navtex()],
      });

      facade.setPromulgate('audio', true);
      expect(facade.message()?.promulgations?.[0]?.promulgate).toBe(true);

      facade.setPromulgate('navtex', true);
      expect(facade.message()?.promulgations?.[1]?.promulgate).toBe(true);
      facade.setPromulgate('navtex', false);
      expect(facade.message()?.promulgations?.[1]?.promulgate).toBe(false);
    });

    it('setPromulgate auto-deduces the type (one messageType, type unset) and fires adjust(inclPromulgations)', () => {
      const sessionDomain: Domain = {
        domainId: 'd',
        messageSeries: [{ seriesId: 's', mainType: 'NW' }],
      };
      const { facade, api, promulgationsApi } = setup({ editor: true, sessionDomain });
      promulgationsApi.getPromulgationType.mockReturnValueOnce(
        of({ typeId: 'navtex', messageTypes: ['COASTAL_WARNING'] } as PromulgationType),
      );
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW', promulgations: [navtex()] });
      api.adjustEditableMessage.mockClear();

      facade.setPromulgate('navtex', true);

      expect(promulgationsApi.getPromulgationType).toHaveBeenCalledWith('navtex');
      expect(facade.message()?.type).toBe('COASTAL_WARNING');
      expect(facade.message()?.messageSeries?.seriesId).toBe('s');
      expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
      expect((api.adjustEditableMessage.mock.calls[0] as unknown[])[1]).toEqual({
        inclPromulgations: true,
      });
    });

    it('setPromulgate does NOT deduce when the type is already set', () => {
      const { facade, promulgationsApi } = setup({ editor: true });
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        type: 'COASTAL_WARNING',
        promulgations: [navtex()],
      });

      facade.setPromulgate('navtex', true);

      expect(promulgationsApi.getPromulgationType).not.toHaveBeenCalled();
    });

    it('setPromulgate does NOT deduce when the promulgation type maps to multiple message types', () => {
      const { facade, api, promulgationsApi } = setup({ editor: true });
      promulgationsApi.getPromulgationType.mockReturnValueOnce(
        of({
          typeId: 'navtex',
          messageTypes: ['COASTAL_WARNING', 'LOCAL_WARNING'],
        } as PromulgationType),
      );
      facade.applyLoaded({ id: 'm', status: 'DRAFT', mainType: 'NW', promulgations: [navtex()] });
      api.adjustEditableMessage.mockClear();

      facade.setPromulgate('navtex', true);

      expect(facade.message()?.type).toBeUndefined();
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('applyGeneratedPromulgation deep-merges by typeId, keeps @class, leaves siblings untouched, no adjust', () => {
      const { facade, api } = setup({ editor: true });
      const [sn, a, n] = [safetynet(), audio(), navtex()];
      facade.applyLoaded({
        id: 'm',
        status: 'DRAFT',
        type: 'COASTAL_WARNING',
        promulgations: [sn, a, n],
      });
      api.adjustEditableMessage.mockClear();

      facade.applyGeneratedPromulgation('navtex', generatedNavtex());

      const proms = facade.message()?.promulgations ?? [];
      const merged = proms[2] as {
        '@class'?: string;
        text?: string;
        preamble?: string;
        promulgate: boolean;
        transmitters?: Record<string, boolean>;
      };
      expect(merged['@class']).toBe('org.niord.core.promulgation.vo.NavtexMessagePromulgationVo');
      expect(merged.text).toBe('ANCHORLAND. NORTH SOUND.');
      // preamble was absent from the response → survives.
      expect(merged.preamble).toBe('${publish-date}');
      expect(merged.promulgate).toBe(true);
      expect(merged.transmitters?.['Baltico']).toBe(true);
      // Sibling entries are the SAME references.
      expect(proms[0]).toBe(sn);
      expect(proms[1]).toBe(a);
      expect(facade.userTouched()).toBe(true);
      expect(api.adjustEditableMessage).not.toHaveBeenCalled();
    });

    it('promulgation mutators are no-ops on a PUBLISHED message for a non-admin editor', () => {
      const { facade } = setup({ editor: true, admin: false });
      facade.applyLoaded({
        id: 'm',
        status: 'PUBLISHED',
        type: 'COASTAL_WARNING',
        promulgations: [navtex()],
      });

      facade.patchPromulgation('navtex', { text: 'x' });
      facade.setPromulgate('navtex', true);
      facade.applyGeneratedPromulgation('navtex', generatedNavtex());

      const entry = facade.message()?.promulgations?.[0] as { text?: string; promulgate: boolean };
      expect(entry.text).toBeUndefined();
      expect(entry.promulgate).toBe(false);
      expect(facade.userTouched()).toBe(false);
    });
  });
});
