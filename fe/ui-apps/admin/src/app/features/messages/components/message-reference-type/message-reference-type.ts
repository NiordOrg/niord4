import { Component, computed, inject, input, output } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { MessageReference } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';

@Component({
  selector: 'app-message-reference-type',
  templateUrl: './message-reference-type.html',
})
export class MessageReferenceType {
  private readonly descriptions = inject(MessageDescriptionService);
  readonly reference = input.required<MessageReference>();
  readonly language = input<string>();
  readonly referenceClicked = output<string>();

  // Suffix text shown after the reference link: parens text for the four
  // "non-plain" reference types, nothing for `REFERENCE`. Translations live
  // under `messages.reference.suffix.<TYPE>` so each language carries the right
  // phrasing (e.g. en: "(updated repetition)").
  private readonly suffixRepetition = translateSignal('messages.reference.suffix.REPETITION');
  private readonly suffixRepetitionNewTime = translateSignal(
    'messages.reference.suffix.REPETITION_NEW_TIME',
  );
  private readonly suffixCancellation = translateSignal('messages.reference.suffix.CANCELLATION');
  private readonly suffixUpdate = translateSignal('messages.reference.suffix.UPDATE');

  readonly suffix = computed(() => {
    switch (this.reference().type) {
      case 'REPETITION':
        return this.suffixRepetition();
      case 'REPETITION_NEW_TIME':
        return this.suffixRepetitionNewTime();
      case 'CANCELLATION':
        return this.suffixCancellation();
      case 'UPDATE':
        return this.suffixUpdate();
      default:
        return '';
    }
  });

  readonly description = computed(() => {
    const ref = this.reference();
    if (!ref.descs || ref.descs.length === 0) {
      return '';
    }
    // Explicit-only language resolution — positional descs[0] when unset (see caption note
    // on MessageAttachment: keeps every existing caller behavior-identical).
    const language = this.language();
    const entry = language ? this.descriptions.getDescription(ref.descs, language) : ref.descs[0];
    const desc = entry?.description ?? '';
    if (!desc) return '';
    return desc.endsWith('.') ? desc : `${desc}.`;
  });

  onReferenceClick(event: MouseEvent): void {
    // Stop the click from bubbling up to surrounding row/card click handlers that
    // would otherwise open the dialog at the wrong (host) message. The reference
    // emit below carries the referenced id; consumers act on that exclusively.
    event.stopPropagation();
    this.referenceClicked.emit(this.reference().messageId);
  }
}
