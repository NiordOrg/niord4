import { Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** The example country's line at the end of the front page's welcome panel. */
@Component({
  selector: 'app-welcome-note',
  imports: [TranslocoPipe],
  template: `<p class="mt-3 text-sm">{{ 'country.welcome.note' | transloco }}</p>`,
})
export class WelcomeNote {}
