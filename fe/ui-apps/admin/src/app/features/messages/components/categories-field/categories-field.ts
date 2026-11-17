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
import type { CategoryRef } from '../../models/messages-filter-params.model';
import { CategoriesApiService } from '../../services/api/categories-api.service';

function categoryLabel(category: CategoryRef): string {
  return category.descs?.[0]?.name ?? `category-${category.id}`;
}

@Component({
  selector: 'app-categories-field',
  templateUrl: './categories-field.html',
  imports: [EntityMultiselectControl, TreePicker],
})
export class CategoriesField extends EntityMultiselectField<CategoryRef, number> {
  private readonly api = inject(CategoriesApiService);
  private readonly notifications = inject(NotificationService);
  private readonly uiLanguage = inject(LanguageService);

  readonly domainId = input<string | null>(null);
  override readonly optionLabel = categoryLabel;
  override readonly optionSuffix = parentBreadcrumb;
  override readonly magnifier: EntityMultiselectMagnifier = {
    icon: 'lucideSearch',
    tooltipKey: 'messages.filter.category.dialog.openTooltip',
    onClick: (event) => this.openPicker(event),
  };

  private readonly picker = viewChild.required(TreePicker<CategoryRef>);
  /** Root hierarchy for the browse popover, loaded on first open + reloaded on language change. */
  protected readonly roots = signal<readonly CategoryRef[]>([]);
  private rootsLoaded = false;

  protected readonly selectedIdList = computed(() => this.value().map((category) => category.id));

  constructor() {
    super();
    // Reload the picker tree in the new language once it's been opened (node labels are
    // server-translated) — same contract as the category filter chip's EntityFilterPanelBase.
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

  protected idOf(item: CategoryRef): number {
    return item.id;
  }
  /** A filter restored from a URL pushes `{ id }` stubs; a resolved category carries `descs`. */
  protected override isResolved(item: CategoryRef): boolean {
    return item.descs != null;
  }
  protected searchEntities(query: string): Observable<readonly CategoryRef[]> {
    return this.api.search(query, { domainId: this.domainId() ?? undefined });
  }
  protected resolveByIds(ids: readonly number[]): Observable<readonly CategoryRef[]> {
    return this.api.getByIds(ids);
  }

  /** Search button: the anchored checkbox-tree popover (same surface as the category filter chip). */
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
  protected onPickerChange(categories: readonly CategoryRef[]): void {
    this.onSelect(categories);
  }
}
