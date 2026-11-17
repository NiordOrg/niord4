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
import { form, FormField, pattern, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { CodeTextInput } from '../../../components/code-text-input/code-text-input';
import type { CodeLanguage } from '../../../components/code-text-input/codemirror-editor';
import {
  ScriptResource,
  ScriptResourcesApiService,
} from '../../../services/api/script-resources-api.service';
import { ScriptResourceHistoryDialog } from './script-resource-history-dialog';

export interface ScriptResourceEditorModel {
  readonly mode: 'add' | 'edit';
  readonly resource: ScriptResource;
}

interface ScriptResourceFormModel {
  type: ScriptResource['type'];
  path: string;
  content: string;
}

/** Per-type path patterns — FM resources end `.ftl`, JS resources end `.js` (case-insensitive). */
const PATH_PATTERN: Record<ScriptResource['type'], RegExp> = {
  FM: /^.+\.ftl$/i,
  JS: /^.+\.js$/i,
};

/**
 * Create/edit/copy form for a script resource, hosted in the shared drawer (sized
 * wide for the code editor). The type is fixed at creation (it drives the editor
 * language + the path pattern), the path is required + type-patterned, and the
 * content is the FM/JS source. History is available only once the resource has an
 * id. Save gates on no-net-change OR invalid (revert-aware).
 */
@Component({
  selector: 'app-script-resource-editor',
  imports: [Drawer, FormField, CodeTextInput, ScriptResourceHistoryDialog, TranslocoPipe],
  templateUrl: './script-resource-editor.html',
  host: { class: 'contents' },
})
export class ScriptResourceEditor {
  private readonly api = inject(ScriptResourcesApiService);
  private readonly notifications = inject(NotificationService);

  readonly model = input.required<ScriptResourceEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  private readonly historyDialog = viewChild.required(ScriptResourceHistoryDialog);

  protected readonly saving = signal(false);

  private readonly formModel = signal<ScriptResourceFormModel>({
    type: 'FM',
    path: '',
    content: '',
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.path);
    // Per-type path pattern (FM → *.ftl, JS → *.js); type is fixed at creation.
    pattern(path.path, () => PATH_PATTERN[this.formModel().type]);
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

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.scriptResources.editor.editTitle'
      : 'admin.scriptResources.editor.addTitle',
  );

  /** CodeMirror language for the content editor — JS resources get the JS grammar, FM the FTL mode. */
  protected readonly editorLanguage = computed<CodeLanguage>(() =>
    this.model()?.resource.type === 'JS' ? 'javascript' : 'ftl',
  );

  /** History is only meaningful for a persisted resource (it keys on the numeric id). */
  protected readonly canShowHistory = computed(() => this.model()?.resource.id !== undefined);

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // Rebuild only on (re)open — never mid-edit.
        untracked(() => this.rebuildForm(model));
      }
    });
  }

  private rebuildForm(model: ScriptResourceEditorModel): void {
    const next: ScriptResourceFormModel = {
      type: model.resource.type,
      path: model.resource.path,
      content: model.resource.content,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected openHistory(): void {
    const id = this.model()?.resource.id;
    if (id !== undefined) {
      this.historyDialog().open(id, this.model()!.resource.type);
    }
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const raw = this.formModel();
    const resource: ScriptResource = {
      id: model.resource.id,
      type: raw.type,
      path: raw.path,
      content: raw.content,
    };
    const request = model.mode === 'add' ? this.api.create(resource) : this.api.update(resource);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.scriptResources.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.scriptResources.toast.saveError');
      },
    });
  }
}
