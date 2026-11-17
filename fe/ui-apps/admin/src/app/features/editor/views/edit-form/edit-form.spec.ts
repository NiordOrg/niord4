import { Location } from '@angular/common';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../shared/testing/zone-testing';
import type { Message } from '../../../messages/models/message.model';
import { MessageDetailsDialogService } from '../../../messages/services/message-details-dialog.service';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { EditorFacade } from '../../services/editor-facade';
import { MessageStatusService } from '../../services/message-status.service';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { TemplateMessageStore } from '../../services/template-message-store';
import { EditForm } from './edit-form';

// Structural view onto the component's protected members the wiring tests reach. The Signal Forms
// fields expose `.value()` (read) / `.value.set()` (write) FieldState signals.
interface FieldHandle<T> {
  value: { (): T; set(next: T): void };
  disabled(): boolean;
}
interface EditFormHandle {
  readonly form: {
    type: () => FieldHandle<string>;
    seriesId: () => FieldHandle<string>;
    shortId: () => FieldHandle<string>;
    titles: () => FieldHandle<Record<string, string>>;
    publishDateFrom: () => FieldHandle<Date | null>;
    publishDateTo: () => FieldHandle<Date | null>;
    followUpDate: () => FieldHandle<Date | null>;
  };
  readonly parts: () => readonly unknown[];
  readonly readOnly: () => boolean;
  save(): void;
  onPartDrop(event: { previousIndex: number; currentIndex: number }): void;
}

const draft: Message = {
  id: 'm-1',
  status: 'DRAFT',
  mainType: 'NW',
  type: 'COASTAL_WARNING',
  messageSeries: { seriesId: 's' },
  descs: [{ lang: 'en', title: 'Title EN', vicinity: 'Off Skagen' }],
  parts: [{ type: 'DETAILS', indexNo: 0 }],
  references: [{ messageId: 'NW-100', type: 'REFERENCE' }],
  created: 'x',
};

function setup(opts: { admin?: boolean; loadError?: number } = {}) {
  // Force the narrow breakpoint so the inline live-preview pane is not mounted (drawer stays closed).
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );

  const auth = {
    hasRole: (role: string) =>
      signal(role === 'editor' ? true : role === 'admin' ? !!opts.admin : false),
  };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [{ seriesId: 's', mainType: 'NW' }] }),
    supportsMainType: () => signal(true),
    capabilities: () => ({ templates: false }),
  };
  const api = {
    editableDetails: vi.fn(() =>
      opts.loadError
        ? throwError(() => new HttpErrorResponse({ status: opts.loadError }))
        : of(draft),
    ),
    adjustEditableMessage: vi.fn((message: Message) => of(message)),
    recentlyEditedDrafts: vi.fn(() => of([] as readonly Message[])),
    copyMessageTemplate: vi.fn(() => of(draft)),
    newMessageTemplate: vi.fn(() => of(draft)),
    saveMessage: vi.fn((message: Message) => of(message)),
  };
  const notify = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() };
  const router = {
    navigate: vi.fn(),
    url: '/editor/edit/m-1',
    events: of(),
    // The editor header's route-based <app-help-anchor> reads routerState.snapshot via HelpUrlService.
    routerState: { snapshot: { root: { data: {}, firstChild: null } } },
  };
  const route = {
    snapshot: { data: { editType: 'edit' }, paramMap: convertToParamMap({ id: 'm-1' }) },
    paramMap: of(convertToParamMap({ id: 'm-1' })),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      // The date controls in the form resolve their day and clock in this zone.
      provideTestSessionZone('Asia/Kolkata'),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      EditorFacade,
      EditorFieldsService,
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      { provide: NotificationService, useValue: notify },
      {
        provide: TemplateMessageStore,
        useValue: { consume: vi.fn(), set: vi.fn(), clear: vi.fn() },
      },
      { provide: MessageDetailsDialogService, useValue: { open: vi.fn(), close: vi.fn() } },
      // The preview's projected status-flow injects the route-scoped status service.
      { provide: MessageStatusService, useValue: { validateForPublish: vi.fn(() => true) } },
      { provide: Router, useValue: router },
      { provide: Location, useValue: { back: vi.fn() } },
      { provide: ActivatedRoute, useValue: route },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(EditForm);
  fixture.detectChanges();
  TestBed.tick();
  const view = fixture.componentInstance as unknown as EditFormHandle;
  return { view, fixture, api, notify, router, facade: TestBed.inject(EditorFacade) };
}

describe('EditForm', () => {
  beforeEach(() => vi.unstubAllGlobals());

  it('renders the three section panels for a loaded editable message', () => {
    const { fixture } = setup();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('app-section-panel').length).toBe(3);
  });

  it('populates the form from the loaded message without tripping userTouched (guarded sync)', () => {
    const { view, facade } = setup();
    expect(view.form.type().value()).toBe('COASTAL_WARNING');
    expect(view.form.titles().value()['en']).toBe('Title EN');
    expect(view.parts().length).toBe(1);
    expect(facade.userTouched()).toBe(false);
    expect(facade.dirty()).toBe(false);
  });

  it('a genuine field edit trips userTouched and patches the message', () => {
    const { view, facade } = setup();
    view.form.shortId().value.set('NW-001');
    TestBed.tick();
    expect(facade.userTouched()).toBe(true);
    expect(facade.message()?.shortId).toBe('NW-001');
  });

  it('type-select auto-deduces the sole permitting series and fires adjust(inclPromulgations)', () => {
    const { view, api, facade } = setup();
    facade.applyLoaded({ id: 'm-1', status: 'DRAFT', mainType: 'NW' });
    TestBed.tick();
    api.adjustEditableMessage.mockClear();

    view.form.type().value.set('COASTAL_WARNING');
    TestBed.tick();

    expect(view.form.seriesId().value()).toBe('s');
    expect(api.adjustEditableMessage).toHaveBeenCalled();
  });

  it('the full-DTO patch keeps untouched slices (references/parts) and preserves other desc fields', () => {
    const { view, facade } = setup();
    const beforeReferences = facade.message()?.references;
    const beforeParts = facade.message()?.parts;

    view.form.shortId().value.set('NW-777');
    TestBed.tick();

    expect(facade.message()?.references).toBe(beforeReferences);
    expect(facade.message()?.parts).toBe(beforeParts);
    // The edited-title language keeps its non-title desc fields (vicinity).
    const desc = facade.message()?.descs?.find((d) => d.lang === 'en');
    expect(desc?.title).toBe('Title EN');
    expect(desc?.vicinity).toBe('Off Skagen');
  });

  it('preserves a desc whose language is outside the configured model languages through an edit', () => {
    const { view, facade } = setup();
    // Load a message carrying an extra 'fr' desc (model languages are en + da in the test config).
    facade.applyLoaded({
      ...draft,
      descs: [
        { lang: 'en', title: 'Title EN' },
        { lang: 'fr', title: 'Titre FR', source: 'Source FR' },
      ],
    });
    TestBed.tick();

    view.form.shortId().value.set('NW-fr');
    TestBed.tick();

    const fr = facade.message()?.descs?.find((d) => d.lang === 'fr');
    expect(fr?.title).toBe('Titre FR');
    expect(fr?.source).toBe('Source FR');
  });

  it('onPartDrop reorders parts through the facade and keeps the field state aligned (drag)', () => {
    const { view, facade } = setup();
    facade.applyLoaded({
      ...draft,
      parts: [
        { type: 'DETAILS', indexNo: 0 },
        { type: 'TIME', indexNo: 1 },
      ],
    });
    TestBed.tick();
    const partMoved = vi.spyOn(TestBed.inject(EditorFieldsService), 'partMoved');

    view.onPartDrop({ previousIndex: 0, currentIndex: 1 });

    expect(facade.message()?.parts?.map((p) => p.type)).toEqual(['TIME', 'DETAILS']);
    expect(facade.message()?.parts?.map((p) => p.indexNo)).toEqual([0, 1]);
    expect(partMoved).toHaveBeenCalledWith(0, 1);
  });

  it('onPartDrop with an unchanged index commits nothing', () => {
    const { view, facade } = setup();
    const before = facade.message()?.parts;
    const partMoved = vi.spyOn(TestBed.inject(EditorFieldsService), 'partMoved');

    view.onPartDrop({ previousIndex: 0, currentIndex: 0 });

    expect(facade.message()?.parts).toBe(before);
    expect(partMoved).not.toHaveBeenCalled();
  });

  // save() moved to EditorViewHeader (the save-gate spans the whole editor) — covered by
  // editor-view-header.spec.ts.

  it('stores a publish date as the instant the picker emitted, unrewritten', () => {
    const { view, facade } = setup();
    // 11 June 00:00 in the domain's UTC+5:30 zone — what its date picker emits for that day.
    // The form is a pass-through: no wall clock is re-read on either side of it.
    const picked = new Date('2026-06-10T18:30:00.000Z');
    view.form.publishDateFrom().value.set(picked);
    TestBed.tick();
    expect(facade.message()?.publishDateFrom).toBe('2026-06-10T18:30:00.000Z');

    facade.applyLoaded({ ...draft, publishDateTo: '2026-06-10T18:30:00.000Z' });
    TestBed.tick();
    expect(view.form.publishDateTo().value()?.getTime()).toBe(picked.getTime());
  });

  it('per-date readonly-by-status: every publish date is editable on a DRAFT', () => {
    const { view } = setup();
    expect(view.form.publishDateFrom().disabled()).toBe(false);
    expect(view.form.publishDateTo().disabled()).toBe(false);
    expect(view.form.followUpDate().disabled()).toBe(false);
  });

  it('per-date readonly-by-status: From locks on PUBLISHED while To/Follow-up stay editable (admin)', () => {
    const { view, facade } = setup({ admin: true });
    facade.applyLoaded({ ...draft, status: 'PUBLISHED' });
    TestBed.tick();
    expect(view.readOnly()).toBe(false);
    expect(view.form.publishDateFrom().disabled()).toBe(true);
    expect(view.form.publishDateTo().disabled()).toBe(false);
    expect(view.form.followUpDate().disabled()).toBe(false);
  });

  it('gates the publication field on showField(publication)', () => {
    const { fixture, facade } = setup();
    const host = fixture.nativeElement as HTMLElement;
    // The default draft carries no editorFields map — the publication row stays hidden.
    expect(host.querySelector('app-publication-field')).toBeNull();

    facade.applyLoaded({ ...draft, editorFields: { publication: true } });
    TestBed.tick();
    fixture.detectChanges();
    expect(host.querySelector('app-publication-field')).not.toBeNull();
  });

  describe('while a save is in flight', () => {
    function pane(fixture: { nativeElement: unknown }): HTMLElement {
      const element = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        '[data-testid="edit-form-pane"]',
      );
      if (!element) {
        throw new Error('the form pane is not rendered');
      }
      return element;
    }

    it('makes the form pane inert and busy until the save has answered', () => {
      const { view, fixture, facade, api } = setup();
      const answer = new Subject<Message>();
      api.saveMessage.mockImplementation(() => answer);
      view.form.shortId().value.set('NW-001');
      TestBed.tick();
      fixture.detectChanges();
      expect(pane(fixture).hasAttribute('inert')).toBe(false);
      expect(pane(fixture).hasAttribute('aria-busy')).toBe(false);

      facade.save().subscribe();
      fixture.detectChanges();
      expect(facade.saving()).toBe(true);
      expect(pane(fixture).hasAttribute('inert')).toBe(true);
      expect(pane(fixture).getAttribute('aria-busy')).toBe('true');

      answer.next({ ...draft, shortId: 'NW-001' });
      answer.complete();
      TestBed.tick();
      fixture.detectChanges();
      expect(facade.saving()).toBe(false);
      expect(pane(fixture).hasAttribute('inert')).toBe(false);
      expect(pane(fixture).hasAttribute('aria-busy')).toBe(false);
    });

    it('frees the form pane again when the save fails, keeping the working copy', () => {
      const { view, fixture, facade, api } = setup();
      const answer = new Subject<Message>();
      api.saveMessage.mockImplementation(() => answer);
      view.form.shortId().value.set('NW-002');
      TestBed.tick();

      facade.save().subscribe({ error: () => undefined });
      fixture.detectChanges();
      expect(pane(fixture).hasAttribute('inert')).toBe(true);

      answer.error(new Error('refused'));
      TestBed.tick();
      fixture.detectChanges();
      expect(pane(fixture).hasAttribute('inert')).toBe(false);
      expect(facade.message()?.shortId).toBe('NW-002');
    });

    it('leaves the header and its Save button outside the inert pane', () => {
      const { view, fixture, facade, api } = setup();
      api.saveMessage.mockImplementation(() => new Subject<Message>());
      view.form.shortId().value.set('NW-003');
      TestBed.tick();

      facade.save().subscribe();
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      const header = host.querySelector('header');
      expect(header).not.toBeNull();
      expect(header?.closest('[inert]')).toBeNull();
      expect(pane(fixture).querySelector('app-section-panel')).not.toBeNull();
    });
  });

  describe('the load error panel', () => {
    function query(fixture: { nativeElement: unknown }, testId: string): HTMLElement | null {
      return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        `[data-testid="${testId}"]`,
      );
    }

    it('a 403 names the cause, offers no Retry and leads back to Start', () => {
      const { fixture, router } = setup({ loadError: 403 });
      fixture.detectChanges();

      const panel = query(fixture, 'editor-load-error');
      expect(panel?.textContent).toContain('editor.load.error.otherDomain');
      expect(query(fixture, 'editor-load-retry')).toBeNull();
      const toStart = query(fixture, 'editor-load-to-start');
      expect(toStart?.textContent).toContain('editor.load.toStart');

      toStart?.click();
      expect(router.navigate).toHaveBeenCalledWith(['/editor']);
    });

    it('another load failure keeps Retry', () => {
      const { fixture, api } = setup({ loadError: 500 });
      fixture.detectChanges();

      expect(query(fixture, 'editor-load-error')?.textContent).toContain('editor.load.errorPanel');
      expect(query(fixture, 'editor-load-to-start')).toBeNull();
      api.editableDetails.mockClear();
      query(fixture, 'editor-load-retry')?.click();
      expect(api.editableDetails).toHaveBeenCalledWith('m-1');
    });
  });

  it('a non-editable (PUBLISHED, non-admin) message renders read-only, not the form', () => {
    const { view, fixture, facade } = setup();
    facade.applyLoaded({ ...draft, status: 'PUBLISHED' });
    TestBed.tick();
    fixture.detectChanges();
    expect(view.readOnly()).toBe(true);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('app-section-panel')).toBeNull();
    expect(host.querySelector('app-message-details-renderer')).not.toBeNull();
  });
});
