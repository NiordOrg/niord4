import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../../shared/testing/zone-testing';
import type { AdminMessageSeries } from '../../../models/admin-message-series.model';
import { SeriesEditorModel, SeriesPage } from './series-page';

const LIST_URL = '/rest/message-series/all?messageNumbers=true';

interface PageInternals {
  editorModel: () => SeriesEditorModel | undefined;
  pager: { first: () => number; setFirst: (first: number) => void };
  onSearch: (value: string) => void;
  addSeries: () => void;
  editSeries: (series: AdminMessageSeries) => void;
  copySeries: (series: AdminMessageSeries) => void;
  deleteSeries: (series: AdminMessageSeries) => void;
}

const NW_YEARLY: AdminMessageSeries = {
  seriesId: 'dma-nw',
  mainType: 'NW',
  types: ['LOCAL_WARNING', 'COASTAL_WARNING'],
  numberSequenceType: 'YEARLY',
  shortFormat: 'NW-${number}-${year}',
  nextMessageNumber: 14,
};
// Negative control for the filter scope: 'NW' appears in every column EXCEPT the id.
const FA_CONTINUOUS: AdminMessageSeries = {
  seriesId: 'dma-fa',
  mainType: 'NW',
  numberSequenceType: 'CONTINUOUS',
  shortFormat: 'NW-${number}',
  nextMessageNumber: 27,
};
const NM_MANUAL: AdminMessageSeries = {
  seriesId: 'dma-nm',
  mainType: 'NM',
  types: [],
  numberSequenceType: 'MANUAL',
  // Even a present number must stay hidden for a manually-numbered series.
  nextMessageNumber: 99,
};
const NM_NONE: AdminMessageSeries = {
  seriesId: 'dma-none',
  mainType: 'NM',
  numberSequenceType: 'NONE',
};

/** Drain the effect scheduler + microtask queue (zoneless — flush manually). */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

async function setup(entries: readonly AdminMessageSeries[], sessionZone?: string) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestSessionZone(sessionZone),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
    ],
  });
  // Real text for the keys the assertions read (the stub loader ships none).
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.series.mainType.NW': 'NW',
      'admin.series.mainType.NM': 'NM',
      'admin.series.sequenceType.YEARLY': 'Yearly',
      'admin.series.sequenceType.CONTINUOUS': 'Continuous',
      'admin.series.sequenceType.MANUAL': 'Manual',
      'admin.series.sequenceType.NONE': 'None',
      'admin.series.column.nextNumber': 'Next Number ({{year}})',
      'admin.series.confirmDelete.header': 'Delete message series?',
      'admin.series.confirmDelete.message': "Delete message series ID '{{seriesId}}'?",
      'msg.type.LOCAL_WARNING': 'Local',
      'msg.type.COASTAL_WARNING': 'Coastal',
    },
    'en',
    { merge: true },
  );
  const fixture = TestBed.createComponent(SeriesPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  TestBed.tick();
  httpTesting.expectOne(LIST_URL).flush([...entries]);
  await settle();
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    confirmService: fixture.debugElement.injector.get(ConfirmService),
  };
}

function headerTexts(fixture: { nativeElement: HTMLElement }): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('thead th')).map(
    (cell) => (cell as HTMLElement).textContent?.trim() ?? '',
  );
}

function cellTexts(fixture: { nativeElement: HTMLElement }, column: number): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr')).map(
    (row) => (row as HTMLTableRowElement).cells[column].textContent?.trim() ?? '',
  );
}

describe('SeriesPage', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
  });

  it('filters on the seriesId substring only — matches in other columns are ignored', async () => {
    const { fixture, page } = await setup([NW_YEARLY, FA_CONTINUOUS]);
    // 'nw' appears in dma-fa's main type + short format, but not in its id.
    page.onSearch('NW');
    fixture.detectChanges();
    expect(cellTexts(fixture, 0)).toEqual(['dma-nw']);

    page.onSearch('');
    fixture.detectChanges();
    expect(cellTexts(fixture, 0)).toEqual(['dma-nw', 'dma-fa']);
  });

  it('search resets the pager to the first page', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({
      ...NW_YEARLY,
      seriesId: `series-${String(i + 1).padStart(2, '0')}`,
    }));
    const { page } = await setup(many);
    page.pager.setFirst(20);
    expect(page.pager.first()).toBe(20);

    page.onSearch('series');
    expect(page.pager.first()).toBe(0);
  });

  it('renders type suffixes as ONE parenthesis group and keyed sequence labels', async () => {
    const { fixture } = await setup([NW_YEARLY, NM_MANUAL]);
    // Each type wrapped separately would read "(Local, )(Coastal)" — normalized to a single group.
    expect(cellTexts(fixture, 1)).toEqual(['NW (Local, Coastal)', 'NM']);
    expect(cellTexts(fixture, 2)).toEqual(['Yearly', 'Manual']);
  });

  it('shows the next number only for auto-numbered series (YEARLY / CONTINUOUS)', async () => {
    const { fixture } = await setup([NW_YEARLY, FA_CONTINUOUS, NM_MANUAL, NM_NONE]);
    expect(cellTexts(fixture, 4)).toEqual(['14', '27', '', '']);
  });

  it('the next-number header carries the year the session domain is on — even for CONTINUOUS series', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // Kiritimati runs at UTC+14, so midday UTC on New Year's Eve is already 1 January
    // there — a year no zone the runner could sit in is on yet.
    vi.setSystemTime(new Date('2026-12-31T12:00:00Z'));
    const { fixture } = await setup([FA_CONTINUOUS], 'Pacific/Kiritimati');
    expect(headerTexts(fixture)[4]).toBe('Next Number (2027)');
  });

  it('the header falls back to the administrator’s year when no domain names a zone', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // Mid-June, where every zone agrees on the year: this states that the column still
    // renders a year without a session domain, without asserting the runner's offset.
    vi.setSystemTime(new Date('2026-06-15T12:00:00Z'));
    const { fixture } = await setup([FA_CONTINUOUS]);
    expect(headerTexts(fixture)[4]).toBe('Next Number (2026)');
  });

  it('delete confirms with the confirm text and DELETEs + refreshes on accept', async () => {
    const { page, httpTesting, confirmService } = await setup([NW_YEARLY]);
    page.deleteSeries(NW_YEARLY);

    const active = confirmService.active();
    expect(active?.header).toBe('Delete message series?');
    expect(active?.message).toBe("Delete message series ID 'dma-nw'?");

    active?.accept();
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.series.toast.deleted',
    );
    // The list refetches after the mutation.
    await settle();
    httpTesting.expectOne(LIST_URL).flush([]);
  });

  it('a delete the server refuses with a reason shows that reason and keeps the list', async () => {
    const { page, httpTesting, confirmService } = await setup([NW_YEARLY]);
    page.deleteSeries(NW_YEARLY);
    confirmService.active()?.accept();

    const detail = 'The message series is in use: 3 messages. Take it out of use first.';
    httpTesting
      .expectOne('/rest/message-series/series/dma-nw')
      .flush(
        { type: 'about:blank', title: 'Conflict', status: 409, detail },
        { status: 409, statusText: 'Conflict' },
      );
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledExactlyOnceWith(
      'admin.series.toast.deleteRefused',
      { detail },
    );
    // No refetch: the afterEach verify fails on an unexpected list request.
    await settle();
  });

  it('any other failed delete shows the plain error', async () => {
    const { page, httpTesting, confirmService } = await setup([NW_YEARLY]);
    page.deleteSeries(NW_YEARLY);
    confirmService.active()?.accept();

    httpTesting
      .expectOne('/rest/message-series/series/dma-nw')
      .flush(
        { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: 'Boom' },
        { status: 500, statusText: 'Internal Server Error' },
      );
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledExactlyOnceWith(
      'admin.series.toast.deleteError',
    );
    await settle();
  });

  it('New seeds the default values in add mode', async () => {
    const { page } = await setup([]);
    page.addSeries();
    expect(page.editorModel()).toEqual({
      mode: 'add',
      series: {
        seriesId: '',
        mainType: 'NW',
        numberSequenceType: 'YEARLY',
        shortFormat: '',
        types: [],
        excludeFromMessageIndex: false,
      },
    });
  });

  it('edit opens the row in edit mode; copy clones it into add mode with a blanked id', async () => {
    const { page } = await setup([NW_YEARLY]);
    page.editSeries(NW_YEARLY);
    expect(page.editorModel()).toEqual({ mode: 'edit', series: NW_YEARLY });

    page.copySeries(NW_YEARLY);
    expect(page.editorModel()).toEqual({
      mode: 'add',
      series: { ...NW_YEARLY, seriesId: '' },
    });
  });
});
