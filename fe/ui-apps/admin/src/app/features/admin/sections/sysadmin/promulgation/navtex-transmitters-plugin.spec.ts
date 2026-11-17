import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { NavtexTransmittersPlugin } from './navtex-transmitters-plugin';
import type { NavtexTransmitter } from './promulgation-admin.model';

@Component({
  imports: [NavtexTransmittersPlugin],
  template: `<app-navtex-transmitters-plugin [typeId]="typeId()" [typeName]="typeName()" />`,
})
class Host {
  // A free-text typeId with a space pins the encodeURIComponent contract.
  readonly typeId = signal('navtex dk');
  readonly typeName = signal('NAVTEX');
}

interface Internals {
  form: NavtexTransmittersPlugin['form'];
  editing: () => 'add' | 'edit' | undefined;
  saveDisabled: () => boolean;
  transmitters: () => readonly NavtexTransmitter[] | undefined;
  loadFailed: () => boolean;
  dialogVisible: () => boolean;
  save: () => void;
}

const LIST_URL = '/rest/promulgation/navtex/transmitters/navtex%20dk/all?lang=en';

const BALTICO: NavtexTransmitter = {
  promulgationType: { typeId: 'navtex dk' },
  name: 'Baltico',
  active: true,
  areas: [{ id: 7, descs: [{ lang: 'en', name: 'Kattegat' }] }],
};

/** A name with a slash pins the encoding of the name-keyed PUT/DELETE paths. */
const ROGALAND: NavtexTransmitter = {
  promulgationType: { typeId: 'navtex dk' },
  name: 'Rogaland/W',
  active: false,
  areas: [],
};

function setup() {
  const notifications = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
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

function internalsOf(fixture: ReturnType<typeof setup>['fixture']): Internals {
  return fixture.debugElement.children[0].componentInstance as unknown as Internals;
}

/** Open the dialog via the in-drawer Manage… button (the plugin row lives in the host DOM). */
function openDialog(fixture: ReturnType<typeof setup>['fixture']): void {
  (fixture.nativeElement as HTMLElement).querySelector('button')?.click();
  fixture.detectChanges();
}

/** The dialog portals to the body-level overlay container — query the document. */
function dialogPanel(): HTMLElement | null {
  return document.querySelector('.dialog-panel');
}

function dialogButton(text: string): HTMLButtonElement {
  const match = Array.from(
    document.querySelectorAll<HTMLButtonElement>('.dialog-panel button'),
  ).find((button) => button.textContent?.includes(text));
  if (!match) {
    throw new Error(`no dialog button containing "${text}"`);
  }
  return match;
}

function rowAction(ariaLabel: string, index = 0): HTMLButtonElement {
  const buttons = document.querySelectorAll<HTMLButtonElement>(`[aria-label="${ariaLabel}"]`);
  const button = buttons.item(index);
  if (!button) {
    throw new Error(`no row action "${ariaLabel}" at index ${index}`);
  }
  return button;
}

describe('NavtexTransmittersPlugin', () => {
  afterEach(() => {
    // The i18n bundle test never configures TestBed — inject leniently.
    TestBed.inject(HttpTestingController, null)?.verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('Manage… opens the dialog and loads the list typeId-keyed with the UI language', () => {
    const { fixture, httpTesting } = setup();
    openDialog(fixture);

    const req = httpTesting.expectOne(LIST_URL);
    expect(req.request.method).toBe('GET');
    req.flush([BALTICO, ROGALAND]);
    fixture.detectChanges();

    const panel = dialogPanel();
    expect(panel?.textContent).toContain('Baltico');
    expect(panel?.textContent).toContain('Rogaland/W');
    // Lang-resolved area names, one per line.
    expect(panel?.textContent).toContain('Kattegat');
  });

  it('renders the empty state for a transmitter-less type', () => {
    const { fixture, httpTesting } = setup();
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.navtex.empty');
  });

  it('a load failure renders inline in the dialog and retry refetches', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(internals.loadFailed()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.navtex.loadError');

    dialogButton('admin.promulgation.retry').click();
    fixture.detectChanges();
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(internals.loadFailed()).toBe(false);
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.navtex.empty');
  });

  it('Add swaps to the edit view with the default values and a validity-gated save', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();

    dialogButton('admin.promulgation.navtex.addTransmitter').click();
    fixture.detectChanges();

    expect(internals.editing()).toBe('add');
    expect(internals.form.name().disabled()).toBe(false);
    expect(internals.form.active().value()).toBe(true);
    // Validity gate: an empty name blocks the save.
    expect(internals.saveDisabled()).toBe(true);
    internals.form.name().value.set('Baltico');
    expect(internals.saveDisabled()).toBe(false);
  });

  it('add save POSTs the trailing-slash URL with the parent ref + minimal {id} area refs, then returns to the refreshed list', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    dialogButton('admin.promulgation.navtex.addTransmitter').click();
    fixture.detectChanges();

    internals.form.name().value.set('Baltico');
    // The picker holds rich objects — the payload must reduce them to {id} refs.
    internals.form
      .areas()
      .value.set([{ id: 7, active: true, descs: [{ lang: 'en', name: 'Kattegat' }] }]);
    internals.save();

    const post = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex%20dk/transmitter/',
    );
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      promulgationType: { typeId: 'navtex dk' },
      name: 'Baltico',
      active: true,
      areas: [{ id: 7 }],
    });
    post.flush(BALTICO);

    // Success returns to the in-dialog list + refetch — the dialog stays open.
    httpTesting.expectOne(LIST_URL).flush([BALTICO]);
    fixture.detectChanges();
    expect(notifications.success).toHaveBeenCalledWith('admin.promulgation.navtex.toast.created');
    expect(internals.editing()).toBeUndefined();
    expect(internals.dialogVisible()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('Baltico');
  });

  it('edit locks the name (the PK) and PUTs to the encoded name-keyed URL', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([ROGALAND]);
    fixture.detectChanges();

    rowAction('admin.promulgation.navtex.edit').click();
    fixture.detectChanges();

    expect(internals.editing()).toBe('edit');
    expect(internals.form.name().disabled()).toBe(true);
    const nameInput = document.querySelector<HTMLInputElement>('#navtex-transmitter-name');
    expect(nameInput?.disabled).toBe(true);

    internals.form.active().value.set(true);
    internals.save();

    const put = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex%20dk/transmitter/Rogaland%2FW',
    );
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      promulgationType: { typeId: 'navtex dk' },
      name: 'Rogaland/W',
      active: true,
      areas: [],
    });
    put.flush(put.request.body as NavtexTransmitter);
    httpTesting.expectOne(LIST_URL).flush([{ ...ROGALAND, active: true }]);
    fixture.detectChanges();
    expect(internals.editing()).toBeUndefined();
  });

  it('a save failure toasts distinctly and keeps the dialog open in the edit view', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    dialogButton('admin.promulgation.navtex.addTransmitter').click();
    fixture.detectChanges();

    internals.form.name().value.set('Baltico');
    internals.save();
    httpTesting
      .expectOne('/rest/promulgation/navtex/transmitters/navtex%20dk/transmitter/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(notifications.error).toHaveBeenCalledWith('admin.promulgation.navtex.toast.createError');
    // No list refetch (verified by afterEach); the edit view stays up for a retry.
    expect(internals.editing()).toBe('add');
    expect(dialogPanel()).not.toBeNull();
  });

  it('delete confirms, DELETEs the encoded name and refreshes the list', () => {
    const { fixture, httpTesting, notifications } = setup();
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([ROGALAND]);
    fixture.detectChanges();

    rowAction('admin.promulgation.navtex.delete').click();
    fixture.detectChanges();

    // The plugin mounts its own <app-confirm>; accept through the rendered dialog.
    const accept = document.querySelector<HTMLButtonElement>('.confirm-foot .btn-danger');
    expect(accept).not.toBeNull();
    accept?.click();
    fixture.detectChanges();

    const del = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex%20dk/transmitter/Rogaland%2FW',
    );
    expect(del.request.method).toBe('DELETE');
    del.flush({});
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(notifications.success).toHaveBeenCalledWith('admin.promulgation.navtex.toast.deleted');
  });

  it('Cancel returns to the (refetched) list without closing the dialog', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([BALTICO]);
    fixture.detectChanges();
    rowAction('admin.promulgation.navtex.edit').click();
    fixture.detectChanges();
    expect(internals.editing()).toBe('edit');

    dialogButton('admin.promulgation.navtex.cancel').click();
    fixture.detectChanges();
    httpTesting.expectOne(LIST_URL).flush([BALTICO]);
    fixture.detectChanges();

    expect(internals.editing()).toBeUndefined();
    expect(internals.dialogVisible()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('Baltico');
  });

  it('every navtex key resolves in the en bundle', () => {
    const leafPaths = (node: unknown, prefix = ''): string[] =>
      Object.entries(node as Record<string, unknown>).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        if (typeof value === 'string') {
          expect(value, path).toMatch(/\S/);
          return [path];
        }
        return leafPaths(value, path);
      });
    expect(leafPaths(en.admin.promulgation.navtex).length).toBeGreaterThan(0);
    expect(en.admin.promulgation.navtex.confirmDelete.header).toBe('Delete Transmitter?');
  });
});
