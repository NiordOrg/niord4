import { Injectable, inject } from '@angular/core';

import type { CategoryRef } from '../../../../messages/models/message.model';
import { CategoriesApiService } from '../../../../messages/services/api/categories-api.service';
import { hierarchicalTreeState } from '../../../shell/hierarchical-tree-state';

/**
 * Page-scoped tree state for the Categories sysadmin section. Loads the category roots (with nested
 * children) via the read-side `CategoriesApiService.getRoots`; the shared `hierarchicalTreeState`
 * helper handles the language-switch refetch + `refresh()` after every mutation.
 */
@Injectable()
export class CategoriesFacade {
  private readonly api = inject(CategoriesApiService);
  private readonly tree = hierarchicalTreeState<CategoryRef>((lang) =>
    this.api.getRoots({ language: lang }),
  );

  readonly categories = this.tree.entities;
  readonly loading = this.tree.loading;
  readonly error = this.tree.error;

  refresh(): void {
    this.tree.refresh();
  }
}
