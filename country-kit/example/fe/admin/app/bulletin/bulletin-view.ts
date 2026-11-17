import { Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { PlainTextToHtmlPipe, type BasePromulgation } from '@niord/admin/api';

/** The harbour bulletin's part of a message: what the harbour office reads out, as plain text. */
export interface BulletinPromulgation extends BasePromulgation {
  readonly text?: string;
}

/** Shows a message's harbour bulletin among its promulgations. */
@Component({
  selector: 'app-bulletin-view',
  imports: [TranslocoPipe, PlainTextToHtmlPipe],
  template: `
    <p class="text-xs font-semibold">{{ 'country.bulletin.title' | transloco }}</p>
    @if (promulgation().text; as text) {
      <div class="text-sm" [innerHTML]="text | plainTextToHtml"></div>
    }
  `,
})
export class BulletinView {
  readonly promulgation = input.required<BulletinPromulgation>();
}
