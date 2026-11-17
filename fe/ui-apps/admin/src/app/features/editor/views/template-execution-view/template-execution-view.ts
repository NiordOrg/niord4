import { Component, computed, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { debounceTime, forkJoin, map, type Observable, Subject } from 'rxjs';

import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Card } from '../../../../shared/components/card/card';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { Drawer } from '../../../../shared/components/drawer/drawer';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../shared/services/discard-confirm.service';
import { Icon } from '../../../../shared/icons/icon';
import { mediaQuerySignal } from '../../../../shared/utils/media-query';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import type { Message } from '../../../messages/models/message.model';
import type {
  ParamType,
  SystemCategory,
  TemplateParam,
  TemplateParamValues,
} from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { AreasApiService } from '../../../messages/services/api/areas-api.service';
import { MessageGeometryService } from '../../../messages/services/message-geometry.service';
import { LivePreview } from '../../components/live-preview/live-preview';
import { TemplateExecuteFields } from '../../components/template-execution/template-execute-fields';
import { TemplateSelectionDrawer } from '../../components/template-execution/template-selection-drawer';
import { mergeTemplateLanguages } from '../../components/template-execution/template-language-merge.util';
import { TemplatesApiService } from '../../services/api/templates-api.service';
import { EditorFacade } from '../../services/editor-facade';
import { TemplateExecutionSeedStore } from '../../services/template-execution-seed-store';
import { TemplateMessageStore } from '../../services/template-message-store';

/** ≤1080 px flips the preview from an inline splitter pane to a header-triggered drawer (as the edit form). */
const NARROW_QUERY = '(max-width: 1080px)';
/** Auto-preview debounce — the house cadence used for the editor's adjust round-trip. */
const PREVIEW_DEBOUNCE_MS = 500;

const emptyFc = (): GeometryFeatureCollection => ({ type: 'FeatureCollection', features: [] });

/** Move an item within a copy of the array from one index to another (splice-move). */
function spliceMove<T>(list: readonly T[], from: number, to: number): T[] {
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

/** A parameter's label in the language shown, as the parameter field prints it. */
function paramLabel(param: TemplateParam, lang: string): string {
  const descs = param.descs;
  return descs?.find((d) => d.lang === lang)?.name ?? descs?.[0]?.name ?? param.paramId;
}

/** Empty: nothing, a blank text or an empty list. `false` and `0` are values. */
function isEmptyValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

/** A tick box always holds a value: unticked is `false`, which a template reads as such. */
function isBoolean(param: TemplateParam, type: ParamType | undefined): boolean {
  return (type?.type ?? 'STANDARD') === 'STANDARD' && (type?.name ?? param.type) === 'boolean';
}

/**
 * The labels of the mandatory parameters left empty, over every chosen template (each label once).
 *
 * A `positionList` parameter is checked in each derived position record (none while the template has
 * no position, when the field is not shown). A COMPOSITE parameter's children are checked one level
 * down; they count only when the composite itself is mandatory or one of its children has a value, so
 * an optional group left wholly empty does not hold Execute back. A mandatory composite none of whose
 * children is mandatory needs one of them filled, and is named by its own label.
 */
function missingMandatoryLabels(
  templates: readonly SystemCategory[],
  values: readonly TemplateParamValues[],
  paramTypes: ReadonlyMap<string, ParamType>,
  lang: string,
): string[] {
  const missing = new Set<string>();
  templates.forEach((template, index) => {
    const record: TemplateParamValues = values[index] ?? {};
    for (const param of template.templateParams ?? []) {
      const records = param.positionList
        ? ((record.positions ?? []) as readonly Record<string, unknown>[])
        : [record as Record<string, unknown>];
      const type = paramTypes.get(param.type);
      for (const rec of records) {
        if (type?.type === 'COMPOSITE') {
          const children = type.templateParams ?? [];
          const anyChildValue = children.some((child) => !isEmptyValue(rec[child.paramId]));
          if (!param.mandatory && !anyChildValue) {
            continue;
          }
          if (param.mandatory && !anyChildValue && !children.some((child) => child.mandatory)) {
            missing.add(paramLabel(param, lang));
            continue;
          }
          for (const child of children) {
            if (
              child.mandatory &&
              !isBoolean(child, paramTypes.get(child.type)) &&
              isEmptyValue(rec[child.paramId])
            ) {
              missing.add(paramLabel(child, lang));
            }
          }
        } else if (param.mandatory && !isBoolean(param, type) && isEmptyValue(rec[param.paramId])) {
          missing.add(paramLabel(param, lang));
        }
      }
    }
  });
  return [...missing];
}

/** The live-preview generation state, driving the Refresh-preview control's indicator. */
type PreviewState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The template-EXECUTION view — a full editor-shell page (sibling of the edit form under the same
 * shell/route providers). Left: a Templates card (chosen chips + Change), the Base fields card and
 * the per-template parameter cards ({@link TemplateExecuteFields}); right: a live preview
 * ({@link LivePreview}) fed by a debounced auto re-execute, with a Refresh-preview control where the
 * edit form's verify/publish stepper sits and an "Execute template" action where Save sits.
 *
 * Data stays backend-driven: `PUT /templates/execute` renders the preview AND the final result; the
 * result hands off to the editor (`/editor/edit`) as a born-dirty draft (create / re-run on a draft)
 * or is applied in place to the shared facade (re-run on a saved message). The working copy is LOCAL
 * until Execute, so a debounced preview round-trip never dirties the real editor state.
 */
@Component({
  selector: 'app-template-execution-view',
  imports: [
    TranslocoPipe,
    NgTemplateOutlet,
    Icon,
    Card,
    Confirm,
    HelpAnchor,
    Drawer,
    LivePreview,
    TemplateExecuteFields,
    TemplateSelectionDrawer,
  ],
  providers: [ConfirmService],
  templateUrl: './template-execution-view.html',
  host: {
    class: 'flex flex-1 flex-col',
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class TemplateExecutionView {
  private readonly templatesApi = inject(TemplatesApiService);
  private readonly categoriesApi = inject(CategoriesApiService);
  private readonly messagesApi = inject(MessagesApiService);
  private readonly areasApi = inject(AreasApiService);
  private readonly geometry = inject(MessageGeometryService);
  private readonly language = inject(LanguageService);
  private readonly notify = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly discardConfirm = inject(DiscardConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly facade = inject(EditorFacade);
  private readonly seedStore = inject(TemplateExecutionSeedStore);
  private readonly templateStore = inject(TemplateMessageStore);

  // ── State (lifted from the retired execute dialog) ────────────────────────────
  /** The working copy the base/param fields edit and that execute renders — LOCAL until Execute. */
  protected readonly messageCopy = signal<Message>({ id: '' });
  /** The chosen TEMPLATE categories (execution order = list order), hydrated with their metadata. */
  protected readonly templates = signal<readonly SystemCategory[]>([]);
  /** Non-template categories the seed carried — preserved through execute. */
  private readonly otherCategories = signal<readonly SystemCategory[]>([]);
  /** Per-template value records, index-aligned with `templates`. */
  protected readonly values = signal<TemplateParamValues[]>([]);
  protected readonly paramTypes = signal<ReadonlyMap<string, ParamType>>(new Map());

  /** The rendered preview message (last good render is kept across a pending / failed re-execute). */
  protected readonly previewMessage = signal<Message | undefined>(undefined);
  protected readonly previewState = signal<PreviewState>('idle');
  protected readonly executing = signal(false);
  /** The view's local dirtiness (the shared facade's `userTouched` is not tripped in this mode). */
  protected readonly touched = signal(false);
  protected readonly drawerOpen = signal(false);

  /** ≤1080 px → drawer preview; > 1080 px → inline splitter pane (same recipe as the edit form). */
  protected readonly narrow = mediaQuerySignal(NARROW_QUERY);
  protected readonly previewOpen = signal(false);

  private returnUrl = '/editor';

  protected readonly lang = computed<string>(() => this.language.selectedLanguage());
  protected readonly selectedIds = computed<readonly number[]>(() =>
    this.templates().map((cat) => cat.id),
  );
  /**
   * The labels of the mandatory parameters still empty, in the order of the cards (each label once).
   * Execute is held back while this is not empty; the preview keeps running.
   */
  protected readonly missingMandatory = computed<readonly string[]>(() =>
    missingMandatoryLabels(this.templates(), this.values(), this.paramTypes(), this.lang()),
  );
  protected readonly canExecute = computed<boolean>(
    () => this.templates().length > 0 && !this.executing() && this.missingMandatory().length === 0,
  );

  private readonly previewTrigger$ = new Subject<void>();
  private previewSeq = 0;

  constructor() {
    this.initFromSeed();

    // Debounced auto-preview: re-execute on any change to the templates / working copy / values.
    this.previewTrigger$
      .pipe(debounceTime(PREVIEW_DEBOUNCE_MS), takeUntilDestroyed())
      .subscribe(() => this.runPreview());
    effect(() => {
      this.templates();
      this.messageCopy();
      this.values();
      untracked(() => this.previewTrigger$.next());
    });
  }

  /** Consume the launcher's seed; a missing seed (a stray deep link) returns to the Start screen. */
  private initFromSeed(): void {
    const seed = this.seedStore.consume();
    if (!seed) {
      void this.router.navigate(['/editor']);
      return;
    }
    this.returnUrl = seed.returnUrl;
    this.messageCopy.set(structuredClone(seed.message));

    // NOT `this.lang()`: the param-type catalogue must carry every language's LIST-value descs so a
    // chosen value re-localizes per body language at execute time (a lang filter would freeze it).
    this.templatesApi
      .parameterTypes()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((types) => this.paramTypes.set(new Map(types.map((t) => [t.name, t]))));

    const ids = (seed.message.categories ?? []).map((cat) => cat.id);
    // Create flows (blank card AND the Start screen's template-search pick — a never-saved seed,
    // with or without categories): swap in a proper server-blank so the base fields
    // (promulgations, series defaults) are populated for editing.
    if (!seed.message.created && seed.message.mainType) {
      this.messagesApi
        .newMessageTemplate(seed.message.mainType)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((base) => {
          if (base) {
            this.messageCopy.set({ ...base, mainType: seed.message.mainType });
          }
        });
    }
    if (ids.length === 0) {
      // Nothing chosen yet — open the selection drawer straight away.
      this.drawerOpen.set(true);
      return;
    }
    this.fetchTemplateDetailsAllLangs(ids)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((cats) => {
        const templates = cats.filter((cat) => cat.type === 'TEMPLATE');
        this.otherCategories.set(cats.filter((cat) => cat.type !== 'TEMPLATE'));
        this.initTemplates(templates);
        if (templates.length === 0) {
          this.drawerOpen.set(true);
        }
      });
  }

  /**
   * Seed the chosen templates + fresh value records WITHOUT touching the working copy's parts — the
   * seed's existing parts (and their geometry) stay index-aligned to the templates; the scaffold
   * effect in the fields ensures a DETAILS part per template and re-derives the positions from them.
   */
  private initTemplates(templates: readonly SystemCategory[]): void {
    this.templates.set(templates);
    this.values.set(templates.map(() => ({ posDecimals: '1', positions: [] })));
  }

  /**
   * Fetch the given template categories in EVERY configured language and merge their descs, so the
   * execution card's labels (category + param names, LIST-value display) re-localize on a language
   * switch. `/categories/search` filters descs to one language, which would otherwise freeze them.
   */
  private fetchTemplateDetailsAllLangs(ids: readonly number[]): Observable<SystemCategory[]> {
    return forkJoin(
      this.language.modelLanguages.map((option) =>
        this.categoriesApi.getTemplateDetails(ids, { language: option.value }),
      ),
    ).pipe(map((perLanguage) => mergeTemplateLanguages(perLanguage)));
  }

  protected nameOf(cat: SystemCategory): string {
    const descs = cat.descs;
    return descs?.find((d) => d.lang === this.lang())?.name ?? descs?.[0]?.name ?? '';
  }

  // ── Template selection (chips + the Change drawer) ────────────────────────────
  protected openDrawer(): void {
    // Only one right drawer can show at a time — close the narrow preview drawer first.
    this.previewOpen.set(false);
    this.drawerOpen.set(true);
  }

  protected removeTemplate(index: number): void {
    const next = this.templates().filter((_, i) => i !== index);
    const nextValues = this.values().filter((_, i) => i !== index);
    const nextParts = (this.messageCopy().parts ?? []).filter((_, i) => i !== index);
    this.templates.set(next);
    this.values.set(nextValues);
    this.messageCopy.update((message) => ({ ...message, parts: nextParts }));
    this.touched.set(true);
  }

  /** Drag/move-reorder the per-template cards, moving the aligned value + template part in lock-step. */
  protected moveTemplate(from: number, to: number): void {
    const count = this.templates().length;
    if (from === to || from < 0 || to < 0 || from >= count || to >= count) {
      return;
    }
    this.templates.update((list) => spliceMove(list, from, to));
    this.values.update((list) => spliceMove(list, from, to));
    this.messageCopy.update((message) => {
      const parts = [...(message.parts ?? [])];
      const moved = spliceMove(parts.slice(0, count), from, to);
      return { ...message, parts: [...moved, ...parts.slice(count)] };
    });
    this.touched.set(true);
  }

  /**
   * The message type changed — re-derive promulgations for the new type via
   * `adjustEditableMessage`. Only when some template exposes the promulgation std field.
   */
  protected onTypeChanged(): void {
    this.touched.set(true);
    if (!this.templates().some((t) => (t.stdTemplateFields ?? []).includes('promulgation'))) {
      return;
    }
    this.messagesApi
      .adjustEditableMessage(this.buildPayload(), { inclPromulgations: true })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) =>
          this.messageCopy.update((m) => ({ ...m, promulgations: result.promulgations })),
        error: () => undefined,
      });
  }

  /**
   * A part's geometry was edited — derive the areas from it.
   *
   * Two refusals make an unattended background call safe: it never touches a NON-EMPTY selection,
   * and it never applies an EMPTY response. So a hand-picked area can't be eaten by a request the
   * user didn't ask for, and drawing a position outside every configured area can't blank what is
   * already there. The manual button below is the deliberate override for both.
   *
   * Not shared with `EditorFacade.computeAreas()`: that one reads the facade's working copy, while
   * this surface edits a LOCAL message copy that stays out of the facade until Execute.
   */
  protected onGeometryChanged(): void {
    if ((this.messageCopy().areas?.length ?? 0) > 0) {
      return;
    }
    this.computeAreasFromGeometry({ applyEmpty: false });
  }

  /** The explicit "compute from locations" action — overwrites whatever is selected. */
  protected onComputeAreas(): void {
    this.touched.set(true);
    this.computeAreasFromGeometry({ applyEmpty: true });
  }

  private computeAreasFromGeometry(options: { applyEmpty: boolean }): void {
    const features = this.geometry.getFeatures(this.messageCopy(), { excludeAffectedAreas: true });
    if (features.length === 0) {
      return;
    }
    this.areasApi
      .intersectingAreas({ type: 'FeatureCollection', features })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (areas) => {
          if (areas.length === 0 && !options.applyEmpty) {
            return;
          }
          this.messageCopy.update((message) => ({ ...message, areas: [...areas] }));
        },
        error: () => this.notify.error('editor.areas.computeError'),
      });
  }

  /** Reconcile the drawer's checked set into the ordered chosen list, keeping values + parts aligned. */
  protected onDrawerConfirm(chosen: readonly SystemCategory[]): void {
    const prev = this.templates();
    const chosenIds = new Set(chosen.map((cat) => cat.id));
    const kept = prev.filter((cat) => chosenIds.has(cat.id));
    const keptIds = new Set(kept.map((cat) => cat.id));
    const added = chosen.filter((cat) => !keptIds.has(cat.id));
    // Apply immediately from the drawer's (single-language) hydration so nothing is lost / no gap.
    this.setTemplates([...kept, ...added], prev);
    this.touched.set(true);
    this.drawerOpen.set(false);
    // Then re-fetch just the ADDED templates in every language and swap in their all-language descs
    // so their labels re-localize too (kept templates already carry all languages). Same id + order,
    // so the reconciled values/parts stay aligned; a failed/empty fetch leaves the drawer version.
    if (added.length === 0) {
      return;
    }
    this.fetchTemplateDetailsAllLangs(added.map((cat) => cat.id))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((allLang) => {
        const byId = new Map(allLang.map((cat) => [cat.id, cat]));
        this.templates.update((templates) => templates.map((cat) => byId.get(cat.id) ?? cat));
      });
  }

  /**
   * Replace the chosen templates, re-aligning `values` and the working copy's template parts by
   * category id so a per-template param value / geometry follows its template through a reselection.
   * `previous` is the prior chosen list to migrate from (empty on the initial seed).
   */
  private setTemplates(next: readonly SystemCategory[], previous: readonly SystemCategory[]): void {
    const prevValues = this.values();
    const valueById = new Map(previous.map((cat, i) => [cat.id, prevValues[i]]));

    const message = this.messageCopy();
    const oldParts = message.parts ?? [];
    const templateParts = oldParts.slice(0, previous.length);
    const extraParts = oldParts.slice(previous.length);
    const partById = new Map(previous.map((cat, i) => [cat.id, templateParts[i]]));

    const nextValues = next.map(
      (cat) => valueById.get(cat.id) ?? { posDecimals: '1', positions: [] },
    );
    const nextTemplateParts = next.map(
      (cat) => partById.get(cat.id) ?? { type: 'DETAILS', geometry: emptyFc(), eventDates: [] },
    );

    this.templates.set(next);
    this.values.set(nextValues);
    this.messageCopy.update((current) => ({
      ...current,
      parts: [...nextTemplateParts, ...extraParts],
    }));
  }

  // ── Live preview ──────────────────────────────────────────────────────────────
  private buildPayload(): Message {
    return {
      ...this.messageCopy(),
      categories: [...this.otherCategories(), ...this.templates()],
    };
  }

  /** Run one preview execute; a monotonic seq drops a superseded (out-of-order) response. */
  private runPreview(): void {
    if (this.templates().length === 0) {
      return;
    }
    const seq = ++this.previewSeq;
    this.previewState.set('loading');
    this.templatesApi
      .execute(this.buildPayload(), this.values())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (seq !== this.previewSeq) {
            return;
          }
          this.previewMessage.set(result);
          this.previewState.set('ready');
        },
        // Keep the last good preview on error — surface a subtle inline state, never a per-keystroke toast.
        error: () => {
          if (seq === this.previewSeq) {
            this.previewState.set('error');
          }
        },
      });
  }

  /** Manual refresh (the fallback control) — fires immediately, bypassing the debounce. */
  protected refreshPreview(): void {
    this.runPreview();
  }

  // ── Execute + navigation ──────────────────────────────────────────────────────
  protected execute(): void {
    if (!this.canExecute()) {
      return;
    }
    const run = () => this.runExecute();
    // The result replaces the editor's message: confirm while it holds anything the database does
    // not (an edit, or a copy / new message never saved) — not for a saved message whose edits
    // were undone.
    if (this.facade.hasUnsavedChanges()) {
      this.confirm.confirmNeutral({
        header: this.transloco.translate('editor.template.confirm.replaceHeader'),
        message: this.transloco.translate('editor.template.confirm.replaceBody'),
        acceptLabel: this.transloco.translate('editor.template.confirm.replaceAccept'),
        rejectLabel: this.transloco.translate('term.cancel'),
        accept: run,
      });
      return;
    }
    run();
  }

  private runExecute(): void {
    this.executing.set(true);
    this.templatesApi
      .execute(this.buildPayload(), this.values())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => this.handoff(result),
        error: () => {
          this.executing.set(false);
          this.notify.error('editor.template.error.execute');
        },
      });
  }

  /**
   * Hand the rendered result to the editor. A saved message (id + created) is applied in place to
   * the shared facade and lands back on its own edit URL; a new / draft result is seeded to the
   * transient store and enters the born-dirty template route.
   */
  private handoff(result: Message): void {
    this.executing.set(false);
    if (result.id && result.created) {
      this.facade.templateExecuted(result);
      this.facade.bypassGuardOnce();
      void this.router.navigate(['/editor/edit', result.id]);
    } else {
      this.templateStore.set(result);
      this.facade.bypassGuardOnce();
      void this.router.navigate(['/editor/edit']);
    }
  }

  protected async cancel(): Promise<void> {
    if (this.touched() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.facade.bypassGuardOnce();
    if (this.returnUrl === '/editor') {
      void this.router.navigate(['/editor']);
      return;
    }
    if (/\/editor\/edit\/[^/]+/.test(this.returnUrl)) {
      // Re-run on a saved message: the untouched working copy is still the facade's — dedupe onto it.
      void this.router.navigateByUrl(this.returnUrl);
      return;
    }
    // Re-run on a draft (the bare template route): re-seed the untouched working message to restore it.
    const current = this.facade.message();
    if (current) {
      this.templateStore.set(current);
    }
    void this.router.navigate(['/editor/edit']);
  }

  /** The CanDeactivate guard reads this for nav-away paths other than Execute / Cancel. */
  isDirty(): boolean {
    return this.touched();
  }

  /**
   * A reload or tab close asks while the view holds typed work. The editor shell's own listener asks
   * only for the shared facade, which this view leaves untouched until Execute. After a confirmed
   * reload the page still returns to Start (the seed is gone).
   */
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.isDirty()) {
      event.preventDefault();
    }
  }
}
