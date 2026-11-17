import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import type { Domain } from '../../../../../core/domain/models/domain.model';
import { DomainMultiselectField } from '../../../../../core/domain/components/domain-multiselect-field/domain-multiselect-field';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { MessageIdField } from '../../../../messages/components/message-id-field/message-id-field';
import { EditorFieldsGrid } from '../../../components/editor-fields-grid/editor-fields-grid';
import { ScriptResourcesField } from '../../../components/script-resources-field/script-resources-field';
import { TemplateParamsField } from '../../../components/template-params-field/template-params-field';
import type { TemplateParam } from '../../../services/api/param-types-admin-api.service';
import {
  type AdminCategory,
  type CategoryType,
  CategoriesAdminApiService,
} from '../../../services/api/categories-admin-api.service';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import { EDITOR_FIELD_CANDIDATES } from '../../../utils/editor-field-candidates';
import { CategoryTemplateResultDialog } from './category-template-result-dialog';

export interface CategoryEditorModel {
  readonly mode: 'add' | 'edit';
  readonly category: AdminCategory;
}

/**
 * The standard-template-field pool (the deployment's `stdTemplateFields` setting) — the standard message
 * fields a template's execute form can show. Pinned like the editor-field pool (the anon web settings
 * don't expose it); labelled via `msg.field.<id>` where present.
 */
const STD_TEMPLATE_FIELDS: readonly string[] = [
  'type',
  'promulgation',
  'id',
  'publish_date',
  'areas',
  'positions',
  'event_dates',
  'work_vessel',
  'markings',
];

const CATEGORY_TYPES: readonly CategoryType[] = ['CATEGORY', 'TEMPLATE'];

interface CategoryFormModel {
  active: boolean;
  names: Record<string, string>;
  mrn: string;
  type: CategoryType;
  atonFilter: string;
  messageId: string | null;
}

const EMPTY: CategoryFormModel = {
  active: true,
  names: {},
  mrn: '',
  type: 'CATEGORY',
  atonFilter: '',
  messageId: null,
};

/**
 * The inline category editor (the right pane of the master-detail Categories page). Signal Forms over
 * a category working copy: active, per-language names (first required), MRN, additional editor fields,
 * AtoN filter, and a CATEGORY|TEMPLATE type radio. When `type === TEMPLATE`, a template-authoring
 * block appears — domains, standard template fields, template parameters, script resources, and an
 * example message with a "Test…" execute-preview. Mirrors the Areas editor minus geometry / sorting.
 */
@Component({
  selector: 'app-category-editor',
  imports: [
    FormField,
    LocalizedTextInput,
    EditorFieldsGrid,
    DomainMultiselectField,
    TemplateParamsField,
    ScriptResourcesField,
    MessageIdField,
    CategoryTemplateResultDialog,
    TranslocoPipe,
  ],
  templateUrl: './category-editor.html',
  host: { class: 'flex min-h-0 flex-1 flex-col' },
})
export class CategoryEditor {
  private readonly api = inject(CategoriesAdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly language = inject(LanguageService);

  /** `undefined` = nothing selected (empty pane); the page owns which category is being edited. */
  readonly model = input.required<CategoryEditorModel | undefined>();
  readonly saved = output<void>();
  readonly cancelled = output<void>();
  readonly dirtyChange = output<boolean>();
  /** The live-edited type (mirrors the radio) — the page gates "Add" off this, not the loaded snapshot. */
  readonly typeChange = output<CategoryType>();

  private readonly resultDialog = viewChild.required(CategoryTemplateResultDialog);

  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly editorFieldCandidates = EDITOR_FIELD_CANDIDATES;
  protected readonly stdTemplateFieldCandidates = STD_TEMPLATE_FIELDS;
  protected readonly categoryTypes = CATEGORY_TYPES;
  protected readonly languages = this.language.modelLanguages;

  private readonly formModel = signal<CategoryFormModel>(EMPTY);
  protected readonly form = form(this.formModel);

  // List-y fields managed outside the Signal-Forms model (bound via [value]/(valueChange)).
  protected readonly editorFields = signal<readonly string[]>([]);
  protected readonly stdTemplateFields = signal<readonly string[]>([]);
  protected readonly domains = signal<readonly Domain[]>([]);
  protected readonly templateParams = signal<TemplateParam[]>([]);
  protected readonly scriptResourcePaths = signal<readonly string[]>([]);

  protected readonly names = computed(() => this.formModel().names);
  protected readonly isTemplate = computed(() => this.formModel().type === 'TEMPLATE');
  /** Read-only parent name shown in add-mode only (reparent is drag-and-drop only). */
  protected readonly parentName = computed<string>(
    () => this.model()?.category.parent?.descs?.[0]?.name ?? '',
  );
  protected readonly testDisabled = computed(() => !this.formModel().messageId || this.testing());

  private readonly baselineJson = signal('');
  private readonly stateJson = computed(
    () =>
      JSON.stringify(this.formModel()) +
      JSON.stringify(this.editorFields()) +
      JSON.stringify(this.stdTemplateFields()) +
      JSON.stringify(this.domains()) +
      JSON.stringify(this.templateParams()) +
      JSON.stringify(this.scriptResourcePaths()),
  );
  protected readonly changed = computed(() => this.stateJson() !== this.baselineJson());

  protected readonly firstNameFilled = computed<boolean>(() => {
    const first = this.language.defaultLanguage;
    return !!first && (this.formModel().names[first] ?? '').trim().length > 0;
  });

  protected readonly saveDisabled = computed(
    () => !this.changed() || !this.firstNameFilled() || this.form().invalid() || this.saving(),
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => this.rebuildForm(model));
      }
    });
    // Dirty is only meaningful while a category is open (the empty-state baseline reads dirty).
    effect(() => this.dirtyChange.emit(this.model() !== undefined && this.changed()));
    // Mirror the live type to the page (drives the toolbar Add-disabled state) while a model is open.
    effect(() => {
      const type = this.formModel().type;
      if (this.model() !== undefined) this.typeChange.emit(type);
    });
  }

  private rebuildForm(model: CategoryEditorModel): void {
    const category = model.category;
    const names: Record<string, string> = {};
    for (const option of this.languages) {
      names[option.value] = category.descs?.find((d) => d.lang === option.value)?.name ?? '';
    }
    const next: CategoryFormModel = {
      active: category.active ?? true,
      names,
      mrn: category.mrn ?? '',
      type: category.type ?? 'CATEGORY',
      atonFilter: category.atonFilter ?? '',
      messageId: category.messageId ?? null,
    };
    this.formModel.set(next);
    this.editorFields.set(category.editorFields ?? []);
    this.stdTemplateFields.set(category.stdTemplateFields ?? []);
    this.domains.set([...(category.domains ?? [])] as Domain[]);
    this.templateParams.set([...(category.templateParams ?? [])]);
    this.scriptResourcePaths.set([...(category.scriptResourcePaths ?? [])]);
    this.baselineJson.set(this.stateJson());
    this.form().reset();
    this.saving.set(false);
    this.testing.set(false);
  }

  protected setName(lang: string, value: string): void {
    this.formModel.update((model) => ({ ...model, names: { ...model.names, [lang]: value } }));
  }
  protected setMessageId(value: string | null): void {
    this.formModel.update((model) => ({ ...model, messageId: value }));
  }

  private buildCategory(): AdminCategory {
    const model = this.model();
    const value = this.formModel();
    const descs = this.languages
      .map((option) => ({
        lang: option.value,
        name: value.names[option.value]?.trim() || undefined,
      }))
      .filter((desc) => desc.name);
    // No `siblingSortOrder`: the backend keeps the stored place among the siblings when the body
    // carries none. The order read when the editor opened goes stale after a Move up/down made while
    // the editor stays open (the page refreshes only the tree), and sending it back would undo the move.
    return {
      id: model?.category.id,
      parent: model?.category.parent
        ? { active: true, descs: [], id: model.category.parent.id }
        : undefined,
      active: value.active,
      mrn: value.mrn.trim() || undefined,
      descs,
      type: value.type,
      editorFields: this.editorFields().length > 0 ? [...this.editorFields()] : undefined,
      atonFilter: value.atonFilter.trim() || undefined,
      domains: this.domains().map((d) => ({ domainId: d.domainId, active: d.active })),
      stdTemplateFields: [...this.stdTemplateFields()],
      templateParams: [...this.templateParams()],
      scriptResourcePaths: this.scriptResourcePaths().filter((path) => path.trim().length > 0),
      messageId: value.messageId || undefined,
    };
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const category = this.buildCategory();
    const request = model.mode === 'add' ? this.api.create(category) : this.api.update(category);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.categories.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.categories.toast.saveError');
      },
    });
  }

  protected test(): void {
    const messageId = this.formModel().messageId;
    if (!messageId || this.testing()) {
      return;
    }
    this.testing.set(true);
    this.api.executeTemplate(this.buildCategory(), messageId).subscribe({
      next: (message) => {
        this.testing.set(false);
        this.resultDialog().open(message);
      },
      error: (error: unknown) => {
        this.testing.set(false);
        // The answer names why the run was refused (an example message outside the
        // current domain, a script that failed): show it; the fixed text stays for an answer
        // without one.
        const detail = problemDetail(error);
        if (detail === undefined) {
          this.notifications.error('admin.categories.toast.testError');
        } else {
          this.notifications.error('admin.categories.toast.testRefused', { detail });
        }
      },
    });
  }
}
