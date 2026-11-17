import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { ConfirmService } from './confirm.service';

/**
 * Promise-shaped "discard unsaved changes?" confirm, rendered through the app confirm dialog so
 * every discard prompt in the app (route guards, drawer/dialog closes, header Clear/Reload) shares
 * ONE surface — the only native prompt left is the browser's own `beforeunload`, which cannot be
 * styled.
 *
 * Owns a private {@link ConfirmService} bus rendered by the always-mounted app-root
 * `DiscardConfirmHost`, so it never interferes with the root bus or any component-scoped one.
 * The promise resolves `true` on accept and `false` on reject/Esc — the shape Angular's
 * `CanDeactivateFn` awaits natively.
 */
@Injectable({ providedIn: 'root' })
export class DiscardConfirmService {
  private readonly transloco = inject(TranslocoService);

  /** The dedicated confirm bus the app-root host renders. */
  readonly bus = new ConfirmService();

  /** Ask to discard unsaved changes; `messageKey` overrides the shared prompt wording. */
  confirmDiscard(messageKey = 'shared.discard.message'): Promise<boolean> {
    return new Promise((resolve) => {
      this.bus.confirmDestructive({
        header: this.transloco.translate('shared.discard.header'),
        message: this.transloco.translate(messageKey),
        accept: () => resolve(true),
        reject: () => resolve(false),
      });
    });
  }
}
