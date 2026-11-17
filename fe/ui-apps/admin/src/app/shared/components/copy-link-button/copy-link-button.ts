import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../core/notifications/notification.service';
import { Icon } from '../../icons/icon';

/**
 * Copy a URL to the clipboard, with a toast saying it happened.
 *
 * Sits beside a link rather than replacing it: the link is still the way to OPEN
 * a document, and this is the way to hand it to somebody else. A user who wants
 * the address of a generated PDF otherwise right-clicks a link whose label is a
 * file name, which is the step that produces the wrong URL.
 *
 * The name comes from `label` — a translation KEY, rendered here rather than by
 * the caller, per the rule that the component placing text in the DOM owns the
 * `| transloco` call. In the `icon` variant that name is carried by `aria-label`
 * and the tooltip alone; in the `button` variant the same text is also visible,
 * for a header action that has to be findable rather than recognised.
 */
@Component({
  selector: 'app-copy-link-button',
  imports: [Icon, TranslocoPipe],
  template: `
    <button
      type="button"
      [class]="variant() === 'button' ? 'btn-secondary' : 'icon-button'"
      [disabled]="!url()"
      [attr.aria-label]="labelKey() | transloco"
      [attr.title]="labelKey() | transloco"
      (click)="copy($event)"
    >
      <app-icon name="lucideCopy" class="text-xs" />
      @if (variant() === 'button') {
        <span>{{ labelKey() | transloco }}</span>
      }
    </button>
  `,
  host: { class: 'contents' },
})
export class CopyLinkButton {
  private readonly notifications = inject(NotificationService);

  /** The address to put on the clipboard. Empty disables the button. */
  readonly url = input<string | undefined>(undefined);

  /**
   * Translation key for the accessible name and tooltip.
   *
   * Defaulted rather than required: every use so far means "copy this link", and
   * a caller with a more specific name (a language, a preview) passes its own key.
   */
  readonly label = input<string | undefined>(undefined);

  /**
   * Icon-only beside the thing it copies, or a labelled button of its own.
   *
   * `icon` is the default because most uses sit at the end of a row that already
   * says what the address is. `button` is for a header action cluster, where an
   * unlabelled glyph among labelled buttons reads as a different KIND of control
   * rather than as one more thing the reader can do here.
   */
  readonly variant = input<'icon' | 'button'>('icon');

  protected readonly labelKey = computed(() => this.label() ?? 'shared.copyLink.aria');

  protected async copy(event?: Event): Promise<void> {
    // Kept off any surrounding row handler: this button copies an address, and a
    // table row that also navigates on click would take the reader somewhere
    // else every time they reached for the clipboard.
    event?.stopPropagation();

    const url = this.url();
    if (!url) {
      return;
    }
    // Absent outside a secure context, so this is a real branch rather than
    // defensive noise — and it must not fall through to the success toast, which
    // would tell the reader the clipboard holds something it does not.
    const clipboard = navigator.clipboard;
    if (!clipboard) {
      this.notifications.error('shared.copyLink.failed');
      return;
    }
    try {
      await clipboard.writeText(url);
      this.notifications.success('shared.copyLink.copied');
    } catch {
      this.notifications.error('shared.copyLink.failed');
    }
  }
}
