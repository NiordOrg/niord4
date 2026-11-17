import { Component, computed, input, output } from '@angular/core';

import { DateTimeField } from '../../../../shared/forms/date-picker/date-time-field';
import { Select } from '../../../../shared/forms/select/select';
import type {
  ParamType,
  TemplateListValue,
  TemplateParam,
} from '../../../messages/models/template.model';
import { RemoteValidatedInput } from './remote-validated-input';

/** One parameter's committed value (the composite renderer forwards its children's). */
export interface ParamChange {
  readonly paramId: string;
  readonly value: unknown;
}

function descName(descs: TemplateParam['descs'], lang: string): string {
  return descs?.find((d) => d.lang === lang)?.name ?? descs?.[0]?.name ?? '';
}

function listLabel(value: TemplateListValue, lang: string): string {
  const descs = value?.descs;
  return descs?.find((d) => d.lang === lang)?.value ?? descs?.[0]?.value ?? value?.key ?? '';
}

/**
 * Renders ONE template parameter as a value-entry control, by its resolved {@link ParamType}:
 * STANDARD (`text`/`number`/`boolean`/`date`; the remote-validated `light_character`/`call_sign`
 * fall through to a plain text input here), LIST (a {@link Select} over the type's dictionary
 * values — the whole value object is stored), or COMPOSITE (self-recursively renders
 * the nested params, one level; each writes its own `paramId` into the shared value record).
 *
 * The component is stateless — it reads the current value from the shared `values` record and emits
 * `(paramChange)` on commit; the owning execute tab holds the record. Scalars commit on blur
 * (`change`) so a partial numeric entry never round-trips through a reformat.
 */
@Component({
  selector: 'app-template-param-field',
  imports: [Select, DateTimeField, RemoteValidatedInput],
  template: `
    @if (kind() === 'COMPOSITE') {
      <fieldset
        class="flex flex-col gap-2 rounded border border-surface-200 p-2 dark:border-surface-700"
      >
        <legend class="px-1 text-xs font-medium text-fg-muted">
          {{ label() }}
          @if (param().mandatory) {
            <span class="text-danger">*</span>
          }
        </legend>
        @for (child of children(); track child.param.paramId) {
          <app-template-param-field
            [param]="child.param"
            [paramType]="child.type"
            [paramTypes]="paramTypes()"
            [values]="values()"
            [lang]="lang()"
            [nested]="true"
            (paramChange)="paramChange.emit($event)"
          />
        }
      </fieldset>
    } @else {
      <div class="flex flex-col gap-1 text-sm">
        <span class="font-medium text-fg-title">
          {{ label() }}
          @if (param().mandatory) {
            <span class="text-danger">*</span>
          }
        </span>

        @switch (kind()) {
          @case ('LIST') {
            <app-select
              [options]="listOptions()"
              [optionLabel]="labelFor"
              [value]="current()"
              (valueChange)="emit($event)"
              [ariaLabel]="label()"
              [ariaRequired]="required()"
              [showClear]="!param().mandatory"
            />
          }
          @default {
            @switch (standardName()) {
              @case ('boolean') {
                <input
                  type="checkbox"
                  class="checkbox self-start"
                  [checked]="!!current()"
                  (change)="emit($any($event.target).checked)"
                  [attr.aria-label]="label()"
                />
              }
              @case ('number') {
                <input
                  type="number"
                  step="any"
                  class="text-input"
                  [value]="numberValue()"
                  (change)="emitNumber($any($event.target).value)"
                  [attr.aria-label]="label()"
                  [attr.aria-required]="required() || null"
                />
              }
              @case ('date') {
                <app-date-time-field [value]="dateValue()" (valueChange)="emitDate($event)" />
              }
              @case ('light_character') {
                <app-remote-validated-input
                  kind="light-character"
                  [value]="current()"
                  (valueChange)="emit($event)"
                  [ariaLabel]="label()"
                  [ariaRequired]="required()"
                />
              }
              @case ('call_sign') {
                <app-remote-validated-input
                  kind="call-sign"
                  [value]="current()"
                  (valueChange)="emit($event)"
                  [ariaLabel]="label()"
                  [ariaRequired]="required()"
                />
              }
              @default {
                <input
                  type="text"
                  class="text-input"
                  [value]="textValue()"
                  (change)="emit($any($event.target).value || undefined)"
                  [attr.aria-label]="label()"
                  [attr.aria-required]="required() || null"
                />
              }
            }
          }
        }
      </div>
    }
  `,
  host: { class: 'block' },
})
export class TemplateParamField {
  readonly param = input.required<TemplateParam>();
  /** The resolved param-type for `param.type` (STANDARD / LIST / COMPOSITE). */
  readonly paramType = input<ParamType | undefined>(undefined);
  /** name → param-type, for resolving COMPOSITE children. */
  readonly paramTypes = input<ReadonlyMap<string, ParamType>>(new Map());
  /** The shared per-template value record: leaf reads `values[param.paramId]`. */
  readonly values = input<Record<string, unknown>>({});
  readonly lang = input<string>('en');
  readonly nested = input<boolean>(false);

  readonly paramChange = output<ParamChange>();

  protected readonly kind = computed(() => this.paramType()?.type ?? 'STANDARD');
  protected readonly standardName = computed(() => this.paramType()?.name ?? '');
  protected readonly label = computed(
    () => descName(this.param().descs, this.lang()) || this.param().paramId,
  );
  /**
   * A mandatory parameter's control says so to assistive technology. Not on a tick box (unticked is a
   * value) nor on the date field, whose trigger is a button, where aria-required is not allowed.
   */
  protected readonly required = computed<boolean>(() => !!this.param().mandatory);
  protected readonly current = computed<unknown>(() => this.values()[this.param().paramId]);
  protected readonly listOptions = computed<readonly TemplateListValue[]>(
    () => this.paramType()?.values ?? [],
  );
  protected readonly children = computed(() =>
    (this.paramType()?.templateParams ?? []).map((child) => ({
      param: child,
      type: this.paramTypes().get(child.type),
    })),
  );

  /** Passed to `Select.optionLabel` — the LIST value's active-language desc. */
  protected readonly labelFor = (value: TemplateListValue): string => listLabel(value, this.lang());

  protected textValue(): string {
    const value = this.current();
    return typeof value === 'string' ? value : '';
  }

  protected numberValue(): string {
    const value = this.current();
    return typeof value === 'number' ? String(value) : typeof value === 'string' ? value : '';
  }

  protected dateValue(): Date | null {
    const value = this.current();
    if (typeof value === 'number') return new Date(value);
    return value instanceof Date ? value : null;
  }

  protected emit(value: unknown): void {
    this.paramChange.emit({ paramId: this.param().paramId, value });
  }

  protected emitNumber(raw: string): void {
    this.emit(raw === '' ? undefined : Number(raw));
  }

  protected emitDate(date: Date | null): void {
    this.emit(date ? date.getTime() : undefined);
  }
}
