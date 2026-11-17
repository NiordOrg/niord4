import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { ErrorBanner } from './error-banner';

function setup() {
  TestBed.configureTestingModule({
    providers: [provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(ErrorBanner);
  fixture.componentRef.setInput('title', 'Error');
  return fixture;
}

describe('ErrorBanner', () => {
  it('renders the title', () => {
    const fixture = setup();
    fixture.componentRef.setInput('title', 'Error loading list');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Error loading list');
  });

  it('renders the message when provided', () => {
    const fixture = setup();
    fixture.componentRef.setInput('message', 'Network timeout');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('span.text-sm')?.textContent?.trim()).toBe(
      'Network timeout',
    );
  });

  it('does not render the message when omitted', () => {
    const fixture = setup();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('span.text-sm')).toBeNull();
  });

  it('renders the retry button only when retryLabel is provided', () => {
    const fixture = setup();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).toBeNull();

    fixture.componentRef.setInput('retryLabel', 'Retry');
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button');
    expect(button).not.toBeNull();
    expect(button.getAttribute('aria-label')).toBe('Retry');
    expect(button.textContent.trim()).toBe('Retry');
  });

  it('emits retry once on click', () => {
    const fixture = setup();
    fixture.componentRef.setInput('retryLabel', 'Retry');
    fixture.detectChanges();

    const spy = vi.fn();
    fixture.componentInstance.retry.subscribe(spy);

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});
