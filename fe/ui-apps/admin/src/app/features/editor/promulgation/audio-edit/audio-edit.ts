import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import type { AudioPromulgation } from '../../../messages/promulgation/audio-view/audio-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';

@Component({
  selector: 'app-audio-edit',
  imports: [TranslocoPipe],
  templateUrl: './audio-edit.html',
})
export class AudioEdit {
  readonly promulgation = input.required<AudioPromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  protected readonly typeId = computed(() => this.promulgation().type.typeId);
  protected readonly disabled = computed(() => this.context().busy(this.typeId()));

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    this.context().patch(this.typeId(), { text: value });
  }

  protected generate(): void {
    this.context().generate(this.typeId());
  }
}
