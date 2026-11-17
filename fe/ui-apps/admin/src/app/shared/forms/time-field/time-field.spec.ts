import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { TimeField } from './time-field';

function setup(locale = 'en', value: Date | null = null) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal(locale), selectedLanguage: signal(locale) },
      },
    ],
  });
  const fixture = TestBed.createComponent(TimeField);
  fixture.componentRef.setInput('value', value);
  fixture.autoDetectChanges();
  return { fixture, comp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

/** The picker portals to the body-level cdk-overlay-container; query it there. */
function options(): HTMLButtonElement[] {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.time-option'));
}
function optionText(text: string): HTMLButtonElement | undefined {
  return options().find((b) => b.textContent?.trim() === text);
}
function columns(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('.time-col'));
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('TimeField', () => {
  it('shows the value in 12h AM/PM for English', async () => {
    const { el, fixture } = setup('en', new Date(2026, 4, 14, 14, 30));
    await fixture.whenStable();
    const text = el.querySelector('button')!.textContent!;
    expect(text).toMatch(/2:30/);
    expect(text.toUpperCase()).toContain('PM');
  });

  it('shows the value in 24h for Danish', async () => {
    const { el, fixture } = setup('da', new Date(2026, 4, 14, 14, 30));
    await fixture.whenStable();
    const text = el.querySelector('button')!.textContent!;
    expect(text).toContain('14');
    expect(text.toUpperCase()).not.toContain('PM');
  });

  it('shows the placeholder when empty', async () => {
    const { el, fixture } = setup('da', null);
    await fixture.whenStable();
    expect(el.querySelector('button')!.textContent).toContain('HH.mm');
  });

  it('opens the popover and picks an hour + minute (24h), preserving the date part', async () => {
    const { el, comp, fixture } = setup('da', new Date(2026, 4, 14, 0, 0));
    await fixture.whenStable();
    el.querySelector('button')!.click(); // open
    await fixture.whenStable();

    optionText('14')!.click(); // hour column leads, so '14' is the hour
    await fixture.whenStable();
    expect(comp.value()!.getHours()).toBe(14);

    optionText('30')!.click(); // minute
    await fixture.whenStable();
    expect(comp.value()!.getMinutes()).toBe(30);
    expect(comp.value()!.getDate()).toBe(14);
  });

  it('maps 12h picks through the meridiem (PM + 2 → 14:00)', async () => {
    const { el, comp, fixture } = setup('en', new Date(2026, 4, 14, 0, 0));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();

    optionText('PM')!.click();
    await fixture.whenStable();
    optionText('2')!.click(); // 12h hour column
    await fixture.whenStable();
    expect(comp.value()!.getHours()).toBe(14);
  });

  it('maps 12 AM to 00:00', async () => {
    const { el, comp, fixture } = setup('en', new Date(2026, 4, 14, 6, 0));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();

    optionText('AM')!.click();
    await fixture.whenStable();
    optionText('12')!.click(); // hour column '12' precedes minute '12' in the DOM
    await fixture.whenStable();
    expect(comp.value()!.getHours()).toBe(0);
  });

  it('does not commit a value when AM/PM is toggled before any hour is chosen', async () => {
    const { el, comp, fixture } = setup('en', null);
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();
    optionText('PM')!.click();
    await fixture.whenStable();
    // No hour picked yet — the field must stay empty rather than commit an arbitrary 12:00 PM.
    expect(comp.value()).toBeNull();
    // Once an hour IS picked, the meridiem takes effect.
    optionText('2')!.click();
    await fixture.whenStable();
    expect(comp.value()!.getHours()).toBe(2); // AM default, no meridiem re-map happened
  });

  it('hides the AM/PM toggle in a 24h locale', async () => {
    const { el, fixture } = setup('da', new Date(2026, 4, 14, 9, 0));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();
    expect(optionText('AM')).toBeUndefined();
    expect(optionText('PM')).toBeUndefined();
  });

  it('exposes the columns as labelled button groups, not lying listboxes', async () => {
    const { el, fixture } = setup('da', new Date(2026, 4, 14, 14, 30));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();

    const cols = columns();
    expect(cols).toHaveLength(2); // hours + minutes (no AM/PM in a 24h locale)
    for (const col of cols) {
      expect(col.getAttribute('role')).toBe('group');
      expect(col.getAttribute('aria-label')).toBeTruthy();
    }
    // The over-promising listbox/option roles are gone — no keyboard nav is claimed.
    expect(document.querySelector('[role="listbox"]')).toBeNull();
    expect(document.querySelector('.time-option[role="option"]')).toBeNull();
  });

  it('marks the current hour and minute with aria-pressed', async () => {
    const { el, fixture } = setup('da', new Date(2026, 4, 14, 14, 30));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();

    expect(optionText('14')!.getAttribute('aria-pressed')).toBe('true'); // hour column leads
    expect(optionText('30')!.getAttribute('aria-pressed')).toBe('true'); // minute
    expect(optionText('13')!.getAttribute('aria-pressed')).toBe('false');
  });

  it('gives each option a descriptive aria-label carrying its unit', async () => {
    const { el, fixture } = setup('en', new Date(2026, 4, 14, 9, 30));
    await fixture.whenStable();
    el.querySelector('button')!.click();
    await fixture.whenStable();

    // 12h hour "9" (bare digit) + the hours unit; minute "30" + the minutes unit.
    // The stub loader renders keys verbatim, so the unit nouns appear as their keys.
    expect(optionText('9')!.getAttribute('aria-label')).toBe('9 common.timePicker.hours');
    expect(optionText('30')!.getAttribute('aria-label')).toBe('30 common.timePicker.minutes');
  });
});
