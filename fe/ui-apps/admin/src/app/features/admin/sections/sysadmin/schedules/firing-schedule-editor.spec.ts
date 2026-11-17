import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { FiringScheduleEditor, FiringScheduleEditorModel } from './firing-schedule-editor';

@Component({
  imports: [FiringScheduleEditor],
  template: `
    <app-firing-schedule-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<FiringScheduleEditorModel | undefined>(undefined);
  savedCount = 0;
}

const DOMAINS: Domain[] = [
  {
    domainId: 'niord-da',
    name: 'Ankerland',
    messageSeries: [{ seriesId: 'dk-nw' }, { seriesId: 'dk-nm' }],
  },
  { domainId: 'niord-fa', name: 'Bøjeland', messageSeries: [{ seriesId: 'fa-nw' }] },
  { domainId: 'niord-gl', name: 'Fyrland', messageSeries: [] },
];

const domainFacadeStub = {
  editableDomains: signal<readonly Domain[]>(DOMAINS),
  sessionDomain: signal<Domain | undefined>(DOMAINS[0]),
};

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
      { provide: DomainFacade, useValue: domainFacadeStub },
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
  form: FiringScheduleEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  seriesOptions: () => readonly { value: string }[];
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

describe('FiringScheduleEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('add mode defaults the source domain to the session domain', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', schedule: { domain: { domainId: 'niord-da' }, active: false } });
    fixture.detectChanges();
    expect(editorOf(fixture).form.sourceDomainId().value()).toBe('niord-da');
  });

  it('choosing a target domain repopulates series options and auto-selects the sole series', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', schedule: { domain: { domainId: 'niord-da' }, active: false } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    // `niord-fa` has exactly one series → auto-select it. detectChanges flushes the
    // dependent-series effect (async, unlike the old synchronous valueChanges).
    editor.form.targetDomainId().value.set('niord-fa');
    fixture.detectChanges();
    expect(editor.seriesOptions().map((o) => o.value)).toEqual(['fa-nw']);
    expect(editor.form.targetSeriesId().value()).toBe('fa-nw');

    // `niord-da` has two series → no auto-select; previous series cleared.
    editor.form.targetDomainId().value.set('niord-da');
    fixture.detectChanges();
    expect(editor.seriesOptions().map((o) => o.value)).toEqual(['dk-nw', 'dk-nm']);
    expect(editor.form.targetSeriesId().value()).toBe('');
  });

  it('edit mode keeps the persisted series for a multi-series target (no auto-select clobber)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      schedule: {
        id: 7,
        domain: { domainId: 'niord-da', name: 'Ankerland' },
        targetDomain: { domainId: 'niord-da', name: 'Ankerland' },
        targetSeriesId: 'dk-nm',
        scheduleDays: 30,
        active: true,
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(editor.form.targetSeriesId().value()).toBe('dk-nm'); // persisted value preserved
    expect(editor.saveDisabled()).toBe(true); // freshly opened, no change
  });

  it('multi-open reuse: opening a second row does not leak the first row series options', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      schedule: {
        id: 1,
        domain: { domainId: 'niord-da' },
        targetDomain: { domainId: 'niord-da' },
        targetSeriesId: 'dk-nw',
        active: true,
      },
    });
    fixture.detectChanges();
    expect(
      editorOf(fixture)
        .seriesOptions()
        .map((o) => o.value),
    ).toEqual(['dk-nw', 'dk-nm']);

    host.model.set({
      mode: 'edit',
      schedule: {
        id: 2,
        domain: { domainId: 'niord-da' },
        targetDomain: { domainId: 'niord-fa' },
        targetSeriesId: 'fa-nw',
        active: true,
      },
    });
    fixture.detectChanges();
    expect(
      editorOf(fixture)
        .seriesOptions()
        .map((o) => o.value),
    ).toEqual(['fa-nw']);
    expect(editorOf(fixture).form.targetSeriesId().value()).toBe('fa-nw');
  });

  it('Save is gated until the three required selects are filled and the form is changed', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'add', schedule: { domain: { domainId: 'niord-da' }, active: false } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.saveDisabled()).toBe(true); // no target domain/series yet

    editor.form.targetDomainId().value.set('niord-fa'); // auto-selects fa-nw
    fixture.detectChanges();
    expect(editor.form.targetSeriesId().value()).toBe('fa-nw');
    expect(editor.saveDisabled()).toBe(false); // all three required selects now set
  });

  it('save flattens both domains to a partial { domainId } and matches the PUT path id', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      schedule: {
        id: 9,
        domain: { domainId: 'niord-da', name: 'Ankerland' },
        targetDomain: { domainId: 'niord-fa', name: 'Bøjeland' },
        targetSeriesId: 'fa-nw',
        scheduleDays: 14,
        active: true,
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.scheduleDays().value.set(21); // make a real change
    editor.save();

    const req = httpTesting.expectOne('/rest/firing-schedules/firing-schedule/9');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      id: 9,
      domain: { domainId: 'niord-da' },
      targetDomain: { domainId: 'niord-fa' },
      targetSeriesId: 'fa-nw',
      scheduleDays: 21,
      active: true,
    });
    req.flush({ id: 9 });
    expect(host.savedCount).toBe(1);
  });

  it('a failed save keeps the drawer open and re-saveable', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', schedule: { domain: { domainId: 'niord-da' }, active: false } });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.targetDomainId().value.set('niord-fa'); // auto-selects fa-nw → valid + changed
    fixture.detectChanges(); // flush the dependent-series auto-select before saving
    editor.save();

    httpTesting
      .expectOne('/rest/firing-schedules/firing-schedule/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined(); // still open
    expect(host.savedCount).toBe(0);
    expect(editor.saving()).toBe(false); // re-saveable
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'add', schedule: { domain: { domainId: 'niord-da' }, active: false } });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.targetDomainId().value.set('niord-fa'); // auto-selects fa-nw → valid + changed
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/firing-schedules/firing-schedule/').flush({ id: 1 });
    expect(host.savedCount).toBe(1);
  });
});

/**
 * FIRING9: the scheduled days are a whole number of 1 or more, or empty (the
 * firingExerciseScheduleDays setting). A negative value made the hourly run
 * cancel every current firing exercise of the schedule, and the backend reads
 * 1.5 as 1 without a word, so the form is the one place a fraction is caught.
 */
describe('FiringScheduleEditor (scheduled days)', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const ERROR_KEY = 'admin.schedules.field.scheduleDaysInvalid';

  function openWithDays(scheduleDays: number | undefined) {
    const ctx = setup();
    ctx.host.model.set({
      mode: 'edit',
      schedule: {
        id: 9,
        domain: { domainId: 'niord-da' },
        targetDomain: { domainId: 'niord-fa' },
        targetSeriesId: 'fa-nw',
        scheduleDays,
        active: true,
      },
    });
    ctx.fixture.detectChanges();
    return { ...ctx, editor: editorOf(ctx.fixture) };
  }

  function typeDays(ctx: ReturnType<typeof openWithDays>, days: number | null): void {
    ctx.editor.form.scheduleDays().value.set(days);
    ctx.fixture.detectChanges();
  }

  function shownError(): string | undefined {
    return document.querySelector('#schedule-days-error')?.textContent?.trim();
  }

  for (const days of [-3, 0]) {
    it(`refuses ${days} days: Save stays disabled, the field says why, and nothing is sent`, () => {
      const ctx = openWithDays(14);
      typeDays(ctx, days);

      expect(ctx.editor.form.scheduleDays().invalid()).toBe(true);
      expect(ctx.editor.saveDisabled()).toBe(true);
      expect(shownError()).toBe(ERROR_KEY);
      expect(document.querySelector('#schedule-days')?.getAttribute('aria-invalid')).toBe('true');

      ctx.editor.save(); // the Enter path: gated the same way, so no PUT (verify() in afterEach)
    });
  }

  it('refuses a fraction (the backend would store 1.5 as 1)', () => {
    const ctx = openWithDays(14);
    typeDays(ctx, 1.5);

    expect(ctx.editor.form.scheduleDays().invalid()).toBe(true);
    expect(ctx.editor.saveDisabled()).toBe(true);
    expect(shownError()).toBe(ERROR_KEY);

    ctx.editor.save();
  });

  it('puts the minimum of 1 on the input', () => {
    openWithDays(14);
    expect(document.querySelector('#schedule-days')?.getAttribute('min')).toBe('1');
  });

  it('accepts 1 day and sends it', () => {
    const ctx = openWithDays(14);
    typeDays(ctx, 1);

    expect(ctx.editor.form.scheduleDays().invalid()).toBe(false);
    expect(ctx.editor.saveDisabled()).toBe(false);
    expect(shownError()).toBeUndefined();
    expect(document.querySelector('#schedule-days')?.hasAttribute('aria-invalid')).toBe(false);

    ctx.editor.save();
    const req = ctx.httpTesting.expectOne('/rest/firing-schedules/firing-schedule/9');
    expect(req.request.body.scheduleDays).toBe(1);
    req.flush({ id: 9 });
  });

  it('accepts an empty field (the setting decides) and sends no days', () => {
    const ctx = openWithDays(14);
    typeDays(ctx, null);

    expect(ctx.editor.form.scheduleDays().invalid()).toBe(false);
    expect(ctx.editor.saveDisabled()).toBe(false);
    expect(shownError()).toBeUndefined();

    ctx.editor.save();
    const req = ctx.httpTesting.expectOne('/rest/firing-schedules/firing-schedule/9');
    expect(req.request.body.scheduleDays).toBeUndefined();
    req.flush({ id: 9 });
  });

  it('a wrong value put right again lets Save through', () => {
    const ctx = openWithDays(14);
    typeDays(ctx, -3);
    expect(ctx.editor.saveDisabled()).toBe(true);

    typeDays(ctx, 30);
    expect(ctx.editor.saveDisabled()).toBe(false);
    expect(shownError()).toBeUndefined();
  });
});
