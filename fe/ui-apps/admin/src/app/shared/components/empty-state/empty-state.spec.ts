import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { EmptyState } from './empty-state';

function setup() {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(EmptyState);
  fixture.componentRef.setInput('title', 'No results');
  return fixture;
}

describe('EmptyState', () => {
  it('renders the title from the input', () => {
    const fixture = setup();
    fixture.componentRef.setInput('title', 'Search prompt');
    fixture.detectChanges();

    const p = fixture.nativeElement.querySelector('p');
    expect(p?.textContent?.trim()).toBe('Search prompt');
  });

  it('renders the body when provided', () => {
    const fixture = setup();
    fixture.componentRef.setInput('body', 'Some helpful detail');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('small')?.textContent?.trim()).toBe(
      'Some helpful detail',
    );
  });

  it('does not render the body when omitted', () => {
    const fixture = setup();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('small')).toBeNull();
  });

  it('renders the icon when provided', () => {
    const fixture = setup();
    fixture.componentRef.setInput('icon', 'lucideSearch');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-icon')).not.toBeNull();
  });

  it('does not render an icon by default', () => {
    const fixture = setup();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-icon')).toBeNull();
  });

  it('renders the roomy box and full-size type by default', () => {
    const fixture = setup();
    fixture.componentRef.setInput('body', 'Some helpful detail');
    fixture.detectChanges();

    const root = fixture.nativeElement.querySelector('div') as HTMLElement;
    expect(root.className).toContain('py-12');
    expect(fixture.nativeElement.querySelector('p')?.className).toContain('text-base');
    expect(fixture.nativeElement.querySelector('small')?.className).toContain('text-sm');
  });

  it('compact tightens the box and steps the type down one tier', () => {
    const fixture = setup();
    fixture.componentRef.setInput('body', 'Some helpful detail');
    fixture.componentRef.setInput('compact', true);
    fixture.detectChanges();

    const root = fixture.nativeElement.querySelector('div') as HTMLElement;
    expect(root.className).toContain('py-6');
    expect(root.className).toContain('gap-1');
    const title = fixture.nativeElement.querySelector('p') as HTMLElement;
    expect(title.classList.contains('text-sm')).toBe(true);
    expect(title.classList.contains('text-fg-muted')).toBe(true);
    const body = fixture.nativeElement.querySelector('small') as HTMLElement;
    expect(body.classList.contains('text-xs')).toBe(true);
    expect(body.classList.contains('text-fg-subtle')).toBe(true);
  });

  // The glyph is what turns the placeholder into a full-height illustration, so a
  // compact one drops it even when the caller asked for one.
  it('compact renders no glyph even when an icon is set', () => {
    const fixture = setup();
    fixture.componentRef.setInput('icon', 'lucideSearch');
    fixture.componentRef.setInput('compact', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-icon')).toBeNull();
  });
});
