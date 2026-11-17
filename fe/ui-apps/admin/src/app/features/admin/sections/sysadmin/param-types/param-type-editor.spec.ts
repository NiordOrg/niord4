import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { ParamType } from '../../../services/api/param-types-admin-api.service';
import { ParamTypeEditor, ParamTypeEditorModel } from './param-type-editor';

@Component({
  imports: [ParamTypeEditor],
  template: `
    <app-param-type-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<ParamTypeEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: ParamTypeEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  onValueChanged: () => void;
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

/** The rendered LIST value editor eagerly loads dictionary catalogs — drain them. */
function drainDict(): void {
  TestBed.inject(HttpTestingController)
    .match((r) => r.url.startsWith('/rest/dictionaries'))
    .forEach((r) => r.flush([]));
}

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  model: ParamTypeEditorModel,
): EditorInternals {
  host.model.set(model);
  fixture.detectChanges();
  drainDict();
  return editorOf(fixture);
}

const addList: ParamTypeEditorModel = {
  mode: 'add',
  paramType: { type: 'LIST', name: '', values: [] },
};

describe('ParamTypeEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('gates Save on the required name', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, addList);

    expect(editor.saveDisabled()).toBe(true); // empty name
    editor.form.name().value.set('My list');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('a freshly opened edit form has Save disabled, and edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      paramType: { id: 3, type: 'LIST', name: 'Colours', values: [] },
    });

    expect(editor.saveDisabled()).toBe(true); // unchanged
    editor.form.name().value.set('Colors');
    expect(editor.saveDisabled()).toBe(false);
    editor.form.name().value.set('Colours');
    expect(editor.saveDisabled()).toBe(true); // reverted
  });

  it('a value-editor change (valueDirty) enables Save even without a name change', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      paramType: { id: 3, type: 'LIST', name: 'Colours', values: [] },
    });

    expect(editor.saveDisabled()).toBe(true);
    editor.onValueChanged(); // the list/composite editor emitted a change
    expect(editor.saveDisabled()).toBe(false);
  });

  it('add saves via POST; edit saves via PUT with the id path', () => {
    const { fixture, host, httpTesting } = setup();
    let editor = open(fixture, host, addList);
    editor.form.name().value.set('My list');
    editor.save();
    const post = httpTesting.expectOne('/rest/templates/parameter-type/');
    expect(post.request.method).toBe('POST');
    expect((post.request.body as ParamType).name).toBe('My list');
    post.flush({});
    expect(host.savedCount).toBe(1);

    editor = open(fixture, host, {
      mode: 'edit',
      paramType: { id: 7, type: 'LIST', name: 'X', values: [] },
    });
    editor.form.name().value.set('Y');
    editor.save();
    const put = httpTesting.expectOne('/rest/templates/parameter-type/7');
    expect(put.request.method).toBe('PUT');
    put.flush({});
    expect(host.savedCount).toBe(2);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, addList);
    editor.form.name().value.set('My list');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/templates/parameter-type/').flush({});
    expect(host.savedCount).toBe(1);
  });
});
