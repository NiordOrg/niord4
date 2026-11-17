import { Component, computed, effect, ElementRef, inject, signal, untracked } from '@angular/core';
import { CdkDrag, type CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { toSignal } from '@angular/core/rxjs-interop';
import { disabled, form } from '@angular/forms/signals';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { Drawer } from '../../../../shared/components/drawer/drawer';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { mediaQuerySignal } from '../../../../shared/utils/media-query';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import type {
  Message,
  MessageDescription,
  MessagePart,
  MessageType,
} from '../../../messages/models/message.model';
import { ManageOptionalFields } from '../../components/manage-optional-fields/manage-optional-fields';
import { EditorViewHeader } from '../../components/editor-view-header/editor-view-header';
import { AreasVicinityField } from '../../components/fields/areas-vicinity-field/areas-vicinity-field';
import { AttachmentsField } from '../../components/fields/attachments-field/attachments-field';
import { CategoriesFieldRow } from '../../components/fields/categories-field-row/categories-field-row';
import { ChartsFieldRow } from '../../components/fields/charts-field-row/charts-field-row';
import { DatesField } from '../../components/fields/dates-field/dates-field';
import { IdField } from '../../components/fields/id-field/id-field';
import { LayoutField } from '../../components/fields/layout-field/layout-field';
import { OrigInfoField } from '../../components/fields/orig-info-field/orig-info-field';
import { PublicationField } from '../../components/fields/publication-field/publication-field';
import { ReferencesFieldEditor } from '../../components/fields/references-field-editor/references-field-editor';
import { SourceField } from '../../components/fields/source-field/source-field';
import { TitleField } from '../../components/fields/title-field/title-field';
import { TypeField } from '../../components/fields/type-field/type-field';
import { PartCard } from '../../components/part-card/part-card';
import { PromulgationField } from '../../promulgation/promulgation-field/promulgation-field';
import { SectionPanel } from '../../components/section-panel/section-panel';
import { LivePreview, type PreviewFocusRequest } from '../../components/live-preview/live-preview';
import { StatusFlow } from '../../components/status-flow/status-flow';
import { type EditorEditType, EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';

/** ≤1080 px flips the preview from an inline splitter pane to a header-triggered drawer. */
const NARROW_QUERY = '(max-width: 1080px)';

/**
 * The ONE Signal Forms value model: the message's scalar + per-language value fields. Structural
 * state (references, parts, geometry, opt-ins, areas/categories/charts membership) is NOT mirrored
 * here — it is mutated through explicit facade/service mutators. Per-language slices are
 * `Record<lang, string>` keyed by the configured model languages (N-language, never two).
 */
interface EditFormModel {
  type: '' | MessageType;
  seriesId: string;
  number: number | null;
  shortId: string;
  publishDateFrom: Date | null;
  publishDateTo: Date | null;
  followUpDate: Date | null;
  originalInformation: boolean;
  autoTitle: boolean;
  horizontalDatum: string;
  separatePage: boolean;
  titles: Record<string, string>;
  vicinities: Record<string, string>;
  sources: Record<string, string>;
}

const EMPTY_FORM: EditFormModel = {
  type: '',
  seriesId: '',
  number: null,
  shortId: '',
  publishDateFrom: null,
  publishDateTo: null,
  followUpDate: null,
  originalInformation: false,
  autoTitle: false,
  horizontalDatum: '',
  separatePage: false,
  titles: {},
  vicinities: {},
  sources: {},
};

/** Stable serialisation for the sync guard + adjust-trigger diffs (order-stable, deep). */
function stableJson(value: unknown): string {
  return JSON.stringify(value ?? null);
}

/**
 * The redesigned edit form host: the three-panel spine (Preamble / Parts / Misc) of field editors,
 * the live-preview two-pane shell (inline splitter > 1080 px, header-triggered drawer ≤ 1080 px),
 * and the header-projected Save / save-status chrome ({@link EditorViewHeader}'s `[headerActions]`
 * slot). It bridges the ONE Signal Forms value model to the facade through a single guarded
 * facade↔form sync point: programmatic loads never trip `userTouched`; genuine edits
 * `markUserTouched` + `patchMessage`, with the type/series auto-deduce + adjust cadence (immediate
 * on type/series, debounced on number/vicinity) computed INSIDE the sync point by diffing the model
 * — no per-field `valueChange` handler writes the same slice twice. Field editability is a single
 * `editableNow()`-driven schema `disabled` gate. Structural ops are facade/service mutators. While
 * a save is in flight (`facade.saving()`) the whole form pane is inert, not disabled: the save
 * repopulates the form from the server's copy, so an edit made in the round trip would be lost
 * without a word, and folding `saving()` into `editableNow()` would unmount the part editors and
 * the Save button for the round trip.
 *
 * Mounted on the `edit/:id`, `copy/:id(/:referenceType)` and `edit` (template) routes; the route
 * bootstrapping resolves which load path to run from the route's `editType` data.
 */
@Component({
  selector: 'app-edit-form',
  imports: [
    TranslocoPipe,
    CdkDropList,
    CdkDrag,
    Icon,
    Confirm,
    Drawer,
    ProgressBar,
    MessageDetailsRenderer,
    EditorViewHeader,
    SectionPanel,
    ManageOptionalFields,
    LivePreview,
    StatusFlow,
    TypeField,
    OrigInfoField,
    IdField,
    TitleField,
    ReferencesFieldEditor,
    DatesField,
    AreasVicinityField,
    AttachmentsField,
    CategoriesFieldRow,
    ChartsFieldRow,
    PublicationField,
    SourceField,
    LayoutField,
    PartCard,
    PromulgationField,
  ],
  providers: [ConfirmService],
  templateUrl: './edit-form.html',
  // While a part card is dragged, its in-flow placeholder (the CDK clone left behind) fades so the
  // drop target reads as a gap rather than a duplicated card.
  styles: `
    .cdk-drag-placeholder {
      opacity: 0.4;
    }
  `,
  // Page-growable editor surface: the view grows with the form and the PAGE scrolls (one
  // scrollbar). The preview column runs the full length of the form column via the row's default
  // flex stretch.
  host: { class: 'flex flex-1 flex-col' },
})
export class EditForm {
  private readonly route = inject(ActivatedRoute);
  private readonly language = inject(LanguageService);
  private readonly hostEl = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);

  protected readonly editableNow = this.facade.editableNow;

  // Per-date readonly-by-status, layered on the `editableNow`
  // baseline in the schema below: the publish-From is editable only while DRAFT|VERIFIED, while the
  // publish-To and Follow-up stay editable through PUBLISHED. The status gate NARROWS the single
  // `editableNow()` gate — it never widens it (a non-admin editor on a PUBLISHED message stays fully
  // read-only because `editableNow()` is already false).
  private readonly fromDateEditableByStatus = computed<boolean>(() => {
    const status = this.facade.message()?.status;
    return status === 'DRAFT' || status === 'VERIFIED';
  });
  private readonly toDateEditableByStatus = computed<boolean>(() => {
    const status = this.facade.message()?.status;
    return status === 'DRAFT' || status === 'VERIFIED' || status === 'PUBLISHED';
  });

  /** ≤1080 px → drawer preview; > 1080 px → inline splitter pane. */
  protected readonly narrow = mediaQuerySignal(NARROW_QUERY);
  protected readonly previewOpen = signal(false);

  private readonly formModel = signal<EditFormModel>(EMPTY_FORM);
  protected readonly form = form(this.formModel, (path) => {
    // The SINGLE editability gate: every value control disables together off `editableNow()`.
    disabled(path.type, { when: () => !this.editableNow() });
    disabled(path.seriesId, { when: () => !this.editableNow() });
    disabled(path.number, { when: () => !this.editableNow() });
    disabled(path.shortId, { when: () => !this.editableNow() });
    // Per-date readonly-by-status narrows the baseline gate (see the status computeds above).
    disabled(path.publishDateFrom, {
      when: () => !this.editableNow() || !this.fromDateEditableByStatus(),
    });
    disabled(path.publishDateTo, {
      when: () => !this.editableNow() || !this.toDateEditableByStatus(),
    });
    disabled(path.followUpDate, {
      when: () => !this.editableNow() || !this.toDateEditableByStatus(),
    });
    disabled(path.originalInformation, { when: () => !this.editableNow() });
    disabled(path.autoTitle, { when: () => !this.editableNow() });
    disabled(path.horizontalDatum, { when: () => !this.editableNow() });
    disabled(path.separatePage, { when: () => !this.editableNow() });
    disabled(path.titles, { when: () => !this.editableNow() });
    disabled(path.vicinities, { when: () => !this.editableNow() });
    disabled(path.sources, { when: () => !this.editableNow() });
  });

  // The last model synced to the facade — the serialized-snapshot guard so a programmatic populate
  // never trips `userTouched`, plus the prev-model the sync point diffs to decide the adjust
  // cadence. Kept as BOTH the object (for diffing) and its JSON (for the guard).
  private lastSyncedModel: EditFormModel = EMPTY_FORM;
  private lastSyncedJson = stableJson(EMPTY_FORM);

  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });

  protected readonly parts = computed<readonly MessagePart[]>(
    () => this.facade.message()?.parts ?? [],
  );

  /** Read-only when the message itself is not editable (published/cancelled, non-admin). */
  protected readonly readOnly = computed<boolean>(() => !this.facade.editable());

  /** Current app language for the read-only details renderer. */
  protected readonly previewLanguage = computed<string>(() => this.language.selectedLanguage());

  constructor() {
    // Route → load path (reacts to param changes so drafts-widget navigation re-resolves).
    const editType = (this.route.snapshot.data['editType'] as EditorEditType) ?? 'edit';
    effect(() => {
      const map = this.params();
      const id = map.get('id') ?? undefined;
      const referenceType = map.get('referenceType') ?? undefined;
      untracked(() => this.facade.initForRoute(editType, id, referenceType));
    });

    // Repopulate the form only on a genuine load (loadSeq), never on the per-field patches the form
    // itself makes (which would loop). THE single guarded facade→form sync point.
    effect(() => {
      this.facade.loadSeq();
      const message = untracked(() => this.facade.message());
      if (message) {
        untracked(() => this.populateForm(message));
      }
    });

    // form → facade: the single write path. A genuine edit trips both latches, patches the message,
    // and drives the adjust cadence from a prev/next diff — no separate per-field change handler.
    effect(() => {
      const model = this.formModel();
      const json = stableJson(model);
      untracked(() => {
        if (json === this.lastSyncedJson) {
          return;
        }
        this.syncModel(this.lastSyncedModel, model);
      });
    });

    // facade → form (programmatic type only): the promulgation auto-deduce sets `message.type`
    // (+ its sole series) straight on the facade without bumping `loadSeq`, so the load-only
    // populate effect above never re-runs and the Type/ID controls would keep showing the old
    // value. Mirror that out-of-band change into the form model here — detected by the message's
    // type/series diverging from `lastSyncedModel` (a form-originated change already updated it, so
    // this stays quiet for genuine edits). The guard is re-seeded BEFORE the model set so the
    // form→facade effect reads it as already-synced: no re-patch, no second adjust, no loop, and no
    // second write path (the facade already patched + adjusted the deduced type).
    effect(() => {
      const message = this.facade.message();
      untracked(() => {
        if (!message) {
          return;
        }
        const type = (message.type ?? '') as EditFormModel['type'];
        const seriesId = message.messageSeries?.seriesId ?? '';
        if (type === this.lastSyncedModel.type && seriesId === this.lastSyncedModel.seriesId) {
          return;
        }
        const next: EditFormModel = { ...this.formModel(), type, seriesId };
        this.lastSyncedModel = next;
        this.lastSyncedJson = stableJson(next);
        this.formModel.set(next);
      });
    });
  }

  // ── Value layer (single write path) ──────────────────────────────────────────

  private syncModel(prev: EditFormModel, next: EditFormModel): void {
    let effective = next;
    // Type change auto-deduces the sole permitting series (writing the series field re-fires this
    // effect, but the pre-seeded guard below makes that re-run a no-op — one adjust, not two).
    if (prev.type !== next.type && next.type) {
      const candidates = this.facade.seriesAllowingType(next.type);
      if (candidates.length === 1) {
        effective = { ...next, seriesId: candidates[0].seriesId };
        this.form.seriesId().value.set(candidates[0].seriesId);
      }
    }
    this.lastSyncedModel = effective;
    this.lastSyncedJson = stableJson(effective);
    this.facade.markUserTouched();
    this.facade.patchMessage(this.mapModelToPatch(effective));
    this.applyAdjustTriggers(prev, effective);
  }

  /** Fire the adjust cadence from a prev/next diff: immediate on type/series, debounced on number/vicinity. */
  private applyAdjustTriggers(prev: EditFormModel, next: EditFormModel): void {
    if (prev.type !== next.type) {
      this.facade.adjust({ inclPromulgations: true }).subscribe();
      return;
    }
    if (prev.seriesId !== next.seriesId) {
      this.facade.adjust().subscribe();
      return;
    }
    if (
      prev.number !== next.number ||
      stableJson(prev.vicinities) !== stableJson(next.vicinities)
    ) {
      this.facade.adjustDebounced();
    }
  }

  private populateForm(message: Message): void {
    const langs = this.modelLanguages();
    const descs = message.descs ?? [];
    const titles: Record<string, string> = {};
    const vicinities: Record<string, string> = {};
    const sources: Record<string, string> = {};
    for (const lang of langs) {
      const desc = descs.find((entry) => entry.lang === lang);
      titles[lang] = desc?.title ?? '';
      vicinities[lang] = desc?.vicinity ?? '';
      sources[lang] = desc?.source ?? '';
    }
    const next: EditFormModel = {
      type: message.type ?? '',
      seriesId: message.messageSeries?.seriesId ?? '',
      number: message.number ?? null,
      shortId: message.shortId ?? '',
      publishDateFrom: message.publishDateFrom ? new Date(message.publishDateFrom) : null,
      publishDateTo: message.publishDateTo ? new Date(message.publishDateTo) : null,
      followUpDate: message.followUpDate ? new Date(message.followUpDate) : null,
      originalInformation: message.originalInformation ?? false,
      autoTitle: message.autoTitle ?? false,
      horizontalDatum: message.horizontalDatum ?? '',
      separatePage: message.separatePage ?? false,
      titles,
      vicinities,
      sources,
    };
    this.formModel.set(next);
    // Seed the guard BEFORE the sync effect flushes so the load reads as "already synced".
    this.lastSyncedModel = next;
    this.lastSyncedJson = stableJson(next);
    this.form().reset();
  }

  /**
   * Map the form model to a message patch, PRESERVING server-derived slices so an adjust copy-back
   * is never reverted by a stale form value (the form only re-populates on load, not on adjust):
   * title kept when `autoTitle`, shortId when the message number drives it — exactly the
   * `applyAdjustment` copy-back rules. Source and vicinity are always form-authoritative (adjust
   * never touches them).
   */
  private mapModelToPatch(value: EditFormModel): Partial<Message> {
    const message = this.facade.message();
    const numberDrivesShortId = this.facade.messageNumberEditable();
    return {
      type: value.type || undefined,
      messageSeries: value.seriesId ? this.facade.resolveSeriesForPatch(value.seriesId) : undefined,
      number: value.number ?? undefined,
      shortId: numberDrivesShortId ? (message?.shortId ?? undefined) : value.shortId || undefined,
      publishDateFrom: value.publishDateFrom ? value.publishDateFrom.toISOString() : undefined,
      publishDateTo: value.publishDateTo ? value.publishDateTo.toISOString() : undefined,
      followUpDate: value.followUpDate ? value.followUpDate.toISOString() : undefined,
      originalInformation: value.originalInformation,
      autoTitle: value.autoTitle,
      horizontalDatum: value.horizontalDatum || undefined,
      separatePage: value.separatePage,
      descs: this.mergeDescs(value, message?.descs),
    };
  }

  private mergeDescs(
    value: EditFormModel,
    existing: readonly MessageDescription[] | undefined,
  ): MessageDescription[] {
    const base = existing ?? [];
    const langs = this.modelLanguages();
    const keepTitle = value.autoTitle;
    const merged = langs.map((lang) => {
      const desc = base.find((entry) => entry.lang === lang) ?? { lang };
      return {
        ...desc,
        lang,
        title: keepTitle ? desc.title : value.titles[lang] || undefined,
        source: value.sources[lang] || undefined,
        vicinity: value.vicinities[lang] || undefined,
      };
    });
    // Preserve descs authored in a language NOT in the configured model-language list (appended
    // unchanged) so a language-set change never drops authored content from the save DTO.
    const extra = base.filter((desc) => !langs.includes(desc.lang ?? ''));
    return [...merged, ...extra];
  }

  private modelLanguages(): readonly string[] {
    return this.language.modelLanguages.map((option) => option.value);
  }

  // ── Parts ──────────────────────────────────────────────────────────────────
  // The Parts panel head owns the Add-part affordance (the per-card add button was removed): append
  // a fresh part after the last one, pairing the facade op with the field-state splice so per-part
  // expand/edited state stays aligned (a changed `parts` reference confirms the op happened).
  protected onAddPart(): void {
    const before = this.facade.message()?.parts;
    const count = before?.length ?? 0;
    this.facade.addPart(count - 1);
    if (this.facade.message()?.parts !== before) {
      this.fields.partAdded(count);
    }
  }

  // Drag-reorder a part card (grip handle → CDK drop): splice-move the part and keep the per-part
  // expand/edited field-state arrays aligned in lock-step, the same pairing as the move-up/down
  // buttons. The facade guards a same-index / out-of-bounds move, so a no-op drop commits nothing.
  protected onPartDrop(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex !== event.currentIndex) {
      this.facade.movePartTo(event.previousIndex, event.currentIndex);
      this.fields.partMoved(event.previousIndex, event.currentIndex);
    }
  }

  // ── Preview shell ────────────────────────────────────────────────────────────

  /**
   * A preview region was clicked: LivePreview already expanded the target row, so close the drawer
   * (narrow), then scroll the row into view and move keyboard focus to its head. The rows are NOT
   * DOM siblings and the field-row host is `display:contents`, so the scroll target is the marker
   * element's first rendered child; the focusable head (the row's registered roving-tabindex
   * element) is its clickable grid head.
   */
  protected onPreviewFocus(request: PreviewFocusRequest): void {
    if (this.narrow()) {
      this.previewOpen.set(false);
    }
    const marker = this.hostEl.nativeElement.querySelector(
      `[data-field-row="${request.fieldId}"][data-field-index="${request.index}"]`,
    );
    if (!marker) {
      return;
    }
    marker.firstElementChild?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    marker.querySelector<HTMLElement>('.ed-row-head')?.focus();
  }
}
