import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MailingListEditor, type MailingListEditorModel } from './mailing-list-editor';

@Component({
  imports: [MailingListEditor],
  template: `
    <app-mailing-list-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<MailingListEditorModel | undefined>(undefined);
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
      // The trigger card injects DomainFacade for the SCHEDULED timezone default — stub it so the
      // real AuthFacade -> KEYCLOAK chain isn't pulled in.
      {
        provide: DomainFacade,
        useValue: { sessionDomain: () => undefined } as unknown as DomainFacade,
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
  form: MailingListEditor['form'];
  save: () => void;
  saveDisabled: () => boolean;
  addTrigger: () => void;
  removeTrigger: (index: number) => void;
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

describe('MailingListEditor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    // The drawer portals its body to the overlay container; leaving it behind would let one
    // test's markup answer the next one's document query.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('locks the id in edit mode, leaves it editable in add mode', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', mailingList: { mailingListId: '', active: true, descs: [] } });
    fixture.detectChanges();
    expect(editorOf(fixture).form.mailingListId().disabled()).toBe(false);

    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
      },
    });
    fixture.detectChanges();
    expect(editorOf(fixture).form.mailingListId().disabled()).toBe(true);
  });

  it('Save is gated on the id and the first-language name being present', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', mailingList: { mailingListId: '', active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    expect(internals.saveDisabled()).toBe(true); // blank id + name

    internals.form.mailingListId().value.set('nm');
    expect(internals.saveDisabled()).toBe(true); // name still missing

    internals.form.descs[0].name().value.set('Navwarn');
    expect(internals.saveDisabled()).toBe(false); // id + first name present
  });

  it('add mode POSTs create; a successful save emits saved', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', mailingList: { mailingListId: '', active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    internals.form.mailingListId().value.set('nm');
    internals.form.descs[0].name().value.set('Navwarn');
    internals.save();

    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/');
    expect(req.request.method).toBe('POST');
    expect((req.request.body as { mailingListId: string }).mailingListId).toBe('nm');
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('edit mode PUTs update with the loaded id; drops fully-blank configured-language rows', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    internals.form.descs[0].description().value.set('Coastal warnings');
    internals.save();

    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/nm');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as { descs: { lang: string }[] };
    // The padded-but-empty da row is dropped; only the filled en desc is sent.
    expect(body.descs.map((d) => d.lang)).toEqual(['en']);
    req.flush({});
  });

  it('expanding a loaded trigger does NOT mark the form dirty (expanded is UI-only)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
        triggers: [
          {
            type: 'STATUS_CHANGE',
            descs: [],
            scriptResourcePaths: [],
            statusChanges: ['PUBLISHED'],
          },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    expect(internals.saveDisabled()).toBe(true); // freshly loaded, pristine
    internals.form.triggers[0].expanded().value.set(true); // expand the card
    expect(internals.saveDisabled()).toBe(true); // still pristine — expand didn't dirty it
  });

  it('a scheduled trigger without a time zone cannot be saved', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
        triggers: [
          {
            type: 'SCHEDULED',
            descs: [],
            scriptResourcePaths: [],
            scheduleType: 'DAILY',
            scheduledExecutionTime: '09:00',
            // No scheduledExecutionTimeZone: 09:00 names no instant until one is chosen.
          },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    const zoneField = internals.form.triggers[0].scheduledExecutionTimeZone();
    expect(zoneField.invalid()).toBe(true);
    expect(internals.saveDisabled()).toBe(true);

    zoneField.value.set('America/Halifax');
    expect(zoneField.invalid()).toBe(false);
    expect(internals.saveDisabled()).toBe(false);
  });

  /**
   * The gate names itself.
   *
   * A scheduled trigger without a zone opens expanded and marks the field, so the disabled
   * Save button has a visible cause instead of a collapsed card hiding it.
   */
  it('opens a zone-less scheduled trigger expanded and marks the field', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
        triggers: [
          {
            type: 'SCHEDULED',
            descs: [],
            scriptResourcePaths: [],
            scheduleType: 'DAILY',
            scheduledExecutionTime: '09:00',
          },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    expect(internals.form.triggers[0].expanded().value()).toBe(true);

    // The stub loader renders keys verbatim; the drawer portals its body to the overlay.
    const select = document.querySelector<HTMLSelectElement>(
      'select[aria-label="admin.mailingLists.trigger.timeZone"]',
    )!;
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(document.body.textContent).toContain('admin.mailingLists.trigger.timeZoneRequired');

    internals.form.triggers[0].scheduledExecutionTimeZone().value.set('America/Halifax');
    fixture.detectChanges();
    expect(select.getAttribute('aria-invalid')).toBeNull();
    expect(document.body.textContent).not.toContain('admin.mailingLists.trigger.timeZoneRequired');
  });

  /** A trigger the schema is content with stays collapsed — the list is a summary, not a form. */
  it('leaves a complete scheduled trigger collapsed', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
        triggers: [
          {
            type: 'SCHEDULED',
            descs: [],
            scriptResourcePaths: [],
            scheduleType: 'DAILY',
            scheduledExecutionTime: '09:00',
            scheduledExecutionTimeZone: 'America/Halifax',
          },
        ],
      },
    });
    fixture.detectChanges();

    expect(editorOf(fixture).form.triggers[0].expanded().value()).toBe(false);
  });

  it('a status-change trigger carries no zone requirement', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      mailingList: {
        mailingListId: 'nm',
        active: true,
        descs: [{ lang: 'en', name: 'NM', description: '' }],
        triggers: [
          {
            type: 'STATUS_CHANGE',
            descs: [],
            scriptResourcePaths: [],
            statusChanges: ['PUBLISHED'],
          },
        ],
      },
    });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    expect(internals.form.triggers[0].scheduledExecutionTimeZone().value()).toBe('');
    expect(internals.form().invalid()).toBe(false);
  });

  it('adds a trigger and serializes it on save (default STATUS_CHANGE + PUBLISHED, no UI-only fields)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', mailingList: { mailingListId: '', active: true, descs: [] } });
    fixture.detectChanges();

    const internals = editorOf(fixture);
    internals.form.mailingListId().value.set('nm');
    internals.form.descs[0].name().value.set('Navwarn');
    internals.addTrigger();
    fixture.detectChanges();
    internals.save();

    const req = httpTesting.expectOne('/rest/mailing-lists/mailing-list/');
    const body = req.request.body as {
      triggers: { type: string; statusChanges: string[]; expanded?: unknown; descs: unknown[] }[];
    };
    expect(body.triggers).toHaveLength(1);
    expect(body.triggers[0].type).toBe('STATUS_CHANGE');
    expect(body.triggers[0].statusChanges).toEqual(['PUBLISHED']);
    // UI-only fields never reach the wire.
    expect('expanded' in body.triggers[0]).toBe(false);
    req.flush({});
  });
});
