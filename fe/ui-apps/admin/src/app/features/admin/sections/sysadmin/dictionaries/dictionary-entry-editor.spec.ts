import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { DictionaryEntryEditor, DictionaryEntryEditorModel } from './dictionary-entry-editor';

@Component({
  imports: [DictionaryEntryEditor],
  template: `
    <app-dictionary-entry-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<DictionaryEntryEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: DictionaryEntryEditor['form'];
  save: () => void;
  saveDisabled: () => boolean;
  warnings: () => readonly string[];
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/** Set the `value` of the desc row for a given language. */
function setValue(drawer: EditorInternals, lang: string, value: string): void {
  const i = drawer.form
    .descs()
    .value()
    .findIndex((d) => d.lang === lang);
  drawer.form.descs[i].value().value.set(value);
}

describe('DictionaryEntryEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('edit mode disables the key (it is the update identifier); add mode keeps it editable', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: { id: 1, key: 'k', descs: [{ lang: 'en', value: 'v' }] },
      showExtended: false,
    });
    fixture.detectChanges();
    expect(editorOf(fixture).form.key().disabled()).toBe(true);

    host.model.set({
      mode: 'add',
      dictionaryName: 'web',
      entry: { key: '', descs: [] },
      showExtended: false,
    });
    fixture.detectChanges();
    expect(editorOf(fixture).form.key().disabled()).toBe(false);
  });

  it('create is blocked while every language value is blank; one value unblocks', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'add',
      dictionaryName: 'web',
      entry: { key: '', descs: [] },
      showExtended: false,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);

    drawer.form.key().value.set('new.key');
    expect(drawer.saveDisabled()).toBe(true); // all values blank

    drawer.form.descs[0].value().value.set('a value');
    expect(drawer.saveDisabled()).toBe(false);
  });

  it('warns (non-blocking) when a blank value carries a filled long version', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: {
        id: 1,
        key: 'k',
        descs: [
          { lang: 'en', value: 'v', longValue: 'long' },
          { lang: 'da', value: '', longValue: 'kun lang' },
        ],
      },
      showExtended: true,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);
    expect(drawer.warnings()).toContain('admin.dictionaries.editor.warnLongValueDiscarded');

    // A real value change makes the form dirty; the warning is non-blocking.
    setValue(drawer, 'en', 'v2');
    expect(drawer.saveDisabled()).toBe(false); // warning does not block
  });

  it('round-trips longValue presence: defined-empty stays, absent is never invented', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: {
        id: 1,
        key: 'k',
        descs: [
          { lang: 'en', value: 'v', longValue: '' },
          { lang: 'da', value: 'w' },
        ],
      },
      showExtended: true,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);
    setValue(drawer, 'en', 'v2');
    drawer.save();

    const req = httpTesting.expectOne('/rest/dictionaries/dictionary/web/k');
    const body = req.request.body as {
      descs: { lang: string; longValue?: string }[];
    };
    const en = body.descs.find((d) => d.lang === 'en');
    const da = body.descs.find((d) => d.lang === 'da');
    expect('longValue' in (en ?? {})).toBe(true); // defined-empty preserved
    expect('longValue' in (da ?? {})).toBe(false); // absent not invented
    req.flush({});
  });

  it('a freshly opened edit form has Save disabled, and edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: { id: 1, key: 'k', descs: [{ lang: 'en', value: 'v' }] },
      showExtended: false,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);

    expect(drawer.saveDisabled()).toBe(true); // no change yet

    setValue(drawer, 'en', 'v2');
    expect(drawer.saveDisabled()).toBe(false); // real change enables Save

    setValue(drawer, 'en', 'v');
    expect(drawer.saveDisabled()).toBe(true); // reverted to the opened value
  });

  it('a value or long version longer than 1,000 characters keeps Save disabled, with a counter under each (ERR7)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: { id: 1, key: 'k', descs: [{ lang: 'en', value: 'v', longValue: 'long v' }] },
      showExtended: true,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);
    const rows = drawer.form.descs().value().length;
    expect(document.querySelectorAll('app-length-counter p')).toHaveLength(rows * 2);

    const i = drawer.form
      .descs()
      .value()
      .findIndex((d) => d.lang === 'en');
    for (const field of ['value', 'longValue'] as const) {
      drawer.form.descs[i][field]().value.set('a'.repeat(1001));
      fixture.detectChanges();
      expect(drawer.saveDisabled()).toBe(true);
      expect(document.querySelector('app-length-counter p[role="alert"]')).not.toBeNull();

      drawer.form.descs[i][field]().value.set('a'.repeat(1000));
      fixture.detectChanges();
      expect(drawer.saveDisabled()).toBe(false);
    }
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      dictionaryName: 'web',
      entry: { id: 1, key: 'k', descs: [{ lang: 'en', value: 'v' }] },
      showExtended: false,
    });
    fixture.detectChanges();
    const drawer = editorOf(fixture);
    setValue(drawer, 'en', 'v2'); // a real change so Save is armed
    fixture.detectChanges();

    // Drive the real submit event (the drawer portals to the CDK overlay, so the
    // form lives at document level). A bare `(ngSubmit)` with no form directive
    // to emit it never fires — calling save() directly would sail right past it.
    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/k').flush({});
    expect(host.savedCount).toBe(1);
  });
});
