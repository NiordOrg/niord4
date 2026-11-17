import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { MESSAGE_TAGS_DATA_SOURCE } from '../../../../../shared/forms/message-tags-field/message-tags-data.token';
import { provideFakeAuthFacade } from '../../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MessagePrintService } from '../../../../messages/services/message-print.service';
import type { SystemMessageReport } from '../../../services/api/message-reports-admin-api.service';
import { ReportEditor, ReportEditorModel } from './report-editor';

@Component({
  imports: [ReportEditor],
  template: `
    <app-report-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<ReportEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: ReportEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  propertiesInvalid: () => boolean;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideFakeAuthFacade(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
      { provide: DomainFacade, useValue: { availableDomains: signal([]) } },
      {
        provide: MESSAGE_TAGS_DATA_SOURCE,
        useValue: { searchScoped: () => of([]), resolveByIds: () => of([]) },
      },
      { provide: MessagePrintService, useValue: { printReportTest: () => of(undefined) } },
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

const ADD_MODEL: ReportEditorModel = {
  mode: 'add',
  report: { reportId: '', name: '', templatePath: '' },
};

/**
 * Fill the three required fields (reportId, name, templatePath). detectChanges is
 * required so the templatePath write reaches the ScriptResourcesField value control —
 * a native input updates synchronously, but a `[formField]` child control does not
 * until the render effect flushes.
 */
function fillRequired(editor: EditorInternals, fixture: ReturnType<typeof setup>['fixture']): void {
  editor.form.reportId().value.set('r1');
  editor.form.name().value.set('Report One');
  editor.form.templatePath().value.set('reports/standard.ftl');
  fixture.detectChanges();
}

describe('ReportEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('gates Save on the required reportId, name, and (via CVA interop) a valid templatePath', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true); // all required empty

    editor.form.reportId().value.set('r1');
    editor.form.name().value.set('Report One');
    // templatePath still empty → the form schema's required(templatePath) makes the form invalid.
    expect(editor.form().invalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    // A wrong extension is invalid (the field's FM pattern flows through interop).
    editor.form.templatePath().value.set('reports/standard.js');
    fixture.detectChanges();
    expect(editor.form().invalid()).toBe(true);

    editor.form.templatePath().value.set('reports/standard.ftl');
    fixture.detectChanges();
    expect(editor.form().valid()).toBe(true);
    expect(editor.saveDisabled()).toBe(false);
  });

  it('edit mode disables the immutable reportId PK', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      report: { reportId: 'r1', name: 'R', templatePath: 'a.ftl' },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(editor.form.reportId().disabled()).toBe(true);
    expect(editor.saveDisabled()).toBe(true); // unchanged
  });

  it('malformed JSON blocks Save and flags the inline error (non-form gate)', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    fillRequired(editor, fixture);
    expect(editor.saveDisabled()).toBe(false); // valid so far

    editor.form.properties().value.set('{ not json');
    expect(editor.propertiesInvalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.form.properties().value.set('{ "a": 1 }');
    expect(editor.propertiesInvalid()).toBe(false);
    expect(editor.saveDisabled()).toBe(false);
  });

  it('add saves via POST with parsed JSON and flattened domains', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    fillRequired(editor, fixture);
    editor.form.properties().value.set('{ "landscape": true }');
    editor.save();

    const req = httpTesting.expectOne('/rest/message-reports/report/');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as SystemMessageReport & { properties: unknown };
    expect(body.reportId).toBe('r1');
    expect(body.templatePath).toBe('reports/standard.ftl');
    expect(body.properties).toEqual({ landscape: true }); // parsed, not the string
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('edit saves via PUT with the URL-encoded reportId path id', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      report: { reportId: 'a/b', name: 'R', templatePath: 'a.ftl' },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.name().value.set('Changed');
    editor.save();

    const req = httpTesting.expectOne('/rest/message-reports/report/a%2Fb');
    expect(req.request.method).toBe('PUT');
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    fillRequired(editor, fixture);
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/message-reports/report/').flush({});
    expect(host.savedCount).toBe(1);
  });
});
