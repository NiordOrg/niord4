import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { ScriptResource } from '../../../services/api/script-resources-api.service';
import { ScriptResourceEditor, ScriptResourceEditorModel } from './script-resource-editor';

@Component({
  imports: [ScriptResourceEditor],
  template: `
    <app-script-resource-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<ScriptResourceEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: ScriptResourceEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
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

const addModel = (type: ScriptResource['type']): ScriptResourceEditorModel => ({
  mode: 'add',
  resource: { type, path: '', content: '' },
});

describe('ScriptResourceEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('gates Save on a required, type-patterned path (FM → *.ftl)', () => {
    const { fixture, host } = setup();
    host.model.set(addModel('FM'));
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true); // empty path

    editor.form.path().value.set('templates/report.txt');
    expect(editor.form().invalid()).toBe(true); // wrong extension
    expect(editor.saveDisabled()).toBe(true);

    editor.form.path().value.set('templates/report.ftl');
    expect(editor.form().valid()).toBe(true);
    expect(editor.saveDisabled()).toBe(false);
  });

  it('applies the JS pattern for a JS resource (*.js)', () => {
    const { fixture, host } = setup();
    host.model.set(addModel('JS'));
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.path().value.set('scripts/helper.ftl');
    expect(editor.form().invalid()).toBe(true); // .ftl not allowed for JS

    editor.form.path().value.set('scripts/helper.js');
    expect(editor.form().valid()).toBe(true);
  });

  it('round-trips the content through the code editor (textarea in jsdom)', () => {
    const { fixture, host } = setup();
    host.model.set(addModel('FM'));
    fixture.detectChanges();
    const editor = editorOf(fixture);

    // The drawer portals to the CDK overlay; code-text-input renders a plain
    // textarea in jsdom and binds via [formField] CVA interop.
    const textarea = document.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '<#-- ftl -->';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    expect(editor.form.content().value()).toBe('<#-- ftl -->');
  });

  it('add saves via POST; edit saves via PUT with the id path', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(addModel('FM'));
    fixture.detectChanges();
    let editor = editorOf(fixture);
    editor.form.path().value.set('a.ftl');
    editor.save();
    const post = httpTesting.expectOne('/rest/script-resources/script-resource/');
    expect(post.request.method).toBe('POST');
    post.flush({});
    expect(host.savedCount).toBe(1);

    host.model.set({ mode: 'edit', resource: { id: 5, type: 'JS', path: 'a.js', content: 'x' } });
    fixture.detectChanges();
    editor = editorOf(fixture);
    editor.form.content().value.set('y'); // a real change
    editor.save();
    const put = httpTesting.expectOne('/rest/script-resources/script-resource/5');
    expect(put.request.method).toBe('PUT');
    put.flush({});
    expect(host.savedCount).toBe(2);
  });

  it('a freshly opened edit form has Save disabled, and edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'edit', resource: { id: 5, type: 'FM', path: 'a.ftl', content: 'x' } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true); // unchanged
    editor.form.content().value.set('y');
    expect(editor.saveDisabled()).toBe(false);
    editor.form.content().value.set('x');
    expect(editor.saveDisabled()).toBe(true); // reverted to the opened value
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(addModel('FM'));
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.path().value.set('a.ftl');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/script-resources/script-resource/').flush({});
    expect(host.savedCount).toBe(1);
  });
});
