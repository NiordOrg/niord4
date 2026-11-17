import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';

import { Icon } from './icon';
import { provideNiordIcons } from './niord-icons';

describe('Icon', () => {
  function render(name: string, filled = false) {
    TestBed.configureTestingModule({ providers: [provideNiordIcons()] });
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', name);
    if (filled) fixture.componentRef.setInput('filled', true);
    fixture.detectChanges();
    return fixture;
  }

  /** The box the glyph sits in — the element `<ng-icon>` used to be. */
  function box(fixture: ReturnType<typeof render>): HTMLElement {
    return fixture.nativeElement.querySelector(':scope > span');
  }

  it('renders the named Lucide glyph as an svg', () => {
    const fixture = render('lucideStar');
    expect(fixture.nativeElement.querySelector('svg')).toBeTruthy();
  });

  it('applies fill-current when [filled] is set', () => {
    const fixture = render('lucideStar', true);
    expect(box(fixture).classList).toContain('fill-current');
  });

  it('is stroke-only (not filled) by default', () => {
    const fixture = render('lucideStar');
    expect(box(fixture).classList).not.toContain('fill-current');
  });

  it('is hidden from assistive technology and sized 1em by default', () => {
    const el = box(render('lucideStar'));
    expect(el.getAttribute('aria-hidden')).toBe('true');
    expect(el.style.width).toBe('1em');
    expect(el.style.height).toBe('1em');
  });

  it('gives every icon its own copy of a glyph', () => {
    TestBed.configureTestingModule({ providers: [provideNiordIcons()] });
    const [a, b] = [TestBed.createComponent(Icon), TestBed.createComponent(Icon)];
    for (const f of [a, b]) {
      f.componentRef.setInput('name', 'lucideStar');
      f.detectChanges();
    }
    const [svgA, svgB] = [a, b].map((f) => f.nativeElement.querySelector('svg'));
    expect(svgA.outerHTML).toBe(svgB.outerHTML);
    expect(svgA).not.toBe(svgB);
  });

  it('swaps the glyph when the name changes, and follows spin and size', () => {
    const fixture = render('lucideStar');
    const star = fixture.nativeElement.querySelector('svg').outerHTML;
    fixture.componentRef.setInput('name', 'lucideX');
    fixture.componentRef.setInput('spin', true);
    fixture.componentRef.setInput('size', '2rem');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('svg')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('svg').outerHTML).not.toBe(star);
    expect(box(fixture).classList).toContain('animate-spin');
    expect(box(fixture).style.width).toBe('2rem');
  });

  it('renders nothing and warns for a name no icon set registers', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const fixture = render('lucideNoSuchGlyph');
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('lucideNoSuchGlyph'));
    warn.mockRestore();
  });
});
