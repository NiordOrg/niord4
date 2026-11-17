import { Component, computed, inject, input, output } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import type { Message } from '../../models/message.model';
import { MessageRowMenu } from '../../message-details-menu/message-row-menu';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageSelectionService } from '../../services/message-selection.service';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { MessageDetailsRenderer } from '../message-details-renderer/message-details-renderer';
import { MessageSelectionToggle } from '../message-selection-toggle/message-selection-toggle';
import { RowLinkAnchorDirective } from '../../../../shared/directives/row-link.directive';

@Component({
  selector: 'app-message-details-row',
  imports: [
    Icon,
    MessageRowMenu,
    MessageDetailsRenderer,
    MessageSelectionToggle,
    RowLinkAnchorDirective,
  ],
  templateUrl: './message-details-row.html',
})
export class MessageDetailsRow {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly urlBuilder = inject(MessageUrlBuilderService);
  private readonly selection = inject(MessageSelectionService);

  readonly message = input.required<Message>();

  /**
   * Emits when a reference inside the renderer is clicked. Carries the
   * referenced message id; the parent view converts that into a
   * `MessageDetailsDialogService.open(...)` call. The row's own open-on-click
   * is handled at the `<tr>` level in the consuming view via `appRowLink`, not
   * by this component — which is also where the thumb's anchor takes its href
   * from.
   */
  readonly openRequested = output<string>();

  readonly title = computed(() => this.descriptionService.getTitle(this.message().descs));
  readonly thumbnailUrl = computed(() => this.urlBuilder.thumbnailUrl(this.message().id));
  readonly hasOriginalInformation = computed(() => this.message().originalInformation === true);
  readonly thumbnailAlt = translateSignal('messages.list.thumbnail.alt');
  readonly originalInfoAriaLabel = translateSignal('messages.list.originalInformation.aria');
  readonly isSelected = computed(() => this.selection.isSelected(this.message().id));
}
