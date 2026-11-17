import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Drawer } from '../../../../shared/components/drawer/drawer';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { SystemCategory } from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { TemplateSelectionDrawer } from './template-selection-drawer';

interface DrawerHandle {
  readonly results: WritableSignal<readonly SystemCategory[]>;
  readonly checkedIds: WritableSignal<ReadonlySet<number>>;
  readonly hovered: WritableSignal<SystemCategory | undefined>;
  isPerPosition(cat: SystemCategory): boolean;
  isChecked(cat: SystemCategory): boolean;
  toggle(cat: SystemCategory): void;
  confirm(): void;
  selectedCount(): number;
}

const perPosition: SystemCategory = {
  id: 1,
  type: 'TEMPLATE',
  descs: [{ lang: 'en', name: 'Wreck' }],
  templateParams: [{ paramId: 'x', type: 'text', positionList: true }],
};
const templateLevel: SystemCategory = {
  id: 2,
  type: 'TEMPLATE',
  descs: [{ lang: 'en', name: 'Light' }],
  templateParams: [{ paramId: 'y', type: 'text' }],
};

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      {
        provide: CategoriesApiService,
        useValue: {
          searchTemplates: () => of([]),
          getTemplateDetails: () => of([]),
          getRoots: () => of([]),
        },
      },
      { provide: DomainFacade, useValue: { sessionDomain: signal({ domainId: 'd' }) } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
    ],
  });
  const fixture = TestBed.createComponent(TemplateSelectionDrawer);
  return { fixture, handle: fixture.componentInstance as unknown as DrawerHandle };
}

describe('TemplateSelectionDrawer', () => {
  it('tags a template per-position iff it has a positionList parameter', () => {
    const { handle } = setup();
    expect(handle.isPerPosition(perPosition)).toBe(true);
    expect(handle.isPerPosition(templateLevel)).toBe(false);
  });

  it('toggles the checked set and reports the count', () => {
    const { handle } = setup();
    handle.results.set([perPosition, templateLevel]);

    expect(handle.isChecked(perPosition)).toBe(false);
    handle.toggle(perPosition);
    expect(handle.isChecked(perPosition)).toBe(true);
    expect(handle.selectedCount()).toBe(1);

    handle.toggle(perPosition);
    expect(handle.isChecked(perPosition)).toBe(false);
    expect(handle.selectedCount()).toBe(0);
  });

  it('fetches the hovered template example localized to the active language', () => {
    const { fixture, handle } = setup();
    handle.hovered.set({
      id: 3,
      type: 'TEMPLATE',
      messageId: 'ex-1',
      descs: [{ lang: 'en', name: 'Ex' }],
      templateParams: [],
    });
    fixture.detectChanges();
    const req = TestBed.inject(HttpTestingController).expectOne(
      (r) => r.url === '/rest/messages/message/ex-1',
    );
    // Without lang the backend returns all languages → a partially-translated example renders mixed.
    expect(req.request.params.get('lang')).toBe('en');
    req.flush({ id: 'ex-1', descs: [] });
  });

  it('passes the drawer its header as a key, which the drawer translates itself', () => {
    const { fixture } = setup();
    const transloco = TestBed.inject(TranslocoService);
    transloco.load('en').subscribe();
    transloco.setTranslationKey('editor.template.drawer.title', 'Select templates', { lang: 'en' });
    fixture.detectChanges();
    const drawer = fixture.debugElement.query(By.directive(Drawer)).componentInstance as Drawer;
    // A translated text here would be looked up again as a key: a missing translation logged.
    expect(drawer.header()).toBe('editor.template.drawer.title');
  });

  it('confirm emits exactly the checked templates (hydrated results)', () => {
    const { fixture, handle } = setup();
    handle.results.set([perPosition, templateLevel]);
    handle.checkedIds.set(new Set([2]));

    const emitted = vi.fn();
    fixture.componentInstance.confirmed.subscribe(emitted);
    handle.confirm();

    expect(emitted).toHaveBeenCalledTimes(1);
    expect(emitted.mock.calls[0][0].map((c: SystemCategory) => c.id)).toEqual([2]);
  });
});
