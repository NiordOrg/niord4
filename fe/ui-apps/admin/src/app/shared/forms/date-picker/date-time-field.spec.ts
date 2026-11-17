import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { getZonedDateParts } from '../../utils/zoned-date';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../testing/zone-testing';
import { DateTimeField } from './date-time-field';

interface Handlers {
  onDateChange(date: Date | null): void;
  onTimeChange(time: Date | null): void;
}

interface SetupOptions {
  readonly value?: Date | null;
  /** The session domain's zone; omitted means "no domain yet". */
  readonly sessionZone?: string;
  readonly local?: boolean;
  /** 'da' gives a 24-hour time column, which makes an hour pick unambiguous. */
  readonly locale?: string;
}

function setup(options: SetupOptions = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideTestSessionZone(options.sessionZone),
      {
        provide: LanguageService,
        useValue: {
          activeLocale: signal(options.locale ?? 'en'),
          selectedLanguage: signal(options.locale ?? 'en'),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(DateTimeField);
  fixture.componentRef.setInput('value', options.value ?? null);
  if (options.local) fixture.componentRef.setInput('local', true);
  fixture.autoDetectChanges();
  const comp = fixture.componentInstance;
  return {
    fixture,
    comp,
    handlers: comp as unknown as Handlers,
    el: fixture.nativeElement as HTMLElement,
  };
}

/** The date trigger is the first button in the field, the time trigger the second. */
function triggers(el: HTMLElement): { date: HTMLButtonElement; time: HTMLButtonElement } {
  const buttons = Array.from(el.querySelectorAll('button'));
  return { date: buttons[0], time: buttons[1] };
}

function day(text: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
    (b) => b.textContent?.trim() === text && !b.classList.contains('is-outside'),
  );
}

/** Hours and minutes are separate columns that both hold e.g. "12" — pick by column. */
function timeOption(column: 'hour' | 'minute', text: string): HTMLButtonElement | undefined {
  const columns = Array.from(document.querySelectorAll<HTMLElement>('.time-col'));
  const target = columns[column === 'hour' ? 0 : 1];
  return Array.from(target.querySelectorAll<HTMLButtonElement>('.time-option')).find(
    (b) => b.textContent?.trim() === text,
  );
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('DateTimeField', () => {
  describe('local mode — a browser wall-clock carrier', () => {
    it('merges a new date while keeping the existing time', () => {
      const { comp, handlers } = setup({ value: new Date(2026, 4, 14, 9, 30), local: true });
      handlers.onDateChange(new Date(2026, 5, 1)); // 1 Jun, midnight
      const v = comp.value()!;
      expect(v.getMonth()).toBe(5);
      expect(v.getDate()).toBe(1);
      expect(v.getHours()).toBe(9);
      expect(v.getMinutes()).toBe(30);
    });

    it('merges a new time while keeping the existing date', () => {
      const { comp, handlers } = setup({ value: new Date(2026, 4, 14, 9, 30), local: true });
      const time = new Date();
      time.setHours(18, 45, 0, 0);
      handlers.onTimeChange(time);
      const v = comp.value()!;
      expect(v.getFullYear()).toBe(2026);
      expect(v.getMonth()).toBe(4);
      expect(v.getDate()).toBe(14);
      expect(v.getHours()).toBe(18);
      expect(v.getMinutes()).toBe(45);
    });

    it('clears the value when the date is cleared', () => {
      const { comp, handlers } = setup({ value: new Date(2026, 4, 14, 9, 30), local: true });
      handlers.onDateChange(null);
      expect(comp.value()).toBeNull();
    });

    it('defaults an unset date to midnight when a date first arrives', () => {
      const { comp, handlers } = setup({ local: true });
      handlers.onDateChange(new Date(2026, 4, 14));
      const v = comp.value()!;
      expect(v.getDate()).toBe(14);
      expect(v.getHours()).toBe(0);
    });

    it('seeds the first date with the current time when defaultToCurrentTime is set', () => {
      const { fixture, comp, handlers } = setup({ local: true });
      fixture.componentRef.setInput('defaultToCurrentTime', true);
      fixture.detectChanges();
      const now = new Date();
      handlers.onDateChange(new Date(2026, 4, 14));
      const v = comp.value()!;
      expect(v.getDate()).toBe(14);
      // Seeded from the wall clock rather than pinned to 00:00.
      expect(v.getHours()).toBe(now.getHours());
      expect(v.getMinutes()).toBe(now.getMinutes());
      expect(v.getSeconds()).toBe(0);
    });

    it('picks a day through the calendar as browser-local midnight', async () => {
      const ctx = setup({ value: new Date(2026, 2, 15, 9, 30), local: true, locale: 'da' });
      await ctx.fixture.whenStable();
      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();

      const v = ctx.comp.value()!;
      expect([v.getFullYear(), v.getMonth(), v.getDate()]).toEqual([2026, 2, 5]);
      expect([v.getHours(), v.getMinutes()]).toEqual([9, 30]);
    });
  });

  describe('the session time zone', () => {
    it('stores the day and clock the user picked as the zone reads them', async () => {
      // The grid opens on March: 2026-03-15T12:00:00Z is 15 March 21:00 in Tokyo.
      const ctx = setup({
        value: new Date('2026-03-15T12:00:00Z'),
        sessionZone: 'Asia/Tokyo',
        locale: 'da',
      });
      await ctx.fixture.whenStable();

      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();

      triggers(ctx.el).time.click();
      await ctx.fixture.whenStable();
      timeOption('hour', '12')!.click();
      await ctx.fixture.whenStable();
      timeOption('minute', '00')!.click();
      await ctx.fixture.whenStable();

      // 5 March 12:00 in Tokyo (UTC+9) → 03:00Z. Nothing of the runner's zone survives.
      expect(ctx.comp.value()!.toISOString()).toBe('2026-03-05T03:00:00.000Z');
    });

    it('stores the picked clock in a zone west of the runner', async () => {
      const zone = 'America/Halifax';
      const ctx = setup({
        value: new Date('2026-03-15T12:00:00Z'),
        sessionZone: zone,
        locale: 'da',
      });
      await ctx.fixture.whenStable();

      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();

      triggers(ctx.el).time.click();
      await ctx.fixture.whenStable();
      timeOption('hour', '12')!.click();
      await ctx.fixture.whenStable();
      timeOption('minute', '00')!.click();
      await ctx.fixture.whenStable();

      expect(wallClockIn(zone, ctx.comp.value()!)).toBe('2026-03-05 12:00:00');
    });

    it('shows an incoming instant as the zone reads it', async () => {
      // 2026-03-15T23:30:00Z is 16 March 08:30 in Tokyo — a different day AND clock
      // from the one a browser east or west of it would show.
      const ctx = setup({
        value: new Date('2026-03-15T23:30:00Z'),
        sessionZone: 'Asia/Tokyo',
        locale: 'da',
      });
      await ctx.fixture.whenStable();

      const { date, time } = triggers(ctx.el);
      expect(date.textContent).toMatch(/16\. mar\. 2026/);
      expect(time.textContent).toContain('08.30');
    });

    it('keeps the day when only the time changes, across the zone boundary', async () => {
      // 23:00 in Tokyo on 5 March is still 5 March there, though 14:00Z is the 5th in
      // the runner's zone too — the day must come from Tokyo, not from either clock.
      const ctx = setup({
        value: new Date('2026-03-05T03:00:00Z'), // 5 March 12:00 Tokyo
        sessionZone: 'Asia/Tokyo',
        locale: 'da',
      });
      await ctx.fixture.whenStable();

      triggers(ctx.el).time.click();
      await ctx.fixture.whenStable();
      timeOption('hour', '23')!.click();
      await ctx.fixture.whenStable();

      expect(ctx.comp.value()!.toISOString()).toBe('2026-03-05T14:00:00.000Z');
      expect(wallClockIn('Asia/Tokyo', ctx.comp.value()!)).toBe('2026-03-05 23:00:00');
    });

    it('seeds a first date at the zone midnight', async () => {
      const ctx = setup({ sessionZone: 'Asia/Tokyo', locale: 'da' });
      await ctx.fixture.whenStable();
      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();

      const cell = day('5')!;
      cell.click();
      await ctx.fixture.whenStable();

      expect(wallClockIn('Asia/Tokyo', ctx.comp.value()!)).toMatch(/-05 00:00:00$/);
    });

    it('seeds defaultToCurrentTime from the zone clock, not the browser clock', async () => {
      const zone = 'Asia/Tokyo';
      const ctx = setup({ sessionZone: zone, locale: 'da' });
      ctx.fixture.componentRef.setInput('defaultToCurrentTime', true);
      await ctx.fixture.whenStable();

      const expected = getZonedDateParts(new Date(), zone);
      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();

      const stored = getZonedDateParts(ctx.comp.value()!, zone);
      expect(stored.day).toBe(5);
      expect(stored.hour).toBe(expected.hour);
      expect(stored.minute).toBe(expected.minute);
    });

    it('an explicit [timeZone] overrides the session zone', async () => {
      const ctx = setup({
        value: new Date('2026-03-15T12:00:00Z'),
        sessionZone: 'Asia/Tokyo',
        locale: 'da',
      });
      ctx.fixture.componentRef.setInput('timeZone', 'UTC');
      await ctx.fixture.whenStable();

      triggers(ctx.el).date.click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();
      triggers(ctx.el).time.click();
      await ctx.fixture.whenStable();
      timeOption('hour', '12')!.click();
      await ctx.fixture.whenStable();
      timeOption('minute', '00')!.click();
      await ctx.fixture.whenStable();

      expect(ctx.comp.value()!.toISOString()).toBe('2026-03-05T12:00:00.000Z');
    });
  });

  describe('with no time zone to work in', () => {
    it('disables both halves rather than storing a guessed instant', async () => {
      const ctx = setup({ value: new Date('2026-03-15T12:00:00Z') });
      await ctx.fixture.whenStable();

      const { date, time } = triggers(ctx.el);
      expect(date.disabled).toBe(true);
      expect(time.disabled).toBe(true);
      // The stub loader renders keys verbatim.
      expect(ctx.el.querySelector('div')?.getAttribute('title')).toBe('common.dateTime.noTimeZone');
      expect(ctx.el.querySelector('div')?.getAttribute('aria-disabled')).toBe('true');
    });
  });
});
