import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { FormField, type FieldTree } from '@angular/forms/signals';

import { Icon } from '../../../../../shared/icons/icon';
import { ChartsField } from '../../../../messages/components/charts-field/charts-field';
import type { MessageChart } from '../../../../messages/models/message.model';
import type { ChartRef } from '../../../../messages/models/messages-filter-params.model';
import type { ComputedMessageChart } from '../../../../messages/services/api/charts-api.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * Default scale assumed for a chart that carries none, so scale-less charts sort last.
 */
const DEFAULT_CHART_SCALE = 10_000_000;

/**
 * Sort charts ascending by scale (most-detailed first); a chart without a scale sorts last.
 * Pure so it is unit-testable without a TestBed, and shared by the component's Sort button.
 */
export function sortChartsByScale(charts: readonly MessageChart[]): MessageChart[] {
  return [...charts].sort(
    (a, b) =>
      ((a as ComputedMessageChart).scale ?? DEFAULT_CHART_SCALE) -
      ((b as ComputedMessageChart).scale ?? DEFAULT_CHART_SCALE),
  );
}

/** The scale/active wire fields a `/charts/search` result carries that `ChartRef` does not declare. */
type ChartSearchResult = ChartRef & { readonly active?: boolean; readonly scale?: number };

/**
 * Prune a picker `ChartRef` to the subset the message stores, keeping `scale` (and `active`) when the
 * wire carries them — so a later Sort-by-scale works on picker-selected charts too. Only the
 * search-only fields (name / fullChartNumber / horizontalDatum / geometry) are dropped.
 */
export function toMessageChart(chart: ChartRef): ComputedMessageChart {
  const result = chart as ChartSearchResult;
  return {
    chartNumber: result.chartNumber,
    internationalNumber: result.internationalNumber,
    ...(result.active === undefined ? {} : { active: result.active }),
    ...(result.scale === undefined ? {} : { scale: result.scale }),
  };
}

/**
 * The `charts` field row (sticky-visible): the message's sea charts + the `horizontalDatum` text.
 *
 * The CHARTS picker (reused `ChartsField`) is structural — its selection flows to
 * `facade.setCharts` (mark dirty, no adjust). **Sort by scale** re-orders the current list
 * client-side through the same mutator ({@link sortChartsByScale}). **Compute from locations**
 * (enabled once a part defines geometry) routes to the facade's `computeCharts` action. The
 * {@link datumField} (`form.horizontalDatum`, `FieldTree<string>`) backs the datum input via
 * `[formField]` — the single form sync point.
 */
@Component({
  selector: 'app-charts-field-row',
  imports: [TranslocoPipe, FormField, Icon, EditorFieldRow, ChartsField],
  template: `
    <app-editor-field-row
      fieldId="charts"
      label="msg.field.charts"
      [edited]="fields.edited('charts')"
      [empty]="charts().length === 0 && !datumControl().value()"
      emptyLabel="editor.field.charts.empty"
    >
      <span fieldViewer>{{ chartSummary() }}</span>

      <div fieldEditor class="flex flex-col gap-3">
        <app-charts-field
          [value]="charts()"
          [disabled]="!facade.editableNow()"
          placeholder="editor.field.charts.pickerPlaceholder"
          (valueChange)="onChartsChange($event)"
        />

        <div class="flex flex-wrap items-center gap-2">
          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded border border-surface-300 px-2.5 py-1 text-xs disabled:opacity-50 dark:border-surface-600"
            [disabled]="!facade.editableNow() || charts().length < 2"
            (click)="onSort()"
          >
            <app-icon name="lucideArrowDownWideNarrow" />
            <span>{{ 'editor.charts.sort' | transloco }}</span>
          </button>
          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded border border-surface-300 px-2.5 py-1 text-xs disabled:opacity-50 dark:border-surface-600"
            [disabled]="!facade.editableNow() || !definesGeometry()"
            (click)="facade.computeCharts()"
          >
            <app-icon name="lucideMapPin" />
            <span>{{ 'editor.charts.computeFromLocations' | transloco }}</span>
          </button>
        </div>

        <label class="flex flex-col gap-1 text-sm">
          <span class="text-fg-subtle">{{
            'editor.field.charts.horizontalDatum' | transloco
          }}</span>
          <input class="text-input w-56" [formField]="datumField()" />
        </label>
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class ChartsFieldRow {
  /** The `horizontalDatum` slice of the host's form model. */
  readonly datumField = input.required<FieldTree<string>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);

  protected readonly datumControl = computed(() => this.datumField()());

  protected readonly charts = computed<readonly MessageChart[]>(
    () => this.facade.message()?.charts ?? [],
  );

  protected readonly chartSummary = computed<string>(() =>
    this.charts()
      .map((chart) => chart.chartNumber)
      .filter((number) => !!number)
      .join(', '),
  );

  protected readonly definesGeometry = computed<boolean>(() =>
    (this.facade.message()?.parts ?? []).some((part) => (part.geometry?.features?.length ?? 0) > 0),
  );

  /** The picker committed a new selection — prune each `ChartRef` to the stored `MessageChart` shape. */
  protected onChartsChange(charts: readonly ChartRef[]): void {
    this.facade.setCharts(charts.map(toMessageChart));
  }

  /** Re-order the current charts by scale through the same membership mutator. */
  protected onSort(): void {
    this.facade.setCharts(sortChartsByScale(this.charts()));
  }
}
