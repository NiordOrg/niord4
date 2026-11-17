import { Injectable, inject } from '@angular/core';

import { AreasApiService } from '../../../../messages/services/api/areas-api.service';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { hierarchicalTreeState } from '../../../shell/hierarchical-tree-state';

/**
 * Page-scoped tree state for the Areas admin section. Loads the area roots (with nested children) via
 * the read-side `AreasApiService.getRoots`; the shared `hierarchicalTreeState` helper handles the
 * language-switch refetch + `refresh()` after every mutation.
 */
@Injectable()
export class AreasFacade {
  private readonly api = inject(AreasApiService);
  private readonly tree = hierarchicalTreeState<AreaRef>((lang) =>
    this.api.getRoots({ language: lang }),
  );

  readonly areas = this.tree.entities;
  readonly loading = this.tree.loading;
  readonly error = this.tree.error;

  refresh(): void {
    this.tree.refresh();
  }
}
