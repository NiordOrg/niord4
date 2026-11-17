import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import en from '../../../../../../../languages/en/i18n/admin/en.json';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../testing/zone-testing';
import { DatePicker } from './date-picker';

interface SetupOptions {
  readonly value?: Date | null;
  /** The session domain's zone; omitted means "no domain yet". */
  readonly sessionZone?: string;
  /** An explicit `[timeZone]`, overriding the session zone. */
  readonly timeZone?: string;
  readonly local?: boolean;
  /** Seed the real English bundle; without it the stub loader renders keys. */
  readonly english?: boolean;
}

function setup(options: SetupOptions = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideTestSessionZone(options.sessionZone),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal('en'), selectedLanguage: signal('en') },
      },
    ],
  });
  if (options.english) TestBed.inject(TranslocoService).setTranslation(en, 'en');
  const fixture = TestBed.createComponent(DatePicker);
  fixture.componentRef.setInput('value', options.value ?? null);
  if (options.timeZone) fixture.componentRef.setInput('timeZone', options.timeZone);
  if (options.local) fixture.componentRef.setInput('local', true);
  fixture.autoDetectChanges();
  return { fixture, comp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

function overlayDays(): HTMLButtonElement[] {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day'));
}

function day(text: string): HTMLButtonElement | undefined {
  return overlayDays().find(
    (b) => b.textContent?.trim() === text && !b.classList.contains('is-outside'),
  );
}

// The calendar portals to the body-level cdk-overlay-container; clear it between tests.
afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('DatePicker', () => {
  describe('local mode — a browser wall-clock carrier', () => {
    it('shows the formatted value in the trigger', async () => {
      const ctx = setup({ value: new Date(2026, 4, 14), local: true });
      await ctx.fixture.whenStable();
      expect(ctx.el.querySelector('button')?.textContent).toMatch(/May/);
    });

    it('shows the placeholder when empty', async () => {
      const ctx = setup({ local: true });
      ctx.fixture.componentRef.setInput('placeholder', 'Pick a date');
      await ctx.fixture.whenStable();
      expect(ctx.el.querySelector('button')?.textContent).toContain('Pick a date');
    });

    it('opens the calendar, and selecting a day sets the value + closes', async () => {
      const ctx = setup({ value: new Date(2026, 4, 14), local: true });
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();

      const cell = day('20');
      expect(cell).toBeTruthy();
      cell!.click();
      await ctx.fixture.whenStable();

      // Browser-local midnight of the clicked day — no conversion anywhere.
      const value = ctx.comp.value()!;
      expect([value.getFullYear(), value.getMonth(), value.getDate()]).toEqual([2026, 4, 20]);
      expect([value.getHours(), value.getMinutes(), value.getSeconds()]).toEqual([0, 0, 0]);
      expect(overlayDays()).toHaveLength(0); // closed
    });

    it('clears the value via the clear button', async () => {
      const ctx = setup({ value: new Date(2026, 4, 14), local: true });
      ctx.fixture.componentRef.setInput('showClear', true);
      ctx.fixture.componentRef.setInput('clearLabel', 'Clear');
      await ctx.fixture.whenStable();
      const clear = Array.from(ctx.el.querySelectorAll('button')).find(
        (b) => b.getAttribute('aria-label') === 'Clear',
      );
      (clear as HTMLButtonElement).click();
      await ctx.fixture.whenStable();
      expect(ctx.comp.value()).toBeNull();
    });

    it('stays enabled with no session domain — it needs no zone', async () => {
      const ctx = setup({ local: true });
      await ctx.fixture.whenStable();
      expect((ctx.el.querySelector('button') as HTMLButtonElement).disabled).toBe(false);
    });
  });

  describe('the session time zone', () => {
    it('emits the clicked day as that day 00:00 in the session zone', async () => {
      // 2026-03-15T12:00:00Z is 15 March 21:00 in Tokyo, so the grid opens on March.
      const ctx = setup({
        value: new Date('2026-03-15T12:00:00Z'),
        sessionZone: 'Asia/Tokyo',
      });
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();

      day('5')!.click();
      await ctx.fixture.whenStable();

      // 5 March 00:00 Tokyo (UTC+9) → 4 March 15:00Z.
      expect(ctx.comp.value()!.toISOString()).toBe('2026-03-04T15:00:00.000Z');
    });

    it('keeps the clicked day in a zone west of the runner', async () => {
      const zone = 'America/Halifax';
      const ctx = setup({ value: new Date('2026-03-15T12:00:00Z'), sessionZone: zone });
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();

      day('5')!.click();
      await ctx.fixture.whenStable();

      expect(wallClockIn(zone, ctx.comp.value()!)).toBe('2026-03-05 00:00:00');
    });

    it('shows and highlights the day the zone is on, not the browser', async () => {
      // 2026-03-15T23:30:00Z is already 16 March 08:30 in Tokyo.
      const ctx = setup({
        value: new Date('2026-03-15T23:30:00Z'),
        sessionZone: 'Asia/Tokyo',
      });
      await ctx.fixture.whenStable();
      expect(ctx.el.querySelector('button')?.textContent).toMatch(/Mar 16, 2026/);

      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();
      const selected = overlayDays().find((b) => b.classList.contains('is-selected'));
      expect(selected?.textContent?.trim()).toBe('16');
    });

    it('lets an explicit [timeZone] override the session zone', async () => {
      const ctx = setup({
        value: new Date('2026-03-15T12:00:00Z'),
        sessionZone: 'Asia/Tokyo',
        timeZone: 'UTC',
      });
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();

      day('5')!.click();
      await ctx.fixture.whenStable();
      expect(ctx.comp.value()!.toISOString()).toBe('2026-03-05T00:00:00.000Z');
    });

    it('round-trips a picked day: what is emitted is what is highlighted next time', async () => {
      const zone = 'Asia/Tokyo';
      const ctx = setup({ value: new Date('2026-03-15T12:00:00Z'), sessionZone: zone });
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();
      day('5')!.click();
      await ctx.fixture.whenStable();

      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();
      const selected = overlayDays().find((b) => b.classList.contains('is-selected'));
      expect(selected?.textContent?.trim()).toBe('5');
    });
  });

  describe('with no time zone to work in', () => {
    it('disables itself rather than standing in the browser zone', async () => {
      const ctx = setup({ value: new Date('2026-03-15T12:00:00Z') });
      await ctx.fixture.whenStable();

      const trigger = ctx.el.querySelector('button') as HTMLButtonElement;
      expect(trigger.disabled).toBe(true);
      expect(trigger.getAttribute('aria-disabled')).toBe('true');
      // The stub loader renders keys verbatim.
      expect(ctx.el.querySelector('div')?.getAttribute('title')).toBe('common.dateTime.noTimeZone');
    });

    it('names both causes in English: no domain chosen, or a domain without a zone', async () => {
      const ctx = setup({ english: true });
      await ctx.fixture.whenStable();

      expect(ctx.el.querySelector('div')?.getAttribute('title')).toBe(
        'No time zone: choose a domain, or ask a sysadmin to give this domain a time zone',
      );
    });

    it('does not open the calendar when clicked', async () => {
      const ctx = setup();
      await ctx.fixture.whenStable();
      (ctx.el.querySelector('button') as HTMLButtonElement).click();
      await ctx.fixture.whenStable();
      expect(overlayDays()).toHaveLength(0);
    });

    it('carries no title once a zone is available', async () => {
      const ctx = setup({ sessionZone: 'UTC' });
      await ctx.fixture.whenStable();
      expect(ctx.el.querySelector('div')?.getAttribute('title')).toBeNull();
      expect((ctx.el.querySelector('button') as HTMLButtonElement).disabled).toBe(false);
    });
  });
});
