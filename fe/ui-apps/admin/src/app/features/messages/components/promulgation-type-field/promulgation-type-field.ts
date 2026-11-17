import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { Observable } from 'rxjs';

import { EntityMultiselectControl } from '../../../../shared/forms/entity-multiselect-control/entity-multiselect-control';
import { EntityMultiselectField } from '../../../../shared/forms/entity-multiselect-field';
import type { PromulgationTypeRef } from '../../models/messages-filter-params.model';
import { PromulgationTypesApiService } from '../../services/api/promulgation-types-api.service';

function promulgationLabel(type: PromulgationTypeRef): string {
  return type.name ?? type.typeId;
}

@Component({
  selector: 'app-promulgation-type-field',
  templateUrl: './promulgation-type-field.html',
  imports: [EntityMultiselectControl, TranslocoPipe],
})
export class PromulgationTypeField extends EntityMultiselectField<PromulgationTypeRef, string> {
  private readonly api = inject(PromulgationTypesApiService);

  override readonly optionLabel = promulgationLabel;

  protected idOf(item: PromulgationTypeRef): string {
    return item.typeId;
  }
  /** A restore stub carries only `typeId`; a resolved ref carries `name`. */
  protected override isResolved(item: PromulgationTypeRef): boolean {
    return item.name != null;
  }
  protected searchEntities(query: string): Observable<readonly PromulgationTypeRef[]> {
    return this.api.search(query);
  }
  protected resolveByIds(ids: readonly string[]): Observable<readonly PromulgationTypeRef[]> {
    return this.api.getByIds(ids);
  }
}
