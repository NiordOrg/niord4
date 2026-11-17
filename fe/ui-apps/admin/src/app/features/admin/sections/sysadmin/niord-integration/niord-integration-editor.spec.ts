import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { NiordIntegrationEditor, NiordIntegrationEditorModel } from './niord-integration-editor';

@Component({
  imports: [NiordIntegrationEditor],
  template: `
    <app-niord-integration-editor
      [model]="model()"
      [seriesIds]="seriesIds()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<NiordIntegrationEditorModel | undefined>(undefined);
  readonly seriesIds = signal<readonly string[]>(['local-nw', 'local-nm']);
  savedCount = 0;
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

interface EditorInternals {
  form: NiordIntegrationEditor['form'];
  addMapping: () => void;
  removeMapping: (index: number) => void;
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  displayId: () => string;
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/** Current mapping rows (the array field is the source of truth). */
function rows(editor: EditorInternals) {
  return editor.form.messageSeriesMappings().value();
}
function setRowTarget(editor: EditorInternals, i: number, target: string): void {
  editor.form.messageSeriesMappings[i].targetSeriesId().value.set(target);
}

const ADD_MODEL: NiordIntegrationEditorModel = {
  mode: 'add',
  integration: {
    url: '',
    active: true,
    assignNewUids: true,
    createBaseData: false,
    messageSeriesMappings: [],
  },
};

describe('NiordIntegrationEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('add mode seeds one blank mapping row and the flag defaults', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(rows(editor).length).toBe(1);
    expect(editor.form.active().value()).toBe(true);
    expect(editor.form.assignNewUids().value()).toBe(true);
    expect(editor.form.createBaseData().value()).toBe(false);
    expect(editor.displayId()).toBe(''); // blank on add
    expect(editor.saveDisabled()).toBe(true); // URL empty + target unset
  });

  it('edit seeds a blank row only when the persisted list is empty; else keeps persisted', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      integration: {
        id: 4,
        url: 'https://remote',
        active: false,
        assignNewUids: false,
        createBaseData: true,
        messageSeriesMappings: [],
      },
    });
    fixture.detectChanges();
    expect(rows(editorOf(fixture)).length).toBe(1); // seeded
    expect(editorOf(fixture).displayId()).toBe('4');

    host.model.set({
      mode: 'edit',
      integration: {
        id: 5,
        url: 'https://remote',
        active: true,
        assignNewUids: true,
        createBaseData: false,
        messageSeriesMappings: [
          { sourceSeriesId: 'remote-a', targetSeriesId: 'local-nw' },
          { sourceSeriesId: 'remote-b', targetSeriesId: 'local-nm' },
        ],
      },
    });
    fixture.detectChanges();
    expect(rows(editorOf(fixture)).length).toBe(2); // persisted kept, no extra seed
  });

  it('Add appends a blank row; trash removes a row but re-seeds a blank if it was the last', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.addMapping();
    expect(rows(editor).length).toBe(2);

    editor.removeMapping(1);
    expect(rows(editor).length).toBe(1);

    // Removing the SOLE row re-seeds a fresh blank — never zero rows.
    editor.removeMapping(0);
    expect(rows(editor).length).toBe(1);
  });

  it('Save is gated until URL + every mapping target are set AND a change exists', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true);

    editor.form.url().value.set('https://remote');
    expect(editor.saveDisabled()).toBe(true); // mapping target still unset

    setRowTarget(editor, 0, 'local-nw');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('save sends the record verbatim — a blank-source row survives with its chosen target', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.url().value.set('https://remote');
    setRowTarget(editor, 0, 'local-nw'); // source stays blank
    editor.save();

    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      id: undefined,
      url: 'https://remote',
      active: true,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [{ sourceSeriesId: '', targetSeriesId: 'local-nw' }],
    });
    req.flush({ id: 1 });
    expect(host.savedCount).toBe(1);
  });

  it('edit save PUTs to the path id matching the body id', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      integration: {
        id: 9,
        url: 'https://remote',
        active: true,
        assignNewUids: true,
        createBaseData: false,
        messageSeriesMappings: [{ sourceSeriesId: 'remote-a', targetSeriesId: 'local-nw' }],
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.url().value.set('https://remote-2'); // a real change

    editor.save();
    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/9');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { id: number }).id).toBe(9);
    req.flush({ id: 9 });
    expect(host.savedCount).toBe(1);
  });

  it('multi-open reuse: opening a second row does not leak the first row mappings', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      integration: {
        id: 1,
        url: 'https://a',
        active: true,
        assignNewUids: true,
        createBaseData: false,
        messageSeriesMappings: [
          { sourceSeriesId: 's1', targetSeriesId: 'local-nw' },
          { sourceSeriesId: 's2', targetSeriesId: 'local-nm' },
        ],
      },
    });
    fixture.detectChanges();
    expect(rows(editorOf(fixture)).length).toBe(2);

    host.model.set({
      mode: 'edit',
      integration: {
        id: 2,
        url: 'https://b',
        active: true,
        assignNewUids: true,
        createBaseData: false,
        messageSeriesMappings: [{ sourceSeriesId: 's3', targetSeriesId: 'local-nw' }],
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(rows(editor).length).toBe(1);
    expect(rows(editor)[0].sourceSeriesId).toBe('s3');
  });

  it('a failed save keeps the drawer open and re-saveable', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.url().value.set('https://remote');
    setRowTarget(editor, 0, 'local-nw');
    editor.save();

    httpTesting
      .expectOne('/rest/niord-integrations/niord-integration/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined(); // still open
    expect(host.savedCount).toBe(0);
    expect(editor.saving()).toBe(false); // re-saveable
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.url().value.set('https://remote');
    setRowTarget(editor, 0, 'local-nw');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/niord-integrations/niord-integration/').flush({ id: 1 });
    expect(host.savedCount).toBe(1);
  });
});
