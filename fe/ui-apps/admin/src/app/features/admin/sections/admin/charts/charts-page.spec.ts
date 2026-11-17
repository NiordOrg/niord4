import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { provideFakeAuthFacade } from '../../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Chart } from '../../../services/api/charts-admin-api.service';
import { ChartsPage } from './charts-page';

// jsdom lacks ResizeObserver, which ol.Map's constructor references (the page renders a map).
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

interface PageInternals {
  search: () => string;
  filtered: () => readonly Chart[];
  onSearch: (value: string) => void;
  pager: { first: () => number; setFirst: (first: number) => void };
  editorModel: () => { mode: string; chart: Chart } | undefined;
  addChart: () => void;
  cogItems: () => { label: string; command: () => void }[];
}

function setup() {
  const openPublic = vi.fn();
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
      { provide: TicketedDownloadService, useValue: { openPublic } },
    ],
  });
  const fixture = TestBed.createComponent(ChartsPage);
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    openPublic,
  };
}

const chart = (chartNumber: string, extras: Partial<Chart> = {}): Chart => ({
  chartNumber,
  active: true,
  ...extras,
});

describe('ChartsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters over all FOUR fields (incl. the non-column horizontalDatum and a numeric substring); falsy fields never match', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/charts/all')
      .flush([
        chart('101', { internationalNumber: 1101, name: 'Skagerrak', horizontalDatum: 'WGS84' }),
        chart('102', { name: 'Kattegat' }),
        chart('190'),
      ]);
    fixture.detectChanges();

    page.onSearch('skager');
    expect(page.filtered().map((c) => c.chartNumber)).toEqual(['101']);

    // numeric internationalNumber matched by substring
    page.onSearch('110');
    expect(page.filtered().map((c) => c.chartNumber)).toEqual(['101']);

    // the non-column horizontalDatum is searchable; charts without it never match
    page.onSearch('wgs');
    expect(page.filtered().map((c) => c.chartNumber)).toEqual(['101']);

    page.onSearch('10');
    expect(page.filtered().map((c) => c.chartNumber)).toEqual(['101', '102']);

    page.onSearch('');
    expect(page.filtered()).toHaveLength(3);
  });

  it('every filter change resets paging to the first page (never lands on a blank page)', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/charts/all')
      .flush(Array.from({ length: 25 }, (_, i) => chart(`c${i}`)));
    fixture.detectChanges();

    page.pager.setFirst(20);
    expect(page.pager.first()).toBe(20);
    page.onSearch('c');
    expect(page.pager.first()).toBe(0);
  });

  it('New Chart opens the editor with the add defaults (active true, datum WGS84)', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting.expectOne('/rest/charts/all').flush([]);
    fixture.detectChanges();

    page.addChart();
    const model = page.editorModel();
    expect(model?.mode).toBe('add');
    expect(model?.chart.active).toBe(true);
    expect(model?.chart.horizontalDatum).toBe('WGS84');
    expect(model?.chart.geometry).toBeUndefined();
  });

  it('Export opens the PUBLIC list URL directly — no ticket', () => {
    const { fixture, page, httpTesting, openPublic } = setup();
    httpTesting.expectOne('/rest/charts/all').flush([]);
    fixture.detectChanges();

    const exportItem = page.cogItems()[0];
    exportItem.command();
    expect(openPublic).toHaveBeenCalledWith('/rest/charts/all');
  });

  it('shows the empty state on an empty catalog', () => {
    const { fixture, httpTesting } = setup();
    httpTesting.expectOne('/rest/charts/all').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('shows the error banner on a failed list load', () => {
    const { fixture, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/charts/all')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });
});
