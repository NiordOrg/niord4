import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Contact } from '../../../services/api/contacts-api.service';
import type { MailingList } from '../../../services/api/mailing-lists-api.service';
import { ContactEditor, ContactEditorModel } from './contact-editor';
import { UsersFacade } from './users.facade';

// jsdom lacks ResizeObserver, which the tab list observes.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  imports: [ContactEditor],
  providers: [UsersFacade],
  template: `
    <app-contact-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<ContactEditorModel | undefined>(undefined);
  savedCount = 0;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The host-provided UsersFacade eagerly loads the lang-keyed lists catalog.
  httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
  return { fixture, host: fixture.componentInstance, httpTesting };
}

interface EditorInternals {
  form: ContactEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  memberIds: () => ReadonlySet<string> | undefined;
  onMembershipToggle: (toggle: { list: MailingList; recipient: boolean }) => void;
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

const PERSISTED: Contact = { id: 7, email: 'a@x.dk', name: 'Anna', language: 'da' };

describe('ContactEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('add mode requires the email before Save enables; add POSTs without an id', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', contact: { email: '' } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true);
    editor.form.name().value.set('Bob');
    expect(editor.saveDisabled()).toBe(true); // email still empty
    editor.form.email().value.set('bob@x.dk');
    expect(editor.saveDisabled()).toBe(false);

    editor.save();
    const req = httpTesting.expectOne('/rest/contacts/contact/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      id: undefined,
      email: 'bob@x.dk',
      name: 'Bob',
      language: undefined,
    });
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('edit PUTs by the numeric id; clearing the language omits it', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', contact: PERSISTED });
    fixture.detectChanges();
    httpTesting.expectOne('/rest/mailing-lists/search?contactEmail=a@x.dk').flush([]);
    const editor = editorOf(fixture);

    editor.form.language().value.set('');
    editor.save();

    const req = httpTesting.expectOne('/rest/contacts/contact/7');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as Contact).language).toBeUndefined();
    req.flush({});
  });

  it('a membership toggle PUTs the FULL contact keyed by EMAIL (never the id), toast-quiet', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', contact: PERSISTED });
    fixture.detectChanges();
    httpTesting.expectOne('/rest/mailing-lists/search?contactEmail=a@x.dk').flush([]);
    const editor = editorOf(fixture);

    editor.onMembershipToggle({ list: { mailingListId: 'list-a' }, recipient: true });
    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/list-a/update-status');
    expect(req.request.body).toEqual({ contact: PERSISTED, recipient: true });
    req.flush(true);

    // Success re-resolves the flags via the EMAIL lookup.
    httpTesting
      .expectOne('/rest/mailing-lists/search?contactEmail=a@x.dk')
      .flush([{ mailingListId: 'list-a' }]);
    expect(editor.memberIds()).toEqual(new Set(['list-a']));
  });

  it('a failed save keeps the drawer open and re-saveable', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', contact: { email: '' } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.email().value.set('bob@x.dk');
    editor.save();
    httpTesting
      .expectOne('/rest/contacts/contact/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined();
    expect(host.savedCount).toBe(0);
    expect(editor.saving()).toBe(false);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', contact: { email: '' } });
    fixture.detectChanges();
    editorOf(fixture).form.email().value.set('bob@x.dk');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/contacts/contact/').flush({});
    expect(host.savedCount).toBe(1);
  });
});
