import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import type { CategoryRef } from '../../../../messages/models/messages-filter-params.model';
import { CategoriesApiService } from '../../../../messages/services/api/categories-api.service';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TemplateExecutionSeedStore } from '../../../services/template-execution-seed-store';
import { CategoriesFieldRow } from './categories-field-row';

interface CatHandle {
  onCategoriesChange(categories: readonly CategoryRef[]): void;
  runTemplate(): void;
}

function setup(options: { templates?: boolean } = {}) {
  const auth = { hasRole: (role: string) => signal(role === 'editor') };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [] }),
    supportsMainType: () => signal(true),
    capabilities: () => ({ templates: options.templates ?? false }),
  };
  const api = {
    editableDetails: vi.fn((id: string) => of({ id, status: 'DRAFT' } as Message)),
    adjustEditableMessage: vi.fn((message: Message) => of(message)),
  };
  const language = {
    modelLanguages: [
      { value: 'en', label: 'English', flagPath: 'assets/img/flags/en.png' },
      { value: 'da', label: 'Dansk', flagPath: 'assets/img/flags/da.png' },
    ],
    selectedLanguage: signal('en'),
  };
  const seedStore = { set: vi.fn(), consume: vi.fn(), clear: vi.fn() };
  const router = { navigate: vi.fn(), url: '/editor/edit/m' };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      EditorFacade,
      EditorFieldsService,
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      { provide: TemplateExecutionSeedStore, useValue: seedStore },
      { provide: CategoriesApiService, useValue: { search: () => of([]), getByIds: () => of([]) } },
      { provide: LanguageService, useValue: language },
      { provide: Router, useValue: router },
    ],
  });

  const facade = TestBed.inject(EditorFacade);
  const fields = TestBed.inject(EditorFieldsService);
  const fixture = TestBed.createComponent(CategoriesFieldRow);
  const handle = fixture.componentInstance as unknown as CatHandle;
  return { fixture, facade, fields, api, handle, seedStore, router };
}

const draft: Message = { id: 'm', status: 'DRAFT', mainType: 'NW' };
const firing: CategoryRef = { id: 3, descs: [{ lang: 'en', name: 'Firing exercises' }] };

describe('CategoriesFieldRow', () => {
  it('a category selection marks dirty only — no adjust round-trip', () => {
    const { facade, api, handle } = setup();
    facade.applyLoaded(draft);
    api.adjustEditableMessage.mockClear();

    handle.onCategoriesChange([firing]);

    const categories = facade.message()?.categories;
    expect(categories?.[0]?.id).toBe(3);
    expect(facade.dirty()).toBe(true);
    expect(api.adjustEditableMessage).not.toHaveBeenCalled();
  });

  it('the viewer lists the selected category names', () => {
    const { fixture, facade, handle } = setup();
    facade.applyLoaded(draft);
    handle.onCategoriesChange([firing]);
    fixture.detectChanges();

    const viewer = (fixture.nativeElement as HTMLElement).querySelector('[fieldViewer]');
    expect(viewer?.textContent).toContain('Firing exercises');
  });

  it('expanding renders the categories picker', () => {
    const { fixture, facade, fields } = setup();
    facade.applyLoaded(draft);
    fields.expand('categories');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('app-categories-field'),
    ).not.toBeNull();
  });

  it('the "Run template…" launcher is hidden when the domain exposes no templates', () => {
    const { fixture, facade, fields } = setup({ templates: false });
    facade.applyLoaded(draft);
    fields.expand('categories');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('editor.template.run');
  });

  it('"Run template…" seeds a clone of the working message and enters the execution route', () => {
    const { facade, handle, seedStore, router } = setup({ templates: true });
    facade.applyLoaded(draft);

    handle.runTemplate();

    expect(seedStore.set).toHaveBeenCalledTimes(1);
    const [seededMessage, returnUrl, blank] = seedStore.set.mock.calls[0];
    expect(seededMessage.id).toBe('m');
    expect(seededMessage).not.toBe(facade.message()); // a clone, not the live copy
    expect(returnUrl).toBe('/editor/edit/m');
    // The working copy is never a placeholder: the execution view keeps it as it is.
    expect(blank ?? false).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/editor/execute-template']);
  });
});
