import { Component, computed, input, output } from '@angular/core';
import type { Feature as OlFeature } from 'ol';
import { TranslocoPipe } from '@jsverse/transloco';

import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../forms/localized-inputs/localized-inputs';
import { Select } from '../../../forms/select/select';
import {
  BUFFER_RADIUS,
  BUFFER_RADIUS_TYPE,
  isBufferFeature,
  RESTRICTION,
} from '../util/buffer-feature';
import type { RadiusUnit } from '../util/ol-buffer';
import type { GeometryEditType } from '../geometry-editor';

/** The restriction values (the `restriction` field); '' = none. */
const RESTRICTIONS = [
  'affected',
  'restricted',
  'speed-restricted',
  'prohibited',
  'stopping-prohibited',
] as const;
const RADIUS_UNITS: readonly RadiusUnit[] = ['m', 'km', 'nm'];
/** Restriction dropdown options, `''` first (= none). */
const RESTRICTION_OPTIONS: readonly string[] = ['', ...RESTRICTIONS];

/**
 * The per-feature property fields, shown inside a geometry card
 * when it is selected: the name per language, restriction, and — for an affected-area buffer
 * feature — the radius + unit. Editing a property writes it onto the feature and emits
 * `(changed)` so the host re-renders the map + recomputes dependent buffers.
 */
@Component({
  selector: 'app-feature-property-panel',
  imports: [TranslocoPipe, LocalizedInputs, Select],
  template: `
    <div
      class="flex flex-col gap-2 border-b border-surface-200 px-2 py-2 text-xs dark:border-surface-700"
    >
      @if (editType() === 'message') {
        <div class="flex flex-col gap-1">
          <span class="text-fg-muted">{{ 'geometry.property.name' | transloco }}</span>
          <app-localized-inputs
            [languages]="languages()"
            [value]="namesRecord()"
            [stacked]="true"
            (valueInput)="onNameInput($event)"
          />
        </div>
      }

      @if (isBuffer()) {
        <label class="flex items-center gap-2">
          <span class="shrink-0 text-fg-muted">{{ 'geometry.property.radius' | transloco }}</span>
          <input
            #radiusInput
            type="number"
            min="0"
            step="0.1"
            class="text-input w-20"
            [value]="radius()"
            [attr.aria-label]="'geometry.property.radius' | transloco"
            (change)="setRadius(radiusInput.value)"
          />
          <select
            #unitSelect
            class="text-input w-auto"
            [attr.aria-label]="'geometry.property.radiusUnit' | transloco"
            (change)="setRadiusUnit(unitSelect.value)"
          >
            <!-- [selected] per option, not [value] on the select: Angular applies a select's
                 value binding before its @for options exist, so a non-default value never sticks on
                 a freshly-rendered panel (e.g. after undo/redo re-select). -->
            @for (unit of units; track unit) {
              <option [value]="unit" [selected]="unit === radiusUnit()">{{ unit }}</option>
            }
          </select>
        </label>
      }

      @if (editType() === 'message') {
        <div class="flex items-center gap-2">
          <span class="shrink-0 text-fg-muted">{{
            'geometry.property.restriction' | transloco
          }}</span>
          <app-select
            class="min-w-0 flex-1"
            [options]="restrictionOptions"
            [value]="restriction()"
            [ariaLabel]="'geometry.property.restriction' | transloco"
            (valueChange)="setRestriction($any($event))"
          >
            <ng-template #option let-value>{{
              'geometry.restriction.' + (value === '' ? 'none' : value) | transloco
            }}</ng-template>
            <ng-template #selectedOption let-value>{{
              'geometry.restriction.' + (value === '' ? 'none' : value) | transloco
            }}</ng-template>
          </app-select>
        </div>
      }
    </div>
  `,
  host: { class: 'block' },
})
export class FeaturePropertyPanel {
  readonly feature = input.required<OlFeature>();
  readonly languages = input<readonly string[]>([]);
  readonly editType = input<GeometryEditType>('features');
  /** Bumped by the host on external change so the read-back bindings refresh. */
  readonly revision = input<number>(0);

  readonly changed = output<void>();

  protected readonly units = RADIUS_UNITS;
  protected readonly restrictionOptions = RESTRICTION_OPTIONS;

  /** The per-language feature names (`name:<lang>`) as the record the shared inputs render. */
  protected readonly namesRecord = computed<Record<string, string>>(() => {
    this.revision();
    const feature = this.feature();
    const record: Record<string, string> = {};
    for (const lang of this.languages()) {
      const value = feature.get(`name:${lang}`);
      if (typeof value === 'string') record[lang] = value;
    }
    return record;
  });
  /**
   * Every affected-area buffer (radius / path / area) carries an editable radius — the Radius
   * control shows for any buffer feature (one with `parentFeatureIds`), and a positive radius
   * offsets a path into a corridor / an area into an expanded ring, not just a point into a circle.
   */
  protected readonly isBuffer = computed(() => {
    this.revision();
    return isBufferFeature(this.feature());
  });

  protected onNameInput({ lang, value }: LocalizedValueInput): void {
    this.setOrUnset(`name:${lang}`, value.trim());
  }

  protected restriction(): string {
    const value = this.feature().get(RESTRICTION);
    return typeof value === 'string' ? value : '';
  }

  protected setRestriction(value: string): void {
    this.setOrUnset(RESTRICTION, value);
  }

  protected radius(): number | null {
    const value = this.feature().get(BUFFER_RADIUS);
    return typeof value === 'number' ? value : null;
  }

  protected radiusUnit(): RadiusUnit {
    const value = this.feature().get(BUFFER_RADIUS_TYPE);
    return value === 'm' || value === 'km' || value === 'nm' ? value : 'nm';
  }

  protected setRadius(value: string): void {
    const trimmed = value.trim();
    const parsed = Number(trimmed);
    if (trimmed === '' || !Number.isFinite(parsed)) {
      // Blank / non-numeric: keep the last radius — an empty radius never deletes the buffer.
      // Re-emit so the host bumps the revision and the input restores the value.
      this.changed.emit();
      return;
    }
    this.feature().set(BUFFER_RADIUS, Math.max(0, parsed));
    this.changed.emit();
  }

  protected setRadiusUnit(value: string): void {
    this.feature().set(BUFFER_RADIUS_TYPE, value);
    this.changed.emit();
  }

  private setOrUnset(key: string, value: string): void {
    if (value.length > 0) {
      this.feature().set(key, value);
    } else {
      this.feature().unset(key);
    }
    this.changed.emit();
  }
}
