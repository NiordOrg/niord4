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
import { applyEach, disabled, form, FormField, required, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  LengthCounter,
  lengthError,
} from '../../../../../shared/forms/length-counter/length-counter';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import {
  DictionariesApiService,
  DictionaryEntry,
  DictionaryEntryDesc,
} from '../../../services/api/dictionaries-api.service';
import { padAndOrderDescs } from '../../../utils/localized-descs';

export interface DictionaryEntryEditorModel {
  readonly mode: 'add' | 'edit';
  readonly dictionaryName: string;
  readonly entry: DictionaryEntry;
  /** Extended mode exposes the Long Version and AtoN filter fields. */
  readonly showExtended: boolean;
}

/** The width of a value's and a long version's columns: varchar(1000). */
const VALUE_MAX = 1000;

interface DescRow {
  lang: string;
  value: string;
  longValue: string;
  /** Whether the server sent a long value — '' must round-trip as defined. */
  hadLongValue: boolean;
}

interface DictionaryFormModel {
  key: string;
  atonFilter: string;
  descs: DescRow[];
}

/**
 * Create/edit/copy form for a dictionary entry, hosted in the shared drawer. The key is immutable in edit
 * mode (it is the update path identifier); copy arrives as mode 'add' with the
 * id already stripped by the page.
 *
 * Save gating: no net change (reverting to the opened value re-disables Save) OR
 * invalid disables Save. On create, at least one language value must be non-blank
 * (a fully blank entry would be unfindable and unrepairable).
 * Two NON-blocking warnings surface data-shape pitfalls: a blank value whose long
 * version is filled (the backend discards the long version), and an edit that
 * blanks every value.
 */
@Component({
  selector: 'app-dictionary-entry-editor',
  imports: [Drawer, FormField, Icon, LengthCounter, LocalizedTextInput, TranslocoPipe],
  templateUrl: './dictionary-entry-editor.html',
  host: { class: 'contents' },
})
export class DictionaryEntryEditor {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(DictionariesApiService);
  private readonly notifications = inject(NotificationService);

  readonly model = input.required<DictionaryEntryEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  private readonly formModel = signal<DictionaryFormModel>({ key: '', atonFilter: '', descs: [] });
  protected readonly form = form(this.formModel, (path) => {
    required(path.key);
    // Key is the update path identifier — immutable once the entry exists.
    disabled(path.key, { when: () => this.model()?.mode === 'edit' });
    // No per-language required: the create rule (≥1 non-blank value) is enforced in saveDisabled.
    // ERR7. A value or long version longer than its column keeps Save back; the
    // counter under the field says by how much.
    applyEach(path.descs, (desc) => {
      validate(desc.value, ({ value }) => lengthError(value(), VALUE_MAX));
      validate(desc.longValue, ({ value }) => lengthError(value(), VALUE_MAX));
    });
  });

  protected readonly valueMax = VALUE_MAX;

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.dictionaries.editor.editTitle'
      : 'admin.dictionaries.editor.addTitle',
  );

  /** Create rule: at least one language value must be non-blank. */
  protected readonly allValuesBlank = computed(() =>
    this.formModel().descs.every((desc) => desc.value.trim() === ''),
  );

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(() => {
    const createBlocked = this.model()?.mode === 'add' && this.allValuesBlank();
    return !this.changed() || this.form().invalid() || createBlocked || this.saving();
  });

  /** Non-blocking data-shape warnings, recomputed as the user types. */
  protected readonly warnings = computed(() => {
    const result: string[] = [];
    const anyDiscardedLongValue = this.formModel().descs.some(
      (desc) => desc.value.trim() === '' && desc.longValue.trim() !== '',
    );
    if (anyDiscardedLongValue) {
      result.push('admin.dictionaries.editor.warnLongValueDiscarded');
    }
    if (this.model()?.mode === 'edit' && this.allValuesBlank()) {
      result.push('admin.dictionaries.editor.warnAllValuesBlank');
    }
    return result;
  });

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only on (re)open — a mid-edit language switch
        // must not reorder rows and wipe unsaved input.
        untracked(() => this.rebuildForm(model));
      }
    });
  }

  private rebuildForm(model: DictionaryEntryEditorModel): void {
    const padded = padAndOrderDescs(
      model.entry.descs,
      this.languageSettings.modelLanguages.map((language) => language.value),
      (lang): DictionaryEntryDesc => ({ lang, value: '' }),
    );
    const descs: DescRow[] = padded.map((desc) => ({
      lang: desc.lang,
      value: desc.value,
      longValue: desc.longValue ?? '',
      hadLongValue: desc.longValue !== undefined,
    }));
    const next: DictionaryFormModel = {
      key: model.entry.key,
      atonFilter: model.entry.atonFilter ?? '',
      descs,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const entry: DictionaryEntry = {
      id: model.entry.id,
      key: value.key,
      descs: value.descs.map((desc) => ({
        lang: desc.lang,
        value: desc.value,
        // Emit longValue only when the user filled it or the server had one —
        // never invent the field on entries that lacked it.
        ...(desc.longValue !== '' || desc.hadLongValue ? { longValue: desc.longValue } : {}),
      })),
      ...(value.atonFilter !== '' || model.entry.atonFilter !== undefined
        ? { atonFilter: value.atonFilter }
        : {}),
    };
    const request =
      model.mode === 'add'
        ? this.api.createEntry(model.dictionaryName, entry)
        : this.api.updateEntry(model.dictionaryName, entry);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.dictionaries.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.dictionaries.toast.saveError');
      },
    });
  }
}
