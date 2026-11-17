import { Component, input } from '@angular/core';

import { PlainTextToHtmlPipe } from '../../../../shared/pipes/plain-text-to-html.pipe';
import type { AudioPromulgation } from './audio-promulgation.model';

@Component({
  selector: 'app-audio-view',
  imports: [PlainTextToHtmlPipe],
  templateUrl: './audio-view.html',
})
export class AudioView {
  readonly promulgation = input.required<AudioPromulgation>();
}
