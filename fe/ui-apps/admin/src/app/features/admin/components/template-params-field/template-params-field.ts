import { Component, computed, inject, input, model, output, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';

import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import { Icon } from '../../../../shared/icons/icon';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { ModelLanguageSelection, type ModelText } from '../../services/model-language-selection';
import { ModelLanguagePicker } from '../model-language-picker/model-language-picker';
import {
  ParamType,
  ParamTypeKind,
  TemplateParam,
} from '../../services/api/param-types-admin-api.service';
import { TemplateParamDialog } from './template-param-dialog';

/** Row drag-reorder payload (drag/drop row indices). */
export interface TemplateParamReorder {
  readonly dragIndex: number | null;
  readonly dropIndex: number | null;
}

/**
 * Reusable editor for a list of nested template parameters (COMPOSITE param types
 * AND TEMPLATE categories consume it). A reorderable table (keyboard move up/down)
 * with add/edit (via `TemplateParamDialog`, which refuses an id another row holds) /
 * delete; `[types]` restricts the dialog's
 * referenced-type pool (COMPOSITE passes `STANDARD,LIST`). Two-way `templateParams`
 * carries the array; `(paramsChanged)` fires after any mutation so the host can
 * mark itself dirty.
 */
@Component({
  selector: 'app-template-params-field',
  imports: [ErrorBanner, Icon, TemplateParamDialog, TranslocoPipe, ModelLanguagePicker],
  templateUrl: './template-params-field.html',
  host: { class: 'block' },
})
export class TemplateParamsField {
  protected readonly modelLanguage =
    inject(ModelLanguageSelection, { optional: true }) ?? new ModelLanguageSelection();
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  readonly templateParams = model<TemplateParam[]>([]);
  readonly types = input('STANDARD,LIST,COMPOSITE');
  readonly paramsChanged = output<void>();

  private readonly dialog = viewChild.required(TemplateParamDialog);
  /** Index being edited; null = adding a new param. */
  private editIndex: number | null = null;

  // All param types — the source for the dialog's referenced-type pool + kind labels.
  protected readonly typesResource = httpResource<readonly ParamType[]>(
    () => `${this.apiBaseUrl}/templates/parameter-types`,
    { defaultValue: [] },
  );

  // value() throws in the resource's error state, so the read is guarded by hasValue(); a failed
  // read is said above the table, with a retry, so an empty type pool is never taken for none.
  private readonly allTypes = computed<readonly ParamType[]>(() =>
    this.typesResource.hasValue() ? this.typesResource.value() : [],
  );

  private readonly allowedKinds = computed(() =>
    this.types()
      .split(',')
      .map((kind) => kind.trim()),
  );

  protected readonly editableTypeNames = computed(() =>
    this.allTypes()
      .filter((type) => type.name !== undefined && this.allowedKinds().includes(type.type))
      .map((type) => type.name as string),
  );

  private readonly kindByName = computed(() => {
    const map = new Map<string, ParamTypeKind>();
    for (const type of this.allTypes()) {
      if (type.name) {
        map.set(type.name, type.type);
      }
    }
    return map;
  });

  protected readonly rows = computed(() => [...this.templateParams()]);

  /** Selected model-language name for the table cell, with default-language fallback. */
  protected currentName(param: TemplateParam): ModelText {
    return this.modelLanguage.text(param.descs, 'name');
  }

  /** i18n key for the referenced type's kind (localized in the template), or '' if unknown. */
  protected kindKey(param: TemplateParam): string {
    const kind = this.kindByName().get(param.type);
    return kind ? `admin.paramTypes.kind.${kind}` : '';
  }

  protected add(): void {
    this.editIndex = null;
    this.dialog().open(null, this.editableTypeNames(), this.idsBesides(null));
  }

  protected edit(index: number): void {
    this.editIndex = index;
    this.dialog().open(
      this.templateParams()[index],
      this.editableTypeNames(),
      this.idsBesides(index),
    );
  }

  /** The ids of every row but `index` (all rows when adding): the dialog refuses them. */
  private idsBesides(index: number | null): string[] {
    return this.templateParams()
      .filter((_, i) => i !== index)
      .map((param) => param.paramId);
  }

  protected onCommitted(param: TemplateParam): void {
    const next = [...this.templateParams()];
    if (this.editIndex === null) {
      next.push(param);
    } else {
      next[this.editIndex] = param;
    }
    this.templateParams.set(next);
    this.paramsChanged.emit();
  }

  protected remove(index: number): void {
    this.templateParams.set(this.templateParams().filter((_, i) => i !== index));
    this.paramsChanged.emit();
  }

  protected moveUp(index: number): void {
    if (index > 0) {
      this.swap(index, index - 1);
    }
  }

  protected moveDown(index: number): void {
    if (index < this.templateParams().length - 1) {
      this.swap(index, index + 1);
    }
  }

  private swap(a: number, b: number): void {
    const next = [...this.templateParams()];
    [next[a], next[b]] = [next[b], next[a]];
    this.templateParams.set(next);
    this.paramsChanged.emit();
  }

  protected onRowReorder(event: TemplateParamReorder): void {
    const { dragIndex, dropIndex } = event;
    if (dragIndex == null || dropIndex == null || dragIndex === dropIndex) {
      return;
    }
    const next = [...this.templateParams()];
    const [moved] = next.splice(dragIndex, 1);
    next.splice(dropIndex, 0, moved);
    this.templateParams.set(next);
    this.paramsChanged.emit();
  }
}
