import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MainType } from '../../../messages/models/message.model';
import type { SystemCategory } from '../../../messages/models/template.model';
import { MessageDetailsDialogService } from '../../../messages/services/message-details-dialog.service';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { provideFakeMessageEditShortcut } from '../../../messages/testing/fake-message-edit-shortcut';
import { EditorFacade } from '../../services/editor-facade';
import { TemplateExecutionSeedStore } from '../../services/template-execution-seed-store';
import { TemplateMessageStore } from '../../services/template-message-store';
import { MessageStart } from './message-start';

/** Reaches the component's protected create handlers the wiring tests drive. */
interface StartHandle {
  createBlank(mainType: MainType): void;
  openTemplateSearch(mainType: MainType, event: MouseEvent): void;
  onTemplatePicked(template: SystemCategory): void;
}

interface Scenario {
  isEditor: boolean;
  supports: readonly MainType[];
  templates: boolean;
}

function setup(scenario: Scenario): {
  fixture: ComponentFixture<MessageStart>;
  handle: StartHandle;
  store: { set: ReturnType<typeof vi.fn> };
  seedStore: { set: ReturnType<typeof vi.fn> };
  navigate: ReturnType<typeof vi.fn>;
  httpMock: HttpTestingController;
} {
  const auth = {
    hasRole: (role: string) => signal(role === 'editor' ? scenario.isEditor : false),
  };
  const domain = {
    supportsMainType: (mainType: MainType) => signal(scenario.supports.includes(mainType)),
    sessionDomain: signal({ domainId: 'd' }),
    capabilities: signal({
      templates: scenario.templates,
      atons: false,
      publish: false,
      firingSchedule: false,
    }),
  };
  const store = { set: vi.fn(), consume: vi.fn(), clear: vi.fn() };
  const seedStore = { set: vi.fn(), consume: vi.fn(), clear: vi.fn() };
  const navigate = vi.fn();
  const language = { selectedLanguage: signal('en') };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      EditorFacade,
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: {} },
      {
        provide: CategoriesApiService,
        useValue: {
          searchTemplates: vi.fn(() => of([])),
          getTemplateDetails: vi.fn(() => of([])),
        },
      },
      { provide: NotificationService, useValue: {} },
      { provide: TemplateMessageStore, useValue: store },
      { provide: TemplateExecutionSeedStore, useValue: seedStore },
      { provide: LanguageService, useValue: language },
      { provide: MessageDetailsDialogService, useValue: { open: vi.fn(), close: vi.fn() } },
      provideFakeMessageEditShortcut(),
      { provide: Router, useValue: { navigate } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(MessageStart);
  fixture.detectChanges();
  const httpMock = TestBed.inject(HttpTestingController);
  return {
    fixture,
    handle: fixture.componentInstance as unknown as StartHandle,
    store,
    seedStore,
    navigate,
    httpMock,
  };
}

/** Count the visible create cards (each rendered card is one `<app-card>` element). */
function cardCount(fixture: ComponentFixture<MessageStart>): number {
  return fixture.nativeElement.querySelectorAll('app-card').length;
}

describe('MessageStart', () => {
  describe('create-card gating (supportsMainType × isEditor)', () => {
    it('an editor in a domain supporting both main types sees both cards', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW', 'NM'], templates: false });
      expect(cardCount(fixture)).toBe(2);
      expect(fixture.nativeElement.textContent).toContain('editor.new_nw');
      expect(fixture.nativeElement.textContent).toContain('editor.new_nm');
    });

    it('an editor in a single-main-type domain sees only that card', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW'], templates: false });
      expect(cardCount(fixture)).toBe(1);
      expect(fixture.nativeElement.textContent).toContain('editor.new_nw');
      expect(fixture.nativeElement.textContent).not.toContain('editor.new_nm');
    });

    it('an editor in a domain supporting neither sees no cards', () => {
      const { fixture } = setup({ isEditor: true, supports: [], templates: false });
      expect(cardCount(fixture)).toBe(0);
    });

    it('a non-editor (user) sees no create cards even where both are supported', () => {
      const { fixture } = setup({ isEditor: false, supports: ['NW', 'NM'], templates: false });
      expect(cardCount(fixture)).toBe(0);
    });
  });

  it('shows the recently-edited drafts list for a plain user (no create affordances)', () => {
    const { fixture } = setup({ isEditor: false, supports: ['NW', 'NM'], templates: true });
    expect(cardCount(fixture)).toBe(0);
    expect(fixture.nativeElement.querySelector('app-recently-edited-drafts')).not.toBeNull();
  });

  describe('the line under the heading', () => {
    function subtitle(fixture: ComponentFixture<MessageStart>): string {
      return (fixture.nativeElement.querySelector('h1 + p')?.textContent ?? '').trim();
    }

    it('tells a plain user that no message can be created here, not that one can be started', () => {
      const { fixture } = setup({ isEditor: false, supports: ['NW', 'NM'], templates: true });
      expect(subtitle(fixture)).toBe('editor.start.subtitleCannotCreate');
    });

    it('tells an editor in a domain with no creatable type the same', () => {
      const { fixture } = setup({ isEditor: true, supports: [], templates: false });
      expect(subtitle(fixture)).toBe('editor.start.subtitleCannotCreate');
    });

    it('offers a blank message or a template where a card is shown and the domain has templates', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW'], templates: true });
      expect(subtitle(fixture)).toBe('editor.start.subtitle');
    });

    it('offers a blank message where a card is shown and the domain has no templates', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW'], templates: false });
      expect(subtitle(fixture)).toBe('editor.start.subtitleNoTemplate');
    });
  });

  describe('from-template affordance', () => {
    it('is hidden when the domain exposes no templates', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW', 'NM'], templates: false });
      expect(cardCount(fixture)).toBe(2);
      expect(fixture.nativeElement.textContent).not.toContain('editor.new_nw_template');
    });

    it('renders a from-template button per visible card when the domain exposes templates', () => {
      const { fixture } = setup({ isEditor: true, supports: ['NW', 'NM'], templates: true });
      expect(fixture.nativeElement.textContent).toContain('editor.new_nw_template');
      expect(fixture.nativeElement.textContent).toContain('editor.new_nm_template');
    });
  });

  describe('create flows', () => {
    it('new-blank seeds the store and enters the editor', () => {
      const { handle, store, navigate } = setup({
        isEditor: true,
        supports: ['NW'],
        templates: false,
      });

      handle.createBlank('NW');

      expect(store.set).toHaveBeenCalledWith({ id: '', mainType: 'NW' });
      expect(navigate).toHaveBeenCalledWith(['/editor/edit']);
    });

    it('a template-search pick seeds a blank execution seed CARRYING the template and enters the execution route', () => {
      const { handle, seedStore, navigate } = setup({
        isEditor: true,
        supports: ['NW'],
        templates: true,
      });
      const template = { id: 42, type: 'TEMPLATE' } as SystemCategory;

      // The from-template button records its card's mainType, then the popover pick fires.
      handle.openTemplateSearch('NW', {
        currentTarget: document.createElement('button'),
      } as unknown as MouseEvent);
      handle.onTemplatePicked(template);

      expect(seedStore.set).toHaveBeenCalledWith(
        { id: '', mainType: 'NW', categories: [template] },
        '/editor',
        true, // a placeholder: the execution view starts from a server blank
      );
      expect(navigate).toHaveBeenCalledWith(['/editor/execute-template']);
    });
  });
});
