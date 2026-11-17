import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { TemplatesApiService } from '../../services/api/templates-api.service';
import { RemoteValidatedInput } from './remote-validated-input';

const validate = vi.fn();

@Component({
  imports: [RemoteValidatedInput],
  template: `
    <app-remote-validated-input
      kind="call-sign"
      [value]="value()"
      (valueChange)="emitted.push($event)"
    />
  `,
})
class Host {
  readonly value = signal<unknown>(undefined);
  readonly emitted: (string | undefined)[] = [];
}

function setup() {
  vi.useFakeTimers();
  TestBed.configureTestingModule({
    providers: [provideNiordIcons(), { provide: TemplatesApiService, useValue: { validate } }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const type = (text: string) => {
    const input = el.querySelector('input')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(300);
  };
  return { fixture, host: fixture.componentInstance, el, type };
}

afterEach(() => vi.useRealTimers());

describe('RemoteValidatedInput', () => {
  it('validates on input and commits the backend-normalized value', async () => {
    validate.mockReturnValue(of({ value: 'OXYZ', valid: true }));
    const { fixture, host, el, type } = setup();
    type('oxyz');
    await fixture.whenStable();

    expect(validate).toHaveBeenCalledWith('call-sign', 'oxyz');
    expect(host.emitted.at(-1)).toBe('OXYZ');
    expect(el.querySelector('app-icon[name="lucideCheck"]')).not.toBeNull();
  });

  it('flags an invalid value', async () => {
    validate.mockReturnValue(of({ value: 'bad', valid: false }));
    const { fixture, el, type } = setup();
    type('bad');
    await fixture.whenStable();

    expect(el.querySelector('app-icon[name="lucideTriangleAlert"]')).not.toBeNull();
    expect(el.querySelector('input')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('treats a backend error as invalid without throwing', async () => {
    validate.mockReturnValue(throwError(() => new Error('boom')));
    const { fixture, el, type } = setup();
    type('x');
    await fixture.whenStable();

    expect(el.querySelector('app-icon[name="lucideTriangleAlert"]')).not.toBeNull();
  });

  it('re-syncs the field when the bound value changes (host @for track $index reuse)', () => {
    const { fixture, host, el } = setup();
    host.value.set('ALFA');
    fixture.detectChanges();
    expect(el.querySelector('input')!.value).toBe('ALFA');
    // No validity glyph: an external rebind resets the state (the new value isn't validated yet).
    expect(el.querySelector('app-icon')).toBeNull();

    // The instance is reused for a different row: the fresh bound value must replace the stale one
    // instead of latching on the first seed.
    host.value.set('BRAVO');
    fixture.detectChanges();
    expect(el.querySelector('input')!.value).toBe('BRAVO');
  });
});
