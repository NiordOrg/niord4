import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../shared/icons/icon';
import { BuildVersionService } from './build-version.service';

/**
 * The shell's update banner: shown on every page once {@link BuildVersionService} knows the running
 * build is no longer the deployed one. It sits in the shell rather than on one page because the
 * moment it matters is wherever the user is when a deploy lands under their open tab — typically a
 * click into a view the old build can no longer load.
 *
 * It offers the reload and never performs it: a reload is the user's action, taken when their own
 * work allows it.
 */
@Component({
  selector: 'app-update-banner',
  imports: [Icon, TranslocoPipe],
  templateUrl: './update-banner.html',
})
export class UpdateBanner {
  protected readonly version = inject(BuildVersionService);

  protected reload(): void {
    window.location.reload();
  }
}
