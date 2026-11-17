import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { FiringSchedule } from '../../../services/api/firing-schedules-api.service';
import { FiringSchedulesPage } from './firing-schedules-page';

interface PageInternals {
  search: () => string;
  filtered: () => readonly { targetSeriesId?: string }[];
  onSearch: (value: string) => void;
  deleteSchedule: (schedule: FiringSchedule) => void;
  pager: { first: () => number; setFirst: (first: number) => void };
}

const domainFacadeStub = {
  editableDomains: signal([]),
  sessionDomain: signal({ domainId: 'niord-da', name: 'Ankerland' }),
};

/**
 * Auto-accept confirm stub: fires the destructive `accept` callback synchronously.
 * `active`/`dismiss` keep the mounted `<app-confirm>` inert (no pending confirm, so
 * no overlay opens) since it injects this same scoped `ConfirmService`.
 */
const autoAcceptConfirm = {
  active: () => undefined,
  dismiss: () => undefined,
  confirmDestructive: (options: { accept: () => void }) => options.accept(),
};

function setup(notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: DomainFacade, useValue: domainFacadeStub },
    ],
  });
  // Override the component-scoped ConfirmService so deletes skip the real dialog
  // and accept immediately.
  TestBed.overrideComponent(FiringSchedulesPage, {
    add: { providers: [{ provide: ConfirmService, useValue: autoAcceptConfirm }] },
  });
  const fixture = TestBed.createComponent(FiringSchedulesPage);
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

/** The cog menu's rows, read through the component's protected `cogItems` computed. */
const cogItems = (fixture: ComponentFixture<FiringSchedulesPage>) =>
  (fixture.componentInstance as unknown as { cogItems: () => MenuItem[] })
    .cogItems()
    .map((item) => ({ ...item, command: () => item.command?.() }));

const vo = (
  id: number,
  domainName: string,
  targetName: string,
  targetSeriesId: string,
  active = true,
) => ({
  id,
  domain: { domainId: `d${id}`, name: domainName },
  targetDomain: { domainId: `t${id}`, name: targetName },
  targetSeriesId,
  active,
});

describe('FiringSchedulesPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters on domain names OR raw target series id, case-insensitive', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/firing-schedules/all?lang=en')
      .flush([vo(1, 'Ankerland', 'Bøjeland', 'fa-nw'), vo(2, 'Fyrland', 'Ankerland', 'dk-nw')]);
    fixture.detectChanges();

    page.onSearch('BØJE');
    expect(page.filtered().length).toBe(1);
    expect(page.filtered()[0].targetSeriesId).toBe('fa-nw');

    page.onSearch('dk-nw');
    expect(page.filtered().length).toBe(1);

    page.onSearch('ankerland');
    expect(page.filtered().length).toBe(2); // matches source on row 1, target on row 2

    page.onSearch('');
    expect(page.filtered().length).toBe(2);
  });

  it('every filter change resets paging to the first page', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/firing-schedules/all?lang=en')
      .flush(Array.from({ length: 45 }, (_, i) => vo(i, `D${i}`, `T${i}`, `s${i}`)));
    fixture.detectChanges();

    page.pager.setFirst(40);
    expect(page.pager.first()).toBe(40);
    page.onSearch('D');
    expect(page.pager.first()).toBe(0);
  });

  it('shows the none-yet empty state for an empty list and the error banner on failure', () => {
    const { fixture, httpTesting } = setup();
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
  });

  it('shows the error banner when the list load fails', () => {
    const { fixture, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/firing-schedules/all?lang=en')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('the cog action fires the exercises PUT and reports the finished run when nothing failed', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() };
    const { fixture, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();

    cogItems(fixture)[0].command();

    const req = httpTesting.expectOne('/rest/firing-schedules/update-firing-exercises');
    expect(req.request.method).toBe('PUT');
    req.flush({ failedSchedules: 0, failedAreas: 0, failedCancellations: 0 });
    expect(notifications.success).toHaveBeenCalledWith('admin.schedules.toast.exercisesStarted');
    expect(notifications.warn).not.toHaveBeenCalled();
    expect(notifications.info).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('the cog action warns with the number of failed changes when the run answers failures', () => {
    // FIRING6: the run answers what failed; the page must not report a clean run.
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() };
    const { fixture, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();

    cogItems(fixture)[0].command();

    httpTesting
      .expectOne('/rest/firing-schedules/update-firing-exercises')
      .flush({ failedSchedules: 0, failedAreas: 2, failedCancellations: 1 });
    expect(notifications.warn).toHaveBeenCalledWith('schedule.toast.exercisesPartlyFailed', {
      count: 3,
    });
    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.info).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('the cog action is disabled while its request runs, and a second press sends nothing', () => {
    // FIRING7: a second press while the run is in flight must not start a second run.
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() };
    const { fixture, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();

    expect(cogItems(fixture)[0].disabled).toBeFalsy();
    cogItems(fixture)[0].command();
    expect(cogItems(fixture)[0].disabled).toBe(true);

    cogItems(fixture)[0].command();
    const req = httpTesting.expectOne('/rest/firing-schedules/update-firing-exercises');

    req.flush({ failedSchedules: 0, failedAreas: 0, failedCancellations: 0 });
    expect(cogItems(fixture)[0].disabled).toBe(false);
    expect(notifications.success).toHaveBeenCalledTimes(1);
  });

  it('the cog action is enabled again after its request fails', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() };
    const { fixture, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();

    cogItems(fixture)[0].command();
    expect(cogItems(fixture)[0].disabled).toBe(true);
    httpTesting
      .expectOne('/rest/firing-schedules/update-firing-exercises')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(cogItems(fixture)[0].disabled).toBe(false);
  });

  it('the cog action toasts an error when the exercises PUT fails', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() };
    const { fixture, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
    fixture.detectChanges();

    cogItems(fixture)[0].command();

    httpTesting
      .expectOne('/rest/firing-schedules/update-firing-exercises')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.schedules.toast.exercisesError');
    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('delete confirms, DELETEs the schedule, then refetches the list', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([vo(7, 'D', 'T', 's7')]);
    fixture.detectChanges();

    page.deleteSchedule({ id: 7, active: true });

    const del = httpTesting.expectOne('/rest/firing-schedules/firing-schedule/7');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    TestBed.tick();
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([]);
  });

  it('delete toasts the delete-specific error when the DELETE fails', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { fixture, page, httpTesting } = setup(notifications);
    httpTesting.expectOne('/rest/firing-schedules/all?lang=en').flush([vo(7, 'D', 'T', 's7')]);
    fixture.detectChanges();

    page.deleteSchedule({ id: 7, active: true });

    httpTesting
      .expectOne('/rest/firing-schedules/firing-schedule/7')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.schedules.toast.deleteError');
    expect(notifications.success).not.toHaveBeenCalled();
  });
});
