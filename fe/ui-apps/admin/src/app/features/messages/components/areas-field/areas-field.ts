import {
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { EntityMultiselectControl } from '../../../../shared/forms/entity-multiselect-control/entity-multiselect-control';
import {
  EntityMultiselectField,
  type EntityMultiselectMagnifier,
} from '../../../../shared/forms/entity-multiselect-field';
import { parentBreadcrumb } from '../../../../shared/forms/parent-breadcrumb';
import { TreePicker } from '../../../../shared/components/tree-picker/tree-picker';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { AreasApiService } from '../../services/api/areas-api.service';

function areaLabel(area: AreaRef): string {
  return area.descs?.[0]?.name ?? `area-${area.id}`;
}

@Component({
  selector: 'app-areas-field',
  templateUrl: './areas-field.html',
  imports: [EntityMultiselectControl, TreePicker],
})
export class AreasField extends EntityMultiselectField<AreaRef, number> {
  private readonly api = inject(AreasApiService);
  private readonly notifications = inject(NotificationService);
  private readonly uiLanguage = inject(LanguageService);

  readonly domainId = input<string | null>(null);
  override readonly optionLabel = areaLabel;
  override readonly optionSuffix = parentBreadcrumb;
  override readonly magnifier: EntityMultiselectMagnifier = {
    icon: 'lucideSearch',
    tooltipKey: 'messages.filter.area.dialog.openTooltip',
    onClick: (event) => this.openPicker(event),
  };

  private readonly picker = viewChild.required(TreePicker<AreaRef>);
  /** Root hierarchy for the browse popover, loaded on first open + reloaded on language change. */
  protected readonly roots = signal<readonly AreaRef[]>([]);
  private rootsLoaded = false;

  protected readonly selectedIdList = computed(() => this.value().map((area) => area.id));

  constructor() {
    super();
    // Reload the picker tree in the new language once it's been opened (node labels are
    // server-translated) — same contract as the area filter chip's EntityFilterPanelBase.
    effect(() => {
      this.uiLanguage.selectedLanguage();
      untracked(() => {
        if (!this.rootsLoaded) return;
        this.api
          .getRoots()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({ next: (roots) => this.roots.set(roots), error: () => undefined });
      });
    });
  }

  protected idOf(item: AreaRef): number {
    return item.id;
  }
  /** A filter restored from a URL pushes `{ id }` stubs; a resolved area carries `descs`. */
  protected override isResolved(item: AreaRef): boolean {
    return item.descs != null;
  }
  protected searchEntities(query: string): Observable<readonly AreaRef[]> {
    return this.api.search(query, { domainId: this.domainId() ?? undefined });
  }
  protected resolveByIds(ids: readonly number[]): Observable<readonly AreaRef[]> {
    return this.api.getByIds(ids);
  }

  /** Search button: the anchored checkbox-tree popover (same surface as the area filter chip). */
  protected openPicker(event: MouseEvent): void {
    if (!(event.currentTarget instanceof HTMLElement)) return;
    const anchor = event.currentTarget;
    if (this.rootsLoaded) {
      this.picker().toggle(anchor);
      return;
    }
    this.rootsLoaded = true;
    this.api
      .getRoots()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (roots) => {
          this.roots.set(roots);
          this.picker().toggle(anchor);
        },
        // Re-arm so the next click retries — a failed open is a dead button otherwise.
        error: () => {
          this.rootsLoaded = false;
          this.notifications.error('common.fetchError');
        },
      });
  }

  /** Live-apply: the picker emits the FULL selection on every checkbox toggle. */
  protected onPickerChange(areas: readonly AreaRef[]): void {
    this.onSelect(areas);
  }
}
