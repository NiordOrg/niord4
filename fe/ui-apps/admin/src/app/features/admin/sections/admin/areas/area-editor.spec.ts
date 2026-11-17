import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { AdminArea } from '../../../services/api/areas-admin-api.service';
import { AreaEditor, type AreaEditorModel } from './area-editor';

interface EditorInternals {
  setName: (lang: string, value: string) => void;
  save: () => void;
  saveDisabled: () => boolean;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
      {
        provide: LanguageService,
        useValue: { modelLanguages: [{ value: 'en', label: 'English' }], defaultLanguage: 'en' },
      },
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
    ],
  });
  // The save path is under test, not the template (which renders a map and the geometry dialogs).
  TestBed.overrideComponent(AreaEditor, { set: { template: '' } });
  const fixture = TestBed.createComponent(AreaEditor);
  const dirty: boolean[] = [];
  fixture.componentInstance.dirtyChange.subscribe((value) => dirty.push(value));
  const open = (model: AreaEditorModel): void => {
    fixture.componentRef.setInput('model', model);
    fixture.detectChanges();
  };
  return {
    open,
    dirty,
    editor: fixture.componentInstance as unknown as EditorInternals,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

/** An area as `GET /areas/area/{id}` returns it: the record carries its place among its siblings. */
const STORED: AdminArea = {
  id: 7,
  active: true,
  descs: [{ lang: 'en', name: 'North Sea' }],
  parent: { id: 3, active: true, descs: [{ lang: 'en', name: 'Waters' }] },
  siblingSortOrder: 2,
};

describe('AreaEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('an edit sends no siblingSortOrder, although the record read carries one', () => {
    const { open, editor, httpTesting } = setup();
    open({ mode: 'edit', area: STORED });

    editor.setName('en', 'North Sea, east');
    editor.save();

    const req = httpTesting.expectOne('/rest/areas/area/7');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as AdminArea;
    expect(body.descs).toEqual([{ lang: 'en', name: 'North Sea, east' }]);
    // The backend keeps the stored order when the body carries none.
    expect('siblingSortOrder' in body).toBe(false);
    req.flush({});
  });

  it('Move up while the editor is open, then Save, sends no order that could undo the move', () => {
    const { open, editor, dirty, httpTesting } = setup();
    open({ mode: 'edit', area: STORED });
    // Pristine: the page enables Move up/down. The move refreshes only the tree, so the editor keeps
    // the model it read (order 2) while the server has given the area a new order.
    expect(dirty.at(-1)).toBe(false);

    editor.setName('en', 'North Sea, renamed after the move');
    editor.save();

    const req = httpTesting.expectOne('/rest/areas/area/7');
    const body = req.request.body as AdminArea;
    expect(body.siblingSortOrder).toBeUndefined();
    expect('siblingSortOrder' in body).toBe(false);
    req.flush({});
  });

  it('an add sends no siblingSortOrder either', () => {
    const { open, editor, httpTesting } = setup();
    open({ mode: 'add', area: { active: true, descs: [], parent: STORED } });

    editor.setName('en', 'Skerries');
    editor.save();

    const req = httpTesting.expectOne('/rest/areas/area/');
    expect(req.request.method).toBe('POST');
    expect('siblingSortOrder' in (req.request.body as AdminArea)).toBe(false);
    req.flush({});
  });

  it('a name longer than its column keeps Save disabled and sends nothing (ERR7)', () => {
    const { open, editor, httpTesting } = setup();
    open({ mode: 'edit', area: STORED });

    editor.setName('en', 'a'.repeat(256));
    expect(editor.saveDisabled()).toBe(true);
    editor.save();
    httpTesting.expectNone('/rest/areas/area/7');

    editor.setName('en', 'a'.repeat(255));
    expect(editor.saveDisabled()).toBe(false);
  });
});
