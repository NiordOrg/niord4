import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { Setting } from '../../../services/api/settings-api.service';
import { SettingEditor, SettingEditorModel } from './setting-editor';

@Component({
  imports: [SettingEditor],
  template: `
    <app-setting-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<SettingEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  editKind: () => string;
  form: SettingEditor['form'];
  saveDisabled: () => boolean;
  jsonInvalid: () => boolean;
  save: () => void;
  saving: () => boolean;
}

function setup(notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  setting: Setting,
): EditorInternals {
  host.model.set({ setting });
  fixture.detectChanges();
  return editorOf(fixture);
}

describe('SettingEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('dispatches the value control kind across all type branches incl. undefined + fallback', () => {
    const { fixture, host } = setup();

    const expectKind = (type: string | undefined, kind: string) => {
      const editor = open(fixture, host, { key: 'k', type, value: '' });
      expect(editor.editKind()).toBe(kind);
    };
    expectKind('Boolean', 'boolean');
    expectKind('Long', 'integer');
    expectKind('Integer', 'integer');
    expectKind('Float', 'decimal');
    expectKind('Double', 'decimal');
    expectKind('Password', 'password');
    expectKind('String', 'text');
    expectKind(undefined, 'text'); // untyped → text
    expectKind('Date', 'text'); // unmatched type → plain-text fallback
    expectKind('json', 'json');
  });

  it('Key and Description are display-only (disabled inputs, never part of the form)', () => {
    const { fixture, host } = setup();
    open(fixture, host, {
      key: 'web.title',
      description: 'Site title',
      type: 'String',
      value: 'N',
    });

    // The drawer chrome portals to the CDK overlay container (document-level),
    // not under the host element — query the document.
    const keyInput = document.querySelector('#setting-key') as HTMLInputElement;
    const descInput = document.querySelector('#setting-description') as HTMLInputElement;
    expect(keyInput.disabled).toBe(true);
    expect(keyInput.value).toBe('web.title');
    expect(descInput.disabled).toBe(true);
    expect(descInput.value).toBe('Site title');
  });

  it('seeds the json control with pretty-printed text and blocks Save on invalid JSON', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { key: 'cfg', type: 'json', value: { a: 1 } });

    // Pretty-printed; a fresh open is pristine so Save is disabled (no change yet).
    expect(editor.form.value!().value()).toContain('"a": 1');
    expect(editor.saveDisabled()).toBe(true);

    // Invalid edit: Save blocked, inline error flagged, never swallowed.
    editor.form.value!().value.set('{ not json');
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(true);
    expect(editor.jsonInvalid()).toBe(true);

    // Valid edit re-enables Save and clears the inline error.
    editor.form.value!().value.set('{ "a": 2 }');
    fixture.detectChanges();
    expect(editor.jsonInvalid()).toBe(false);
    expect(editor.saveDisabled()).toBe(false);
  });

  it('a freshly opened edit form has Save disabled, and edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { key: 'web.title', type: 'String', value: 'Niord' });

    expect(editor.saveDisabled()).toBe(true); // unchanged

    editor.form.value!().value.set('Niord 2');
    expect(editor.saveDisabled()).toBe(false); // real change enables Save

    editor.form.value!().value.set('Niord');
    expect(editor.saveDisabled()).toBe(true); // reverted to the opened value
  });

  it('the json validity-flip path re-enables Save even though `changed` never re-flips', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { key: 'cfg', type: 'json', value: { a: 1 } });

    // Break the JSON: changed=true but invalid → Save stays disabled.
    editor.form.value!().value.set('{ broken');
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(true);

    // Repair to NEW valid JSON: validity recovers while changed stays true.
    editor.form.value!().value.set('{ "a": 2 }');
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(false);
  });

  it('Save PUTs the URL-encoded key with the wire-shaped value and emits saved (no toast)', () => {
    const { fixture, host, httpTesting, notifications } = setup();
    const editor = open(fixture, host, { key: 'web.title', type: 'String', value: 'Niord' });
    editor.form.value!().value.set('Niord 2');
    fixture.detectChanges();
    editor.save();

    const put = httpTesting.expectOne('/rest/settings/setting/web.title');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      key: 'web.title',
      description: undefined,
      type: 'String',
      value: 'Niord 2',
    });
    put.flush({ key: 'web.title', type: 'String', value: 'Niord 2' });

    expect(host.savedCount).toBe(1); // page closes + refetches on saved
    expect(notifications.success).not.toHaveBeenCalled(); // no success toast
  });

  it('a json Save sends the PARSED object, not the string', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { key: 'cfg', type: 'json', value: { a: 1 } });
    editor.form.value!().value.set('{ "a": 2 }');
    fixture.detectChanges();
    editor.save();

    const put = httpTesting.expectOne('/rest/settings/setting/cfg');
    expect((put.request.body as { value: unknown }).value).toEqual({ a: 2 });
    put.flush({ key: 'cfg', type: 'json', value: { a: 2 } });
  });

  it('a save error toasts and STAYS open (the drawer is re-saveable)', () => {
    const { fixture, host, httpTesting, notifications } = setup();
    const editor = open(fixture, host, { key: 'web.title', type: 'String', value: 'Niord' });
    editor.form.value!().value.set('Niord 2');
    fixture.detectChanges();
    editor.save();

    httpTesting
      .expectOne('/rest/settings/setting/web.title')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.settings.toast.saveError');
    expect(host.model()).toBeDefined(); // still open for retry
    expect(host.savedCount).toBe(0);
    expect(editor.saving()).toBe(false);
  });

  it('multi-open builds a fresh control each time (no leaked json error or value)', () => {
    const { fixture, host } = setup();
    const first = open(fixture, host, { key: 'a', type: 'json', value: { a: 1 } });
    first.form.value!().value.set('{ broken');
    fixture.detectChanges();
    expect(first.jsonInvalid()).toBe(true);

    // Re-open a different (text) setting WITHOUT closing in between.
    const second = open(fixture, host, { key: 'b', type: 'String', value: 'clean' });
    expect(second.editKind()).toBe('text');
    expect(second.form.value!().value()).toBe('clean');
    expect(second.saveDisabled()).toBe(true); // fresh + pristine
    expect(second.jsonInvalid()).toBe(false); // no leaked json error
  });

  it('boolean / integer / decimal kinds map to and from the wire correctly', () => {
    const { fixture, host, httpTesting } = setup();

    const bool = open(fixture, host, { key: 'flag', type: 'Boolean', value: false });
    expect(bool.form.value!().value()).toBe(false);
    bool.form.value!().value.set(true);
    bool.save();
    const boolPut = httpTesting.expectOne('/rest/settings/setting/flag');
    expect((boolPut.request.body as { value: unknown }).value).toBe(true);
    boolPut.flush({ key: 'flag', type: 'Boolean', value: true });

    const num = open(fixture, host, { key: 'count', type: 'Integer', value: 3 });
    num.form.value!().value.set('7');
    num.save();
    const numPut = httpTesting.expectOne('/rest/settings/setting/count');
    expect((numPut.request.body as { value: unknown }).value).toBe(7); // numeric, not "7"
    numPut.flush({ key: 'count', type: 'Integer', value: 7 });
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { key: 'web.title', type: 'String', value: 'Niord' });
    editor.form.value!().value.set('Niord 2');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting
      .expectOne('/rest/settings/setting/web.title')
      .flush({ key: 'web.title', type: 'String', value: 'Niord 2' });
    expect(host.savedCount).toBe(1);
  });
});

/** The hint under a number setting's input, or null when the drawer shows none. */
function defaultHint(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-testid="setting-value-default-hint"]');
}

describe('SettingEditor (an emptied number setting means its default)', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('an integer setting says under its input that an empty value uses the default', () => {
    const { fixture, host } = setup();
    open(fixture, host, { key: 'count', type: 'Integer', value: 3 });

    const hint = defaultHint();
    expect(hint).not.toBeNull();
    expect(hint!.textContent).toContain('admin.settings.editor.emptyMeansDefault');
    const input = document.querySelector('#setting-value') as HTMLInputElement;
    expect(input.getAttribute('aria-describedby')).toBe(hint!.id);
  });

  it('a Long, Float or Double setting says so too', () => {
    const { fixture, host } = setup();
    for (const type of ['Long', 'Float', 'Double']) {
      open(fixture, host, { key: `n-${type}`, type, value: 1 });
      expect(defaultHint(), type).not.toBeNull();
      const input = document.querySelector('#setting-value') as HTMLInputElement;
      expect(input.getAttribute('aria-describedby'), type).toBe('setting-value-default-hint');
    }
  });

  it('a text, password, json or boolean setting shows no such hint (an empty value is kept as meant)', () => {
    const { fixture, host } = setup();
    for (const type of ['String', 'Password', 'json', 'Boolean', 'Path', undefined]) {
      open(fixture, host, { key: `k-${String(type)}`, type, value: '' });
      expect(defaultHint(), String(type)).toBeNull();
    }
  });

  it('emptying an integer setting saves it empty (null), which the backend reads as the default', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { key: 'count', type: 'Integer', value: 3 });
    editor.form.value!().value.set(null);
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(false);
    editor.save();

    const put = httpTesting.expectOne('/rest/settings/setting/count');
    expect((put.request.body as { value: unknown }).value).toBeNull();
    put.flush({ key: 'count', type: 'Integer', value: null });
    expect(host.savedCount).toBe(1);
  });

  it('a text value longer than its column holds keeps Save disabled; the counter says by how much (ERR7)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { key: 'k', type: 'String', value: 'old' });
    const input = document.querySelector('#setting-value') as HTMLInputElement;
    expect(input.hasAttribute('maxlength')).toBe(false);
    expect(input.getAttribute('aria-describedby')).toBe('setting-value-counter');

    // The value is stored as JSON, so its two quotes share the varchar(2000).
    editor.form.value!().value.set('a'.repeat(1999));
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(true);
    const counter = document.querySelector('#setting-value-counter') as HTMLElement;
    expect(counter.getAttribute('role')).toBe('alert');

    editor.form.value!().value.set('a'.repeat(1998));
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(false);
    expect(document.querySelector('#setting-value-counter')?.getAttribute('role')).toBeNull();
  });

  it('only a text value carries the counter and the limit', () => {
    const { fixture, host } = setup();
    for (const type of ['Password', 'json', 'Boolean', 'Integer']) {
      open(fixture, host, { key: `k-${type}`, type, value: '' });
      expect(document.querySelector('#setting-value-counter'), type).toBeNull();
    }
  });
});
