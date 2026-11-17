import { Component, inject } from '@angular/core';

import { ConfirmService } from '../../services/confirm.service';
import { DiscardConfirmService } from '../../services/discard-confirm.service';
import { Confirm } from './confirm';

/**
 * App-root host for {@link DiscardConfirmService}'s confirm bus. The provider is scoped to THIS
 * element only, so the inner `<app-confirm>` binds the discard bus without shadowing the root
 * `ConfirmService` for anything else in the tree. Mounted once in the root App template — always
 * alive, so route guards can await a discard confirm regardless of which feature is on screen.
 */
@Component({
  selector: 'app-discard-confirm-host',
  imports: [Confirm],
  providers: [{ provide: ConfirmService, useFactory: () => inject(DiscardConfirmService).bus }],
  template: '<app-confirm width="26rem" />',
  host: { class: 'contents' },
})
export class DiscardConfirmHost {}
