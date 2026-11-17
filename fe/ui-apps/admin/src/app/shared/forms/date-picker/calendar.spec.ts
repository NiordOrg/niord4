import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Calendar } from './calendar';

function setup(locale = 'en', selected: Date | null = new Date(2026, 4, 14)) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal(locale), selectedLanguage: signal(locale) },
      },
    ],
  });
  const fixture = TestBed.createComponent(Calendar);
  fixture.componentRef.setInput('selected', selected);
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

function days(el: HTMLElement): HTMLButtonElement[] {
  return Array.from(el.querySelectorAll<HTMLButtonElement>('.calendar-day'));
}

function header(el: HTMLElement): string {
  return el.querySelector('[aria-live="polite"]')?.textContent?.trim() ?? '';
}

function headerButton(el: HTMLElement): HTMLButtonElement {
  return el.querySelector<HTMLButtonElement>('[aria-live="polite"]')!;
}

function yearCells(el: HTMLElement): HTMLButtonElement[] {
  return Array.from(el.querySelectorAll<HTMLButtonElement>('.calendar-year'));
}

/** The single roving-tabindex year cell (the keyboard-focused one). */
function focusedYear(el: HTMLElement): string {
  return (
    el.querySelector<HTMLButtonElement>('.calendar-year[tabindex="0"]')?.textContent?.trim() ?? ''
  );
}

function pressYearKey(el: HTMLElement, key: string): void {
  const focused = el.querySelector<HTMLButtonElement>('.calendar-year[tabindex="0"]')!;
  focused.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
}

describe('Calendar', () => {
  it('renders 7 weekday headers and a 6×7 day grid', () => {
    const { el } = setup();
    expect(el.querySelectorAll('[role="columnheader"]')).toHaveLength(7);
    expect(days(el)).toHaveLength(42);
  });

  it('opens on the selected month (English)', () => {
    const { el } = setup('en', new Date(2026, 4, 14));
    expect(header(el)).toMatch(/May 2026/);
  });

  it('localizes the month header (Danish)', () => {
    const { el } = setup('da', new Date(2026, 4, 14));
    expect(header(el).toLowerCase()).toContain('maj');
  });

  it('emits the clicked in-month day', () => {
    const { comp, el } = setup('en', new Date(2026, 4, 14));
    const emitted: Date[] = [];
    comp.dateSelected.subscribe((d) => emitted.push(d));
    const twenty = days(el).find(
      (b) => b.textContent?.trim() === '20' && !b.classList.contains('is-outside'),
    );
    twenty!.click();
    expect(emitted).toHaveLength(1);
    expect(emitted[0].getMonth()).toBe(4);
    expect(emitted[0].getDate()).toBe(20);
  });

  it('marks the selected day', () => {
    const { el } = setup('en', new Date(2026, 4, 14));
    const selected = el.querySelectorAll('.calendar-day.is-selected');
    expect(selected).toHaveLength(1);
    expect(selected[0].textContent?.trim()).toBe('14');
  });

  it('navigates to the next month via the nav button', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    const navButtons = el.querySelectorAll<HTMLButtonElement>('.icon-button');
    navButtons[1].click(); // next
    fixture.detectChanges();
    expect(header(el)).toMatch(/June 2026/);
  });

  it('opens the year grid from the header and jumps to a distant year', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    const cells = yearCells(el);
    expect(cells).toHaveLength(12);
    // The 12-year page containing 2026 runs 2016–2027.
    expect(cells[0].textContent?.trim()).toBe('2016');
    expect(header(el)).toBe('2016 – 2027');
    cells.find((b) => b.textContent?.trim() === '2017')!.click();
    fixture.detectChanges();
    // Back on the day grid, same month, in 2017.
    expect(days(el)).toHaveLength(42);
    expect(header(el)).toMatch(/May 2017/);
  });

  it('pages the year grid by 12 years', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    const nav = el.querySelectorAll<HTMLButtonElement>('.icon-button');
    nav[0].click(); // previous page of years
    fixture.detectChanges();
    expect(yearCells(el)[0].textContent?.trim()).toBe('2004');
  });

  it('disables years beyond the max bound', () => {
    const { fixture, comp, el } = setup('en', new Date(2026, 4, 14));
    fixture.componentRef.setInput('max', new Date(2026, 11, 31));
    headerButton(el).click();
    fixture.detectChanges();
    const y2027 = yearCells(el).find((b) => b.textContent?.trim() === '2027');
    expect(y2027!.disabled).toBe(true);
    expect(comp).toBeTruthy();
  });

  it('gives the year grid a single roving tab stop on the focused year', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    expect(yearCells(el).filter((b) => b.getAttribute('tabindex') === '0')).toHaveLength(1);
    expect(focusedYear(el)).toBe('2026');
  });

  it('moves year focus with the arrow keys within the page', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    pressYearKey(el, 'ArrowUp'); // 2026 − 3 (one row up)
    fixture.detectChanges();
    expect(focusedYear(el)).toBe('2023');
    expect(header(el)).toBe('2016 – 2027');
    pressYearKey(el, 'ArrowRight'); // 2023 + 1
    fixture.detectChanges();
    expect(focusedYear(el)).toBe('2024');
  });

  it('jumps to the page ends with Home and End', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    pressYearKey(el, 'End');
    fixture.detectChanges();
    expect(focusedYear(el)).toBe('2027');
    pressYearKey(el, 'Home');
    fixture.detectChanges();
    expect(focusedYear(el)).toBe('2016');
  });

  it('re-pages the year grid when arrowing past the edge', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    pressYearKey(el, 'ArrowDown'); // 2026 + 3 = 2029 → next 12-year page
    fixture.detectChanges();
    expect(header(el)).toBe('2028 – 2039');
    expect(focusedYear(el)).toBe('2029');
  });

  it('selects the focused year with Enter and returns to the day grid', () => {
    const { fixture, el } = setup('en', new Date(2026, 4, 14));
    headerButton(el).click();
    fixture.detectChanges();
    pressYearKey(el, 'ArrowUp'); // focus 2023
    fixture.detectChanges();
    pressYearKey(el, 'Enter');
    fixture.detectChanges();
    // Back on the day grid, same month, in 2023.
    expect(days(el)).toHaveLength(42);
    expect(header(el)).toMatch(/May 2023/);
  });

  describe('[today]', () => {
    it('marks the day the parent calls today, not the one the runner is on', () => {
      const { fixture, el } = setup('en', new Date(2026, 4, 14));
      fixture.componentRef.setInput('today', new Date(2026, 4, 20));
      fixture.detectChanges();

      const marked = days(el).filter((d) => d.classList.contains('is-today'));
      expect(marked).toHaveLength(1);
      expect(marked[0].textContent?.trim()).toBe('20');
      expect(marked[0].getAttribute('aria-current')).toBe('date');
    });

    it('opens on the given today when nothing is selected', () => {
      const { fixture, el } = setup('en', null);
      fixture.componentRef.setInput('today', new Date(2017, 0, 9));
      fixture.detectChanges();
      expect(header(el)).toMatch(/January 2017/);
    });
  });
});
