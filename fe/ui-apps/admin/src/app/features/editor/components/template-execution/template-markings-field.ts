import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Select } from '../../../../shared/forms/select/select';
import { Icon } from '../../../../shared/icons/icon';
import type { ParamType, TemplateListValue } from '../../../messages/models/template.model';
import { RemoteValidatedInput } from './remote-validated-input';

/** One marking row (a buoy or an other-marking) stored on a template's `templateData.markings`. */
export interface Marking {
  type?: TemplateListValue;
  color?: TemplateListValue;
  bearing?: TemplateListValue;
  lightCharacter?: string;
  distance?: number;
}

/**
 * The bespoke `markings` standard template field (the sample's templates name it,
 * be/niord/sample/archive/categories.json): a mode toggle (Unmarked / Buoys / Other
 * markings) and, for the two marked modes,
 * an editable table of markings. Buoy rows carry an AtoN type + light character + distance + bearing;
 * other-marking rows carry a colour + type + distance + bearing. The dropdown options come from the
 * `marking_type_list` / `marking_bearing` / `other_marking_*` parameter types. Value-in / value-out.
 */
@Component({
  selector: 'app-template-markings-field',
  imports: [TranslocoPipe, Select, Icon, RemoteValidatedInput],
  templateUrl: './template-markings-field.html',
  host: { class: 'block' },
})
export class TemplateMarkingsField {
  readonly markings = input<readonly Marking[]>([]);
  readonly markingType = input<string>('unmarked');
  readonly paramTypes = input<ReadonlyMap<string, ParamType>>(new Map());
  readonly lang = input<string>('en');

  readonly markingsChange = output<readonly Marking[]>();
  readonly markingTypeChange = output<string>();

  /** LIST option sets from the parameter-type catalogue (empty until they load). */
  protected readonly buoyTypes = computed<readonly TemplateListValue[]>(
    () => this.paramTypes().get('marking_type_list')?.values ?? [],
  );
  protected readonly bearings = computed<readonly TemplateListValue[]>(
    () => this.paramTypes().get('marking_bearing')?.values ?? [],
  );
  protected readonly otherColors = computed<readonly TemplateListValue[]>(
    () => this.paramTypes().get('other_marking_color_list')?.values ?? [],
  );
  protected readonly otherTypes = computed<readonly TemplateListValue[]>(
    () => this.paramTypes().get('other_marking_type_list')?.values ?? [],
  );

  /** The markings field is only usable once its list parameter types are configured. */
  protected readonly available = computed<boolean>(
    () =>
      !!this.paramTypes().get('marking_type_list') && !!this.paramTypes().get('marking_bearing'),
  );

  /** Passed to `Select.optionLabel` — a LIST value's active-language display value. */
  protected readonly labelFor = (value: TemplateListValue): string =>
    value?.descs?.find((d) => d.lang === this.lang())?.value ??
    value?.descs?.[0]?.value ??
    value?.key ??
    '';

  /** A light character only applies to light-type AtoNs (the type key contains 'light'). */
  protected isLightType(marking: Marking): boolean {
    const key = marking.type?.key ?? '';
    return key.indexOf('light') !== -1;
  }

  /** Switch mode: unmarked clears the table; the two marked modes reset it to a single blank row. */
  protected setMode(mode: string): void {
    this.markingTypeChange.emit(mode);
    this.markingsChange.emit(mode === 'unmarked' ? [] : [{}]);
  }

  protected addMarking(): void {
    this.markingsChange.emit([...this.markings(), {}]);
  }

  protected deleteMarking(index: number): void {
    this.markingsChange.emit(this.markings().filter((_, i) => i !== index));
  }

  protected setMarking(index: number, patch: Partial<Marking>): void {
    this.markingsChange.emit(
      this.markings().map((marking, i) => (i === index ? { ...marking, ...patch } : marking)),
    );
  }

  protected toNumber(raw: string): number | undefined {
    return raw === '' ? undefined : Number(raw);
  }
}
