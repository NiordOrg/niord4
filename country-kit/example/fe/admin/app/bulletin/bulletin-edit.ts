import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { PromulgationEditorContext } from '@niord/admin/api';
import type { BulletinPromulgation } from './bulletin-view';

/** Edits a message's harbour bulletin: its text, written through the editor's context. */
@Component({
  selector: 'app-bulletin-edit',
  imports: [TranslocoPipe],
  template: `
    <textarea
      rows="4"
      class="w-full text-input"
      [attr.aria-label]="'country.bulletin.text' | transloco"
      [value]="promulgation().text ?? ''"
      [disabled]="disabled()"
      (input)="onInput($event)"
    ></textarea>
  `,
})
export class BulletinEdit {
  readonly promulgation = input.required<BulletinPromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  protected readonly typeId = computed(() => this.promulgation().type.typeId);
  protected readonly disabled = computed(() => this.context().busy(this.typeId()));

  protected onInput(event: Event): void {
    const text = (event.target as HTMLTextAreaElement).value;
    this.context().patch(this.typeId(), { text });
  }
}
