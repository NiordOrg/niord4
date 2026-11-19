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
import type { SafetyNetArea, SafetyNetAreaType } from './promulgation-admin.model';
import { SafetyNetAreasPlugin } from './safetynet-areas-plugin';

@Component({
  imports: [SafetyNetAreasPlugin],
  template: `<app-safetynet-areas-plugin [typeId]="typeId()" [typeName]="typeName()" />`,
})
class Host {
  // A free-text typeId with a space pins the encodeURIComponent contract.
  readonly typeId = signal('safetynet dk');
  readonly typeName = signal('SafetyNET');
}

interface Internals {
  form: SafetyNetAreasPlugin['form'];
  editing: () => 'add' | 'edit' | undefined;
  saveDisabled: () => boolean;
  areas: () => readonly SafetyNetArea[] | undefined;
  loadFailed: () => boolean;
  dialogVisible: () => boolean;
  save: () => void;
  addArea: (type: SafetyNetAreaType) => void;
}

const LIST_URL = '/rest/promulgation/safetynet/areas/safetynet%20dk/all?lang=en';
const CREATE_URL = '/rest/promulgation/safetynet/areas/safetynet%20dk/area/';

const CIRCLE: SafetyNetArea = {
  promulgationType: { typeId: 'safetynet dk' },
  type: 'CIRCULAR',
  name: 'Circle 1',
  active: true,
  sortOrder: 100,
  center: '56 11',
  radius: '10',
};

/** A name with a slash pins the encoding of the name-keyed PUT/DELETE paths. */
const COASTAL: SafetyNetArea = {
  promulgationType: { typeId: 'safetynet dk' },
  type: 'COASTAL',
  name: 'Baltic/A',
  active: false,
  sortOrder: 50,
  repetition: 'REPETITION_42',
  navarea: 'I',
  coastalArea: 'B',
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

/** ids of all shape-specific text inputs currently rendered in the edit view. */
function shapeInputIds(): string[] {
  return Array.from(
    document.querySelectorAll<HTMLElement>(
      '#safetynet-area-center, #safetynet-area-radius, #safetynet-area-sw-corner, ' +
        '#safetynet-area-northings, #safetynet-area-eastings, #safetynet-area-navarea, ' +
        '#safetynet-area-coastal-area',
    ),
  ).map((el) => el.id);
}

/** The JSON the backend actually receives — `undefined`-valued keys are dropped. */
function wireBody(body: unknown): Record<string, unknown> {
  return JSON.parse(JSON.stringify(body)) as Record<string, unknown>;
}

describe('SafetyNetAreasPlugin', () => {
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
    req.flush([CIRCLE, COASTAL]);
    fixture.detectChanges();

    const panel = dialogPanel();
    expect(panel?.textContent).toContain('Circle 1');
    expect(panel?.textContent).toContain('Baltic/A');
    // Localized shape labels, never the raw enum.
    expect(panel?.textContent).toContain('admin.promulgation.safetynet.shape.circular');
    expect(panel?.textContent).toContain('admin.promulgation.safetynet.shape.coastal');
    expect(panel?.textContent).not.toContain('CIRCULAR');
    // Sort Order column.
    expect(panel?.textContent).toContain('100');
  });

  it('renders the empty state for an area-less type', () => {
    const { fixture, httpTesting } = setup();
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.safetynet.empty');
  });

  it('a load failure renders inline in the dialog and retry refetches', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(internals.loadFailed()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.safetynet.loadError');

    dialogButton('admin.promulgation.retry').click();
    fixture.detectChanges();
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(internals.loadFailed()).toBe(false);
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.safetynet.empty');
  });

  it('the footer Add Area menu offers the four shapes and an item swaps to a seeded edit view', async () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    // The trigger↔panel wiring resolves through effects — settle before opening.
    await fixture.whenStable();

    dialogButton('admin.promulgation.safetynet.addArea').click();
    await fixture.whenStable();

    // Command leaves render as <div cdkMenuItem class="menu-item"> rows.
    const items = Array.from(document.querySelectorAll<HTMLElement>('.menu-panel .menu-item'));
    expect(items.map((item) => item.textContent?.trim())).toEqual([
      'admin.promulgation.safetynet.shape.circular',
      'admin.promulgation.safetynet.shape.rectangular',
      'admin.promulgation.safetynet.shape.coastal',
      'admin.promulgation.safetynet.shape.navarea',
    ]);

    items[0].click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(internals.editing()).toBe('add');
    // Add defaults: blank name, active, sort order 100.
    expect(internals.form.name().value()).toBe('');
    expect(internals.form.active().value()).toBe(true);
    expect(internals.form.sortOrder().value()).toBe(100);
    expect(internals.form.name().disabled()).toBe(false);
    // The shape is fixed at add time and shown read-only — no shape picker exists.
    expect(dialogPanel()?.textContent).toContain('admin.promulgation.safetynet.shape.circular');
  });

  const SHAPE_FIELDS: readonly (readonly [SafetyNetAreaType, string[]])[] = [
    ['CIRCULAR', ['safetynet-area-center', 'safetynet-area-radius']],
    [
      'RECTANGULAR',
      ['safetynet-area-sw-corner', 'safetynet-area-northings', 'safetynet-area-eastings'],
    ],
    ['COASTAL', ['safetynet-area-navarea', 'safetynet-area-coastal-area']],
    ['NAVAREA', ['safetynet-area-navarea']],
  ];
  SHAPE_FIELDS.forEach(([type, expected]) => {
    it(`add seeding for ${type} renders exactly its own shape fields`, () => {
      const { fixture, httpTesting } = setup();
      const internals = internalsOf(fixture);
      openDialog(fixture);
      httpTesting.expectOne(LIST_URL).flush([]);
      fixture.detectChanges();

      internals.addArea(type);
      fixture.detectChanges();

      expect(internals.editing()).toBe('add');
      expect(shapeInputIds()).toEqual(expected);
    });
  });

  it('CIRCULAR: required + pattern rules gate the save', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('CIRCULAR');
    fixture.detectChanges();

    expect(internals.saveDisabled()).toBe(true);
    internals.form.name().value.set('Circle 1');
    expect(internals.saveDisabled()).toBe(true); // center + radius still required
    internals.form.center().value.set('56 11');
    internals.form.radius().value.set('10');
    expect(internals.saveDisabled()).toBe(false);
    // Pattern -?\d+\s+-?\d+ — decimals rejected, signed integers accepted.
    internals.form.center().value.set('56.5 11');
    expect(internals.saveDisabled()).toBe(true);
    internals.form.center().value.set('-56 -11');
    expect(internals.saveDisabled()).toBe(false);
    // Pattern \d+ — the radius is a bare unsigned integer.
    internals.form.radius().value.set('10 NM');
    expect(internals.saveDisabled()).toBe(true);
  });

  it('RECTANGULAR: sw corner is a position, northings/eastings unsigned integers', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('RECTANGULAR');
    fixture.detectChanges();

    internals.form.name().value.set('Box 1');
    expect(internals.saveDisabled()).toBe(true);
    internals.form.swCorner().value.set('54 7');
    internals.form.northings().value.set('2');
    internals.form.eastings().value.set('3');
    expect(internals.saveDisabled()).toBe(false);
    internals.form.northings().value.set('-2');
    expect(internals.saveDisabled()).toBe(true);
  });

  it('NAVAREA: only common rules apply (selects optional); sortOrder is required and min 0', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('NAVAREA');
    fixture.detectChanges();

    internals.form.name().value.set('Nav I');
    expect(internals.saveDisabled()).toBe(false);
    internals.form.sortOrder().value.set(null);
    expect(internals.saveDisabled()).toBe(true);
    internals.form.sortOrder().value.set(-1);
    expect(internals.saveDisabled()).toBe(true);
    internals.form.sortOrder().value.set(100);
    expect(internals.saveDisabled()).toBe(false);
  });

  it('add save POSTs the trailing-slash URL with the parent ref and the chosen repetition, then returns to the refreshed list', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('CIRCULAR');
    fixture.detectChanges();

    internals.form.name().value.set('Circle 1');
    internals.form.center().value.set('56 11');
    internals.form.radius().value.set('10');
    internals.form.repetition().value.set('REPETITION_16');
    internals.save();

    const post = httpTesting.expectOne(CREATE_URL);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      // Parent ref in every body — the backend 400s when it mismatches the URL.
      promulgationType: { typeId: 'safetynet dk' },
      type: 'CIRCULAR',
      name: 'Circle 1',
      active: true,
      sortOrder: 100,
      repetition: 'REPETITION_16',
      center: '56 11',
      radius: '10',
    });
    post.flush(CIRCLE);

    // Success returns to the in-dialog list + refetch — the dialog stays open.
    httpTesting.expectOne(LIST_URL).flush([CIRCLE]);
    fixture.detectChanges();
    expect(notifications.success).toHaveBeenCalledWith(
      'admin.promulgation.safetynet.toast.created',
    );
    expect(internals.editing()).toBeUndefined();
    expect(internals.dialogVisible()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('Circle 1');
  });

  it('blank optionals are OMITTED from the wire body — never sent as empty strings', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('NAVAREA');
    fixture.detectChanges();

    internals.form.name().value.set('Nav I');
    internals.save();

    const post = httpTesting.expectOne(CREATE_URL);
    const wire = wireBody(post.request.body);
    expect(wire).toEqual({
      promulgationType: { typeId: 'safetynet dk' },
      type: 'NAVAREA',
      name: 'Nav I',
      active: true,
      sortOrder: 100,
    });
    expect('repetition' in wire).toBe(false);
    expect('messageAreaPrefix' in wire).toBe(false);
    expect('navarea' in wire).toBe(false);
    post.flush(post.request.body as SafetyNetArea);
    httpTesting.expectOne(LIST_URL).flush([]);
  });

  it('edit locks the name (the PK), PUTs to the encoded name-keyed URL and clearing the repetition omits it', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([COASTAL]);
    fixture.detectChanges();

    rowAction('admin.promulgation.safetynet.edit').click();
    fixture.detectChanges();

    expect(internals.editing()).toBe('edit');
    expect(internals.form.name().disabled()).toBe(true);
    const nameInput = document.querySelector<HTMLInputElement>('#safetynet-area-name');
    expect(nameInput?.disabled).toBe(true);

    internals.form.repetition().value.set('');
    internals.save();

    const put = httpTesting.expectOne(
      '/rest/promulgation/safetynet/areas/safetynet%20dk/area/Baltic%2FA',
    );
    expect(put.request.method).toBe('PUT');
    const wire = wireBody(put.request.body);
    // The untouched shape fields round-trip from the opened row.
    expect(wire).toEqual({
      promulgationType: { typeId: 'safetynet dk' },
      type: 'COASTAL',
      name: 'Baltic/A',
      active: false,
      sortOrder: 50,
      navarea: 'I',
      coastalArea: 'B',
    });
    expect('repetition' in wire).toBe(false);
    put.flush(put.request.body as SafetyNetArea);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(notifications.success).toHaveBeenCalledWith(
      'admin.promulgation.safetynet.toast.updated',
    );
    expect(internals.editing()).toBeUndefined();
  });

  it('a save failure toasts distinctly and keeps the dialog open in the edit view', () => {
    const { fixture, httpTesting, notifications } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    internals.addArea('NAVAREA');
    fixture.detectChanges();

    internals.form.name().value.set('Nav I');
    internals.save();
    httpTesting.expectOne(CREATE_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.promulgation.safetynet.toast.createError',
    );
    // No list refetch (verified by afterEach); the edit view stays up for a retry.
    expect(internals.editing()).toBe('add');
    expect(dialogPanel()).not.toBeNull();
  });

  it('delete confirms, DELETEs the encoded name and refreshes the list', () => {
    const { fixture, httpTesting, notifications } = setup();
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([COASTAL]);
    fixture.detectChanges();

    rowAction('admin.promulgation.safetynet.delete').click();
    fixture.detectChanges();

    // The plugin mounts its own <app-confirm>; accept through the rendered dialog.
    const accept = document.querySelector<HTMLButtonElement>('.confirm-foot .btn-danger');
    expect(accept).not.toBeNull();
    accept?.click();
    fixture.detectChanges();

    const del = httpTesting.expectOne(
      '/rest/promulgation/safetynet/areas/safetynet%20dk/area/Baltic%2FA',
    );
    expect(del.request.method).toBe('DELETE');
    del.flush({});
    httpTesting.expectOne(LIST_URL).flush([]);
    fixture.detectChanges();
    expect(notifications.success).toHaveBeenCalledWith(
      'admin.promulgation.safetynet.toast.deleted',
    );
  });

  it('Cancel returns to the (refetched) list without closing the dialog', () => {
    const { fixture, httpTesting } = setup();
    const internals = internalsOf(fixture);
    openDialog(fixture);
    httpTesting.expectOne(LIST_URL).flush([CIRCLE]);
    fixture.detectChanges();
    rowAction('admin.promulgation.safetynet.edit').click();
    fixture.detectChanges();
    expect(internals.editing()).toBe('edit');

    dialogButton('admin.promulgation.safetynet.cancel').click();
    fixture.detectChanges();
    httpTesting.expectOne(LIST_URL).flush([CIRCLE]);
    fixture.detectChanges();

    expect(internals.editing()).toBeUndefined();
    expect(internals.dialogVisible()).toBe(true);
    expect(dialogPanel()?.textContent).toContain('Circle 1');
  });

  it('every safetynet key resolves in the en bundle', () => {
    const leafPaths = (node: unknown, prefix = ''): string[] =>
      Object.entries(node as Record<string, unknown>).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        if (typeof value === 'string') {
          expect(value, path).toMatch(/\S/);
          return [path];
        }
        return leafPaths(value, path);
      });
    expect(leafPaths(en.admin.promulgation.safetynet).length).toBeGreaterThan(0);
    expect(en.admin.promulgation.safetynet.confirmDelete.header).toBe('Delete Area?');
    // The four repetition option labels are the expected strings verbatim.
    expect(en.admin.promulgation.safetynet.repetition.REPETITION_11).toBe(
      'Two transmissions, 6 min. interval',
    );
    // The 21 NAVAREA options carry their country labels.
    expect(Object.keys(en.admin.promulgation.safetynet.navareaOptions)).toHaveLength(21);
    expect(en.admin.promulgation.safetynet.navareaOptions.XIX).toBe('XIX - Norway');
    // The position help states one sign per hemisphere: north and east positive, south and west negative.
    for (const text of [
      en.admin.promulgation.safetynet.centerTooltip,
      en.admin.promulgation.safetynet.swCornerTooltip,
    ]) {
      expect(text).toContain('Positive for N and E, negative for S and W');
    }
  });
});
