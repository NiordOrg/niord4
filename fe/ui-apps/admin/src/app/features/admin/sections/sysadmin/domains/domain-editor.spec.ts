import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainEditor } from './domain-editor';
import type { DomainEditorModel } from './domains-page';
import { DomainsFacade } from './domains.facade';

@Component({
  imports: [DomainEditor],
  template: `
    <app-domain-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<DomainEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: DomainEditor['form'];
  changed: () => boolean;
  saveDisabled: () => boolean;
  saving: () => boolean;
  provisioning: () => boolean;
  inKeycloak: () => boolean;
  timeZones: () => readonly string[];
  save: () => void;
  provision: () => void;
}

/** New-domain seed (what the page's addDomain produces) — the zone starts unanswered. */
const NEW_DOMAIN: AdminDomain = {
  domainId: '',
  active: true,
  sortOrder: 0,
  timeZone: '',
  messageSortOrder: 'AREA ASC',
  publish: false,
  atons: false,
  templates: false,
};

/** A persisted domain, deliberately carrying the derived read-only fields. */
const EXISTING: AdminDomain = {
  domainId: 'niord-nw',
  name: 'Warnings',
  active: true,
  sortOrder: 1,
  timeZone: 'Europe/Copenhagen',
  lat: 56.13,
  lon: 11.28,
  zoomLevel: 6,
  messageSortOrder: 'AREA ASC',
  color: '#ff0000',
  inKeycloak: false,
  firingSchedule: true,
  areas: [{ id: 1, descs: [{ lang: 'en', name: 'Kattegat' }] }],
  categories: [{ id: 7, descs: [{ lang: 'en', name: 'Firing Exercises' }] }],
  messageSeries: [{ seriesId: 'dma-nw', types: ['COASTAL_WARNING'] }],
  publish: true,
  atons: false,
  templates: true,
};

function setup() {
  const facadeDomains = signal<readonly AdminDomain[]>([]);
  const facade = { refresh: vi.fn(), domains: facadeDomains, loading: () => false };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: DomainsFacade, useValue: facade },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
    facade,
    facadeDomains,
  };
}

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  model: DomainEditorModel,
): EditorInternals {
  host.model.set(model);
  fixture.detectChanges();
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/** The drawer portals its body to the overlay container — query the document. */
function drawerField<T extends HTMLElement>(selector: string): T | null {
  return document.querySelector<T>(selector);
}

describe('DomainEditor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('requires the domainId in add mode and locks it in edit mode', () => {
    const { fixture, host } = setup();
    let editor = open(fixture, host, { mode: 'add', domain: NEW_DOMAIN });
    expect(editor.form.domainId().disabled()).toBe(false);
    expect(editor.saveDisabled()).toBe(true); // pristine AND invalid (empty id)
    editor.form.domainId().value.set('niord-new');
    expect(editor.form.domainId().invalid()).toBe(false);
    editor.form.timeZone().value.set('America/Halifax');
    editor.form.name().value.set('New');
    expect(editor.saveDisabled()).toBe(false);

    editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    expect(editor.form.domainId().disabled()).toBe(true);
    expect(drawerField<HTMLInputElement>('#domain-id')?.disabled).toBe(true);
  });

  // The backend refuses a domain without a name (400, before Keycloak is asked for its client)
  it('requires the name in add and edit mode', () => {
    const { fixture, host } = setup();
    let editor = open(fixture, host, { mode: 'add', domain: NEW_DOMAIN });
    editor.form.domainId().value.set('niord-new');
    editor.form.timeZone().value.set('America/Halifax');
    expect(editor.form.name().invalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);
    editor.form.name().value.set('New');
    expect(editor.saveDisabled()).toBe(false);

    editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    editor.form.name().value.set('');
    expect(editor.saveDisabled()).toBe(true);
  });

  /**
   * A domain opened without a zone asks for one on its face.
   *
   * The blank is the unanswered state, so the trigger shows the placeholder rather than an
   * empty line — otherwise Save is disabled with nothing on screen naming the cause.
   */
  it('shows the time-zone placeholder while the zone is unanswered, and the zone once picked', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'add', domain: NEW_DOMAIN });

    const trigger = drawerField<HTMLElement>('#domain-time-zone')!;
    // The stub loader renders keys verbatim.
    expect(trigger.querySelector('.sel-placeholder')?.textContent?.trim()).toBe(
      'admin.domains.field.timeZonePlaceholder',
    );

    editor.form.timeZone().value.set('America/Halifax');
    fixture.detectChanges();
    expect(trigger.querySelector('.sel-placeholder')).toBeNull();
    expect(trigger.textContent).toContain('America/Halifax');
  });

  it('copy (add mode with a blanked id) leaves the id editable and required again', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'add',
      domain: { ...EXISTING, domainId: '' },
    });
    expect(editor.form.domainId().disabled()).toBe(false);
    // The blanked id keeps the form invalid until a new one is typed.
    editor.form.name().value.set('Copy of Warnings');
    expect(editor.saveDisabled()).toBe(true);
    editor.form.domainId().value.set('niord-copy');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('a freshly opened edit form has Save disabled; edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    expect(editor.saveDisabled()).toBe(true);
    editor.form.name().value.set('Renamed');
    expect(editor.saveDisabled()).toBe(false);
    editor.form.name().value.set('Warnings');
    expect(editor.saveDisabled()).toBe(true);
  });

  it('picker changes flip the save gate like any typed edit', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    expect(editor.changed()).toBe(false);

    editor.form
      .areas()
      .value.set([
        ...editor.form.areas().value(),
        { id: 9, descs: [{ lang: 'en', name: 'Bornholm' }] },
      ]);
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(false);

    editor.form.messageSeries().value.set([]);
    editor.form.areas().value.set([{ id: 1, descs: [{ lang: 'en', name: 'Kattegat' }] }]);
    expect(editor.changed()).toBe(true);
  });

  it('zoom outside 3–15 makes the form invalid', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    editor.form.zoomLevel().value.set(16);
    expect(editor.form().invalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);
    editor.form.zoomLevel().value.set(15);
    expect(editor.form().invalid()).toBe(false);
  });

  it('hides the Keycloak status row in add mode', () => {
    const { fixture, host } = setup();
    open(fixture, host, { mode: 'add', domain: { ...EXISTING, domainId: '' } });
    expect(drawerField('#domain-create-keycloak')).toBeNull();
    expect(drawerField('#domain-kc-exists')).toBeNull();
  });

  it('offers Create in Keycloak for false AND null/absent inKeycloak; shows the exists text for true', () => {
    const { fixture, host } = setup();
    open(fixture, host, { mode: 'edit', domain: EXISTING }); // inKeycloak: false
    expect(drawerField('#domain-create-keycloak')).not.toBeNull();
    expect(drawerField('#domain-kc-exists')).toBeNull();

    open(fixture, host, { mode: 'edit', domain: { ...EXISTING, inKeycloak: null } });
    expect(drawerField('#domain-create-keycloak')).not.toBeNull();

    open(fixture, host, { mode: 'edit', domain: { ...EXISTING, inKeycloak: undefined } });
    expect(drawerField('#domain-create-keycloak')).not.toBeNull();

    open(fixture, host, { mode: 'edit', domain: { ...EXISTING, inKeycloak: true } });
    expect(drawerField('#domain-create-keycloak')).toBeNull();
    expect(drawerField('#domain-kc-exists')).not.toBeNull();
  });

  it('provisioning POSTs (no derived fields), re-fetches the list, and the row flips in place — drawer open', () => {
    const { fixture, host, httpTesting, facade, facadeDomains } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });

    editor.provision();
    expect(editor.provisioning()).toBe(true);

    const req = httpTesting.expectOne('/rest/domains/keycloak');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as Record<string, unknown>;
    expect(body['domainId']).toBe('niord-nw');
    expect('inKeycloak' in body).toBe(false);
    expect('firingSchedule' in body).toBe(false);
    req.flush(null);

    expect(editor.provisioning()).toBe(false);
    expect(facade.refresh).toHaveBeenCalled();

    // The re-fetched list reports the client — the status row updates in place.
    facadeDomains.set([{ ...EXISTING, inKeycloak: true }]);
    fixture.detectChanges();
    expect(editor.inKeycloak()).toBe(true);
    expect(drawerField('#domain-kc-exists')).not.toBeNull();
    expect(drawerField('#domain-create-keycloak')).toBeNull();
    expect(host.model()).toBeDefined(); // the drawer stayed open
  });

  it('a failed provisioning toasts and keeps the drawer open', () => {
    const { fixture, host, httpTesting, facade } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });

    editor.provision();
    httpTesting
      .expectOne('/rest/domains/keycloak')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(editor.provisioning()).toBe(false);
    expect(facade.refresh).not.toHaveBeenCalled();
    expect(host.model()).toBeDefined();
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.domains.toast.provisionError',
    );
  });

  it('edit saves via PUT with minimal refs and without the derived fields', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    editor.form.name().value.set('Renamed');
    editor.form.color().value.set(''); // cleared optional → omitted
    editor.save();

    const req = httpTesting.expectOne('/rest/domains/domain/niord-nw');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as Record<string, unknown>;
    expect(body['name']).toBe('Renamed');
    expect(body['areas']).toEqual([{ id: 1 }]);
    expect(body['categories']).toEqual([{ id: 7 }]);
    expect(body['messageSeries']).toEqual([{ seriesId: 'dma-nw' }]);
    expect('inKeycloak' in body).toBe(false);
    expect('firingSchedule' in body).toBe(false);
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('color');
    req.flush({});
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.domains.toast.saved',
    );
    expect(host.savedCount).toBe(1);
  });

  it('add saves via POST to the trailing-slash collection path', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'add', domain: NEW_DOMAIN });
    editor.form.domainId().value.set('niord-new');
    editor.form.name().value.set('New');
    editor.form.timeZone().value.set('Europe/Copenhagen');
    editor.save();

    const req = httpTesting.expectOne('/rest/domains/domain/');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as AdminDomain;
    expect(body.domainId).toBe('niord-new');
    expect(body.active).toBe(true);
    expect(body.timeZone).toBe('Europe/Copenhagen');
    expect(body.messageSortOrder).toBe('AREA ASC');
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('a failed save keeps the drawer open with values intact and toasts the error', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', domain: EXISTING });
    editor.form.name().value.set('Renamed');
    editor.save();

    httpTesting
      .expectOne('/rest/domains/domain/niord-nw')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(host.savedCount).toBe(0);
    expect(host.model()).toBeDefined(); // drawer still open
    expect(editor.saving()).toBe(false); // retry possible
    expect(editor.form.name().value()).toBe('Renamed');
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.domains.toast.saveError',
    );
  });

  it('prepends a persisted timezone missing from the Intl list and keeps it savable', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      domain: { ...EXISTING, timeZone: 'Custom/Legacy_Zone' },
    });
    expect(editor.timeZones()[0]).toBe('Custom/Legacy_Zone');
    expect(editor.form.timeZone().value()).toBe('Custom/Legacy_Zone');
    expect(editor.form.timeZone().invalid()).toBe(false);
  });

  it('a domain with no persisted timezone opens blank and stays unsavable until one is picked', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      domain: { ...EXISTING, timeZone: undefined },
    });
    // Nothing is invented into the field — a zone the domain never chose would be a guess.
    expect(editor.timeZones()).not.toContain('');
    expect(editor.form.timeZone().value()).toBe('');
    expect(editor.form.timeZone().invalid()).toBe(true);

    // Even an otherwise valid edit cannot be saved while the zone is unanswered.
    editor.form.name().value.set('Renamed');
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.form.timeZone().value.set('America/Halifax');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('tolerates a blank messageSortOrder on open — nothing is forced, blank is omitted on save', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      domain: { ...EXISTING, messageSortOrder: undefined },
    });
    expect(editor.form.messageSortOrder().value()).toBe('');
    expect(editor.changed()).toBe(false); // an untouched open is not a change

    editor.form.name().value.set('Renamed');
    editor.save();
    const req = httpTesting.expectOne('/rest/domains/domain/niord-nw');
    expect(JSON.parse(JSON.stringify(req.request.body))).not.toHaveProperty('messageSortOrder');
    req.flush({});
  });
});
