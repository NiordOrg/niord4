import { Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { PlainTextToHtmlPipe } from '../../../../shared/pipes/plain-text-to-html.pipe';
import type { SafetynetPromulgation } from './safetynet-promulgation.model';

@Component({
  selector: 'app-safetynet-view',
  imports: [TranslocoPipe, PlainTextToHtmlPipe],
  templateUrl: './safetynet-view.html',
})
export class SafetynetView {
  readonly promulgation = input.required<SafetynetPromulgation>();
}
