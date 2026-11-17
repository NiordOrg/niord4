import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { TwitterSettings } from './promulgation-admin.model';
import { TwitterSettingsPlugin } from './twitter-settings-plugin';

@Component({
  imports: [TwitterSettingsPlugin],
  template: `<app-twitter-settings-plugin [typeId]="typeId()" [typeName]="typeName()" />`,
})
class Host {
  // A free-text typeId with a space pins the encodeURIComponent contract.
  readonly typeId = signal('twitter dk');
  readonly typeName = signal('Twitter');
}

interface Internals {
  form: TwitterSettingsPlugin['form'];
  mode: () => 'add' | 'edit' | undefined;
  saveDisabled: () => boolean;
  dialogVisible: () => boolean;
  save: () => void;
}

/** The GET and the typeId-keyed PUT share this URL; the create POSTs the collection. */
const SETTINGS_URL = '/rest/promulgation/twitter-settings/twitter%20dk';
const CREATE_URL = '/rest/promulgation/twitter-settings/';

const SETTINGS: TwitterSettings = {
  promulgationType: { typeId: 'twitter dk' },
  format: 'NW: {{text}}',
  includeThumbnail: false,
  apiKey: 'key-1',
  apiSecret: 'secret-1',
  accessToken: 'token-1',
  accessTokenSecret: 'token-secret-1',
};

function setup() {
  const notifications = { success: vi.fn(), error: vi.fn() };
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
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

function internalsOf(fixture: ReturnType<typeof setup>['fixture']): Internals {
  return fixture.debugElement.children[0].componentInstance as unknown as Internals;
}

/** Open the dialog via the in-drawer Update Settings… button (the plugin row lives in the host DOM). */
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

function fieldInput(id: string): HTMLInputElement {
  const input = document.querySelector<HTMLInputElement>(`#${id}`);
  if (!input) {
    throw new Error(`no input #${id}`);
  }
  return input;
}

/** Fill the four required credentials (the pristine-or-invalid gate's happy path). */
function fillCredentials(internals: Internals): void {
  internals.form.apiKey().value.set('k');
  internals.form.apiSecret().value.set('s');
  internals.form.accessToken().value.set('t');
  internals.form.accessTokenSecret().value.set('ts');
}

describe('TwitterSettingsPlugin', () => {
  afterEach(() => {
    // The i18n bundle test never configures TestBed — inject leniently.
    TestBed.inject(HttpTestingController, null)?.verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('a loaded record opens in edit-mode with every persisted value seeded — the secrets in clear inside password inputs', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);

    const req = httpTesting.expectOne(SETTINGS_URL);
    expect(req.request.method).toBe('GET');
    req.flush(SETTINGS);
    fixture.detectChanges();

    expect(internals.mode()).toBe('edit');
    expect(dialogPanel()).not.toBeNull();

    // The two secrets are password inputs…
    const apiSecret = fieldInput('twitter-settings-api-secret');
    const accessTokenSecret = fieldInput('twitter-settings-access-token-secret');
    expect(apiSecret.type).toBe('password');
    expect(accessTokenSecret.type).toBe('password');
    // …but the persisted clear-text values still round-trip into them.
    expect(apiSecret.value).toBe('secret-1');
    expect(accessTokenSecret.value).toBe('token-secret-1');

    expect(fieldInput('twitter-settings-api-key').type).toBe('text');
    expect(fieldInput('twitter-settings-api-key').value).toBe('key-1');
    expect(fieldInput('twitter-settings-access-token').type).toBe('text');
    expect(fieldInput('twitter-settings-access-token').value).toBe('token-1');
    expect(fieldInput('twitter-settings-format').value).toBe('NW: {{text}}');
    expect(fieldInput('twitter-settings-include-thumbnail').checked).toBe(false);
  });

  it('a null GET body flips to create-on-demand add-mode with the default values', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(null);
    fixture.detectChanges();

    expect(internals.mode()).toBe('add');
    expect(internals.form.includeThumbnail().value()).toBe(true);
    expect(internals.form.format().value()).toBe('');
    expect(internals.form.apiKey().value()).toBe('');
    expect(internals.form.apiSecret().value()).toBe('');
    expect(internals.form.accessToken().value()).toBe('');
    expect(internals.form.accessTokenSecret().value()).toBe('');
  });

  it('a GET error also flips to add-mode — silently, without an error toast', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(internals.mode()).toBe('add');
    expect(internals.form.includeThumbnail().value()).toBe(true);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('save is gated on pristine || invalid: all four credentials required, and reverting to the loaded values re-disables', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(SETTINGS);
    fixture.detectChanges();

    // Pristine: nothing changed yet.
    expect(internals.saveDisabled()).toBe(true);
    // A change enables…
    internals.form.apiKey().value.set('key-2');
    expect(internals.saveDisabled()).toBe(false);
    // …clearing a required credential disables (invalid)…
    internals.form.apiKey().value.set('');
    expect(internals.saveDisabled()).toBe(true);
    // …and reverting to the loaded value is pristine again.
    internals.form.apiKey().value.set('key-1');
    expect(internals.saveDisabled()).toBe(true);
  });

  it('in add-mode the gate holds until ALL four credentials are filled', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(null);
    fixture.detectChanges();

    internals.form.apiKey().value.set('k');
    internals.form.apiSecret().value.set('s');
    internals.form.accessToken().value.set('t');
    expect(internals.saveDisabled()).toBe(true);
    internals.form.accessTokenSecret().value.set('ts');
    expect(internals.saveDisabled()).toBe(false);
  });

  it('add-mode save POSTs the trailing-slash collection URL with the parent ref and CLOSES the dialog on success', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(null);
    fixture.detectChanges();

    fillCredentials(internals);
    internals.save();

    const post = httpTesting.expectOne(CREATE_URL);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      promulgationType: { typeId: 'twitter dk' },
      includeThumbnail: true,
      apiKey: 'k',
      apiSecret: 's',
      accessToken: 't',
      accessTokenSecret: 'ts',
    });
    // The blank optional format is OMITTED from the body — never sent as ''.
    expect((post.request.body as TwitterSettings).format).toBeUndefined();
    post.flush(post.request.body as TwitterSettings);
    fixture.detectChanges();

    expect(notifications.success).toHaveBeenCalledWith('admin.promulgation.twitter.toast.created');
    // Save success closes the dialog (unlike the NAVTEX/SafetyNET plugins).
    expect(internals.dialogVisible()).toBe(false);
  });

  it('edit-mode save PUTs the encoded typeId-keyed URL, round-trips the clear-text secrets, and closes', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(SETTINGS);
    fixture.detectChanges();

    internals.form.format().value.set('NM: {{text}}');
    internals.save();

    const put = httpTesting.expectOne(SETTINGS_URL);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      promulgationType: { typeId: 'twitter dk' },
      format: 'NM: {{text}}',
      includeThumbnail: false,
      apiKey: 'key-1',
      apiSecret: 'secret-1',
      accessToken: 'token-1',
      accessTokenSecret: 'token-secret-1',
    });
    put.flush(put.request.body as TwitterSettings);
    fixture.detectChanges();

    expect(notifications.success).toHaveBeenCalledWith('admin.promulgation.twitter.toast.updated');
    expect(internals.dialogVisible()).toBe(false);
  });

  it('a save failure toasts distinctly and keeps the dialog open with the entered values', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(null);
    fixture.detectChanges();

    fillCredentials(internals);
    internals.save();
    httpTesting.expectOne(CREATE_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.promulgation.twitter.toast.createError',
    );
    expect(internals.dialogVisible()).toBe(true);
    expect(internals.mode()).toBe('add');
    // The entered values survive for a retry.
    expect(internals.form.apiKey().value()).toBe('k');
    expect(internals.form.accessTokenSecret().value()).toBe('ts');
  });

  it('a failed update toasts the update-specific error', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(SETTINGS);
    fixture.detectChanges();

    internals.form.apiKey().value.set('key-2');
    internals.save();
    httpTesting.expectOne(SETTINGS_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.promulgation.twitter.toast.updateError',
    );
    expect(internals.dialogVisible()).toBe(true);
    expect(internals.mode()).toBe('edit');
  });

  it('Close dismisses the dialog without saving', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(SETTINGS_URL).flush(SETTINGS);
    fixture.detectChanges();

    internals.form.apiKey().value.set('key-2');
    dialogButton('admin.promulgation.twitter.close').click();
    fixture.detectChanges();

    // No save request was issued (verified by afterEach) and the dialog is gone.
    expect(internals.dialogVisible()).toBe(false);
  });

  it('every twitter key resolves in the en bundle', () => {
    const leafPaths = (node: unknown, prefix = ''): string[] =>
      Object.entries(node as Record<string, unknown>).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        if (typeof value === 'string') {
          expect(value, path).toMatch(/\S/);
          return [path];
        }
        return leafPaths(value, path);
      });
    expect(leafPaths(en.admin.promulgation.twitter).length).toBeGreaterThan(0);
    // Fixed affordances.
    expect(en.admin.promulgation.twitter.title).toBe('OAuth Settings');
    expect(en.admin.promulgation.twitter.updateSettings).toBe('Update Settings...');
  });
});
