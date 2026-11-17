import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LanguageSettingsService } from '../../../../../core/i18n/language-settings.service';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ParamType } from '../../../services/api/param-types-admin-api.service';
import { ParamTypesPage } from './param-types-page';
import { ParamTypeEditorModel } from './param-type-editor';

interface PageInternals {
  editorModel: () => ParamTypeEditorModel | undefined;
  editParamType: (paramType: ParamType) => void;
  copyParamType: (paramType: ParamType) => void;
}

const STANDARD: ParamType = { id: 1, type: 'STANDARD', name: 'text' };
const LIST: ParamType = { id: 2, type: 'LIST', name: 'sea_state', values: [] };

function setup(rows: readonly ParamType[] = [STANDARD, LIST]) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: vi.fn() } },
    ],
  });
  const uiLanguage = TestBed.inject(LanguageService);
  TestBed.inject(LanguageSettingsService).configure({
    modelLanguages: ['da', 'en', 'kl'],
    defaultLanguage: 'da',
  });
  const fixture = TestBed.createComponent(ParamTypesPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  // The facade loads the list on creation.
  fixture.detectChanges();
  httpTesting
    .match((r) => r.url === '/rest/templates/parameter-types')
    .forEach((r) => r.flush(rows));
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    uiLanguage,
  };
}

describe('ParamTypesPage', () => {
  it('shows KL values and nested parameter names while the UI and source rows stay unchanged', () => {
    const rows: ParamType[] = [
      {
        id: 2,
        type: 'LIST',
        name: 'sea_state',
        values: [
          {
            key: 'calm',
            descs: [
              { lang: 'en', value: 'English sea' },
              { lang: 'da', value: 'Dansk sø' },
              { lang: 'kl', value: 'Kalaallisut imaq' },
            ],
          },
        ],
      },
      {
        id: 3,
        type: 'COMPOSITE',
        name: 'conditions',
        templateParams: [
          {
            paramId: 'condition',
            type: 'text',
            mandatory: false,
            positionList: false,
            descs: [
              { lang: 'en', name: 'Condition' },
              { lang: 'da', name: 'Tilstand' },
              { lang: 'kl', name: 'Pissuseq' },
            ],
          },
          {
            paramId: 'fallback',
            type: 'text',
            mandatory: false,
            positionList: false,
            descs: [
              { lang: 'en', name: 'English fallback' },
              { lang: 'da', name: 'Dansk reserve' },
            ],
          },
        ],
      },
    ];
    const before = structuredClone(rows);
    const { fixture, httpTesting, uiLanguage, page } = setup(rows);
    expect(fixture.nativeElement.textContent).toContain('English sea');
    fixture.debugElement.injector.get(ModelLanguageSelection).select('kl');
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Kalaallisut imaq');
    expect(text).toContain('Pissuseq');
    expect(text).toContain('Dansk reserve');
    expect(text).toContain('sea_state');
    const rendered = fixture.nativeElement as HTMLElement;
    expect(
      [...rendered.querySelectorAll('[lang="kl"]')].some((element) =>
        element.textContent?.includes('Kalaallisut imaq'),
      ),
    ).toBe(true);
    expect(
      [...rendered.querySelectorAll('[lang="da"]')].some((element) =>
        element.textContent?.includes('Dansk reserve'),
      ),
    ).toBe(true);
    expect(uiLanguage.selectedLanguage()).toBe('en');
    expect(page.editorModel()).toBeUndefined();
    expect(rows).toEqual(before);
    httpTesting.expectNone('/rest/templates/parameter-types');
    httpTesting.verify();
  });
  it('STANDARD types are read-only — edit is a no-op (no detail fetch, no editor)', () => {
    const { page, httpTesting } = setup();
    page.editParamType(STANDARD);
    expect(page.editorModel()).toBeUndefined();
    // No detail request was made for the STANDARD type.
    expect(httpTesting.match('/rest/templates/parameter-type/1').length).toBe(0);
  });

  it('lists the standard types from code, which have no id, beside database rows', () => {
    // The backend holds the standard types in memory, so they come without an id.
    const text: ParamType = { type: 'STANDARD', name: 'text' };
    const number: ParamType = { type: 'STANDARD', name: 'number' };
    const { fixture, page, httpTesting } = setup([text, number, LIST]);
    const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')];
    expect(rows.map((row) => row.querySelector('td')?.textContent?.trim())).toEqual([
      'text',
      'number',
      'sea_state',
    ]);
    const buttonCount = (name: string) =>
      rows
        .find((row) => row.querySelector('td')?.textContent?.trim() === name)!
        .querySelectorAll('button').length;
    expect(buttonCount('text')).toBe(0);
    expect(buttonCount('number')).toBe(0);
    expect(buttonCount('sea_state')).toBe(3);
    page.editParamType(text);
    expect(page.editorModel()).toBeUndefined();
    httpTesting.expectNone((request) => request.url.startsWith('/rest/templates/parameter-type/'));
    httpTesting.verify();
  });

  it('Copy loads detail then clones as new — id and name nulled, add mode', () => {
    const { page, httpTesting } = setup();
    page.copyParamType(LIST);
    httpTesting.expectOne('/rest/templates/parameter-type/2').flush({
      id: 2,
      type: 'LIST',
      name: 'sea_state',
      values: [{ id: 9, key: 'calm', descs: [] }],
    });
    const model = page.editorModel();
    expect(model?.mode).toBe('add');
    expect(model?.paramType.id).toBeUndefined();
    expect(model?.paramType.name).toBeUndefined();
    // The body (values) is preserved on the clone.
    expect(model?.paramType.values?.length).toBe(1);
  });

  it('Edit loads detail into edit mode', () => {
    const { page, httpTesting } = setup();
    page.editParamType(LIST);
    httpTesting
      .expectOne('/rest/templates/parameter-type/2')
      .flush({ id: 2, type: 'LIST', name: 'sea_state', values: [] });
    const model = page.editorModel();
    expect(model?.mode).toBe('edit');
    expect(model?.paramType.id).toBe(2);
  });
});
