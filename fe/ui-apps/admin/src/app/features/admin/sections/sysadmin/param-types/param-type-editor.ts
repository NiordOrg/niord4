import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { TemplateParamsField } from '../../../components/template-params-field/template-params-field';
import type { DictionaryEntry } from '../../../services/api/dictionaries-api.service';
import {
  ParamType,
  ParamTypesAdminApiService,
  TemplateParam,
} from '../../../services/api/param-types-admin-api.service';
import { ListValuesEditor } from './list-values-editor';

export interface ParamTypeEditorModel {
  readonly mode: 'add' | 'edit';
  /** Full param type (detail-loaded on edit/copy; skeleton on add). Its `type` drives the kind sub-view. */
  readonly paramType: ParamType;
}

interface ParamTypeFormModel {
  name: string;
}

/**
 * Create/edit/copy form for a LIST or COMPOSITE parameter type, hosted in the
 * shared drawer. STANDARD types never reach this editor (read-only). A small
 * reactive `name` form plus a kind-specific value editor (LIST transfer list or
 * COMPOSITE nested-param field) bridged into the dirty/save gate.
 */
@Component({
  selector: 'app-param-type-editor',
  imports: [Drawer, FormField, ListValuesEditor, TemplateParamsField, TranslocoPipe],
  templateUrl: './param-type-editor.html',
  host: { class: 'contents' },
})
export class ParamTypeEditor {
  private readonly api = inject(ParamTypesAdminApiService);
  private readonly notifications = inject(NotificationService);

  readonly model = input.required<ParamTypeEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  /** Set true on any value-editor edit; the arrays are not revert-tracked. */
  protected readonly valueDirty = signal(false);

  protected readonly listValues = signal<DictionaryEntry[]>([]);
  protected readonly compositeParams = signal<TemplateParam[]>([]);

  private readonly formModel = signal<ParamTypeFormModel>({ name: '' });
  protected readonly form = form(this.formModel, (path) => {
    required(path.name);
  });

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly kind = computed(() => this.model()?.paramType.type);

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.paramTypes.editor.editTitle'
      : 'admin.paramTypes.editor.addTitle',
  );

  /** The value editors (arrays) aren't revert-tracked — so OR in valueDirty. */
  protected readonly dirty = computed(() => this.changed() || this.valueDirty());

  protected readonly saveDisabled = computed(
    () => !this.dirty() || this.form().invalid() || this.saving(),
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => this.rebuildForm(model));
      }
    });
  }

  private rebuildForm(model: ParamTypeEditorModel): void {
    const next: ParamTypeFormModel = { name: model.paramType.name ?? '' };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.listValues.set([...(model.paramType.values ?? [])]);
    this.compositeParams.set([...(model.paramType.templateParams ?? [])]);
    this.valueDirty.set(false);
    this.saving.set(false);
  }

  protected onValueChanged(): void {
    this.valueDirty.set(true);
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const kind = model.paramType.type;
    const paramType: ParamType = {
      id: model.paramType.id,
      type: kind,
      name: this.formModel().name,
      ...(kind === 'LIST' ? { values: this.listValues() } : {}),
      ...(kind === 'COMPOSITE' ? { templateParams: this.compositeParams() } : {}),
    };
    const request = model.mode === 'add' ? this.api.create(paramType) : this.api.update(paramType);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.paramTypes.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.paramTypes.toast.saveError');
      },
    });
  }
}
