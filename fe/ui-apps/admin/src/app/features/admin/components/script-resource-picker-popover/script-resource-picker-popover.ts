import { Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { PickerPopover } from '../../../../shared/components/picker-popover/picker-popover';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import {
  ScriptResource,
  ScriptResourcesApiService,
  ScriptResourceType,
} from '../../services/api/script-resources-api.service';
import { ScriptResourceTypeBadge } from '../script-resource-type-badge/script-resource-type-badge';

/**
 * Anchored popover picker over the script-resource catalog (the messages
 * tag-search idiom), optionally narrowed to one type (the single-path field
 * passes `'FM'`). Filters by path substring; picking a row emits its path and
 * closes. Loads its catalog on open.
 */
@Component({
  selector: 'app-script-resource-picker-popover',
  imports: [PickerPopover, FormsModule, ProgressBar, ScriptResourceTypeBadge, TranslocoPipe],
  templateUrl: './script-resource-picker-popover.html',
  host: { class: 'contents' },
})
export class ScriptResourcePickerPopover {
  private readonly api = inject(ScriptResourcesApiService);
  private readonly shell = viewChild.required(PickerPopover);

  readonly picked = output<string>();

  protected readonly loading = signal(false);
  protected readonly filter = signal('');
  private readonly resources = signal<readonly ScriptResource[]>([]);

  protected readonly filtered = computed(() => {
    const term = this.filter().trim().toLowerCase();
    const resources = this.resources();
    if (term === '') {
      return resources;
    }
    return resources.filter((resource) => resource.path.toLowerCase().includes(term));
  });

  /** Open anchored to `anchor`; `type` narrows the catalog to FM or JS. */
  open(anchor: HTMLElement, type?: ScriptResourceType): void {
    this.filter.set('');
    this.resources.set([]);
    this.loading.set(true);
    this.shell().open(anchor);
    this.api.getAll(type).subscribe({
      next: (resources) => {
        this.resources.set(resources);
        this.loading.set(false);
      },
      error: () => {
        this.resources.set([]);
        this.loading.set(false);
      },
    });
  }

  protected select(resource: ScriptResource): void {
    this.picked.emit(resource.path);
    this.shell().close();
  }
}
