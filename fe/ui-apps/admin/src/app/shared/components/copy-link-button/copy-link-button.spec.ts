import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { CopyLinkButton } from './copy-link-button';

@Component({
  imports: [CopyLinkButton],
  template: `<app-copy-link-button [url]="url()" [variant]="variant()" />`,
})
class Host {
  readonly url = signal<string | undefined>('https://example.test/EfS-27-2026.pdf');
  readonly variant = signal<'icon' | 'button'>('icon');
}

const notifications = { success: vi.fn(), error: vi.fn() };

const writeText = vi.fn(() => Promise.resolve());
let originalClipboard: PropertyDescriptor | undefined;

function setClipboard(value: unknown): void {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true });
}

function setup() {
  vi.clearAllMocks();
  TestBed.configureTestingModule({
    providers: [
      { provide: NotificationService, useValue: notifications },
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  return { fixture, button };
}

/** Let the awaited clipboard promise resolve and its continuation run. */
function settled(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('CopyLinkButton', () => {
  beforeEach(() => {
    originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    setClipboard({ writeText });
  });

  afterEach(() => {
    if (originalClipboard) {
      Object.defineProperty(navigator, 'clipboard', originalClipboard);
    } else {
      setClipboard(undefined);
    }
  });

  /** The whole point: the address goes on the clipboard exactly as given. */
  it('writes the url to the clipboard', async () => {
    const { button } = setup();

    button.click();
    await settled();

    expect(writeText).toHaveBeenCalledWith('https://example.test/EfS-27-2026.pdf');
    expect(notifications.success).toHaveBeenCalledWith('shared.copyLink.copied');
  });

  /**
   * The click stops at the button.
   *
   * The issue rows this sits in navigate on click, so a leaked event would take
   * the reader somewhere else every time they reached for the clipboard.
   */
  it('keeps the click off a surrounding row handler', async () => {
    const { fixture, button } = setup();
    const onRow = vi.fn();
    fixture.nativeElement.addEventListener('click', onRow);

    button.click();
    await settled();

    expect(onRow).not.toHaveBeenCalled();
  });

  /** Icon-only, so the accessible name has to be on the button itself. */
  it('names itself for a screen reader', () => {
    const { button } = setup();

    expect(button.getAttribute('aria-label')).toBe('shared.copyLink.aria');
    expect(button.classList.contains('icon-button')).toBe(true);
    expect(button.textContent?.trim()).toBe('');
  });

  /**
   * The labelled variant, for a header action cluster.
   *
   * An unlabelled glyph among labelled buttons reads as a different KIND of
   * control rather than as one more thing the reader can do here — so the same
   * name the icon variant hides in `aria-label` becomes visible, and the button
   * takes the secondary framing its neighbours have.
   */
  it('shows the label and the secondary framing in the button variant', () => {
    const { fixture, button } = setup();
    (fixture.componentInstance as Host).variant.set('button');
    fixture.detectChanges();

    expect(button.classList.contains('btn-secondary')).toBe(true);
    expect(button.classList.contains('icon-button')).toBe(false);
    expect(button.textContent).toContain('shared.copyLink.aria');
    // The glyph stays: the label explains the action, the icon identifies it.
    expect(button.querySelector('app-icon')).not.toBeNull();
  });

  /** The name is carried in both variants, visible or not. */
  it('keeps the accessible name and tooltip in the button variant', () => {
    const { fixture, button } = setup();
    (fixture.componentInstance as Host).variant.set('button');
    fixture.detectChanges();

    expect(button.getAttribute('aria-label')).toBe('shared.copyLink.aria');
    expect(button.getAttribute('title')).toBe('shared.copyLink.aria');
  });

  /** Nothing to copy is nothing to press. */
  it('is disabled without a url', () => {
    const { fixture, button } = setup();
    (fixture.componentInstance as Host).url.set(undefined);
    fixture.detectChanges();

    expect(button.disabled).toBe(true);
  });

  /**
   * No clipboard means no success toast.
   *
   * `navigator.clipboard` is absent outside a secure context, and telling the
   * reader the link was copied when it was not is worse than saying it failed.
   */
  it('reports a failure rather than claiming success without a clipboard', async () => {
    setClipboard(undefined);
    const { button } = setup();

    button.click();
    await settled();

    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.error).toHaveBeenCalledWith('shared.copyLink.failed');
  });

  /** A rejected write is a failure too — the browser can refuse the permission. */
  it('reports a rejected write', async () => {
    writeText.mockReturnValueOnce(Promise.reject(new Error('denied')));
    const { button } = setup();

    button.click();
    await settled();

    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.error).toHaveBeenCalledWith('shared.copyLink.failed');
  });
});
