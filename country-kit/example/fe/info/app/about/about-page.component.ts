import { Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** The example country's own page: who runs the service, in the words of its branding. */
@Component({
  selector: 'app-about-page',
  imports: [TranslocoPipe],
  template: `
    <section class="app-shell-gutter py-6">
      <h1 class="text-xl font-semibold">{{ 'country.about.title' | transloco }}</h1>
      <p class="mt-2">{{ 'country.about.text' | transloco }}</p>
    </section>
  `,
})
export class AboutPageComponent {}
