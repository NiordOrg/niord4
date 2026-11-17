import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageSettingsService } from '../../../../../core/i18n/language-settings.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SourceEditor, SourceEditorModel } from './source-editor';

@Component({
  imports: [SourceEditor],
  template: `
    <app-source-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<SourceEditorModel | undefined>(undefined);
  savedCount = 0;
}

function setup(settings?: Record<string, unknown>) {
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
  if (settings) TestBed.inject(LanguageSettingsService).configure(settings);
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): SourceEditor {
  return fixture.debugElement.children[0].componentInstance as SourceEditor;
}

interface EditorInternals {
  form: SourceEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
}

/** The current descs array (source of truth for the form). */
function descs(internals: EditorInternals) {
  return internals.form.descs().value();
}
/** Set the name of every configured-language row. */
function setAllNames(internals: EditorInternals, name: string): void {
  descs(internals).forEach((_, i) => internals.form.descs[i].name().value.set(name));
}

describe('SourceEditor', () => {
  it('requires the explicit default language and leaves optional translations absent on save', () => {
    const { fixture, host, httpTesting } = setup({
      siteLanguages: ['en', 'da'],
      modelLanguages: ['en', 'kl', 'da'],
      defaultLanguage: 'da',
    });
    host.model.set({ mode: 'add', source: { active: true, descs: [] } });
    fixture.detectChanges();
    const editor = editorOf(fixture) as unknown as EditorInternals;
    expect(descs(editor).map((d) => d.lang)).toEqual(['en', 'kl', 'da']);
    editor.form.descs[2].name().value.set('Standardnavn');
    expect(editor.saveDisabled()).toBe(false);
    editor.save();
    const request = httpTesting.expectOne('/rest/sources/source/');
    // Signal Forms adds internal symbols; HTTP sends the JSON representation.
    expect(JSON.parse(JSON.stringify(request.request.body.descs))).toEqual([
      { lang: 'da', name: 'Standardnavn', abbreviation: '' },
    ]);
    request.flush(request.request.body);
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('edit mode pads every configured language AND preserves unknown-language descs', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      source: {
        id: 3,
        active: true,
        // Missing 'en'; carries an unconfigured 'fo' desc the save must not drop.
        descs: [
          { lang: 'da', name: 'Kilde', abbreviation: 'K' },
          { lang: 'fo', name: 'Færøsk', abbreviation: 'F' },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    const langs = descs(internals).map((d) => d.lang);
    expect(langs).toContain('en');
    expect(langs).toContain('da');
    expect(langs).toContain('fo'); // unknown language preserved
    expect(descs(internals).length).toBe(3);
  });

  it('Save is gated on every configured language having a Name', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', source: { active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    expect(internals.form().invalid()).toBe(true); // all names empty

    setAllNames(internals, 'x');
    expect(internals.form().valid()).toBe(true); // abbreviation stays optional
  });

  it('a failed save keeps the drawer open with values intact', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', source: { active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    setAllNames(internals, 'Name');
    internals.save();
    httpTesting
      .expectOne('/rest/sources/source/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined(); // still open
    expect(host.savedCount).toBe(0);
    expect(descs(internals)[0].name).toBe('Name');
    expect(internals.saving()).toBe(false); // re-saveable
  });

  it('a freshly opened edit form has Save disabled, and edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      source: {
        id: 3,
        active: true,
        descs: [
          { lang: 'en', name: 'Source', abbreviation: 'S' },
          { lang: 'da', name: 'Kilde', abbreviation: 'K' },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    expect(internals.form().valid()).toBe(true);
    expect(internals.saveDisabled()).toBe(true); // valid but unchanged

    const enIndex = descs(internals).findIndex((d) => d.lang === 'en');
    const enName = internals.form.descs[enIndex].name();
    enName.value.set('Source!');
    expect(internals.saveDisabled()).toBe(false); // real change enables Save

    enName.value.set('Source');
    expect(internals.saveDisabled()).toBe(true); // reverted to the opened value

    // Validity flip while still off-baseline: clear (invalid, changed) then type a
    // NEW name — Save must enable even though the value never returns to baseline.
    enName.value.set('');
    expect(internals.saveDisabled()).toBe(true); // changed but invalid
    enName.value.set('Renamed');
    expect(internals.saveDisabled()).toBe(false); // validity recovery re-enables
  });

  it('a name or abbreviation longer than its column keeps Save disabled; a counter sits under each field (ERR7)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      source: {
        id: 3,
        active: true,
        descs: [
          { lang: 'en', name: 'Source', abbreviation: 'S' },
          { lang: 'da', name: 'Kilde', abbreviation: 'K' },
        ],
      },
    });
    fixture.detectChanges();
    const internals = editorOf(fixture) as unknown as EditorInternals;
    const enIndex = descs(internals).findIndex((d) => d.lang === 'en');

    // One counter per language for the name and one for the abbreviation.
    expect(document.querySelectorAll('app-length-counter p')).toHaveLength(
      descs(internals).length * 2,
    );

    for (const field of ['name', 'abbreviation'] as const) {
      const control = internals.form.descs[enIndex][field]();
      control.value.set('a'.repeat(256));
      fixture.detectChanges();
      expect(internals.saveDisabled()).toBe(true);
      expect(document.querySelector('app-length-counter p[role="alert"]')).not.toBeNull();

      control.value.set('a'.repeat(255));
      fixture.detectChanges();
      expect(internals.saveDisabled()).toBe(false);
      expect(document.querySelector('app-length-counter p[role="alert"]')).toBeNull();
    }
  });

  it('a successful save emits saved (the page closes + refetches)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', source: { active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    setAllNames(internals, 'Name');
    internals.save();
    httpTesting.expectOne('/rest/sources/source/').flush({ id: 1, active: true, descs: [] });
    expect(host.savedCount).toBe(1);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', source: { active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture) as unknown as EditorInternals;
    setAllNames(internals, 'Name');
    fixture.detectChanges();

    // Drive the real submit event (the drawer portals to the CDK overlay, so the
    // form lives at document level). A bare `(ngSubmit)` with no form directive
    // to emit it never fires — calling save() directly would sail right past it.
    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/sources/source/').flush({ id: 1, active: true, descs: [] });
    expect(host.savedCount).toBe(1);
  });
});
