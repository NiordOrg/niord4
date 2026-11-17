import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { FormatToken, FormatTokenInput } from './format-token-input';

@Component({
  imports: [FormatTokenInput],
  template: `
    <app-format-token-input
      [tokens]="tokens()"
      [multiline]="multiline()"
      [disabled]="disabled()"
      [(value)]="value"
      (touch)="touched()"
    />
  `,
})
class Host {
  readonly tokens = signal<readonly FormatToken[]>([
    { token: '${year}', label: 'Year' },
    { token: '${number}', label: 'Number' },
  ]);
  readonly multiline = signal(false);
  readonly disabled = signal(false);
  readonly value = signal('');
  readonly touched = vi.fn();
}

describe('FormatTokenInput', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  async function setup(value = '', multiline = false) {
    TestBed.configureTestingModule({ providers: [provideNiordIcons(), provideTestTransloco()] });
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    host.value.set(value);
    host.multiline.set(multiline);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      host,
      field: () => el.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')!,
      trigger: () => el.querySelector<HTMLButtonElement>('button')!,
    };
  }

  /** The menu portals to the body-level overlay container; rows are queried there. */
  function menuRows(): HTMLElement[] {
    return Array.from(document.querySelectorAll<HTMLElement>('.menu-item'));
  }

  async function insertToken(
    fixture: { whenStable(): Promise<unknown> },
    trigger: HTMLButtonElement,
    label: string,
  ) {
    trigger.click();
    await fixture.whenStable();
    menuRows()
      .find((row) => row.textContent?.trim() === label)!
      .click();
    await fixture.whenStable();
  }

  it('renders a single-line input by default', async () => {
    const { field } = await setup();
    expect(field().tagName).toBe('INPUT');
  });

  it('renders a textarea when multiline', async () => {
    const { field } = await setup('', true);
    expect(field().tagName).toBe('TEXTAREA');
  });

  it('offers every token by its friendly label — never the raw token text', async () => {
    const { fixture, trigger } = await setup();
    trigger().click();
    await fixture.whenStable();
    expect(menuRows().map((row) => row.textContent?.trim())).toEqual(['Year', 'Number']);
  });

  it('inserts the labelled token literal, appending when the field was never focused', async () => {
    const { fixture, host, field, trigger } = await setup('DK-${number}');
    await insertToken(fixture, trigger(), 'Year');

    expect(host.value()).toBe('DK-${number}${year}');
    expect(document.activeElement).toBe(field());
    expect(field().selectionStart).toBe('DK-${number}${year}'.length);
  });

  it('splices at the caret once the field has been focused', async () => {
    const { fixture, host, field, trigger } = await setup('DK-NW');
    field().focus();
    field().setSelectionRange(3, 3);
    await insertToken(fixture, trigger(), 'Year');

    expect(host.value()).toBe('DK-${year}NW');
    // Caret parked right after the inserted token, field refocused.
    expect(document.activeElement).toBe(field());
    expect(field().selectionStart).toBe('DK-${year}'.length);
    expect(field().selectionEnd).toBe('DK-${year}'.length);
  });

  it('replaces a selection range', async () => {
    const { fixture, host, field, trigger } = await setup('DK-XX-NW');
    field().focus();
    field().setSelectionRange(3, 5); // "XX"
    await insertToken(fixture, trigger(), 'Number');

    expect(host.value()).toBe('DK-${number}-NW');
  });

  it('falls back to appending after an external value write (editor reopen/reset)', async () => {
    const { fixture, host, field, trigger } = await setup('OLD');
    field().focus();
    field().setSelectionRange(0, 0);
    // The host swaps the text under the field (e.g. a fresh entity is loaded).
    host.value.set('NEW');
    await fixture.whenStable();
    await insertToken(fixture, trigger(), 'Year');

    expect(host.value()).toBe('NEW${year}');
  });

  it('propagates the insert through value + touch so a host dirty-gate flips', async () => {
    const { fixture, host, trigger } = await setup('');
    expect(host.touched).not.toHaveBeenCalled();
    await insertToken(fixture, trigger(), 'Year');

    expect(host.value()).toBe('${year}');
    expect(host.touched).toHaveBeenCalled();
  });

  it('propagates typing into the value model', async () => {
    const { fixture, host, field } = await setup('');
    const input = field();
    input.value = 'abc';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    expect(host.value()).toBe('abc');
  });

  it('disables the field and the insert trigger when disabled', async () => {
    const { fixture, host, field, trigger } = await setup('x');
    host.disabled.set(true);
    await fixture.whenStable();
    expect(field().disabled).toBe(true);
    expect(trigger().disabled).toBe(true);
  });
});
