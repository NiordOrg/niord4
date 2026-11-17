import { Component, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { EntityMultiselectControl } from '../../../../shared/forms/entity-multiselect-control/entity-multiselect-control';
import { EntityMultiselectField } from '../../../../shared/forms/entity-multiselect-field';
import type { ChartRef } from '../../models/messages-filter-params.model';
import { ChartsApiService } from '../../services/api/charts-api.service';

@Component({
  selector: 'app-charts-field',
  templateUrl: '../../../../shared/forms/entity-multiselect-field.html',
  imports: [EntityMultiselectControl],
})
export class ChartsField extends EntityMultiselectField<ChartRef, string> {
  private readonly api = inject(ChartsApiService);

  override readonly optionLabel = 'chartNumber';
  override readonly optionSuffix = (chart: ChartRef): string => chart.name ?? '';

  protected idOf(item: ChartRef): string {
    return item.chartNumber;
  }
  protected searchEntities(query: string): Observable<readonly ChartRef[]> {
    return this.api.search(query);
  }
  protected resolveByIds(ids: readonly string[]): Observable<readonly ChartRef[]> {
    return this.api.getByChartNumbers(ids);
  }
}
