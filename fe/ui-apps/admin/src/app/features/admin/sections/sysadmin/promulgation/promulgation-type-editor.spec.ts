import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { MessageType } from '../../../../messages/models/message.model';
import type { AdminPromulgationType } from './promulgation-admin.model';
import {
  PROMULGATION_ADMIN_PLUGINS,
  type PromulgationAdminPlugin,
} from './promulgation-admin-plugin.token';
import { PromulgationTypeEditor } from './promulgation-type-editor';
import type { PromulgationTypeEditorModel } from './promulgation-types-page';

@Component({
  imports: [PromulgationTypeEditor],
  template: `
    <app-promulgation-type-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<PromulgationTypeEditorModel | undefined>(undefined);
  savedCount = 0;
}

/** Stand-in admin plugin — echoes the inputs the outlet must pass. */
@Component({
  template: `<div class="test-admin-plugin">{{ typeId() }}|{{ typeName() }}</div>`,
})
class TestAdminPlugin {
  readonly typeId = input.required<string>();
  readonly typeName = input.required<string>();
}

interface EditorInternals {
  form: PromulgationTypeEditor['form'];
  checks: () => Readonly<Record<MessageType, boolean>>;
  changed: () => boolean;
  saveDisabled: () => boolean;
  saving: () => boolean;
  supportedGroups: () => Record<'NW' | 'NM', boolean>;
  languageOptions: () => readonly { value: string; label: string }[];
  toggleType: (type: MessageType) => void;
  save: () => void;
}

const NW_DOMAIN: Domain = {
  domainId: 'd-nw',
  name: 'NW only',
  messageSeries: [{ seriesId: 'nw-series', mainType: 'NW' }],
};
const NM_DOMAIN: Domain = {
  domainId: 'd-nm',
  name: 'NM only',
  messageSeries: [{ seriesId: 'nm-series', mainType: 'NM' }],
};
const BARE_DOMAIN: Domain = { domainId: 'd-bare', name: 'Bare' };

/** The page's add seed for the NAVTEX service (default values). */
const NEW_TYPE: AdminPromulgationType = {
  serviceId: 'navtex',
  typeId: '',
  name: '',
  priority: 0,
  active: true,
  requirement: 'OPTIONAL',
  domains: [],
};

const SAVED_TYPE: AdminPromulgationType = {
  typeId: 'navtex dk',
  serviceId: 'navtex',
  name: 'NAVTEX',
  priority: 10,
  active: true,
  requirement: 'MANDATORY',
  language: 'da',
  domains: [NW_DOMAIN, NM_DOMAIN],
  messageTypes: ['LOCAL_WARNING', 'TEMPORARY_NOTICE'],
  scriptResourcePaths: ['templates/navtex.ftl'],
};

function setup(
  catalog: readonly Domain[] = [NW_DOMAIN, NM_DOMAIN, BARE_DOMAIN],
  plugins: readonly PromulgationAdminPlugin[] = [],
) {
  TestBed.configureTestingModule({
    providers: [
      ...plugins.map((plugin) => ({
        provide: PROMULGATION_ADMIN_PLUGINS,
        useValue: plugin,
        multi: true,
      })),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          modelLanguages: [
            { label: 'English', value: 'en', locale: 'en-GB' },
            { label: 'Dansk', value: 'da', locale: 'da' },
          ],
        },
      },
      { provide: DomainFacade, useValue: { availableDomains: signal(catalog) } },
      { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
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

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  model: PromulgationTypeEditorModel,
): EditorInternals {
  host.model.set(model);
  fixture.detectChanges();
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/** The drawer portals its body to the overlay container — query the document. */
function drawerField<T extends HTMLElement>(selector: string): T | null {
  return document.querySelector<T>(selector);
}

function restrictCheckboxLabels(): string[] {
  return Array.from(
    document.querySelectorAll('fieldset .grid label'),
    (label) => label.textContent?.trim() ?? '',
  );
}

describe('PromulgationTypeEditor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('add mode seeds the default values with typeId editable and Save disabled', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'add', type: NEW_TYPE });
    expect(editor.form.typeId().disabled()).toBe(false);
    expect(editor.form.active().value()).toBe(true);
    expect(editor.form.requirement().value()).toBe('OPTIONAL');
    expect(editor.form.priority().value()).toBe(0);
    expect(editor.form.language().value()).toBe(''); // no language restriction
    expect(editor.form.domains().value()).toEqual([]);
    expect(editor.saveDisabled()).toBe(true); // pristine AND invalid (empty typeId + name)
    // No domains selected ⇒ BOTH restrict groups are supported.
    expect(editor.supportedGroups()).toEqual({ NW: true, NM: true });
  });

  it('edit mode locks the typeId (the immutable PK) and fires no detail refetch', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    expect(editor.form.typeId().disabled()).toBe(true);
    expect(drawerField<HTMLInputElement>('#promulgation-type-id')?.disabled).toBe(true);
    httpTesting.expectNone((req) => req.url.includes('promulgation'));
  });

  it('edit never mutates the list row it opened on', () => {
    const { fixture, host } = setup();
    const row = structuredClone(SAVED_TYPE);
    const before = JSON.stringify(row);
    const editor = open(fixture, host, { mode: 'edit', type: row });
    editor.form.name().value.set('Changed');
    editor.form.domains().value.set([NW_DOMAIN]);
    editor.toggleType('COASTAL_WARNING');
    expect(JSON.stringify(row)).toBe(before);
  });

  it('re-resolves stored domains against the FRESH catalog by domainId (stale series ignored)', () => {
    // The stored VO claims d-nw carries an NM series; the session catalog says NW.
    const stale: Domain = {
      domainId: 'd-nw',
      name: 'Stale copy',
      messageSeries: [{ seriesId: 'old', mainType: 'NM' }],
    };
    const { fixture, host } = setup([NW_DOMAIN]);
    const editor = open(fixture, host, {
      mode: 'edit',
      type: { ...SAVED_TYPE, domains: [stale] },
    });
    expect(editor.supportedGroups()).toEqual({ NW: true, NM: false });
  });

  it('restrict groups appear/disappear as the domain selection changes support', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    fixture.detectChanges();
    expect(editor.supportedGroups()).toEqual({ NW: true, NM: true });
    expect(restrictCheckboxLabels()).toHaveLength(8);

    editor.form.domains().value.set([NW_DOMAIN]);
    fixture.detectChanges();
    expect(editor.supportedGroups()).toEqual({ NW: true, NM: false });
    expect(restrictCheckboxLabels()).toHaveLength(4);

    editor.form.domains().value.set([BARE_DOMAIN]);
    fixture.detectChanges();
    expect(editor.supportedGroups()).toEqual({ NW: false, NM: false });
    expect(restrictCheckboxLabels()).toHaveLength(0);

    editor.form.domains().value.set([]);
    fixture.detectChanges();
    expect(restrictCheckboxLabels()).toHaveLength(8);
  });

  it('checks survive domain add/remove in-session — including a support round trip', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    editor.toggleType('COASTAL_WARNING'); // unsaved in-session check
    editor.form.domains().value.set([NW_DOMAIN, NM_DOMAIN, BARE_DOMAIN]); // support remains
    expect(editor.checks().COASTAL_WARNING).toBe(true);
    expect(editor.checks().LOCAL_WARNING).toBe(true);

    // Lose NW support, then regain it: the working checks were never wiped.
    editor.form.domains().value.set([NM_DOMAIN]);
    editor.form.domains().value.set([NW_DOMAIN, NM_DOMAIN]);
    expect(editor.checks().COASTAL_WARNING).toBe(true);
    expect(editor.checks().LOCAL_WARNING).toBe(true);
  });

  it('save serializes checked keys of SUPPORTED groups only — NW block first, pruned set persisted', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    // Dropping the NM domain hides the NM group; its saved TEMPORARY_NOTICE is pruned.
    editor.form.domains().value.set([NW_DOMAIN]);
    editor.save();

    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex%20dk');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as AdminPromulgationType;
    expect(body.messageTypes).toEqual(['LOCAL_WARNING']);
    expect(body.domains).toEqual([NW_DOMAIN]);
    expect(body.typeId).toBe('navtex dk'); // body id equals the (encoded) path id
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('with no domains every check survives, serialized NW-then-NM regardless of stored order', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, {
      mode: 'edit',
      // Stored NM-first: the payload must still lead with the NW block.
      type: { ...SAVED_TYPE, domains: [], messageTypes: ['TEMPORARY_NOTICE', 'LOCAL_WARNING'] },
    });
    editor.form.name().value.set('Changed');
    editor.save();

    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex%20dk');
    expect((req.request.body as AdminPromulgationType).messageTypes).toEqual([
      'LOCAL_WARNING',
      'TEMPORARY_NOTICE',
    ]);
    req.flush({});
  });

  it('unchecking everything persists [] (unrestricted) — never an omitted field', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    editor.toggleType('LOCAL_WARNING');
    editor.toggleType('TEMPORARY_NOTICE');
    editor.save();

    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex%20dk');
    expect((req.request.body as AdminPromulgationType).messageTypes).toEqual([]);
    req.flush({});
  });

  it('add saves via POST to the trailing-slash collection path with the drawer values', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'add', type: NEW_TYPE });
    editor.form.typeId().value.set('navtex-new');
    editor.form.name().value.set('New NAVTEX');
    editor.toggleType('LOCAL_WARNING');
    editor.save();

    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as AdminPromulgationType;
    expect(body.serviceId).toBe('navtex'); // seeded by the Add menu, untouched
    expect(body.typeId).toBe('navtex-new');
    expect(body.active).toBe(true);
    expect(body.requirement).toBe('OPTIONAL');
    expect(body.priority).toBe(0);
    expect(body.language).toBeUndefined(); // blank = any language, omitted
    expect(body.messageTypes).toEqual(['LOCAL_WARNING']);
    req.flush({});
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.promulgation.toast.created',
    );
    expect(host.savedCount).toBe(1);
  });

  it('offers the configured model languages plus the blank "any" option; blank round-trips as omitted', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    expect(editor.languageOptions().map((option) => option.value)).toEqual(['', 'en', 'da']);
    expect(editor.form.language().value()).toBe('da');

    editor.form.language().value.set('');
    editor.save();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex%20dk');
    expect((req.request.body as AdminPromulgationType).language).toBeUndefined();
    req.flush({});
  });

  it('a freshly opened edit form has Save disabled; checkbox-only toggles flip the gate (and dirty)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    expect(editor.saveDisabled()).toBe(true);
    expect(editor.changed()).toBe(false);

    editor.toggleType('COASTAL_WARNING');
    expect(editor.changed()).toBe(true); // drawer [dirty] covers the checkbox map
    expect(editor.saveDisabled()).toBe(false);

    editor.toggleType('COASTAL_WARNING');
    expect(editor.changed()).toBe(false); // reverted
    expect(editor.saveDisabled()).toBe(true);
  });

  it('a failed save keeps the drawer open with values intact and toasts the distinct error', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    editor.form.name().value.set('Changed');
    editor.save();

    httpTesting
      .expectOne('/rest/promulgations/promulgation-type/navtex%20dk')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(host.savedCount).toBe(0);
    expect(host.model()).toBeDefined(); // drawer still open
    expect(editor.saving()).toBe(false); // retry possible
    expect(editor.form.name().value()).toBe('Changed');
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.promulgation.toast.updateError',
    );
  });

  it('a failed add toasts the distinct create error', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'add', type: NEW_TYPE });
    editor.form.typeId().value.set('x');
    editor.form.name().value.set('X');
    editor.save();

    httpTesting
      .expectOne('/rest/promulgations/promulgation-type/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.promulgation.toast.createError',
    );
  });

  it('shows the serviceId read-only — no input renders for it', () => {
    const { fixture, host } = setup();
    open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    fixture.detectChanges();
    const drawer = document.querySelector('.niord-drawer-panel');
    expect(drawer?.textContent).toContain('navtex');
    expect(drawerField('#promulgation-service-id')).toBeNull();
  });

  it('edit mode renders the registered admin plugin with the persisted typeId and name', () => {
    const { fixture, host } = setup(undefined, [
      { serviceId: 'navtex', component: TestAdminPlugin },
    ]);
    const editor = open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    fixture.detectChanges();
    expect(document.querySelector('.test-admin-plugin')?.textContent).toBe('navtex dk|NAVTEX');

    // Plugin inputs stay the PERSISTED identifiers while the form is dirty.
    editor.form.name().value.set('Renamed in form');
    fixture.detectChanges();
    expect(document.querySelector('.test-admin-plugin')?.textContent).toBe('navtex dk|NAVTEX');
  });

  it('add mode shows the save-the-type-first hint and never the plugin — even when registered', () => {
    const { fixture, host } = setup(undefined, [
      { serviceId: 'navtex', component: TestAdminPlugin },
    ]);
    open(fixture, host, { mode: 'add', type: NEW_TYPE });
    fixture.detectChanges();
    expect(document.querySelector('.test-admin-plugin')).toBeNull();
    expect(document.querySelector('.niord-drawer-panel')?.textContent).toContain(
      'admin.promulgation.plugin.saveTypeFirst',
    );
  });

  it('edit mode with an unregistered serviceId renders neither plugin nor hint', () => {
    const { fixture, host } = setup(undefined, [
      { serviceId: 'navtex', component: TestAdminPlugin },
    ]);
    open(fixture, host, { mode: 'edit', type: { ...SAVED_TYPE, serviceId: 'audio' } });
    fixture.detectChanges();
    expect(document.querySelector('.test-admin-plugin')).toBeNull();
    expect(document.querySelector('.niord-drawer-panel')?.textContent).not.toContain(
      'admin.promulgation.plugin.saveTypeFirst',
    );
  });

  it('with no plugin registry provided at all, edit mode simply renders no plugin section', () => {
    const { fixture, host } = setup();
    open(fixture, host, { mode: 'edit', type: SAVED_TYPE });
    fixture.detectChanges();
    expect(document.querySelector('.test-admin-plugin')).toBeNull();
    expect(document.querySelector('.niord-drawer-panel')?.textContent).not.toContain(
      'admin.promulgation.plugin.saveTypeFirst',
    );
  });
});
