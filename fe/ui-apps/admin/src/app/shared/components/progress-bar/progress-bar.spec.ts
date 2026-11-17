import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { ProgressBar } from './progress-bar';

function setup() {
  const fixture = TestBed.createComponent(ProgressBar);
  fixture.componentRef.setInput('visible', false);
  return fixture;
}

describe('ProgressBar', () => {
  it('renders the progress bar when visible is true', () => {
    const fixture = setup();
    fixture.componentRef.setInput('visible', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.progress')).not.toBeNull();
  });

  it('does not render the progress bar when visible is false', () => {
    const fixture = setup();
    fixture.componentRef.setInput('visible', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.progress')).toBeNull();
  });

  it('marks the bar indeterminate by default', () => {
    const fixture = setup();
    fixture.componentRef.setInput('visible', true);
    fixture.detectChanges();

    const bar = fixture.nativeElement.querySelector('.progress') as HTMLElement;
    expect(bar.classList.contains('is-indeterminate')).toBe(true);
  });

  it('sets the fill width from the value in determinate mode', () => {
    const fixture = setup();
    fixture.componentRef.setInput('visible', true);
    fixture.componentRef.setInput('mode', 'determinate');
    fixture.componentRef.setInput('value', 42);
    fixture.detectChanges();

    const bar = fixture.nativeElement.querySelector('.progress') as HTMLElement;
    expect(bar.classList.contains('is-indeterminate')).toBe(false);
    const fill = bar.querySelector('.progress-fill') as HTMLElement;
    expect(fill.style.width).toBe('42%');
  });
});
