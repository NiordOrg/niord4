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
import { applyEach, form, FormField, required, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  LengthCounter,
  lengthError,
} from '../../../../../shared/forms/length-counter/length-counter';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import {
  Source,
  SourceDesc,
  SourcesApiService,
} from '../../../../messages/services/api/sources-api.service';
import { padAndOrderDescs } from '../../../utils/localized-descs';

export interface SourceEditorModel {
  readonly mode: 'add' | 'edit';
  readonly source: Source;
}

/** The width of a source's name and abbreviation columns: varchar(255). */
const SOURCE_TEXT_MAX = 255;

interface SourceFormModel {
  active: boolean;
  descs: SourceDesc[];
}

/**
 * Create/edit form for a source, hosted in the shared drawer. The descs list is built from the
 * configured model-language list (padded so EVERY configured language has an
 * editable row, on create and edit alike), in configured-list order;
 * descs in unconfigured languages round-trip unchanged.
 *
 * Name is required in the configured default language and gates Save; Enter submits via (ngSubmit)
 * under the same gate — deliberately, Enter submits rather than discarding.
 * A failed save keeps the drawer open with values intact.
 */
@Component({
  selector: 'app-source-editor',
  imports: [Drawer, FormField, LengthCounter, LocalizedTextInput, TranslocoPipe],
  templateUrl: './source-editor.html',
  host: { class: 'contents' },
})
export class SourceEditor {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(SourcesApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<SourceEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  private readonly formModel = signal<SourceFormModel>({ active: true, descs: [] });
  protected readonly form = form(this.formModel, (path) => {
    applyEach(path.descs, (descPath) => {
      required(descPath.name, {
        when: (ctx) => ctx.valueOf(descPath.lang) === this.languageSettings.defaultLanguage,
      });
      // ERR7. A longer text than the column holds keeps Save back; the counter
      // under the field says by how much.
      validate(descPath.name, ({ value }) => lengthError(value(), SOURCE_TEXT_MAX));
      validate(descPath.abbreviation, ({ value }) => lengthError(value(), SOURCE_TEXT_MAX));
    });
  });

  protected readonly textMax = SOURCE_TEXT_MAX;

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  /** Save is gated on a real, valid change (and not mid-save). */
  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.sources.editor.editTitle'
      : 'admin.sources.editor.addTitle',
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — a mid-edit language
        // switch must not rebuild the form and wipe unsaved input.
        untracked(() => this.rebuildForm(model.source));
      }
    });
  }

  private rebuildForm(source: Source): void {
    const padded = padAndOrderDescs(
      source.descs,
      this.languageSettings.modelLanguages.map((language) => language.value),
      (lang): SourceDesc => ({ lang, name: '', abbreviation: '' }),
    );
    const next: SourceFormModel = { active: source.active, descs: padded };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    // Reset touched/dirty so the freshly-loaded values become the pristine baseline.
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button — an
    // unchanged or invalid form never fires a save.
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const source: Source = {
      id: model.source.id,
      active: value.active,
      descs: value.descs.filter((desc) => desc.name.trim() || desc.abbreviation?.trim()),
    };
    const request = model.mode === 'add' ? this.api.create(source) : this.api.update(source);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.sources.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.sources.toast.saveError');
      },
    });
  }
}
