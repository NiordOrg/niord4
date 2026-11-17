import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import type { GeometryFeatureCollection } from '../../../../../shared/models/geometry.model';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { provideFakeAuthFacade } from '../../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Chart } from '../../../services/api/charts-admin-api.service';
import { ChartEditor, ChartEditorModel } from './chart-editor';

// jsdom lacks ResizeObserver, which ol.Map's constructor references (the page renders a map).
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  imports: [ChartEditor],
  template: `
    <app-chart-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<ChartEditorModel | undefined>(undefined);
  savedCount = 0;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideFakeAuthFacade(),
      // Stub DomainFacade (the geometry surfaces' domain-default map view reads
      // mapDefaults()) so the real facade's AuthFacade -> KEYCLOAK chain isn't pulled in.
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
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
  form: ChartEditor['form'];
  save: () => void;
  saving: () => boolean;
  saveDisabled: () => boolean;
  hasExtent: () => boolean;
  onGeometryCommit: (fc: GeometryFeatureCollection) => void;
  openGeometryEditor: () => void;
  geometryEditorOpen: () => boolean;
  currentFc: () => GeometryFeatureCollection;
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): EditorInternals {
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

const ADD_MODEL: ChartEditorModel = {
  mode: 'add',
  chart: { chartNumber: '', active: true, horizontalDatum: 'WGS84' },
};

const pointFc = (lon: number, lat: number): GeometryFeatureCollection => ({
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: {} },
  ],
});

describe('ChartEditor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('add mode seeds the default values and gates Save on the required chartNumber', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.form.active().value()).toBe(true);
    expect(editor.form.horizontalDatum().value()).toBe('WGS84');
    expect(editor.form.chartNumber().disabled()).toBe(false);
    expect(editor.saveDisabled()).toBe(true); // no change yet

    editor.form.name().value.set('Skagerrak');
    expect(editor.saveDisabled()).toBe(true); // changed but chartNumber still empty — required gate blocks

    editor.form.chartNumber().value.set('101');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('edit mode disables the immutable chartNumber PK', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      chart: { chartNumber: '101', active: true, name: 'Skagerrak' },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.form.chartNumber().disabled()).toBe(true);
    expect(editor.saveDisabled()).toBe(true); // unchanged
  });

  it('add saves via POST; cleared optionals are OMITTED from the payload', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.chartNumber().value.set('101');
    editor.form.horizontalDatum().value.set('');
    editor.save();

    const req = httpTesting.expectOne('/rest/charts/chart/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      chartNumber: '101',
      internationalNumber: undefined,
      name: undefined,
      scale: undefined,
      horizontalDatum: undefined,
      active: true,
      geometry: undefined,
    });
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('edit saves via PUT with the URL-encoded chartNumber path id', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: 'a/b 1', active: true } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.name().value.set('Changed');
    editor.save();

    const req = httpTesting.expectOne('/rest/charts/chart/a%2Fb%201');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as Chart).chartNumber).toBe('a/b 1'); // disabled PK still in the payload
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('a single-feature commit stores a bare geometry, flips the Save gate, and rides the payload', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: '101', active: true } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.hasExtent()).toBe(false);
    expect(editor.saveDisabled()).toBe(true);

    editor.onGeometryCommit(pointFc(10, 56));
    expect(editor.hasExtent()).toBe(true);
    expect(editor.saveDisabled()).toBe(false); // geometry edits count as changes

    editor.save();
    const req = httpTesting.expectOne('/rest/charts/chart/101');
    expect((req.request.body as Chart).geometry).toEqual({
      type: 'Point',
      coordinates: [10, 56],
    });
    req.flush({});
  });

  it('committing MULTIPLE features collapses them into a GeometryCollection (no feature is dropped)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: '101', active: true } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.onGeometryCommit({
      type: 'FeatureCollection',
      features: [
        { type: 'Feature', geometry: { type: 'Point', coordinates: [10, 56] }, properties: {} },
        { type: 'Feature', geometry: { type: 'Point', coordinates: [11, 57] }, properties: {} },
      ],
    });

    editor.save();
    const req = httpTesting.expectOne('/rest/charts/chart/101');
    expect((req.request.body as Chart).geometry).toEqual({
      type: 'GeometryCollection',
      geometries: [
        { type: 'Point', coordinates: [10, 56] },
        { type: 'Point', coordinates: [11, 57] },
      ],
    });
    req.flush({});
  });

  it('committing an EMPTY collection clears the extent — the chart saves with no geometry', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set({
      mode: 'edit',
      chart: {
        chartNumber: '101',
        active: true,
        geometry: { type: 'Point', coordinates: [10, 56] },
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.hasExtent()).toBe(true);
    editor.onGeometryCommit({ type: 'FeatureCollection', features: [] });
    expect(editor.hasExtent()).toBe(false);

    editor.save();
    const req = httpTesting.expectOne('/rest/charts/chart/101');
    expect((req.request.body as Chart).geometry).toBeUndefined();
    req.flush({});
  });

  it('the working extent is exposed as a one-feature FeatureCollection (preview + modals)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      chart: {
        chartNumber: '101',
        active: true,
        geometry: { type: 'Point', coordinates: [10, 56] },
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.openGeometryEditor();
    expect(editor.geometryEditorOpen()).toBe(true);
    expect(editor.currentFc().features).toHaveLength(1);
    expect(editor.currentFc().features[0].geometry).toEqual({
      type: 'Point',
      coordinates: [10, 56],
    });
  });

  it('a stored GeometryCollection expands back into one feature per member (round-trips intact)', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      chart: {
        chartNumber: '101',
        active: true,
        geometry: {
          type: 'GeometryCollection',
          geometries: [
            { type: 'Point', coordinates: [10, 56] },
            { type: 'Point', coordinates: [11, 57] },
          ],
        },
      },
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    const features = editor.currentFc().features;
    expect(features).toHaveLength(2);
    expect(features[0].geometry).toEqual({ type: 'Point', coordinates: [10, 56] });
    expect(features[1].geometry).toEqual({ type: 'Point', coordinates: [11, 57] });
  });

  it('a failed save keeps the drawer open and re-saveable', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.chartNumber().value.set('101');
    editor.save();
    httpTesting
      .expectOne('/rest/charts/chart/')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(host.model()).toBeDefined(); // still open
    expect(host.savedCount).toBe(0);
    expect(editor.saving()).toBe(false);
  });

  it('multi-open reuse: a second model does not leak the first chart values or extent', () => {
    const { fixture, host } = setup();
    host.model.set({
      mode: 'edit',
      chart: {
        chartNumber: '101',
        active: true,
        name: 'First',
        geometry: { type: 'Point', coordinates: [10, 56] },
      },
    });
    fixture.detectChanges();
    expect(editorOf(fixture).hasExtent()).toBe(true);

    host.model.set({ mode: 'edit', chart: { chartNumber: '102', active: false, name: 'Second' } });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(editor.form.chartNumber().value()).toBe('102');
    expect(editor.form.name().value()).toBe('Second');
    expect(editor.hasExtent()).toBe(false);
    expect(editor.saveDisabled()).toBe(true); // fresh baseline
  });

  it('a number input binds through as a number, not a string (native valueAsNumber)', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.chartNumber().value.set('101');
    // Drive the real number input so the native valueAsNumber path is exercised
    // (the drawer portals to the CDK overlay, so query the document).
    const scaleInput = document.querySelector('#chart-scale') as HTMLInputElement;
    scaleInput.value = '50000';
    scaleInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    editor.save();
    const req = httpTesting.expectOne('/rest/charts/chart/');
    expect((req.request.body as Chart).scale).toBe(50000); // number, not "50000"
    req.flush({});
  });

  it('a scale of 0 or below keeps Save disabled; an empty scale stays allowed (L-178)', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: '101', active: true, scale: 50000 } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    editor.form.scale().value.set(0);
    expect(editor.form.scale().invalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.form.scale().value.set(-5);
    expect(editor.saveDisabled()).toBe(true);

    editor.form.scale().value.set(1);
    expect(editor.saveDisabled()).toBe(false);

    editor.form.scale().value.set(null);
    expect(editor.form.scale().invalid()).toBe(false);
    expect(editor.saveDisabled()).toBe(false);
  });

  it('the scale input carries min="1", so the browser marks a lower value (L-178)', () => {
    const { fixture, host } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();

    const scaleInput = document.querySelector('#chart-scale') as HTMLInputElement;
    expect(scaleInput.getAttribute('min')).toBe('1');
  });

  it('a text longer than its column keeps Save disabled, and its counter says so (ERR7)', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: '101', active: true, name: 'Old' } });
    fixture.detectChanges();
    const editor = editorOf(fixture);

    for (const field of ['name', 'horizontalDatum'] as const) {
      editor.form[field]().value.set('a'.repeat(256));
      fixture.detectChanges();
      expect(editor.saveDisabled()).toBe(true);

      editor.form[field]().value.set('a'.repeat(255));
      fixture.detectChanges();
      expect(editor.saveDisabled()).toBe(false);
    }

    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    editor.form.chartNumber().value.set('1'.repeat(256));
    expect(editor.saveDisabled()).toBe(true);
    editor.form.chartNumber().value.set('1'.repeat(255));
    expect(editor.saveDisabled()).toBe(false);
  });

  it('a pasted name is kept whole: no maxlength on the input, and the counter names the excess (ERR7)', () => {
    const { fixture, host } = setup();
    host.model.set({ mode: 'edit', chart: { chartNumber: '101', active: true } });
    fixture.detectChanges();

    const nameInput = document.querySelector('#chart-name') as HTMLInputElement;
    expect(nameInput.hasAttribute('maxlength')).toBe(false);
    expect(nameInput.getAttribute('aria-describedby')).toBe('chart-name-counter');

    nameInput.value = 'a'.repeat(300);
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    expect(editorOf(fixture).form.name().value()).toHaveLength(300);
    const counter = document.querySelector('#chart-name-counter') as HTMLElement;
    expect(counter.getAttribute('role')).toBe('alert');
    expect(counter.textContent?.trim()).toBe('shared.forms.lengthCounter.tooLong');
    expect(editorOf(fixture).saveDisabled()).toBe(true);
  });

  it('Enter submits through the <form> binding, not just a direct save() call', () => {
    const { fixture, host, httpTesting } = setup();
    host.model.set(ADD_MODEL);
    fixture.detectChanges();
    const editor = editorOf(fixture);
    editor.form.chartNumber().value.set('101');
    fixture.detectChanges();

    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/charts/chart/').flush({});
    expect(host.savedCount).toBe(1);
  });
});
