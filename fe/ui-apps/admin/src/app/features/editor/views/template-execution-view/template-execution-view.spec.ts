import { NgTemplateOutlet } from '@angular/common';
import { NO_ERRORS_SCHEMA, type Signal, signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../../messages/models/message.model';
import type {
  ParamType,
  SystemCategory,
  TemplateParam,
  TemplateParamValues,
} from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { AreasApiService } from '../../../messages/services/api/areas-api.service';
import { MessageGeometryService } from '../../../messages/services/message-geometry.service';
import { TemplatesApiService } from '../../services/api/templates-api.service';
import { EditorFacade } from '../../services/editor-facade';
import {
  type TemplateExecutionSeed,
  TemplateExecutionSeedStore,
} from '../../services/template-execution-seed-store';
import { TemplateMessageStore } from '../../services/template-message-store';
import { TemplateExecutionView } from './template-execution-view';

/** The protected surface the logic tests drive. */
interface ViewHandle {
  readonly templates: WritableSignal<readonly SystemCategory[]>;
  readonly values: WritableSignal<TemplateParamValues[]>;
  readonly messageCopy: WritableSignal<Message>;
  readonly drawerOpen: WritableSignal<boolean>;
  readonly touched: WritableSignal<boolean>;
  readonly paramTypes: WritableSignal<ReadonlyMap<string, ParamType>>;
  readonly missingMandatory: Signal<readonly string[]>;
  readonly canExecute: Signal<boolean>;
  onDrawerConfirm(chosen: readonly SystemCategory[]): void;
  removeTemplate(index: number): void;
  execute(): void;
  isDirty(): boolean;
  onGeometryChanged(): void;
  onComputeAreas(): void;
}

function tmpl(id: number, name: string): SystemCategory {
  return {
    id,
    type: 'TEMPLATE',
    descs: [{ lang: 'en', name }],
    templateParams: [],
    stdTemplateFields: [],
  };
}

function setup(options: {
  seed?: TemplateExecutionSeed;
  executeResult?: Message;
  hydrated?: readonly SystemCategory[];
  /** The editor's sticky latch: set by any edit, even one undone since. */
  dirty?: boolean;
  /** The editor's `hasUnsavedChanges`: an edit not undone, or a message never saved. */
  unsaved?: boolean;
  /** Render the real template, its child components left as unknown, inert elements. */
  render?: boolean;
  /** What the server answers for a fresh blank message (none by default). */
  serverBlank?: Message;
}) {
  const execute = vi.fn(() => of(options.executeResult ?? ({ id: '', mainType: 'NW' } as Message)));
  const templatesApi = { parameterTypes: () => of([]), execute };
  const getTemplateDetails = vi.fn(() => of(options.hydrated ?? []));
  const categoriesApi = { getTemplateDetails };
  // Area auto-compute: stubbed to "no geometry", so it short-circuits before any request. The
  // compute itself is covered by its own describe below, which overrides these.
  const intersectingAreas = vi.fn(() => of([]));
  const areasApi = { intersectingAreas };
  const getFeatures = vi.fn(() => []);
  const geometryService = { getFeatures };
  const messagesApi = {
    newMessageTemplate: vi.fn(() => of(options.serverBlank ?? null)),
    adjustEditableMessage: vi.fn(() => of({ id: '', promulgations: [] } as Message)),
  };
  const navigate = vi.fn();
  const navigateByUrl = vi.fn();
  const templateStore = { set: vi.fn(), consume: vi.fn(), clear: vi.fn() };
  const facade = {
    dirty: signal(options.dirty ?? false),
    hasUnsavedChanges: signal(options.unsaved ?? false),
    message: signal<Message | undefined>(undefined),
    templateExecuted: vi.fn(),
    bypassGuardOnce: vi.fn(),
  };
  const seedStore = { consume: () => options.seed, set: vi.fn(), clear: vi.fn() };
  const discardConfirm = { confirmDiscard: vi.fn(() => Promise.resolve(true)) };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: TemplatesApiService, useValue: templatesApi },
      { provide: CategoriesApiService, useValue: categoriesApi },
      { provide: MessagesApiService, useValue: messagesApi },
      { provide: AreasApiService, useValue: areasApi },
      { provide: MessageGeometryService, useValue: geometryService },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), modelLanguages: [{ value: 'en' }] },
      },
      { provide: NotificationService, useValue: { error: vi.fn() } },
      { provide: EditorFacade, useValue: facade },
      { provide: TemplateMessageStore, useValue: templateStore },
      { provide: TemplateExecutionSeedStore, useValue: seedStore },
      { provide: DiscardConfirmService, useValue: discardConfirm },
      { provide: Router, useValue: { navigate, navigateByUrl } },
    ],
  });

  // Render nothing — this suite drives the component CLASS logic (seed/reconcile/execute/cancel);
  // the heavy child components (drawer / live preview / fields) have their own specs. With
  // `render`, the real template renders and those children stay unknown, inert elements.
  TestBed.overrideComponent(
    TemplateExecutionView,
    options.render
      ? { set: { imports: [TranslocoPipe, NgTemplateOutlet], schemas: [NO_ERRORS_SCHEMA] } }
      : { set: { template: '' } },
  );
  const fixture = TestBed.createComponent(TemplateExecutionView);
  const handle = fixture.componentInstance as unknown as ViewHandle;
  return {
    fixture,
    handle,
    execute,
    getTemplateDetails,
    messagesApi,
    navigate,
    navigateByUrl,
    templateStore,
    facade,
    intersectingAreas,
    getFeatures,
  };
}

const createSeed: TemplateExecutionSeed = {
  message: { id: '', mainType: 'NW', categories: [] },
  returnUrl: '/editor',
  blank: true,
};

describe('TemplateExecutionView', () => {
  it('a create seed (no categories) opens the selection drawer', () => {
    const { handle } = setup({ seed: createSeed });
    expect(handle.drawerOpen()).toBe(true);
    expect(handle.templates()).toHaveLength(0);
  });

  it('a re-run seed hydrates the working templates and does not open the drawer', () => {
    const { handle, messagesApi } = setup({
      seed: {
        message: {
          id: 'm',
          created: 't',
          mainType: 'NW',
          categories: [{ id: 5 } as SystemCategory],
        },
        returnUrl: '/editor/edit/m',
        blank: false,
      },
      hydrated: [tmpl(5, 'Light')],
    });
    expect(handle.templates().map((t) => t.id)).toEqual([5]);
    expect(handle.drawerOpen()).toBe(false);
    // A saved working copy is the base — never replaced by a fresh server blank.
    expect(messagesApi.newMessageTemplate).not.toHaveBeenCalled();
  });

  it('a Start-screen template pick (blank seed WITH a category) hydrates it, seeds a server blank, and skips the drawer', () => {
    const { handle, messagesApi } = setup({
      seed: {
        message: { id: '', mainType: 'NW', categories: [{ id: 5 } as SystemCategory] },
        returnUrl: '/editor',
        blank: true,
      },
      hydrated: [tmpl(5, 'Light')],
    });
    expect(handle.templates().map((t) => t.id)).toEqual([5]);
    expect(handle.drawerOpen()).toBe(false);
    // The blank pick still starts from a proper server blank (promulgations, series defaults).
    expect(messagesApi.newMessageTemplate).toHaveBeenCalledWith('NW');
  });

  describe('a working copy never saved is kept as it is', () => {
    // A server blank that differs from the seed in every field the tests read.
    const serverBlank: Message = { id: 'blank', mainType: 'NW', descs: [] };

    function spyConfirm(fixture: ReturnType<typeof setup>['fixture']) {
      const confirm = fixture.debugElement.injector.get(ConfirmService);
      return vi.spyOn(confirm, 'confirmNeutral').mockImplementation(() => undefined);
    }

    it('a new message keeps what was filled in, on the page and in the Execute payload', () => {
      const { fixture, handle, messagesApi, execute } = setup({
        seed: {
          message: {
            id: 'uuid-1',
            mainType: 'NW',
            categories: [{ id: 5 } as SystemCategory],
            areas: [{ id: 7 }],
            descs: [{ lang: 'en', title: 'Typed' }],
          },
          returnUrl: '/editor/edit',
          blank: false,
        },
        hydrated: [tmpl(5, 'Light')],
        unsaved: true,
        serverBlank,
      });
      expect(messagesApi.newMessageTemplate).not.toHaveBeenCalled();
      expect(handle.messageCopy().id).toBe('uuid-1');
      expect(handle.messageCopy().areas).toEqual([{ id: 7 }]);
      expect(handle.messageCopy().descs?.[0]?.title).toBe('Typed');

      const confirmNeutral = spyConfirm(fixture);
      handle.execute();
      confirmNeutral.mock.calls[0][0].accept();

      const payload = (execute.mock.calls as unknown as [Message][])[0][0];
      expect(payload.id).toBe('uuid-1');
      expect(payload.areas).toEqual([{ id: 7 }]);
      expect(payload.descs?.[0]?.title).toBe('Typed');
    });

    it('a copy of a message is not replaced by a blank', () => {
      const geometry = {
        type: 'FeatureCollection' as const,
        features: [
          {
            type: 'Feature' as const,
            properties: {},
            geometry: { type: 'Point' as const, coordinates: [10, 56] },
          },
        ],
      };
      const { handle, messagesApi } = setup({
        seed: {
          message: {
            id: 'uuid-2',
            mainType: 'NW',
            categories: [{ id: 5 } as SystemCategory],
            parts: [{ type: 'DETAILS', geometry }],
          },
          returnUrl: '/editor/edit',
          blank: false,
        },
        hydrated: [tmpl(5, 'Light')],
        serverBlank,
      });

      expect(messagesApi.newMessageTemplate).not.toHaveBeenCalled();
      expect(handle.messageCopy().id).toBe('uuid-2');
      expect(handle.messageCopy().parts?.[0]?.geometry).toEqual(geometry);
    });
  });

  it('a drawer confirm reconciles values by category id (keep chosen, append new, drop unchecked)', () => {
    const { handle } = setup({ seed: createSeed });
    handle.templates.set([tmpl(1, 'A'), tmpl(2, 'B')]);
    handle.values.set([
      { posDecimals: '1', positions: [], mark: 'A' },
      { posDecimals: '1', positions: [], mark: 'B' },
    ]);
    handle.messageCopy.set({ id: '', parts: [{ type: 'DETAILS' }, { type: 'DETAILS' }] });

    // Uncheck A, keep B, add C.
    handle.onDrawerConfirm([tmpl(2, 'B'), tmpl(3, 'C')]);

    expect(handle.templates().map((t) => t.id)).toEqual([2, 3]);
    expect(handle.values()[0]['mark']).toBe('B'); // B's value followed it
    expect(handle.values()[1]['mark']).toBeUndefined(); // C got a fresh record
    expect(handle.values()).toHaveLength(2);
    expect(handle.touched()).toBe(true);
    expect(handle.drawerOpen()).toBe(false);
  });

  it('removing a template drops its aligned value + part in lock-step', () => {
    const { handle } = setup({ seed: createSeed });
    handle.templates.set([tmpl(1, 'A'), tmpl(2, 'B')]);
    handle.values.set([
      { posDecimals: '1', positions: [], mark: 'A' },
      { posDecimals: '1', positions: [], mark: 'B' },
    ]);
    handle.messageCopy.set({ id: '', parts: [{ type: 'DETAILS' }, { type: 'CULTURE' }] });

    handle.removeTemplate(0);

    expect(handle.templates().map((t) => t.id)).toEqual([2]);
    expect(handle.values()[0]['mark']).toBe('B');
    expect(handle.messageCopy().parts?.[0]?.type).toBe('CULTURE');
  });

  it('executing a NEW result seeds the store and enters the born-dirty template route', () => {
    const { handle, templateStore, navigate, facade } = setup({
      seed: {
        message: { id: '', mainType: 'NW', categories: [{ id: 5 } as SystemCategory] },
        returnUrl: '/editor',
        blank: true,
      },
      hydrated: [tmpl(5, 'Light')],
      executeResult: { id: '', mainType: 'NW', descs: [{ lang: 'en', title: 'X' }] },
    });

    handle.execute();

    expect(templateStore.set).toHaveBeenCalledTimes(1);
    expect(facade.templateExecuted).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/editor/edit']);
  });

  it('executing a SAVED result applies it in place and lands on its own edit URL', () => {
    const { handle, navigate, facade, templateStore } = setup({
      seed: {
        message: {
          id: 'm',
          created: 't',
          mainType: 'NW',
          categories: [{ id: 5 } as SystemCategory],
        },
        returnUrl: '/editor/edit/m',
        blank: false,
      },
      hydrated: [tmpl(5, 'Light')],
      executeResult: { id: 'm', created: 't', mainType: 'NW' },
    });

    handle.execute();

    expect(facade.templateExecuted).toHaveBeenCalledTimes(1);
    expect(templateStore.set).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/editor/edit', 'm']);
  });

  describe('the replace confirm before Execute', () => {
    const savedSeed = {
      seed: {
        message: {
          id: 'm',
          created: 't',
          mainType: 'NW' as const,
          categories: [{ id: 5 } as SystemCategory],
        },
        returnUrl: '/editor/edit/m',
        blank: false,
      },
      hydrated: [tmpl(5, 'Light')],
      executeResult: { id: 'm', created: 't', mainType: 'NW' as const },
    };

    function spyConfirm(fixture: ReturnType<typeof setup>['fixture']) {
      const confirm = fixture.debugElement.injector.get(ConfirmService);
      return vi.spyOn(confirm, 'confirmNeutral').mockImplementation(() => undefined);
    }

    it('asks before replacing a message with unsaved changes, and runs only on accept', () => {
      const { fixture, handle, execute } = setup({ ...savedSeed, dirty: true, unsaved: true });
      const confirmNeutral = spyConfirm(fixture);

      handle.execute();

      expect(confirmNeutral).toHaveBeenCalledTimes(1);
      expect(execute).not.toHaveBeenCalled();
      confirmNeutral.mock.calls[0][0].accept();
      expect(execute).toHaveBeenCalledTimes(1);
    });

    it('does not ask for a saved message whose edits were undone (the sticky dirty latch is not read)', () => {
      const { fixture, handle, execute } = setup({ ...savedSeed, dirty: true, unsaved: false });
      const confirmNeutral = spyConfirm(fixture);

      handle.execute();

      expect(confirmNeutral).not.toHaveBeenCalled();
      expect(execute).toHaveBeenCalledTimes(1);
    });
  });

  it('isDirty reflects the local touched flag', () => {
    const { handle } = setup({ seed: createSeed });
    expect(handle.isDirty()).toBe(false);
    handle.touched.set(true);
    expect(handle.isDirty()).toBe(true);
  });

  describe('a reload or tab close', () => {
    /** Fire the browser's leave event at the window; true when a listener asked the browser to stay. */
    function leavePrevented(): boolean {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    }

    it('makes the browser ask once a parameter was typed, and not before', async () => {
      const { fixture } = setup({
        seed: {
          message: { id: '', mainType: 'NW', categories: [{ id: 5 } as SystemCategory] },
          returnUrl: '/editor',
          blank: true,
        },
        hydrated: [tmpl(5, 'Light')],
        render: true,
      });
      await fixture.whenStable();

      expect(leavePrevented()).toBe(false);

      // The parameter cards report a typed value as `userEdited`.
      const fields = (fixture.nativeElement as HTMLElement).querySelector(
        'app-template-execute-fields',
      )!;
      fields.dispatchEvent(new CustomEvent('userEdited'));

      expect(leavePrevented()).toBe(true);
    });

    it('lets the page go once the view is gone', () => {
      const { fixture, handle } = setup({ seed: createSeed });
      handle.touched.set(true);
      fixture.destroy();

      expect(leavePrevented()).toBe(false);
    });
  });
});

describe('TemplateExecutionView area auto-compute', () => {
  const withGeometry = { seed: createSeed, hydrated: [tmpl(5, 'Light')] };
  const oneFeature = [{ type: 'Feature' }] as never[];

  it('derives the areas once locations are entered', () => {
    const { handle, intersectingAreas, getFeatures } = setup(withGeometry);
    getFeatures.mockReturnValue(oneFeature);
    intersectingAreas.mockReturnValue(of([{ id: 7 }]) as never);

    handle.onGeometryChanged();

    expect(intersectingAreas).toHaveBeenCalledTimes(1);
    expect(handle.messageCopy().areas).toEqual([{ id: 7 }]);
  });

  it('refuses to touch a selection the user made by hand', () => {
    const { handle, intersectingAreas, getFeatures } = setup(withGeometry);
    getFeatures.mockReturnValue(oneFeature);
    handle.messageCopy.update((m) => ({ ...m, areas: [{ id: 1 }] }));

    handle.onGeometryChanged();

    expect(intersectingAreas).not.toHaveBeenCalled();
    expect(handle.messageCopy().areas).toEqual([{ id: 1 }]);
  });

  it('refuses to BLANK the areas when the geometry hits nothing', () => {
    const { handle, intersectingAreas, getFeatures } = setup(withGeometry);
    getFeatures.mockReturnValue(oneFeature);
    intersectingAreas.mockReturnValue(of([]) as never);
    handle.messageCopy.update((m) => ({ ...m, areas: undefined }));

    handle.onGeometryChanged();

    expect(handle.messageCopy().areas).toBeUndefined();
  });

  it('the manual action overrides both refusals', () => {
    const { handle, intersectingAreas, getFeatures } = setup(withGeometry);
    getFeatures.mockReturnValue(oneFeature);
    intersectingAreas.mockReturnValue(of([{ id: 9 }]) as never);
    handle.messageCopy.update((m) => ({ ...m, areas: [{ id: 1 }] }));

    handle.onComputeAreas();

    expect(handle.messageCopy().areas).toEqual([{ id: 9 }]);
  });

  it('makes no request at all when no part carries geometry', () => {
    const { handle, intersectingAreas } = setup(withGeometry);

    handle.onGeometryChanged();

    expect(intersectingAreas).not.toHaveBeenCalled();
  });
});

describe('TemplateExecutionView mandatory parameters', () => {
  const depth: TemplateParam = {
    paramId: 'depth',
    type: 'number',
    mandatory: true,
    descs: [{ lang: 'en', name: 'Depth' }],
  };
  const note: TemplateParam = {
    paramId: 'note',
    type: 'text',
    descs: [{ lang: 'en', name: 'Note' }],
  };
  const types = new Map<string, ParamType>([
    ['number', { type: 'STANDARD', name: 'number' }],
    ['text', { type: 'STANDARD', name: 'text' }],
    ['boolean', { type: 'STANDARD', name: 'boolean' }],
    [
      'aton',
      {
        type: 'COMPOSITE',
        name: 'aton',
        templateParams: [
          { paramId: 'atonName', type: 'text', descs: [{ lang: 'en', name: 'AtoN name' }] },
          {
            paramId: 'atonType',
            type: 'text',
            mandatory: true,
            descs: [{ lang: 'en', name: 'AtoN type' }],
          },
        ],
      },
    ],
    [
      'pair',
      {
        type: 'COMPOSITE',
        name: 'pair',
        templateParams: [
          { paramId: 'a', type: 'text' },
          { paramId: 'b', type: 'text' },
        ],
      },
    ],
  ]);

  function withParams(params: readonly TemplateParam[], render = false) {
    const template: SystemCategory = { ...tmpl(5, 'Reduced depth'), templateParams: params };
    const context = setup({
      seed: {
        message: { id: '', mainType: 'NW', categories: [{ id: 5 } as SystemCategory] },
        returnUrl: '/editor',
        blank: true,
      },
      hydrated: [template],
      render,
    });
    context.handle.paramTypes.set(types);
    return context;
  }

  it('Execute is held back while a mandatory parameter is empty, and names it', () => {
    const { handle, templateStore, navigate } = withParams([depth, note]);

    expect(handle.missingMandatory()).toEqual(['Depth']);
    expect(handle.canExecute()).toBe(false);

    handle.execute();

    expect(templateStore.set).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalledWith(['/editor/edit']);
  });

  it('a blank text and an empty list count as empty; false and 0 are values', () => {
    const flag: TemplateParam = { paramId: 'flag', type: 'boolean', mandatory: true };
    const words: TemplateParam = { paramId: 'words', type: 'text', mandatory: true };
    const { handle } = withParams([depth, flag, words]);

    handle.values.set([{ posDecimals: '1', positions: [], depth: 0, words: '  ' }]);
    expect(handle.missingMandatory()).toEqual(['words']);

    handle.values.set([{ posDecimals: '1', positions: [], depth: [], words: 'x', flag: false }]);
    expect(handle.missingMandatory()).toEqual(['Depth']);
  });

  it('Execute is offered once it is filled', () => {
    const { handle, templateStore, navigate } = withParams([depth, note]);

    handle.values.set([{ posDecimals: '1', positions: [], depth: 0.6 }]);

    expect(handle.missingMandatory()).toEqual([]);
    expect(handle.canExecute()).toBe(true);
    handle.execute();
    expect(templateStore.set).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/editor/edit']);
  });

  it('an optional composite left empty does not block Execute', () => {
    const { handle } = withParams([{ paramId: 'aton', type: 'aton' }]);

    expect(handle.missingMandatory()).toEqual([]);
    expect(handle.canExecute()).toBe(true);
  });

  it('a mandatory child of a composite counts once the composite has a value', () => {
    const { handle } = withParams([{ paramId: 'aton', type: 'aton' }]);

    handle.values.set([{ posDecimals: '1', positions: [], atonName: 'Buoy 7' }]);

    expect(handle.missingMandatory()).toEqual(['AtoN type']);
    expect(handle.canExecute()).toBe(false);
  });

  it('a mandatory child of a mandatory composite counts with the composite empty', () => {
    const { handle } = withParams([{ paramId: 'aton', type: 'aton', mandatory: true }]);

    expect(handle.missingMandatory()).toEqual(['AtoN type']);
  });

  it('a mandatory composite with no mandatory child needs one child filled, and is named', () => {
    const pair: TemplateParam = {
      paramId: 'pair',
      type: 'pair',
      mandatory: true,
      descs: [{ lang: 'en', name: 'Pair' }],
    };
    const { handle } = withParams([pair]);

    expect(handle.missingMandatory()).toEqual(['Pair']);
    handle.values.set([{ posDecimals: '1', positions: [], b: 'x' }]);
    expect(handle.missingMandatory()).toEqual([]);
  });

  it('a mandatory position-list parameter is asked in every position, and not before one exists', () => {
    const perPosition: TemplateParam = { ...depth, positionList: true };
    const { handle } = withParams([perPosition]);

    expect(handle.missingMandatory()).toEqual([]);

    handle.values.set([{ posDecimals: '1', positions: [{ depth: 3 }, {}] }]);
    expect(handle.missingMandatory()).toEqual(['Depth']);

    handle.values.set([{ posDecimals: '1', positions: [{ depth: 3 }, { depth: 4 }] }]);
    expect(handle.missingMandatory()).toEqual([]);
  });

  it('the header names the empty parameters by the disabled Execute button', async () => {
    const { fixture, handle } = withParams([depth, note], true);
    TestBed.inject(TranslocoService).setTranslationKey(
      'editor.template.missingMandatory',
      'Fill in the parameters marked * to execute: {{names}}',
      { lang: 'en' },
    );
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;

    const line = el.querySelector('#template-missing-mandatory');
    expect(line?.textContent?.trim()).toBe('Fill in the parameters marked * to execute: Depth');
    const button = el.querySelector<HTMLButtonElement>('header .btn-primary')!;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-describedby')).toBe('template-missing-mandatory');

    handle.values.set([{ posDecimals: '1', positions: [], depth: 2 }]);
    await fixture.whenStable();

    expect(el.querySelector('#template-missing-mandatory')).toBeNull();
    expect(button.disabled).toBe(false);
    expect(button.hasAttribute('aria-describedby')).toBe(false);
  });
});
