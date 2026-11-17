import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { AdminCategory } from '../../../services/api/categories-admin-api.service';
import { CategoryEditor, type CategoryEditorModel } from './category-editor';

interface EditorInternals {
  setName: (lang: string, value: string) => void;
  setMessageId: (value: string | null) => void;
  save: () => void;
  test: () => void;
  testing: () => boolean;
}

function setup() {
  const errors: unknown[][] = [];
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: LanguageService,
        useValue: { modelLanguages: [{ value: 'en', label: 'English' }], defaultLanguage: 'en' },
      },
      {
        provide: NotificationService,
        useValue: {
          success: () => undefined,
          error: (...args: unknown[]) => errors.push(args),
        },
      },
    ],
  });
  // The save path is under test, not the template (the template-authoring fields and the Test dialog).
  TestBed.overrideComponent(CategoryEditor, { set: { template: '' } });
  const fixture = TestBed.createComponent(CategoryEditor);
  const dirty: boolean[] = [];
  fixture.componentInstance.dirtyChange.subscribe((value) => dirty.push(value));
  const open = (model: CategoryEditorModel): void => {
    fixture.componentRef.setInput('model', model);
    fixture.detectChanges();
  };
  return {
    open,
    dirty,
    errors,
    editor: fixture.componentInstance as unknown as EditorInternals,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

/** A category as `GET /categories/category/{id}` returns it: the record carries its sibling order. */
const STORED: AdminCategory = {
  id: 11,
  active: true,
  type: 'CATEGORY',
  descs: [{ lang: 'en', name: 'Buoys' }],
  parent: { id: 4, active: true, descs: [{ lang: 'en', name: 'Aids to navigation' }] },
  siblingSortOrder: 3,
};

describe('CategoryEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('an edit sends no siblingSortOrder, although the record read carries one', () => {
    const { open, editor, httpTesting } = setup();
    open({ mode: 'edit', category: STORED });

    editor.setName('en', 'Buoys and beacons');
    editor.save();

    const req = httpTesting.expectOne('/rest/categories/category/11');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as AdminCategory;
    expect(body.descs).toEqual([{ lang: 'en', name: 'Buoys and beacons' }]);
    // The backend keeps the stored order when the body carries none (L-157).
    expect('siblingSortOrder' in body).toBe(false);
    req.flush({});
  });

  it('Move up while the editor is open, then Save, sends no order that could undo the move', () => {
    const { open, editor, dirty, httpTesting } = setup();
    open({ mode: 'edit', category: STORED });
    // Pristine: the page enables Move up/down. The move refreshes only the tree, so the editor keeps
    // the model it read (order 3) while the server has given the category a new order.
    expect(dirty.at(-1)).toBe(false);

    editor.setName('en', 'Buoys, renamed after the move');
    editor.save();

    const req = httpTesting.expectOne('/rest/categories/category/11');
    const body = req.request.body as AdminCategory;
    expect(body.siblingSortOrder).toBeUndefined();
    expect('siblingSortOrder' in body).toBe(false);
    req.flush({});
  });

  it('an add sends no siblingSortOrder either', () => {
    const { open, editor, httpTesting } = setup();
    open({ mode: 'add', category: { active: true, descs: [], parent: STORED } });

    editor.setName('en', 'Lights');
    editor.save();

    const req = httpTesting.expectOne('/rest/categories/category/');
    expect(req.request.method).toBe('POST');
    expect('siblingSortOrder' in (req.request.body as AdminCategory)).toBe(false);
    req.flush({});
  });
});

describe('CategoryEditor Test', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const TEMPLATE: AdminCategory = { ...STORED, type: 'TEMPLATE', messageId: 'NW-001-26' };

  it('a refused Test says why: the toast carries the answer detail (L-179)', () => {
    const { open, editor, errors, httpTesting } = setup();
    open({ mode: 'edit', category: TEMPLATE });

    editor.test();
    const detail = 'Domain: warnings does not contain message series notices';
    const req = httpTesting.expectOne('/rest/templates/execute');
    expect(req.request.method).toBe('PUT');
    req.flush(
      { type: 'about:blank', title: 'Forbidden', status: 403, detail },
      { status: 403, statusText: 'Forbidden' },
    );

    expect(errors).toEqual([['admin.categories.toast.testRefused', { detail }]]);
    expect(editor.testing()).toBe(false);
  });

  it('a failed Test with no detail in the answer keeps the fixed text', () => {
    const { open, editor, errors, httpTesting } = setup();
    open({ mode: 'edit', category: TEMPLATE });

    editor.test();
    httpTesting
      .expectOne('/rest/templates/execute')
      .flush('gateway down', { status: 502, statusText: 'Bad Gateway' });

    expect(errors).toEqual([['admin.categories.toast.testError']]);
    expect(editor.testing()).toBe(false);
  });
});
