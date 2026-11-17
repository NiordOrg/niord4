import { computed, inject, Injectable, Signal } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../shared/models/menu-item.model';

import { HelpUrlService } from '../../help/help-url.service';

@Injectable({ providedIn: 'root' })
export class HelpMenuService {
  private readonly helpUrlService = inject(HelpUrlService);
  private readonly helpLabel = translateSignal('core.help.label');

  readonly menuItem: Signal<MenuItem | null> = computed(() => {
    const url = this.helpUrlService.routeUrl();
    if (!url) {
      return null;
    }
    return {
      label: this.helpLabel(),
      icon: 'lucideCircleHelp',
      url,
      target: '_blank',
    };
  });
}
