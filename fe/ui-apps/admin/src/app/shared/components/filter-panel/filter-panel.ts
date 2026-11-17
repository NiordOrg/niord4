import { Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';

@Component({
  selector: 'app-filter-panel',
  imports: [TranslocoPipe, Icon],
  templateUrl: './filter-panel.html',
})
export class FilterPanel {
  readonly label = input.required<string>();
  readonly closable = input<boolean>(true);
  // Drops the 56 px min-height used by toolbar chips (which house full-height
  // dropdowns). Read-only chips like the map-extent display sit better compact.
  readonly dense = input<boolean>(false);
  readonly clear = output<void>();
}
