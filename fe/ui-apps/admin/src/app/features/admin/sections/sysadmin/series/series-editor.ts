import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { disabled, form, FormField, required, validate } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  FormatToken,
  FormatTokenInput,
} from '../../../../../shared/forms/format-token-input/format-token-input';
import {
  LengthCounter,
  lengthError,
} from '../../../../../shared/forms/length-counter/length-counter';
import { Icon } from '../../../../../shared/icons/icon';
import { MAIN_TYPES, type MainType } from '../../../../../shared/models/main-type.model';
import { EditorFieldsGrid } from '../../../components/editor-fields-grid/editor-fields-grid';
import {
  NUMBER_SEQUENCE_TYPES,
  type NumberSequenceType,
} from '../../../models/admin-message-series.model';
import { MessageSeriesAdminApiService } from '../../../services/api/message-series-admin-api.service';
import { EDITOR_FIELD_CANDIDATES } from '../../../utils/editor-field-candidates';
import { NextNumberPopover } from './next-number-popover';
import {
  buildSeriesPayload,
  currentYearIn,
  definesShortFormat,
  numberAssigned,
  RESTRICT_TYPES,
  type SeriesFormValue,
} from './series-rules';
import type { SeriesEditorModel } from './series-page';
import { SeriesFacade } from './series.facade';

/** The short-ID insert tokens, in menu order, labelled via `admin.series.token.*`. */
const SHORT_FORMAT_TOKENS: readonly { token: string; labelKey: string }[] = [
  { token: '${year}', labelKey: 'admin.series.token.year' },
  { token: '${year-2-digits}', labelKey: 'admin.series.token.year2Digits' },
  { token: '${week}', labelKey: 'admin.series.token.week' },
  { token: '${week-2-digits}', labelKey: 'admin.series.token.week2Digits' },
  { token: '${number}', labelKey: 'admin.series.token.number' },
  { token: '${number-3-digits}', labelKey: 'admin.series.token.number3Digits' },
  { token: '${id}', labelKey: 'admin.series.token.id' },
  { token: '${legacy-id}', labelKey: 'admin.series.token.legacyId' },
];

/** Offered after the common tokens for NM series only (T = temporary, P = preliminary). */
const T_OR_P_TOKEN = { token: '${t-or-p}', labelKey: 'admin.series.token.tOrP' } as const;

const NAVTEX_TOKENS: readonly { token: string; labelKey: string }[] = [
  { token: '${publish-date}', labelKey: 'admin.series.token.publishDate' },
  { token: '${short-id}', labelKey: 'admin.series.token.shortId' },
];

/** The width of the short ID and NAVTEX format columns: varchar(255). */
const FORMAT_MAX = 255;

type SeriesFormModel = { -readonly [K in keyof SeriesFormValue]: SeriesFormValue[K] };

const EMPTY: SeriesFormModel = {
  seriesId: '',
  mainType: 'NW',
  numberSequenceType: 'YEARLY',
  shortFormat: '',
  navtexFormat: '',
  excludeFromMessageIndex: false,
};

/**
 * Create/edit/copy form for a message series, hosted in the shared drawer.
 * Signal Forms over the flat scalar fields; the two restrict-type checkbox
 * maps and the editor-fields selection live OUTSIDE the form as signals —
 * seeded once per open and folded into the baseline compare, so toggles flip
 * the save gate like any typed edit.
 *
 * Behavioral notes carried by this component:
 * - the restrict-type maps keep their session state across main-type switches;
 *   only the CURRENT main type's checks reach the save payload;
 * - the Short Format group hides for NONE but the working text survives — it
 *   is stripped from the save payload only;
 * - the NAVTEX preamble is NW-gated yet survives NONE and never gets deleted;
 * - the next-number row appears only in edit mode on an auto-numbered series,
 *   and its pencil is locked while the form has unsaved changes.
 */
@Component({
  selector: 'app-series-editor',
  imports: [
    Drawer,
    EditorFieldsGrid,
    FormatTokenInput,
    FormField,
    Icon,
    LengthCounter,
    NextNumberPopover,
    TranslocoPipe,
  ],
  templateUrl: './series-editor.html',
  host: { class: 'contents' },
})
export class SeriesEditor {
  private readonly api = inject(MessageSeriesAdminApiService);
  private readonly facade = inject(SeriesFacade);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<SeriesEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  protected readonly editorFieldCandidates = EDITOR_FIELD_CANDIDATES;
  protected readonly restrictTypes = RESTRICT_TYPES;
  protected readonly sequenceTypes = NUMBER_SEQUENCE_TYPES;
  protected readonly mainTypes = MAIN_TYPES;

  /**
   * The next-number row/column always names the current year (even for CONTINUOUS),
   * as the session domain's clock reads it.
   */
  protected readonly currentYear = computed(() =>
    currentYearIn(this.localeFormat.sessionTimeZone()),
  );

  private readonly formModel = signal<SeriesFormModel>({ ...EMPTY });
  protected readonly form = form(this.formModel, (path) => {
    required(path.seriesId);
    // seriesId is the immutable string PK (the PUT path id) — locked on edit.
    disabled(path.seriesId, { when: () => this.model()?.mode === 'edit' });
    // ERR7. Each format goes into a varchar(255) column; a longer one keeps Save
    // back and the counter under it says by how much. The short format only
    // while the series has one: under NONE it stays in the field but is not sent.
    validate(path.shortFormat, ({ value, valueOf }) =>
      definesShortFormat({ numberSequenceType: valueOf(path.numberSequenceType) })
        ? lengthError(value(), FORMAT_MAX)
        : null,
    );
    validate(path.navtexFormat, ({ value }) => lengthError(value(), FORMAT_MAX));
  });

  protected readonly formatMax = FORMAT_MAX;

  /**
   * Restrict-type checks per main type, kept OUTSIDE the form. Seeded once per
   * open from the entity's `types` and deliberately NOT re-seeded on main-type
   * switches, so checks made in the session survive an NW→NM→NW round trip.
   */
  protected readonly typeChecks = signal<Record<MainType, Record<string, boolean>>>(
    seedChecks(undefined),
  );

  /** The chosen additional editor fields (outside the form — a set toggle). */
  protected readonly editorFields = signal<readonly string[]>([]);

  /** Shown in the next-number row; refreshed in the background after a popover update. */
  protected readonly displayNextNumber = signal<number | undefined>(undefined);

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does. The
   * out-of-form signals are folded in so checkbox toggles flip the gate too.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () =>
      JSON.stringify([this.formModel(), this.typeChecks(), this.editorFields()]) !==
      this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.series.editor.editTitle'
      : 'admin.series.editor.addTitle',
  );

  /** Short Format group gate — hidden for NONE; the working text survives the hide. */
  protected readonly showShortFormat = computed(() =>
    definesShortFormat({ numberSequenceType: this.formModel().numberSequenceType }),
  );

  /** NAVTEX Preamble is NW-only (but survives NONE, unlike the short format). */
  protected readonly showNavtex = computed(() => this.formModel().mainType === 'NW');

  /** Next-number row: edit mode + auto-numbered series only (gated on the working copy). */
  protected readonly showNextNumber = computed(() => {
    const mode = this.model()?.mode;
    const auto = numberAssigned({ numberSequenceType: this.formModel().numberSequenceType });
    return mode === 'edit' && auto;
  });

  protected readonly mainTypeValue = computed(() => this.formModel().mainType);
  protected readonly sequenceTypeValue = computed(() => this.formModel().numberSequenceType);

  /** Insert-menu tokens with translated labels; `${t-or-p}` is offered for NM series only. */
  protected readonly shortFormatTokens = computed<readonly FormatToken[]>(() => {
    this.language.selectedLanguage();
    const mainType = this.formModel().mainType;
    const defs = mainType === 'NM' ? [...SHORT_FORMAT_TOKENS, T_OR_P_TOKEN] : SHORT_FORMAT_TOKENS;
    return defs.map(({ token, labelKey }) => ({
      token,
      label: this.transloco.translate(labelKey),
    }));
  });

  protected readonly navtexTokens = computed<readonly FormatToken[]>(() => {
    this.language.selectedLanguage();
    return NAVTEX_TOKENS.map(({ token, labelKey }) => ({
      token,
      label: this.transloco.translate(labelKey),
    }));
  });

  private readonly seriesIdField = viewChild<ElementRef<HTMLInputElement>>('seriesIdField');

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — not on every keystroke.
        untracked(() => this.rebuildForm(model));
      }
    });

    // Initial focus lands on the seriesId input on add/copy only;
    // in edit mode the input is disabled, so the drawer's default focus stands.
    // The macrotask runs after the drawer's own microtask ✕-focus.
    effect(() => {
      const field = this.seriesIdField()?.nativeElement;
      const mode = this.model()?.mode;
      if (field && mode === 'add') {
        setTimeout(() => field.focus(), 0);
      }
    });
  }

  private rebuildForm(model: SeriesEditorModel): void {
    const series = model.series;
    const next: SeriesFormModel = {
      seriesId: series.seriesId,
      mainType: series.mainType,
      numberSequenceType: series.numberSequenceType,
      shortFormat: series.shortFormat ?? '',
      navtexFormat: series.navtexFormat ?? '',
      excludeFromMessageIndex: series.excludeFromMessageIndex ?? false,
    };
    // Both maps sync from the entity's types (NW and NM are seeded alike).
    const checks = seedChecks(series.types);
    const editorFields = series.editorFields ?? [];
    this.formModel.set(next);
    this.typeChecks.set(checks);
    this.editorFields.set(editorFields);
    this.baselineJson.set(JSON.stringify([next, checks, editorFields]));
    this.displayNextNumber.set(series.nextMessageNumber);
    this.form().reset();
    this.saving.set(false);
  }

  protected setMainType(mainType: MainType): void {
    this.formModel.update((value) => ({ ...value, mainType }));
  }

  protected setSequenceType(sequenceType: NumberSequenceType): void {
    // No shortFormat delete here: the working text survives NONE (stripped on save only).
    this.formModel.update((value) => ({ ...value, numberSequenceType: sequenceType }));
  }

  protected toggleType(mainType: MainType, type: string): void {
    this.typeChecks.update((checks) => ({
      ...checks,
      [mainType]: { ...checks[mainType], [type]: !checks[mainType][type] },
    }));
  }

  /** The popover persisted a number: refresh the row's display + the list, drawer stays open. */
  protected onNextNumberUpdated(): void {
    const model = this.model();
    if (!model) {
      return;
    }
    this.facade.refresh();
    this.api.getNextNumber(model.series.seriesId, this.currentYear()).subscribe({
      next: (nextNumber) => this.displayNextNumber.set(nextNumber ?? undefined),
      // The list refresh already ran; a failed display refetch just keeps the old value.
      error: () => undefined,
    });
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (!model || this.saveDisabled()) {
      return;
    }
    const series = buildSeriesPayload(this.formModel(), this.typeChecks(), this.editorFields());
    const request = model.mode === 'add' ? this.api.create(series) : this.api.update(series);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.series.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.series.toast.saveError');
      },
    });
  }
}

/** Fresh per-mainType check maps, in candidate order, ticked by `types` membership. */
function seedChecks(
  types: readonly string[] | undefined,
): Record<MainType, Record<string, boolean>> {
  const seed = (mainType: MainType): Record<string, boolean> =>
    Object.fromEntries(RESTRICT_TYPES[mainType].map((type) => [type, !!types?.includes(type)]));
  return { NW: seed('NW'), NM: seed('NM') };
}
