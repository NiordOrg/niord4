import { Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** The example country's notice above the footer of every page of the public site. */
@Component({
  selector: 'app-site-notice',
  imports: [TranslocoPipe],
  template: `
    <aside class="app-shell-gutter py-2 text-sm">{{ 'country.notice.text' | transloco }}</aside>
  `,
})
export class SiteNoticeComponent {}
