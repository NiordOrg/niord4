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
import { applyEach, form, FormField, required, disabled } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { Icon } from '../../../../../shared/icons/icon';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import { padAndOrderDescs } from '../../../utils/localized-descs';
import { MailingListsApiService } from '../../../services/api/mailing-lists-api.service';
import { TriggerCard } from './trigger-card';
import { TriggerTestDialog, type TriggerTestModel } from './trigger-test-dialog';
import {
  formToTrigger,
  newTriggerForm,
  triggerToForm,
  type MailingList,
  type MailingListDesc,
  type TriggerFormModel,
} from './mailing-list.model';

export interface MailingListEditorModel {
  readonly mode: 'add' | 'edit';
  readonly mailingList: MailingList;
}

interface MailingListFormModel {
  mailingListId: string;
  active: boolean;
  descs: MailingListDesc[];
  triggers: TriggerFormModel[];
}

/**
 * Create / edit / copy form for a mailing list, hosted in the shared wide drawer. The id is a
 * user-entered string PK: editable in add/copy, locked in edit. Descs are padded to every
 * configured model language (name required for the first language only); descs in unconfigured
 * languages round-trip unchanged. Copy arrives with the id cleared so save POSTs a create.
 */
@Component({
  selector: 'app-mailing-list-editor',
  imports: [
    Drawer,
    FormField,
    Icon,
    LocalizedTextInput,
    TriggerCard,
    TriggerTestDialog,
    TranslocoPipe,
  ],
  templateUrl: './mailing-list-editor.html',
  host: { class: 'contents' },
})
export class MailingListEditor {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(MailingListsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<MailingListEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  private readonly formModel = signal<MailingListFormModel>({
    mailingListId: '',
    active: true,
    descs: [],
    triggers: [],
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.mailingListId);
    // The string PK is immutable once created (the PUT/DELETE path id).
    disabled(path.mailingListId, { when: () => this.model()?.mode === 'edit' });
    // A scheduled trigger fires at a wall-clock time, which names no instant until the zone
    // it is read in is known — so a scheduled trigger without one cannot be saved.
    applyEach(path.triggers, (trigger) => {
      required(trigger.scheduledExecutionTimeZone, {
        when: (ctx) => ctx.valueOf(trigger.type) === 'SCHEDULED',
      });
    });
  });

  /**
   * The first configured language's name is mandatory;
   * padAndOrderDescs renders configured-list order, so it is `descs[0]`. Gated here rather than in
   * the Signal Forms schema because a per-array-index validator has no ergonomic form-schema
   * expression — this gates Save equivalently.
   */
  private readonly firstNameMissing = computed(() => {
    const first = this.formModel().descs[0];
    return first === undefined || first.name.trim() === '';
  });

  /**
   * Serialize for change detection with the UI-only trigger `expanded` flag stripped — expanding /
   * collapsing a trigger card must NOT mark the form dirty.
   */
  private normalizeForCompare(model: MailingListFormModel): string {
    // `expanded` set to undefined → JSON.stringify omits it, so expand/collapse never diffs.
    return JSON.stringify({
      ...model,
      triggers: model.triggers.map((trigger) => ({ ...trigger, expanded: undefined })),
    });
  }

  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => this.normalizeForCompare(this.formModel()) !== this.baselineJson(),
  );
  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.firstNameMissing() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.mailingLists.editor.editTitle'
      : 'admin.mailingLists.editor.addTitle',
  );

  /** The trigger index whose test dialog is open (`undefined` = closed). Wired to the dialog below. */
  protected readonly testTriggerIndex = signal<number | undefined>(undefined);

  protected onTestTrigger(index: number): void {
    this.testTriggerIndex.set(index);
  }

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — a mid-edit language switch must not
        // wipe unsaved input.
        untracked(() => this.rebuildForm(model.mailingList));
      }
    });
  }

  private rebuildForm(mailingList: MailingList): void {
    const langs = this.languageSettings.modelLanguages.map((language) => language.value);
    const padded = padAndOrderDescs(
      mailingList.descs,
      langs,
      (lang): MailingListDesc => ({ lang, name: '', description: '' }),
    );
    const triggers = (mailingList.triggers ?? []).map((trigger) => triggerToForm(trigger, langs));
    const next: MailingListFormModel = {
      mailingListId: mailingList.mailingListId,
      active: mailingList.active,
      descs: padded,
      triggers,
    };
    this.formModel.set(next);
    this.baselineJson.set(this.normalizeForCompare(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected addTrigger(): void {
    const langs = this.languageSettings.modelLanguages.map((language) => language.value);
    this.formModel.update((model) => ({
      ...model,
      triggers: [...model.triggers, newTriggerForm(langs)],
    }));
  }

  protected copyTrigger(index: number): void {
    this.formModel.update((model) => {
      const source = model.triggers[index];
      if (source === undefined) {
        return model;
      }
      const clone: TriggerFormModel = {
        ...structuredClone(source),
        scheduledExecutionTime: source.scheduledExecutionTime
          ? new Date(source.scheduledExecutionTime.getTime())
          : null,
        expanded: true,
      };
      const triggers = [...model.triggers];
      triggers.splice(index + 1, 0, clone);
      return { ...model, triggers };
    });
  }

  protected removeTrigger(index: number): void {
    this.formModel.update((model) => ({
      ...model,
      triggers: model.triggers.filter((_, i) => i !== index),
    }));
  }

  /**
   * The current working list serialized to the wire shape. Descs are dropped only for languages
   * whose name AND description are both blank — never send an empty configured-language row the
   * user never filled. Triggers serialize through formToTrigger, which strips the UI-only fields
   * (expanded / per-language enabled) and the inactive variant's fields, so the payload stays
   * clean. Used by both save and the (unsaved-copy) test dialog.
   */
  private buildWorkingList(): MailingList {
    const value = this.formModel();
    const descs = value.descs.filter(
      (desc) => desc.name.trim() !== '' || desc.description.trim() !== '',
    );
    return {
      mailingListId: value.mailingListId,
      active: value.active,
      descs,
      triggers: value.triggers.map(formToTrigger),
    };
  }

  /** The test dialog's model — the working copy + the positional index (recomputes as edits land). */
  protected readonly testModel = computed<TriggerTestModel | undefined>(() => {
    const index = this.testTriggerIndex();
    return index === undefined ? undefined : { list: this.buildWorkingList(), triggerIndex: index };
  });

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const mailingList = this.buildWorkingList();
    const request =
      model.mode === 'add' ? this.api.create(mailingList) : this.api.update(mailingList);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.mailingLists.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.mailingLists.toast.saveError');
      },
    });
  }
}
