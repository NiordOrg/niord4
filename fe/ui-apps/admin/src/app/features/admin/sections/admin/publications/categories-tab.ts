import {
  DOCUMENT,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { disabled, form, FormField, max, min, required, validate } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { ModelLanguageSelection, type ModelText } from '../../../services/model-language-selection';
import { ModelLanguagePicker } from '../../../components/model-language-picker/model-language-picker';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  LengthCounter,
  lengthErrorAny,
} from '../../../../../shared/forms/length-counter/length-counter';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import {
  PublicationSeriesApiService,
  type PublicationCategory,
  type PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { decodePublicationError, publicationErrorKey } from './publication-errors';
import { isServableId, UNSERVABLE_ID, UNSERVABLE_ID_KEY } from './publication-id';

/** The drawer's subject. `undefined` = closed. */
interface CategoryEditorModel {
  readonly mode: 'edit' | 'add';
  readonly category: PublicationCategory;
}

/**
 * Text being edited, keyed by language.
 *
 * Every configured language carries an entry, blank where the category has no
 * text, so a comparison against the baseline never turns on a key's absence.
 */
type TextModel = Record<string, string>;

/**
 * What the drawer edits.
 *
 * The per-language text lives in the model rather than beside it: dynamic keys
 * are not addressable as Signal-Forms fields, but keeping them here means one
 * baseline comparison covers the whole editor.
 */
interface CategoryFormModel {
  categoryId: string;
  /** `null` while the box is empty; the form then holds Save back. */
  priority: number | null;
  publish: boolean;
  names: TextModel;
  descriptions: TextModel;
}

const EMPTY: CategoryFormModel = {
  categoryId: '',
  priority: null,
  publish: false,
  names: {},
  descriptions: {},
};

/** Where a new category sorts before anybody says otherwise. */
const DEFAULT_CATEGORY_PRIORITY = 100;

/** The refusal that names how many things still point at a category. */
const CATEGORY_IN_USE = 'CATEGORY_IN_USE';

/** The bounds of the `priority` column, a 32-bit integer (PUBCAT3). */
const PRIORITY_MIN = -2147483648;
const PRIORITY_MAX = 2147483647;

/** The width of a category's name and description columns: varchar(255). */
const TEXT_MAX = 255;

/** The error kinds of the form's own rules, read to show their sentences. */
const NAME_REQUIRED = 'nameRequired';
const WHOLE_NUMBER = 'wholeNumber';
const PRIORITY_ERRORS: ReadonlySet<string> = new Set(['min', 'max', WHOLE_NUMBER]);

/**
 * How many series and publications a refusal counted, if it said.
 *
 * The server states the counts in one English sentence and carries no fields for
 * them, so the two numbers are read off the front of it and the sentence itself
 * is never shown: pasting untranslated server prose into a Danish UI is the
 * defect this exists to avoid. An unrecognised shape yields nothing and the
 * caller says the same thing without the numbers, rather than guessing.
 */
function categoryUsage(
  message: string | undefined,
): { series: number; publications: number } | undefined {
  const match = /^(\d+)\s+series\s+and\s+(\d+)\s+publication/i.exec(message?.trim() ?? '');
  return match ? { series: Number(match[1]), publications: Number(match[2]) } : undefined;
}

/**
 * Publication categories: what groups a series on the public page.
 *
 * Small, and load-bearing out of proportion to its size. A category carries the
 * `publish` flag that decides whether its published issues are exposed publicly
 * at all, and every series must belong to one — so with no way to create a
 * category, an admin could be blocked from saving a series by a required field
 * whose legal values nothing in the product could extend.
 *
 * Delete is refused by the server while any series still points at the category,
 * and that refusal is re-stated rather than shown as a generic failure: "three
 * series still belong to this category" tells an admin what to do next, and
 * "could not delete" does not.
 */
@Component({
  selector: 'app-publication-categories-tab',
  imports: [
    Confirm,
    Drawer,
    EmptyState,
    ErrorBanner,
    FormField,
    Icon,
    LengthCounter,
    LocalizedTextInput,
    ModelLanguagePicker,
    Menu,
    MenuTrigger,
    ProgressBar,
    TranslocoPipe,
  ],
  providers: [ConfirmService, ModelLanguageSelection],
  templateUrl: './categories-tab.html',
  host: { class: 'flex min-w-0 flex-col' },
})
export class PublicationCategoriesTab {
  protected readonly modelLanguage = inject(ModelLanguageSelection);
  private readonly api = inject(PublicationSeriesApiService);
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);

  protected readonly installationLanguages = this.language.modelLanguages;

  private readonly refreshTick = signal(0);

  private readonly state = toSignal(
    toObservable(computed(() => this.refreshTick())).pipe(
      switchMap(() =>
        this.api.publicationCategories().pipe(
          map((categories) => ({ categories, loading: false, error: false })),
          catchError(() =>
            of({ categories: [] as PublicationCategory[], loading: false, error: true }),
          ),
          startWith({ categories: [] as PublicationCategory[], loading: true, error: false }),
        ),
      ),
    ),
    { initialValue: { categories: [] as PublicationCategory[], loading: true, error: false } },
  );

  protected readonly categories = computed(() => this.state().categories);
  protected readonly loading = computed(() => this.state().loading);
  protected readonly error = computed(() => this.state().error);

  /**
   * The whole estate, read once, to count what belongs to each category.
   *
   * A category carries no count of its own, and the number an admin wants here
   * is not the desk's: a category is shared across domains and the refusal that
   * blocks a delete counts every domain's series, so the list is fetched
   * unscoped. One-off publications are series of their own kind and are
   * included in the same answer, which is why one read covers both.
   *
   * `resolved` gates the column rather than a count of zero. "0" states that
   * nothing belongs to a category, which is the one thing an admin acts on
   * here — a list that has not arrived, or did not arrive at all, must not say
   * it.
   */
  private readonly estate = toSignal(
    this.api.searchSeries().pipe(
      map((series) => ({ series, resolved: true })),
      catchError(() => of({ series: [] as PublicationSeries[], resolved: false })),
      startWith({ series: [] as PublicationSeries[], resolved: false }),
    ),
    { initialValue: { series: [] as PublicationSeries[], resolved: false } },
  );

  private readonly countsByCategory = computed<ReadonlyMap<string, number> | undefined>(() => {
    const state = this.estate();
    if (!state.resolved) {
      return undefined;
    }
    const counts = new Map<string, number>();
    for (const series of state.series) {
      const categoryId = series.categoryId;
      if (categoryId) {
        counts.set(categoryId, (counts.get(categoryId) ?? 0) + 1);
      }
    }
    return counts;
  });

  protected readonly editorModel = signal<CategoryEditorModel | undefined>(undefined);
  protected readonly saving = signal(false);

  /** The filter over the list, matching name and id alike. */
  protected readonly search = signal('');

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const categories = this.categories();
    if (term === '') {
      return categories;
    }
    return categories.filter(
      (category) =>
        category.categoryId.toLowerCase().includes(term) ||
        this.name(category).value.toLowerCase().includes(term),
    );
  });

  private readonly formModel = signal<CategoryFormModel>(EMPTY);
  protected readonly form = form(this.formModel, (path) => {
    required(path.categoryId);
    // The key every series points at, and the one the import file is written
    // against — immutable once the category exists.
    disabled(path.categoryId, { when: () => this.editorModel()?.mode === 'edit' });
    // PUBCAT2. Only for a new id: the server's create refuses the same
    // characters, because no address could reach the category afterwards.
    validate(path.categoryId, ({ value }) =>
      this.editorModel()?.mode !== 'add' || isServableId(value()) ? null : { kind: UNSERVABLE_ID },
    );
    // PUB4. The server refuses a category left with no name in any language:
    // its name heads its section on the public page.
    validate(path.names, ({ value }) =>
      Object.values(value()).some((name) => (name ?? '').trim() !== '')
        ? null
        : { kind: NAME_REQUIRED },
    );
    // ERR7. A name or description longer than its column keeps Save back; the
    // counter under the field says by how much.
    validate(path.names, ({ value }) => lengthErrorAny(value(), TEXT_MAX));
    validate(path.descriptions, ({ value }) => lengthErrorAny(value(), TEXT_MAX));
    // Every category has an Order: the column cannot hold "none", and an
    // emptied box used to be sent as null, kept as the old number by the server
    // and reported as saved. Required, so Save waits and the field says why.
    required(path.priority);
    // PUBCAT3. The column is a 32-bit integer; the server refuses a fraction
    // or a number past it rather than storing it wrapped. Empty is the rule above's.
    min(path.priority, PRIORITY_MIN);
    max(path.priority, PRIORITY_MAX);
    validate(path.priority, ({ value }) => {
      const priority = value();
      return priority === null || Number.isNaN(priority) || Number.isInteger(priority)
        ? null
        : { kind: WHOLE_NUMBER };
    });
  });

  /** The new id holds what the server cannot serve; the field says so. */
  protected readonly categoryIdUnservable = computed(() =>
    this.form
      .categoryId()
      .errors()
      .some((error) => error.kind === UNSERVABLE_ID),
  );

  /**
   * No language has a name yet; the name heading says what is missing.
   *
   * Read by its kind: a name that is too long is invalid too, and its counter,
   * not this line, says so.
   */
  protected readonly nameMissing = computed(() =>
    this.form
      .names()
      .errors()
      .some((error) => error.kind === NAME_REQUIRED),
  );

  protected readonly textMax = TEXT_MAX;

  /** Order is a fraction or outside the column's range; the field says so. */
  protected readonly priorityOutOfRange = computed(() =>
    this.form
      .priority()
      .errors()
      .some((error) => PRIORITY_ERRORS.has(error.kind)),
  );

  /** Order is empty; the field says it is needed. */
  protected readonly priorityMissing = computed(() =>
    this.form
      .priority()
      .errors()
      .some((error) => error.kind === 'required'),
  );

  /** The one line under Order: what is missing first, then what is out of range. */
  protected readonly priorityErrorKey = computed(() => {
    if (this.priorityMissing()) {
      return 'admin.publications.categories.hint.priorityRequired';
    }
    return this.priorityOutOfRange() ? 'admin.publications.categories.hint.priorityRange' : null;
  });

  protected readonly unservableIdKey = UNSERVABLE_ID_KEY;

  /** Per-language text: dynamic keys are not addressable as fields, so read here. */
  protected readonly names = computed(() => this.formModel().names);
  protected readonly descriptions = computed(() => this.formModel().descriptions);

  /**
   * Net change against the opened values.
   *
   * Signal Forms' `dirty` tracks interaction and never clears on revert, so it
   * cannot answer "differs from what was loaded" — a serialized comparison does,
   * and it covers the per-language text in the same pass.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  /** True while the drawer is editing a category that exists — the only one deletable. */
  protected readonly editing = computed(() => this.editorModel()?.mode === 'edit');

  protected readonly headerKey = computed(() =>
    this.editing()
      ? 'admin.publications.categories.editor.editTitle'
      : 'admin.publications.categories.editor.addTitle',
  );

  constructor() {
    effect(() => {
      const model = this.editorModel();
      if (model) {
        untracked(() => this.rebuild(model));
      }
    });
  }

  private rebuild(model: CategoryEditorModel): void {
    const c = model.category;
    const names: TextModel = {};
    const descriptions: TextModel = {};
    for (const lang of this.installationLanguages) {
      const desc = c.descs?.find((d) => d.lang === lang.value);
      names[lang.value] = desc?.name ?? '';
      // Read as well as sent. Loading only the name meant every save round-tripped
      // the category with its descriptions blanked, so editing an unrelated field
      // silently deleted text nothing on this screen had ever shown.
      descriptions[lang.value] = desc?.description ?? '';
    }
    // A missing priority is `null`, not `undefined`, so the box and the baseline
    // agree on an empty Order and reopening an unchanged category is not dirty.
    const next: CategoryFormModel = {
      categoryId: c.categoryId ?? '',
      priority: c.priority ?? null,
      publish: c.publish ?? false,
      names,
      descriptions,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  /**
   * Selected model-language name, falling back to the default, then other stored text,
   * and finally the category id, which carries no language so the row is not marked as model text.
   */
  protected name(category: PublicationCategory): ModelText {
    const text = this.modelLanguage.text(category.descs, 'name');
    return text.value ? text : { value: category.categoryId, lang: undefined };
  }

  /** How many series and one-off publications point here, or `—` until it is known. */
  protected publicationCount(category: PublicationCategory): string {
    const counts = this.countsByCategory();
    return counts ? String(counts.get(category.categoryId) ?? 0) : '—';
  }

  protected refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }

  /**
   * A new category starts in the middle of the order.
   *
   * `priority` sorts the categories on the public page and every existing one
   * carries a number, so a blank leaves the new category sorting against values
   * it was never compared with. Starting at {@link DEFAULT_CATEGORY_PRIORITY}
   * leaves room to place it either side of what is already there without
   * renumbering the rest.
   */
  protected add(): void {
    this.editorModel.set({
      mode: 'add',
      category: {
        categoryId: '',
        priority: DEFAULT_CATEGORY_PRIORITY,
        publish: false,
        descs: [],
      },
    });
  }

  protected edit(category: PublicationCategory): void {
    this.editorModel.set({ mode: 'edit', category });
  }

  protected setName(lang: string, value: string): void {
    this.formModel.update((model) => ({ ...model, names: { ...model.names, [lang]: value } }));
  }

  protected setDescription(lang: string, value: string): void {
    this.formModel.update((model) => ({
      ...model,
      descriptions: { ...model.descriptions, [lang]: value },
    }));
  }

  protected save(): void {
    const model = this.editorModel();
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    // Always a number here: Order is required, so an empty box keeps Save
    // disabled. The server refuses an explicit null on update, and the
    // column could not hold "no order" anyway.
    const priority = value.priority;
    if (priority === null) {
      return;
    }
    const names = value.names;
    const descriptions = value.descriptions;
    const payload: PublicationCategory = {
      categoryId: value.categoryId.trim(),
      priority,
      publish: value.publish,
      // Only languages with a NAME. A blank name would render as a nameless row
      // in the picker every series uses, which is worse than the language simply
      // not being covered yet — and the server drops a nameless row anyway.
      // The description travels with the name because the row is the pair: a
      // language filtered out here loses its description too.
      descs: this.installationLanguages
        .filter((lang) => (names[lang.value] ?? '').trim() !== '')
        .map((lang) => ({
          lang: lang.value,
          name: (names[lang.value] ?? '').trim(),
          // Sent even when blank, so clearing a description clears it on the
          // server instead of leaving the previous text in place.
          description: (descriptions[lang.value] ?? '').trim(),
        })),
    };

    const request =
      model.mode === 'add'
        ? this.api.createCategory(payload)
        : this.api.updateCategory(model.category.categoryId, payload);

    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.publications.categories.toast.saved');
        this.editorModel.set(undefined);
        this.refresh();
      },
      // Through the section's code mapping, so a duplicate id or a
      // category the server's own checks refuse says why; anything else keeps
      // the generic sentence. The drawer stays open on what was typed.
      error: (response: unknown) => {
        this.saving.set(false);
        this.notifications.error(
          publicationErrorKey(response, 'admin.publications.categories.toast.saveError'),
        );
      },
    });
  }

  /**
   * Delete the category the drawer is open on.
   *
   * The only way in. Delete used to sit on every row of a list that changes a
   * few times a year, which put a destructive control one mis-click from the
   * pencil beside it; from the footer of the open editor, the thing being
   * deleted is the thing on the screen.
   */
  protected removeCurrent(): void {
    const model = this.editorModel();
    if (model?.mode === 'edit') {
      this.remove(model.category);
    }
  }

  /**
   * Delete, once confirmed.
   *
   * The server refuses while anything still belongs to the category, and what
   * an admin acts on is the count -- "three series still belong to this
   * category" says what to do next and "could not delete" does not. The
   * SENTENCE is the server's own English, so it is never shown: the refusal is
   * recognised by its code and re-stated in the reader's language, with the two
   * numbers lifted out of it. A refusal that does not carry them is stated
   * without them rather than with a guess.
   */
  protected remove(category: PublicationCategory): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.publications.categories.confirmDelete.header'),
      message: this.transloco.translate('admin.publications.categories.confirmDelete.message', {
        name: this.name(category).value,
      }),
      accept: () =>
        this.api.deleteCategory(category.categoryId).subscribe({
          next: () => {
            this.notifications.success('admin.publications.categories.toast.deleted');
            // The editor is closed only on success: a refused delete leaves the
            // admin where the message applies, with the category still open.
            this.editorModel.set(undefined);
            this.refresh();
          },
          error: (response: unknown) => this.reportDeleteFailure(category, response),
        }),
    });
  }

  /**
   * Read through the section's one decoder rather than off `response.error`.
   *
   * The refusal is not one shape on the wire — a coded object from the mapped
   * endpoints, a message beginning with the same code from the ones that still
   * throw plainly — and a hand-written optional chain here sees only the first.
   */
  private reportDeleteFailure(category: PublicationCategory, response: unknown): void {
    const name = this.name(category).value;
    const decoded = decodePublicationError(response);
    if (decoded.kind !== 'publication' || decoded.code !== CATEGORY_IN_USE) {
      this.notifications.error('admin.publications.categories.toast.deleteError');
      return;
    }
    const usage = categoryUsage(decoded.message);
    if (usage) {
      this.notifications.error('admin.publications.categories.toast.deleteInUse', {
        name,
        series: usage.series,
        publications: usage.publications,
      });
      return;
    }
    this.notifications.error('admin.publications.categories.toast.deleteInUseUnknown', { name });
  }

  protected close(): void {
    this.editorModel.set(undefined);
  }

  // ------------------------------------------------------ export and import

  private readonly document = inject(DOCUMENT);
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('categoryFileInput');
  private readonly exportLabel = translateSignal('admin.publications.categories.menu.export');
  private readonly importLabel = translateSignal('admin.publications.categories.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), command: () => this.exportCategories() },
    { label: this.importLabel(), command: () => this.confirmImport() },
  ]);

  protected readonly importing = signal(false);

  /**
   * Download the categories as the file the importer reads back.
   *
   * Saved as the server's own bytes rather than re-serialized from a parsed
   * object: the export IS an import file, and a round trip through this client's
   * model silently drops any field it does not carry.
   */
  protected exportCategories(): void {
    this.api.exportCategories().subscribe({
      next: (blob) => this.saveBlob(blob),
      error: () => this.notifications.error('admin.publications.categories.toast.exportError'),
    });
  }

  private saveBlob(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'publication-categories.json';
    link.click();
    // Released once the click has been dispatched: the browser has taken its own
    // reference by then, and holding it keeps the export in memory for the life
    // of the page.
    URL.revokeObjectURL(url);
  }

  /**
   * Ask before the picker opens, naming what the file overwrites.
   *
   * The upload is not additive: every category the file names is rewritten field
   * for field, and one that exists only here is left standing while every series
   * pointing at a rewritten one now sorts and publishes by somebody else's
   * settings. None of that is visible from a file picker, and by the time the
   * job has run it is done.
   */
  protected confirmImport(): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.publications.categories.import.confirm.header'),
      message: this.transloco.translate('admin.publications.categories.import.confirm.message'),
      acceptLabel: this.transloco.translate('admin.publications.categories.import.confirm.accept'),
      accept: () => this.fileInput()?.nativeElement.click(),
    });
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Cleared either way, so the same file can be chosen again after a failure.
    input.value = '';
    if (!file) {
      return;
    }
    this.importing.set(true);
    this.api.importCategories(file).subscribe({
      next: () => {
        this.importing.set(false);
        // The import runs as a background batch job, so this reports that it was
        // ACCEPTED, not that it has finished. The refresh below shows whatever
        // has landed by the time it returns.
        this.notifications.success('admin.publications.categories.toast.importSubmitted');
        this.refresh();
      },
      error: (response: unknown) => {
        this.importing.set(false);
        this.notifications.error(
          publicationErrorKey(response, 'admin.publications.categories.toast.importError'),
        );
      },
    });
  }
}
