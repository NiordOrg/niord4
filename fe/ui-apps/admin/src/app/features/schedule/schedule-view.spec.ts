import { Component, input, output, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { type Observable, of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../core/config/app-config.token';
import { DomainFacade } from '../../core/domain/facades/domain.facade';
import { LanguageService } from '../../core/main-shell/services/language.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { ConfirmService } from '../../shared/services/confirm.service';
import { provideNiordIcons } from '../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import type { FiringRunResult } from '../../shared/models/firing-run-result.model';
import { Confirm } from '../../shared/components/confirm/confirm';
import { DatePicker } from '../../shared/forms/date-picker/date-picker';
import { TreePicker } from '../../shared/components/tree-picker/tree-picker';
import { BaseMapLayers } from '../../shared/components/ol-map/base-map-layers';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { OlMap } from '@niord/map/ol-map';
import { OlAreasLayer } from '../messages/components/map-layers/ol-areas-layer';
import type { AreaRef } from '../messages/models/messages-filter-params.model';
import { TimeScheduleEditor } from './components/time-schedule-editor/time-schedule-editor';
import type { ScheduleRow } from './schedule.facade';
import { ScheduleFacade } from './schedule.facade';
import { ScheduleApiService } from './services/api/schedule-api.service';
import { ScheduleView } from './schedule-view';

// --- Lightweight stand-ins for the heavy map subtree + form widgets. Each mirrors
// only the selector + the inputs/outputs the ScheduleView template binds, so the
// real component's branch structure renders without pulling in OpenLayers / CDK
// overlays. (The map + editor + pickers have their own specs.)
@Component({ selector: 'app-ol-map', template: `<ng-content />` })
class StubOlMap {
  readonly view = input<unknown>();
}
@Component({ selector: 'app-base-map-layers', template: `` })
class StubBaseMapLayers {
  readonly openSeaMap = input(false);
}
@Component({ selector: 'app-ol-areas-layer', template: `` })
class StubAreasLayer {
  readonly areas = input<unknown>();
  readonly name = input<string>();
  readonly fitExtent = input(false);
  readonly hovered = input<number | undefined>(undefined);
  readonly showInLayerSwitcher = input(false);
}
@Component({ selector: 'app-ol-layer-switcher', template: `` })
class StubLayerSwitcher {
  readonly title = input<string>();
}
@Component({ selector: 'app-time-schedule-editor', template: `` })
class StubTimeScheduleEditor {
  readonly firingAreaPeriods = input<unknown>();
  readonly date = input<Date>();
}
@Component({ selector: 'app-confirm', template: `` })
class StubConfirm {}
@Component({ selector: 'app-date-picker', template: `` })
class StubDatePicker {
  readonly value = input<Date | undefined>(undefined);
  readonly ariaLabel = input<string>();
  readonly local = input(false);
  readonly valueChange = output<Date | undefined>();
}
@Component({ selector: 'app-tree-picker', template: `` })
class StubTreePicker {
  readonly header = input<string>();
  readonly entities = input<readonly unknown[]>([]);
  readonly selectedIds = input<readonly number[]>([]);
  readonly selectionChange = output<readonly AreaRef[]>();
  toggle(): void {
    /* popover open is out of scope for these render-branch tests */
  }
}

interface FacadeStub {
  query: ReturnType<typeof signal<string>>;
  selectedAreas: ReturnType<typeof signal<readonly AreaRef[]>>;
  date: ReturnType<typeof signal<Date>>;
  loading: ReturnType<typeof signal<boolean>>;
  error: ReturnType<typeof signal<boolean>>;
  rows: ReturnType<typeof signal<readonly ScheduleRow[]>>;
  firingAreas: ReturnType<typeof signal<readonly AreaRef[]>>;
  offsetDate: ReturnType<typeof vi.fn>;
  setDate: ReturnType<typeof vi.fn>;
  retry: ReturnType<typeof vi.fn>;
}

function makeFacade(): FacadeStub {
  return {
    query: signal(''),
    selectedAreas: signal<readonly AreaRef[]>([]),
    date: signal(new Date(2026, 5, 15, 12, 0, 0, 0)),
    loading: signal(false),
    error: signal(false),
    rows: signal<readonly ScheduleRow[]>([]),
    firingAreas: signal<readonly AreaRef[]>([]),
    offsetDate: vi.fn(),
    setDate: vi.fn(),
    retry: vi.fn(),
  };
}

function setup(firingSchedule = true) {
  const facade = makeFacade();
  const domain = {
    capabilities: signal({ atons: false, templates: false, publish: false, firingSchedule }),
    mapDefaults: signal({ center: undefined, zoom: undefined }),
  };
  const confirm = { confirmNeutral: vi.fn((o: { accept: () => void }) => o.accept()) };
  const scheduleApi = {
    updateFiringExercises: vi.fn(
      (): Observable<FiringRunResult> =>
        of({ failedSchedules: 0, failedAreas: 0, failedCancellations: 0 }),
    ),
  };
  const notifications = { success: vi.fn(), warn: vi.fn(), error: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      {
        provide: APP_CONFIG,
        useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {}, map: {} },
      },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: DomainFacade, useValue: domain },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      { provide: ScheduleApiService, useValue: scheduleApi },
      { provide: NotificationService, useValue: notifications },
    ],
  });

  // Swap the OpenLayers subtree + CDK-overlay widgets for stubs, and feed a
  // fully-controlled facade + confirm through the component-scoped providers.
  TestBed.overrideComponent(ScheduleView, {
    remove: {
      imports: [
        OlMap,
        BaseMapLayers,
        OlAreasLayer,
        OlLayerSwitcher,
        TimeScheduleEditor,
        Confirm,
        DatePicker,
        TreePicker,
      ],
    },
    add: {
      imports: [
        StubOlMap,
        StubBaseMapLayers,
        StubAreasLayer,
        StubLayerSwitcher,
        StubTimeScheduleEditor,
        StubConfirm,
        StubDatePicker,
        StubTreePicker,
      ],
      providers: [
        { provide: ScheduleFacade, useValue: facade },
        { provide: ConfirmService, useValue: confirm },
      ],
    },
  });

  const fixture = TestBed.createComponent(ScheduleView);
  return { fixture, facade, domain, confirm, scheduleApi, notifications };
}

const areaWithName = (id: number, name: string): AreaRef => ({ id, descs: [{ lang: 'en', name }] });

describe('ScheduleView', () => {
  afterEach(() => {
    // The area-roots httpResource fires on init regardless of the branch; drain it.
    TestBed.inject(HttpTestingController)
      .match(() => true)
      .forEach((req) => !req.cancelled && req.flush([]));
  });

  it('gated-out (no firingSchedule domain): shows the empty state and hides the filters + action', () => {
    const { fixture } = setup(false);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-empty-state')).not.toBeNull();
    expect(el.querySelectorAll('app-filter-panel').length).toBe(0);
    expect(el.querySelector('table')).toBeNull();
    // The Update-Exercises button is the only .btn-secondary and is gated away.
    expect(el.querySelector('.btn-secondary')).toBeNull();
  });

  it('firing-enabled: renders the filter bar + date nav + the Update-Exercises action', () => {
    const { fixture } = setup(true);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('app-filter-panel').length).toBe(2);
    expect(el.querySelector('app-date-picker')).not.toBeNull();
    expect(el.querySelector('.btn-secondary')).not.toBeNull();
  });

  it('puts the Layers card at the bottom left, clear of the zoom buttons and the credit line', () => {
    const { fixture } = setup(true);
    fixture.detectChanges();

    // The zoom buttons and the map's credit line sit at the bottom right of every map.
    const switcher = (fixture.nativeElement as HTMLElement).querySelector('app-ol-layer-switcher');
    expect(switcher).not.toBeNull();
    expect(switcher!.classList.contains('left-2')).toBe(true);
    expect(switcher!.classList.contains('bottom-2')).toBe(true);
    expect(switcher!.classList.contains('right-2')).toBe(false);
  });

  it('loading branch: keeps the table mounted (latched rows) with the progress overlay visible', () => {
    const { fixture, facade } = setup(true);
    facade.loading.set(true);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    // loading → the empty-state condition (`!loading && rows==0`) is false, so the table stays.
    expect(el.querySelector('table')).not.toBeNull();
    expect(el.querySelector('app-error-banner')).toBeNull();
    const bar = fixture.debugElement.query(By.directive(ProgressBar));
    expect((bar.componentInstance as ProgressBar).visible()).toBe(true);
    expect(el.querySelector('[role="progressbar"]')).not.toBeNull();
  });

  it('error branch: shows the error banner (no table) and Retry re-runs the search', () => {
    const { fixture, facade } = setup(true);
    facade.error.set(true);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-error-banner')).not.toBeNull();
    expect(el.querySelector('table')).toBeNull();
    expect(el.querySelector('app-empty-state')).toBeNull();

    (el.querySelector('app-error-banner button') as HTMLButtonElement).click();
    expect(facade.retry).toHaveBeenCalledTimes(1);
  });

  it('says so when the areas for the picker could not be read, and Retry reads them again', async () => {
    const { fixture } = setup(true);
    fixture.detectChanges();
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url.endsWith('/areas/area-roots'))
      .flush('down', { status: 503, statusText: 'Service Unavailable' });
    await Promise.resolve();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const banner = () =>
      [...el.querySelectorAll('[role="alert"]')].find((node) =>
        node.textContent?.includes('common.fetchError'),
      );
    expect(banner()).toBeDefined();

    banner()?.querySelector('button')?.click();
    TestBed.tick();
    http
      .expectOne((r) => r.url.endsWith('/areas/area-roots'))
      .flush([areaWithName(1, 'Firing area')]);
    await Promise.resolve();
    fixture.detectChanges();
    expect(banner()).toBeUndefined();
  });

  it('empty branch: settled with zero rows shows the inbox empty state, not the table', () => {
    const { fixture, facade } = setup(true);
    facade.loading.set(false);
    facade.error.set(false);
    facade.rows.set([]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const empty = el.querySelector('app-empty-state');
    expect(empty).not.toBeNull();
    expect(empty?.textContent).toContain('schedule.empty');
    expect(el.querySelector('table')).toBeNull();
  });

  it('rows branch: renders one area-heading row + one editor row per firing area', () => {
    const { fixture, facade } = setup(true);
    facade.rows.set([
      {
        fap: { area: areaWithName(1, 'Alpha'), firingPeriods: [] },
        areaHeading: areaWithName(20, 'Group C'),
      },
      { fap: { area: areaWithName(2, 'Bravo'), firingPeriods: [] } },
    ]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('table')).not.toBeNull();
    // 1 heading row + 2 data rows.
    expect(el.querySelectorAll('tbody tr').length).toBe(3);
    expect(el.querySelectorAll('app-time-schedule-editor').length).toBe(2);
    expect(el.querySelector('tbody th')?.textContent).toContain('Group C');
    const bodyText = el.querySelector('tbody')?.textContent ?? '';
    expect(bodyText).toContain('Alpha');
    expect(bodyText).toContain('Bravo');
  });

  it('date navigation: the prev/next arrows shift the day, the picker sets it', () => {
    const { fixture, facade } = setup(true);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[aria-label="schedule.previousDay"]') as HTMLButtonElement).click();
    expect(facade.offsetDate).toHaveBeenCalledWith(-1);
    (el.querySelector('[aria-label="schedule.nextDay"]') as HTMLButtonElement).click();
    expect(facade.offsetDate).toHaveBeenCalledWith(1);

    const picker = fixture.debugElement.query(By.directive(StubDatePicker))
      .componentInstance as StubDatePicker;
    const day = new Date(2026, 0, 2, 12);
    picker.valueChange.emit(day);
    expect(facade.setDate).toHaveBeenCalledWith(day);
    // The `$event &&` guard swallows a cleared date.
    picker.valueChange.emit(undefined);
    expect(facade.setDate).toHaveBeenCalledTimes(1);
  });

  it('the tree-picker selection and the chip removal both flow through the facade', () => {
    const { fixture, facade } = setup(true);
    facade.selectedAreas.set([areaWithName(1, 'Alpha')]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const chip = el.querySelector('.chip.is-active') as HTMLButtonElement;
    expect(chip.textContent).toContain('Alpha');

    // Selecting in the popover replaces the selection.
    const picker = fixture.debugElement.query(By.directive(StubTreePicker))
      .componentInstance as StubTreePicker;
    picker.selectionChange.emit([areaWithName(1, 'Alpha'), areaWithName(2, 'Bravo')]);
    expect(facade.selectedAreas().map((a) => a.id)).toEqual([1, 2]);

    // Clicking a chip removes just that area.
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.chip.is-active') as HTMLButtonElement).click();
    expect(facade.selectedAreas().map((a) => a.id)).toEqual([2]);
  });

  it('Update-Exercises confirms then reports success when the run failed nothing', () => {
    const { fixture, confirm, scheduleApi, notifications } = setup(true);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement).click();

    expect(confirm.confirmNeutral).toHaveBeenCalledTimes(1);
    expect(scheduleApi.updateFiringExercises).toHaveBeenCalledTimes(1);
    expect(notifications.success).toHaveBeenCalledWith('schedule.toast.exercisesUpdated');
    expect(notifications.warn).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('Update-Exercises warns with the number of failed changes when the run answers failures', () => {
    // FIRING6: the run answers what failed; the success toast must not claim a clean run.
    const { fixture, scheduleApi, notifications } = setup(true);
    scheduleApi.updateFiringExercises = vi.fn(() =>
      of({ failedSchedules: 1, failedAreas: 2, failedCancellations: 1 }),
    );
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement).click();

    expect(notifications.warn).toHaveBeenCalledWith('schedule.toast.exercisesPartlyFailed', {
      count: 4,
    });
    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('Update-Exercises warns with the singular text when one change failed', () => {
    const { fixture, scheduleApi, notifications } = setup(true);
    scheduleApi.updateFiringExercises = vi.fn(() =>
      of({ failedSchedules: 0, failedAreas: 0, failedCancellations: 1 }),
    );
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement).click();

    expect(notifications.warn).toHaveBeenCalledWith('schedule.toast.exercisesPartlyFailedOne', {
      count: 1,
    });
    expect(notifications.success).not.toHaveBeenCalled();
  });

  it('Update-Exercises reports an error toast when the PUT fails', () => {
    const { fixture, scheduleApi, notifications } = setup(true);
    scheduleApi.updateFiringExercises = vi.fn(() => throwError(() => new Error('boom')));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement).click();

    expect(notifications.error).toHaveBeenCalledWith('schedule.toast.exercisesError');
    expect(notifications.success).not.toHaveBeenCalled();
  });

  it('Update-Exercises is disabled while its request runs, and a second press sends nothing', () => {
    // FIRING7: a second press while the run is in flight must not start a second run.
    const { fixture, confirm, scheduleApi, notifications } = setup(true);
    const answer = new Subject<FiringRunResult>();
    scheduleApi.updateFiringExercises = vi.fn(() => answer.asObservable());
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement;

    button.click();
    fixture.detectChanges();
    expect(scheduleApi.updateFiringExercises).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');

    // The component's own guard holds even if the press arrives some other way.
    (fixture.componentInstance as unknown as { updateExercises: () => void }).updateExercises();
    expect(confirm.confirmNeutral).toHaveBeenCalledTimes(1);
    expect(scheduleApi.updateFiringExercises).toHaveBeenCalledTimes(1);

    answer.next({ failedSchedules: 0, failedAreas: 0, failedCancellations: 0 });
    answer.complete();
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
    expect(notifications.success).toHaveBeenCalledTimes(1);
  });

  it('Update-Exercises is enabled again after its request fails', () => {
    const { fixture, scheduleApi } = setup(true);
    const answer = new Subject<FiringRunResult>();
    scheduleApi.updateFiringExercises = vi.fn(() => answer.asObservable());
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('.btn-secondary') as HTMLButtonElement;

    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBe(true);

    answer.error(new Error('boom'));
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
  });
});
