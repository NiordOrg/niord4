import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { AreasApiService } from '../../../../messages/services/api/areas-api.service';
import { ChartsApiService } from '../../../../messages/services/api/charts-api.service';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { MessageGeometryService } from '../../../../messages/services/message-geometry.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TemplateMessageStore } from '../../../services/template-message-store';
import { AreasVicinityField } from './areas-vicinity-field';

// Binds the field the way the host does — a real Signal Forms `form()` slice for `vicinities` — so
// the copy-on-focus and blur-flush write paths run through the actual form field, not a stub.
@Component({
  imports: [AreasVicinityField],
  template: `<app-areas-vicinity-field [vicinitiesField]="f.vicinities" />`,
})
class Host {
  readonly model = signal<{ vicinities: Record<string, string> }>({
    vicinities: { en: '', da: '' },
  });
  readonly f = form(this.model);
}

interface AreasHandle {
  onAreasChange(areas: readonly { id: number; descs?: { lang?: string; name?: string }[] }[]): void;
}

function setup() {
  const auth = { hasRole: (role: string) => signal(role === 'editor') };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [] }),
    supportsMainType: () => signal(true),
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
      {
        provide: TemplateMessageStore,
        useValue: { consume: vi.fn(), set: vi.fn(), clear: vi.fn() },
      },
      {
        provide: AreasApiService,
        useValue: {
          search: () => of([]),
          getByIds: vi.fn(() => of([])),
          intersectingAreas: vi.fn(() => of([])),
          areaToFeatureCollection: () => ({ type: 'FeatureCollection', features: [] }),
        },
      },
      { provide: ChartsApiService, useValue: { intersectingCharts: () => of([]) } },
      {
        provide: MessageGeometryService,
        useValue: { getFeatures: () => [], serializeReadableCoordinates: () => [] },
      },
      { provide: LanguageService, useValue: language },
    ],
  });

  const facade = TestBed.inject(EditorFacade);
  const fields = TestBed.inject(EditorFieldsService);
  const fixture = TestBed.createComponent(Host);
  const host = fixture.componentInstance;
  const child = fixture.debugElement.query(By.directive(AreasVicinityField))
    .componentInstance as unknown as AreasHandle;
  return { fixture, host, facade, fields, api, child };
}

const draft: Message = {
  id: 'm',
  status: 'DRAFT',
  mainType: 'NW',
  descs: [{ lang: 'en' }, { lang: 'da' }],
};

function vicinityInputs(fixture: { nativeElement: unknown }): HTMLInputElement[] {
  const host = fixture.nativeElement as HTMLElement;
  return Array.from(host.querySelectorAll<HTMLInputElement>('app-localized-inputs input'));
}

/** The action button whose (transloco-keyed) label contains `text` — the stub renders the raw key. */
function buttonByText(fixture: { nativeElement: unknown }, text: string): HTMLButtonElement {
  const host = fixture.nativeElement as HTMLElement;
  const button = Array.from(host.querySelectorAll('button')).find((b) =>
    (b.textContent ?? '').includes(text),
  );
  if (!button) {
    throw new Error(`No button labelled "${text}"`);
  }
  return button as HTMLButtonElement;
}

const draftWithGeometry: Message = {
  ...draft,
  parts: [
    {
      type: 'DETAILS',
      indexNo: 0,
      geometry: {
        type: 'FeatureCollection',
        features: [
          { type: 'Feature', geometry: { type: 'Point', coordinates: [0, 0] }, properties: {} },
        ],
      },
    },
  ],
};

describe('AreasVicinityField', () => {
  it('an area selection sets the areas and re-derives immediately (area-changed adjust)', () => {
    const { facade, api, child } = setup();
    facade.applyLoaded(draft);
    api.adjustEditableMessage.mockClear();

    child.onAreasChange([{ id: 5, descs: [{ lang: 'en', name: 'Baltic' }] }]);

    expect(facade.message()?.areas?.[0]?.id).toBe(5);
    expect(facade.message()?.areas?.[0]?.descs?.[0]?.name).toBe('Baltic');
    expect(facade.dirty()).toBe(true);
    expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
  });

  it('shows the primary desc vicinity (descs[0]) in the collapsed summary, not the UI-language desc', () => {
    const { fixture, facade } = setup();
    facade.applyLoaded({
      ...draft,
      descs: [
        { lang: 'da', vicinity: 'Primary DA vicinity' },
        { lang: 'en', vicinity: 'Secondary EN vicinity' },
      ],
    });
    fixture.detectChanges();
    // Current UI language is 'en', but the vicinity summary must read descs[0] (the Danish primary).
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Primary DA vicinity');
    expect(text).not.toContain('Secondary EN vicinity');
  });

  it('a blank vicinity input copies another language on focus (checkCopyVicinity)', () => {
    const { fixture, host, facade, fields } = setup();
    facade.applyLoaded(draft);
    host.model.set({ vicinities: { en: 'Off Skagen', da: '' } });
    fields.expand('areas');
    fixture.detectChanges();

    const inputs = vicinityInputs(fixture);
    // [en, da] in configured order — focusing the blank Danish input copies the English value.
    inputs[1].dispatchEvent(new Event('focus'));
    fixture.detectChanges();

    expect(host.model().vicinities['da']).toBe('Off Skagen');
  });

  it('a vicinity blur flushes the pending debounced adjust immediately', () => {
    const { fixture, facade, fields, api } = setup();
    facade.applyLoaded(draft);
    fields.expand('areas');
    fixture.detectChanges();

    // The host would have queued the debounced adjust on the vicinity keystroke.
    facade.adjustDebounced();
    api.adjustEditableMessage.mockClear();

    vicinityInputs(fixture)[0].dispatchEvent(new Event('blur'));
    expect(api.adjustEditableMessage).toHaveBeenCalledTimes(1);
  });

  it('Compute-from-locations routes to facade.computeAreas once a part defines geometry', () => {
    const { fixture, facade, fields } = setup();
    facade.applyLoaded(draftWithGeometry);
    const spy = vi.spyOn(facade, 'computeAreas').mockImplementation(() => undefined);
    fields.expand('areas');
    fixture.detectChanges();

    const button = buttonByText(fixture, 'computeFromLocations');
    expect(button.disabled).toBe(false);
    button.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('Copy-locations routes to facade.copyAreaLocations when areas are selected', () => {
    const { fixture, facade, fields } = setup();
    facade.applyLoaded({ ...draft, areas: [{ id: 3, descs: [{ lang: 'en', name: 'Sound' }] }] });
    const spy = vi.spyOn(facade, 'copyAreaLocations').mockImplementation(() => undefined);
    fields.expand('areas');
    fixture.detectChanges();

    const button = buttonByText(fixture, 'copyLocations');
    expect(button.disabled).toBe(false);
    button.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});
