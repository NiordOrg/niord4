import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Pager } from './pager';

@Component({
  imports: [Pager],
  template: `<app-pager
    [first]="first()"
    [rows]="10"
    [total]="total()"
    [inCard]="inCard()"
    (pageChange)="last.set($event)"
  />`,
})
class PagerHost {
  readonly first = signal(0);
  readonly total = signal(35);
  readonly inCard = signal(false);
  readonly last = signal(-1);
}

describe('Pager', () => {
  function setup() {
    TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
    const fixture = TestBed.createComponent(PagerHost);
    fixture.detectChanges();
    return fixture;
  }

  const q = (fixture: ReturnType<typeof setup>, sel: string) =>
    fixture.nativeElement.querySelector(sel) as HTMLElement | null;

  it('hides when there is a single page', () => {
    const fixture = setup();
    fixture.componentInstance.total.set(8); // <= rows 10 -> 1 page
    fixture.detectChanges();
    expect(q(fixture, '.pager')).toBeNull();
  });

  it('shows the language-neutral page count (35 rows / 10 = page 1 of 4)', () => {
    const fixture = setup();
    expect(q(fixture, '.pager-status')?.textContent?.trim()).toBe('1 / 4');
  });

  it('disables prev on the first page, next on the last', () => {
    const fixture = setup();
    expect((q(fixture, '.pager-btn:first-of-type') as HTMLButtonElement).disabled).toBe(true);
    expect((q(fixture, '.pager-btn:last-of-type') as HTMLButtonElement).disabled).toBe(false);

    fixture.componentInstance.first.set(30); // last page
    fixture.detectChanges();
    expect((q(fixture, '.pager-btn:first-of-type') as HTMLButtonElement).disabled).toBe(false);
    expect((q(fixture, '.pager-btn:last-of-type') as HTMLButtonElement).disabled).toBe(true);
  });

  it('next emits first + rows', () => {
    const fixture = setup();
    (q(fixture, '[aria-label="shared.pager.next"]') as HTMLButtonElement).click();
    expect(fixture.componentInstance.last()).toBe(10);
  });

  it('shows first/last buttons only when there is more than one extra page', () => {
    const fixture = setup(); // 35 rows / 10 = 4 pages
    expect(q(fixture, '[aria-label="shared.pager.first"]')).not.toBeNull();
    expect(q(fixture, '[aria-label="shared.pager.last"]')).not.toBeNull();

    fixture.componentInstance.total.set(15); // 2 pages -> no first/last
    fixture.detectChanges();
    expect(q(fixture, '[aria-label="shared.pager.first"]')).toBeNull();
    expect(q(fixture, '[aria-label="shared.pager.last"]')).toBeNull();
  });

  // Free under a list the pager needs its own separation; in a card foot the card
  // already provides it, and a second gap reads as a stray band.
  it('keeps its standalone top margin unless it is in a card foot', () => {
    const fixture = setup();
    expect(q(fixture, '.pager')?.className).not.toContain('mt-0');

    fixture.componentInstance.inCard.set(true);
    fixture.detectChanges();
    const nav = q(fixture, '.pager') as HTMLElement;
    expect(nav.className).toContain('mt-0');
    expect(nav.className).toContain('w-full');
  });

  it('last emits the first-row index of the final page', () => {
    const fixture = setup(); // 35 rows / 10 = 4 pages -> last page starts at 30
    (q(fixture, '[aria-label="shared.pager.last"]') as HTMLButtonElement).click();
    expect(fixture.componentInstance.last()).toBe(30);
  });
});
