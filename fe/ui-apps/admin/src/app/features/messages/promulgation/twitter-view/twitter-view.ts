import { Component, input } from '@angular/core';

import { PlainTextToHtmlPipe } from '../../../../shared/pipes/plain-text-to-html.pipe';
import type { TwitterPromulgation } from './twitter-promulgation.model';

@Component({
  selector: 'app-twitter-view',
  host: { class: 'contents' },
  imports: [PlainTextToHtmlPipe],
  templateUrl: './twitter-view.html',
})
export class TwitterView {
  readonly promulgation = input.required<TwitterPromulgation>();
}
