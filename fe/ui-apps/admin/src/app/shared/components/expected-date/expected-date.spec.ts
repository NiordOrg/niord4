import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { ExpectedDate, type ExpectedDateFormat } from './expected-date';

/** The two ends of the period the range tests mark. */
const FROM = 1_756_814_400_000;
const TO = 1_757_419_200_000;

@Component({
  imports: [ExpectedDate],
  template: `<app-expected-date [value]="value()" [to]="to()" [format]="format()" />`,
})
class Host {
  readonly value = signal<Date | number | string | null | undefined>(FROM);
  readonly to = signal<Date | number | string | null | undefined>(null);
  readonly format = signal<ExpectedDateFormat>('dateTime');
}

/**
 * The three formats, told apart by which method they route to, and the two
 * instants told apart by their text.
 *
 * Stubbed rather than run through Intl: what this component owns is the choice
 * of format and the marker around it, and the layout of each format is already
 * the subject of `date-time-format.spec.ts`.
 */
const localeFormat = {
  formatDate: (v: unknown) => (v === TO ? '9. sep. 2026' : '2. sep. 2026'),
  formatDateTime: (v: unknown) => (v === TO ? '9. sep. 2026, 12.00' : '2. sep. 2026, 12.00'),
  formatWeekdayDateTime: (v: unknown) => (v === TO ? 'ons. 9. sep., 12.00' : 'ons. 2. sep., 12.00'),
} as unknown as LocaleFormatService;

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: LocaleFormatService, useValue: localeFormat },
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, element: fixture.nativeElement as HTMLElement };
}

describe('ExpectedDate', () => {
  it('renders the date and time by default', () => {
    const { element } = setup();

    expect(element.querySelector('.expected-date')?.textContent).toContain('2. sep. 2026, 12.00');
  });

  it('renders the date alone when asked for one', () => {
    const { fixture, element } = setup();
    (fixture.componentInstance as Host).format.set('date');
    fixture.detectChanges();

    expect(element.querySelector('.expected-date')?.textContent?.trim()).toBe('2. sep. 2026');
  });

  /** The weekday form: the day a reader plans around, with no year. */
  it('renders the weekday form when asked for one', () => {
    const { fixture, element } = setup();
    (fixture.componentInstance as Host).format.set('weekday');
    fixture.detectChanges();

    expect(element.querySelector('.expected-date')?.textContent).toContain('ons. 2. sep., 12.00');
  });

  /**
   * The reason the marker exists, said in words.
   *
   * The glyph, the italics and the dotted rule are all shape; the title is the
   * only carrier that explains what the shape means.
   */
  it('says in words why the date is marked', () => {
    const { element } = setup();

    expect(element.querySelector('.expected-date')?.getAttribute('title')).toBe(
      'admin.publications.expected.title',
    );
  });

  /** A clock, and only one — the marker is the glyph plus the text, not two glyphs. */
  it('leads with the clock glyph', () => {
    const { element } = setup();
    const icons = element.querySelectorAll('.expected-date app-icon');

    expect(icons.length).toBe(1);
    expect(element.querySelector('.expected-date svg')).not.toBeNull();
  });

  /**
   * Nothing at all without a value.
   *
   * A marker over an empty cell claims a date is coming when the caller has
   * none, and the host is `display: contents` so the empty wrapper costs no gap.
   */
  it('renders nothing without a usable value', () => {
    const { fixture, element } = setup();

    for (const empty of [null, undefined, '']) {
      (fixture.componentInstance as Host).value.set(empty);
      fixture.detectChanges();
      expect(element.querySelector('.expected-date')).toBeNull();
    }
  });

  /**
   * A period is ONE expectation.
   *
   * A public window that has not opened yet is unstamped at both ends, and a
   * marker on each reads as two projections that happen to sit side by side.
   */
  it('marks a period as one range under a single clock', () => {
    const { fixture, element } = setup();
    (fixture.componentInstance as Host).to.set(TO);
    (fixture.componentInstance as Host).format.set('date');
    fixture.detectChanges();

    // Each part is its own element, so the marker's text runs together here; the
    // space a reader sees is the flex gap on `.expected-date`.
    const marker = element.querySelector('.expected-date');
    expect(marker?.textContent?.replace(/\s+/g, '')).toBe('2.sep.2026–9.sep.2026');
    expect(element.querySelectorAll('.expected-date app-icon').length).toBe(1);
  });

  /** Both ends read at the same resolution — one format, applied twice. */
  it('runs both ends of a range through the chosen format', () => {
    const { fixture, element } = setup();
    (fixture.componentInstance as Host).to.set(TO);
    (fixture.componentInstance as Host).format.set('weekday');
    fixture.detectChanges();

    const text = element.querySelector('.expected-date')?.textContent ?? '';
    expect(text).toContain('ons. 2. sep., 12.00');
    expect(text).toContain('ons. 9. sep., 12.00');
  });

  /** Without a far end it is still a single date, dash and all left out. */
  it('marks a single date when no far end is given', () => {
    const { element } = setup();

    expect(element.querySelector('.expected-date')?.textContent).not.toContain('–');
  });
});
