import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ScriptResourceType } from '../../services/api/script-resources-api.service';

/**
 * Small FM/JS type badge extending the shared `.status-badge` widget with two
 * orthogonal state classes (`.is-script-fm` / `.is-script-js`) whose colours are
 * Layer-3 tokens — never hardcoded literals. The label is keyed (lowercased),
 * never the raw enum.
 */
@Component({
  selector: 'app-script-resource-type-badge',
  imports: [TranslocoPipe],
  template: `<span
    class="status-badge"
    [class.is-script-fm]="type() === 'FM'"
    [class.is-script-js]="type() === 'JS'"
    >{{ labelKey() | transloco }}</span
  >`,
})
export class ScriptResourceTypeBadge {
  readonly type = input.required<ScriptResourceType>();

  protected readonly labelKey = computed(() => `admin.scriptResources.type.${this.type()}`);
}
