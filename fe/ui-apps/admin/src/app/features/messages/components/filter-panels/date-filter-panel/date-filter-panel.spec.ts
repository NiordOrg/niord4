import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';

import { DateFilterPanel } from './date-filter-panel';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { MessagesFacade } from '../../../services/messages-facade.service';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { wallClockIn } from '../../../../../shared/testing/zone-testing';

interface DateSlice {
  fromDate?: number;
  toDate?: number;
  dateType: 'PUBLISH_DATE' | 'EVENT_DATE' | 'CREATED_DATE' | 'UPDATED_DATE' | 'PUBLISH_FROM_DATE';
}

/** The picker's own selection entry point — the panel is fed by it, never by a raw Date. */
interface PickerInternals {
  onDateSelected: (date: Date) => void;
  clear: (event: MouseEvent) => void;
}

function setup(opts?: { timeZone?: string }) {
  const dateSlice = signal<DateSlice>({ dateType: 'PUBLISH_DATE' });
  const setSliceValue = vi.fn((...args: ['date', DateSlice]) => dateSlice.set(args[1]));
  const clearFilter = vi.fn(() => dateSlice.set({ dateType: 'PUBLISH_DATE' }));

  const filter = {
    slice: (name: 'date') => {
      if (name !== 'date') throw new Error(`unexpected slice ${name}`);
      return dateSlice;
    },
    setSliceValue,
    clearFilter,
  };

  const sessionDomain = signal<Domain | undefined>(
    opts?.timeZone ? ({ domainId: 'test', timeZone: opts.timeZone } as Domain) : undefined,
  );

  TestBed.configureTestingModule({
    providers: [
      { provide: MessagesFacade, useValue: { filter } },
      { provide: DomainService, useValue: { sessionDomain } },
      provideTestTransloco(),
    ],
  });

  const fixture = TestBed.createComponent(DateFilterPanel);
  fixture.detectChanges();
  TestBed.tick();
  const [from, to] = fixture.debugElement
    .queryAll(By.directive(DatePicker))
    .map((el) => el.componentInstance as unknown as PickerInternals);
  return {
    fixture,
    comp: fixture.componentInstance,
    dateSlice,
    setSliceValue,
    sessionDomain,
    fromPicker: from,
    toPicker: to,
  };
}

/** A click on the calendar's 14 May cell: the grid always hands back a browser-local day. */
function clickMay14(): Date {
  return new Date(2026, 4, 14);
}

describe('DateFilterPanel', () => {
  describe('the picked day is bounded in the session domain zone', () => {
    // A domain west of the browser is the case that used to slip: reading the picker's
    // browser-local midnight back out by the domain's calendar landed on 13 May, a day the
    // user never clicked. The assertion states the wall clock Intl reports rather than an
    // offset, so it holds whatever the zone's rules.
    it('stores the clicked day, not the day before it, for a westward zone', () => {
      const { fromPicker, setSliceValue } = setup({ timeZone: 'America/Halifax' });

      fromPicker.onDateSelected(clickMay14());

      const stored = setSliceValue.mock.calls[0][1];
      expect(wallClockIn('America/Halifax', new Date(stored.fromDate!))).toBe(
        '2026-05-14 00:00:00',
      );
    });

    it('interprets the picked day as midnight in a fixed-offset zone (UTC)', () => {
      const { fromPicker, setSliceValue } = setup({ timeZone: 'UTC' });

      fromPicker.onDateSelected(clickMay14());

      const stored = setSliceValue.mock.calls[0][1];
      expect(new Date(stored.fromDate!).toISOString()).toBe('2026-05-14T00:00:00.000Z');
    });

    it('interprets the picked day in Copenhagen (summer DST, UTC+2)', () => {
      const { fromPicker, setSliceValue } = setup({ timeZone: 'Europe/Copenhagen' });

      fromPicker.onDateSelected(clickMay14());

      const stored = setSliceValue.mock.calls[0][1];
      expect(new Date(stored.fromDate!).toISOString()).toBe('2026-05-13T22:00:00.000Z');
    });

    it('works for an east-of-browser zone (Tokyo, UTC+9)', () => {
      const { fromPicker, setSliceValue } = setup({ timeZone: 'Asia/Tokyo' });

      fromPicker.onDateSelected(clickMay14());

      const stored = setSliceValue.mock.calls[0][1];
      expect(new Date(stored.fromDate!).toISOString()).toBe('2026-05-13T15:00:00.000Z');
    });

    it('runs the upper bound to the last millisecond of the picked day', () => {
      const { toPicker, setSliceValue } = setup({ timeZone: 'UTC' });

      toPicker.onDateSelected(clickMay14());

      const stored = setSliceValue.mock.calls[0][1];
      // Inclusive: a mail stamped at 23:59:59.500 on 14 May is inside the range asked for.
      expect(new Date(stored.toDate!).toISOString()).toBe('2026-05-14T23:59:59.999Z');
    });

    it('re-displays the stored instant on the day the domain reads it on', () => {
      const { comp, fromPicker, toPicker } = setup({ timeZone: 'America/Halifax' });

      fromPicker.onDateSelected(clickMay14());
      toPicker.onDateSelected(clickMay14());

      expect(wallClockIn('America/Halifax', comp.fromDate()!)).toBe('2026-05-14 00:00:00');
      expect(wallClockIn('America/Halifax', comp.toDate()!)).toBe('2026-05-14 23:59:59');
    });
  });

  it('disables both pickers when no session domain names a zone', () => {
    const { fixture } = setup();

    const host = fixture.nativeElement as HTMLElement;
    const triggers = host.querySelectorAll<HTMLButtonElement>('button[aria-haspopup="dialog"]');
    expect(triggers.length).toBe(2);
    for (const trigger of triggers) {
      expect(trigger.disabled).toBe(true);
      expect(trigger.getAttribute('aria-disabled')).toBe('true');
    }
    expect(host.querySelectorAll('[title="common.dateTime.noTimeZone"]').length).toBe(2);
  });

  it('clearing a bound stores undefined', () => {
    const { comp, setSliceValue } = setup({ timeZone: 'America/Halifax' });

    comp.onFromDateChange(null);

    expect(setSliceValue.mock.calls[0][1].fromDate).toBeUndefined();
  });
});
