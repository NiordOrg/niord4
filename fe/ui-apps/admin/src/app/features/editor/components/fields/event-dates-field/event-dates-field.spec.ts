import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import type { Message } from '../../../../messages/models/message.model';
import { MessageDateFormatterService } from '../../../../messages/services/message-date-formatter.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../../shared/testing/zone-testing';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EventDatesField } from './event-dates-field';

/** The session domain's zone for every case here: UTC+5:30, no DST. */
const DOMAIN_ZONE = 'Asia/Kolkata';

class StubFacade {
  readonly editableNow = signal(true);
  readonly message = signal<Message>({
    id: 'm',
    parts: [
      {
        type: 'DETAILS',
        eventDates: [
          {
            allDay: true,
            fromDate: '2026-06-10T00:00:00.000Z',
            toDate: '2026-06-10T23:59:59.999Z',
          },
          { allDay: false, fromDate: '2026-06-11T09:00:00.000Z', toDate: undefined },
        ],
      },
    ],
  });
  setPartEventDate = vi.fn();
  addPartEventDate = vi.fn();
  removePartEventDate = vi.fn();
  duplicatePartEventDate = vi.fn();
}

class StubFields {
  readonly expanded = signal(true);
  isExpanded = vi.fn(() => this.expanded());
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
}

@Component({
  imports: [EventDatesField],
  template: `<app-event-dates-field [partIndex]="0" />`,
})
class HostComponent {}

function setup() {
  const facade = new StubFacade();
  const fields = new StubFields();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: MessageDateFormatterService, useValue: { formatDateInterval: () => 'INTERVAL' } },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
      // A half-hour offset the runner cannot share: an emitted instant landing on :30 can
      // only have come from resolving the picked day in the domain's zone.
      provideTestSessionZone(DOMAIN_ZONE),
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const byAria = (key: string) => el.querySelector<HTMLButtonElement>(`[aria-label="${key}"]`)!;
  return { fixture, facade, fields, el, byAria };
}

/** The day cell for `text` in the open calendar overlay (skipping the adjacent months' cells). */
function calendarDay(text: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
    (cell) => cell.textContent?.trim() === text && !cell.classList.contains('is-outside'),
  );
}

// The calendar portals to the body-level cdk-overlay-container; clear it between tests.
afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('EventDatesField', () => {
  it('renders a date-only picker for an all-day row and a date+time field for a timed row', async () => {
    const { fixture, el } = setup();
    await fixture.whenStable();
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    // Row 0 (all-day) → two date-only pickers, no date+time fields.
    expect(rows[0].querySelectorAll('app-date-picker').length).toBe(2);
    expect(rows[0].querySelectorAll('app-date-time-field').length).toBe(0);
    // Row 1 (timed) → two date+time fields (each nests its own date picker, hence not counted here).
    expect(rows[1].querySelectorAll('app-date-time-field').length).toBe(2);
  });

  it("an all-day row emits the domain zone's midnight for the picked day", async () => {
    const { fixture, facade, el } = setup();
    await fixture.whenStable();
    const rows = el.querySelectorAll('tbody tr');
    const trigger = rows[0].querySelectorAll('app-date-picker')[0].querySelector('button')!;
    trigger.click();
    await fixture.whenStable();

    calendarDay('5')!.click();
    await fixture.whenStable();

    // 5 June 00:00 in Kolkata (UTC+5:30) is 4 June 18:30Z — the facade then snaps the
    // interval to that same domain day.
    expect(facade.setPartEventDate).toHaveBeenCalledWith(0, 0, {
      fromDate: '2026-06-04T18:30:00.000Z',
    });
  });

  it("a timed row keeps the domain zone's wall clock when only the day is repicked", async () => {
    const { fixture, facade, el } = setup();
    await fixture.whenStable();
    const rows = el.querySelectorAll('tbody tr');
    // Row 1 holds 2026-06-11T09:00:00Z — 14:30 on 11 June in Kolkata.
    const trigger = rows[1]
      .querySelectorAll('app-date-time-field')[0]
      .querySelector<HTMLButtonElement>('app-date-picker button')!;
    trigger.click();
    await fixture.whenStable();

    calendarDay('5')!.click();
    await fixture.whenStable();

    // Still 14:30 in Kolkata, now on 5 June.
    expect(facade.setPartEventDate).toHaveBeenCalledWith(0, 1, {
      fromDate: '2026-06-05T09:00:00.000Z',
    });
  });

  it('toggles all-day off through the facade (row 0 was all-day)', async () => {
    const { fixture, facade, el } = setup();
    await fixture.whenStable();
    const box = el.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    box.checked = false;
    box.dispatchEvent(new Event('change', { bubbles: true }));
    expect(facade.setPartEventDate).toHaveBeenCalledWith(0, 0, { allDay: false });
  });

  it('wires the row actions to the facade interval mutators', async () => {
    const { fixture, facade, byAria } = setup();
    await fixture.whenStable();

    byAria('editor.field.eventDates.duplicate').dispatchEvent(new MouseEvent('click'));
    expect(facade.duplicatePartEventDate).toHaveBeenCalledWith(0, 0);

    byAria('editor.field.eventDates.duplicateNextDay').dispatchEvent(new MouseEvent('click'));
    expect(facade.duplicatePartEventDate).toHaveBeenCalledWith(0, 0, 1);

    byAria('editor.field.eventDates.remove').dispatchEvent(new MouseEvent('click'));
    expect(facade.removePartEventDate).toHaveBeenCalledWith(0, 0);
  });

  it('appends an interval via the facade on Add', async () => {
    const { fixture, facade, el } = setup();
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('button.border-dashed')!.dispatchEvent(
      new MouseEvent('click'),
    );
    expect(facade.addPartEventDate).toHaveBeenCalledWith(0);
  });

  it('summarizes the collapsed viewer as the first interval phrase + a "(more…)" suffix', async () => {
    // Two intervals → the first phrase plus the localized more-suffix (the whole table stays hidden).
    const { fixture, fields, el } = setup();
    fields.expanded.set(false);
    await fixture.whenStable();
    const collapsed = el.querySelector('.ed-row-val')!;
    expect(collapsed.textContent).toContain('INTERVAL');
    expect(collapsed.textContent).toContain('editor.row.more');
    // Only the FIRST phrase is shown, not one span per interval.
    const phrases = Array.from(el.querySelectorAll('.ed-row-val')).flatMap(
      (cell) => (cell.textContent ?? '').match(/INTERVAL/g) ?? [],
    );
    expect(phrases.length).toBe(1);
  });

  it('omits the "(more…)" suffix when the part has a single interval', async () => {
    const { fixture, facade, fields, el } = setup();
    facade.message.set({
      id: 'm',
      parts: [
        {
          type: 'DETAILS',
          eventDates: [
            {
              allDay: true,
              fromDate: '2026-06-10T00:00:00.000Z',
              toDate: '2026-06-10T23:59:59.999Z',
            },
          ],
        },
      ],
    });
    fields.expanded.set(false);
    await fixture.whenStable();
    const collapsed = el.querySelector('.ed-row-val')!;
    expect(collapsed.textContent).toContain('INTERVAL');
    expect(collapsed.textContent).not.toContain('editor.row.more');
  });
});
