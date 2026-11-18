import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, type OnDestroy, signal } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, finalize, map, Observable, switchMap, tap } from 'rxjs';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { DomainMessageSeriesRef } from '../../../core/domain/models/domain.model';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { shiftDaysInZone } from '../../../shared/utils/zoned-date';
import {
  type BasePromulgation,
  type DateInterval,
  type MainType,
  type Message,
  type MessageArea,
  type MessageAttachment,
  type MessageChart,
  type MessageDescription,
  type MessageGeometryFeatureCollection,
  type MessagePart,
  type MessageReference,
  type MessageSeries,
  type MessageType,
  type ReferenceType,
} from '../../messages/models/message.model';
import type { AreaRef, CategoryRef } from '../../messages/models/messages-filter-params.model';
import { AreasApiService } from '../../messages/services/api/areas-api.service';
import { ChartsApiService } from '../../messages/services/api/charts-api.service';
import { MessagesApiService } from '../../messages/services/api/messages-api.service';
import { PromulgationsApiService } from '../../messages/services/api/promulgations-api.service';
import { MessageGeometryService } from '../../messages/services/message-geometry.service';
import { attachmentEditRepoPath, isCurrentRevisionPath } from '../util/attachment-repo.util';
import { reindexStartCoordIndices } from '../util/coordinate-reindex.util';
import { forceAllDayBounds } from '../util/event-date-bounds.util';
import { applyAdjustment } from '../util/message-adjust.util';
import { messageContentSignature } from '../util/message-change.util';
import { mergeGeneratedPromulgation } from '../util/promulgation-merge.util';
import {
  changePartType,
  ensureParts,
  insertPartAfter,
  movePartTo as movePartToUtil,
  removePartAt,
  swapPart,
} from '../util/message-parts.util';
import {
  isMessageNumberEditable,
  isShortIdEditable,
  rehydrateMessageSeries,
  resolveDomainSeries,
  seriesPermitsType,
} from '../util/message-series-rules.util';
import { TemplateMessageStore } from './template-message-store';

export type EditorEditType = 'edit' | 'copy' | 'template';
export type EditorLoadStatus = 'idle' | 'loading' | 'loaded' | 'error';

/** Validation outcome of a save attempt. `errorKey` is a Transloco key for the abort toast. */
export interface SaveValidation {
  readonly ok: boolean;
  readonly errorKey?: string;
}

/** Trailing debounce (ms) for keystroke-driven adjust triggers (number / vicinity / subject). */
const ADJUST_DEBOUNCE_MS = 500;

// The relationship types a copy may carry to its original. The four lifecycle relations come from
// the editor's own copy actions; the plain `REFERENCE` is what the copy dialog's "include reference
// to the copied message" box sends, and the server adds it to the draft's references. Anything else
// on the route degrades to a plain copy, like an empty value.
const COPY_REFERENCE_TYPES: ReadonlySet<ReferenceType> = new Set<ReferenceType>([
  'CANCELLATION',
  'REPETITION',
  'REPETITION_NEW_TIME',
  'UPDATE',
  'REFERENCE',
]);

/**
 * Shift an ISO date-time string by whole days in `timeZone`, keeping the wall clock it
 * reads there — so "the same event tomorrow" survives a DST transition. Undefined and
 * unparseable values stay as they are.
 *
 * The zoned shift works to whole seconds, so the sub-second part is carried over by hand:
 * an all-day end bound is stamped at `.999`, and dropping it would move the copy a
 * millisecond back out of the day it is meant to close.
 */
function shiftIsoDays(iso: string | undefined, days: number, timeZone: string): string | undefined {
  if (!iso || days === 0) {
    return iso;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const shifted = shiftDaysInZone(date, days, timeZone);
  return new Date(shifted.getTime() + date.getMilliseconds()).toISOString();
}

/** Recursively freeze an object graph so a stored snapshot can't be mutated through a shared ref. */
function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object') {
    for (const key of Object.keys(value)) {
      deepFreeze((value as Record<string, unknown>)[key]);
    }
    Object.freeze(value);
  }
  return value;
}

/**
 * Shell-scoped state and lifecycle for the message editor. Provided in the editor route's
 * `providers` (NOT root) so one instance backs the edit/status/history/comments child routes
 * and is discarded when the user leaves the editor.
 *
 * It owns the editor state spine — the single working `message` signal and its lifecycle
 * invariants (the sticky `dirty` latch, `userTouched` and `hasUnsavedChanges`, `loadSeq`), plus
 * the five load/create/copy paths, the POST/PUT save roundtrip, and the part / adjust mutators.
 * The pure rules those mutators apply (the adjust merge, part-array algebra, series re-hydration)
 * live in `../util/*` and are delegated to here.
 */
@Injectable()
export class EditorFacade implements OnDestroy {
  private readonly auth = inject(AuthFacade);
  private readonly domain = inject(DomainFacade);
  private readonly router = inject(Router);
  private readonly api = inject(MessagesApiService);
  private readonly promulgationsApi = inject(PromulgationsApiService);
  private readonly notify = inject(NotificationService);
  private readonly templateStore = inject(TemplateMessageStore);
  private readonly language = inject(LanguageService);
  private readonly areasApi = inject(AreasApiService);
  private readonly chartsApi = inject(ChartsApiService);
  private readonly geometry = inject(MessageGeometryService);

  private readonly _message = signal<Message | undefined>(undefined);
  readonly message = this._message.asReadonly();

  // One-way "dirty" latch — once tripped it stays true until the next load or save. Born true on
  // copy/new/template. No control keys on it: the header, the guards, the template page's replace
  // confirm and the status buttons read `hasUnsavedChanges`, which an undone edit clears.
  private readonly _dirty = signal(false);
  readonly dirty = this._dirty.asReadonly();

  // Distinct from `dirty`: set ONLY on a genuine user edit, never by a born-dirty programmatic
  // load (copy / new / template). Half of `hasUnsavedChanges`: an edit counts only once touched.
  private readonly _userTouched = signal(false);
  readonly userTouched = this._userTouched.asReadonly();

  /**
   * The loaded content is not what the database holds: a born-dirty load (a copy of any reference
   * type, a blank new message, a template's result, or a template re-run on a saved message). Set
   * by `applyLoaded` from its `dirty` option; an ordinary load, a reload, the post-save reload and
   * `resetWorkingState` clear it. Undoing edits cannot clear it — only a save or a reload can.
   */
  private readonly _unstoredLoad = signal(false);

  // Double-submit lock for the save roundtrip.
  private readonly _saving = signal(false);
  readonly saving = this._saving.asReadonly();

  /**
   * Content-based change flag: the working copy's EDITABLE content differs from the loaded baseline,
   * comparing canonical signatures so the many non-user rewrites (adjust copy-back, the form sync's
   * `mergeDescs` padding, `''`/`undefined`/`false` normalisation, `indexNo` renumbering) never count.
   */
  readonly changed = computed<boolean>(() => {
    const working = this._message();
    const baseline = this._loadedBaseline();
    if (!working || !baseline) {
      return false;
    }
    return messageContentSignature(working) !== messageContentSignature(baseline);
  });

  /**
   * Whether the editor holds anything the database does not: either the user has touched the
   * working copy AND its content actually differs from the baseline, or the loaded message itself
   * is not stored (a copy, a blank new message, a template's result — `_unstoredLoad`).
   * Reverting an edit on a stored message clears this (unlike the sticky `dirty` latch); a message
   * never saved, or a template result not yet saved, stays unsaved until it is saved or reloaded.
   * Drives the header's save-status chip and Save button, the Clear and Reload confirms, the
   * discard guards and `beforeunload`, the template page's replace confirm, and the status
   * buttons (disabled while this holds).
   */
  readonly hasUnsavedChanges = computed<boolean>(
    () => (this._userTouched() && this.changed()) || this._unstoredLoad(),
  );

  // Immutable snapshot of the message as last loaded from the server (set on every load path and
  // re-set on the post-save reload). The edited-dot compare in `EditorFieldsService` diffs the
  // working message against this baseline — so it must be a deep copy the working copy can't mutate.
  private readonly _loadedBaseline = signal<Message | undefined>(undefined);
  readonly loadedBaseline = this._loadedBaseline.asReadonly();

  // Drives the loading / inline-error-with-retry UX so a failed load never dead-ends on a blank editor.
  private readonly _loadStatus = signal<EditorLoadStatus>('idle');
  readonly loadStatus = this._loadStatus.asReadonly();

  // Bumped on every fresh load/create/save so the edit view repopulates its form only then —
  // NOT on the per-field `patchMessage` writes the form itself makes (which would loop).
  private readonly _loadSeq = signal(0);
  readonly loadSeq = this._loadSeq.asReadonly();

  // The last load action, replayed by `retryLoad()` from the inline error panel.
  private retry: (() => void) | undefined;

  // The inline error panel's text (a Transloco key) and whether its Retry can help. A 403 and the
  // loads with nothing to replay (no template, a refused create) leave Retry off; the panel then
  // offers only the way back to the Start screen.
  private readonly _loadErrorPanel = signal('editor.load.errorPanel');
  readonly loadErrorPanel = this._loadErrorPanel.asReadonly();
  private readonly _canRetryLoad = signal(false);
  readonly canRetryLoad = this._canRetryLoad.asReadonly();

  // Monotonic adjust counter — bumped on every fired adjust AND on every load. An adjust response
  // applies its server fields ONLY if it is still the latest, so an out-of-order or superseded
  // response (rapid part edits, or an adjust racing a load) cannot revert newer state.
  private adjustSeq = 0;

  // Monotonic load counter — bumped before every async load/copy/create/reload. A response is
  // applied ONLY if its token is still the latest, so an out-of-order load (navigating to a
  // sibling message while the first fetch is in flight) cannot clobber the newer message.
  private loadToken = 0;

  readonly isEditor = this.auth.hasRole('editor');
  readonly isAdmin = this.auth.hasRole('admin');

  // A message is editable while DRAFT or VERIFIED, or always for an admin.
  readonly editable = computed<boolean>(() => {
    const status = this._message()?.status;
    return status === 'DRAFT' || status === 'VERIFIED' || this.isAdmin();
  });

  // The single "may mutate now" gate: an editor (not a plain user) acting on an editable message.
  // Every form-field disable, the part add/remove/move controls, and the Save affordance key off
  // this so they cannot diverge — a non-admin editor on a PUBLISHED/CANCELLED message is read-only,
  // while the admin override rides through `editable()`.
  readonly editableNow = computed<boolean>(() => this.editable() && this.isEditor());

  // Candidate message series for the dropdown: the current session domain's series, narrowed to
  // the active message's mainType when one is set. Null-guarded: missing domain/list yields [].
  readonly messageSeries = computed<readonly DomainMessageSeriesRef[]>(() => {
    const mainType = this._message()?.mainType;
    const all = this.domainSeriesList();
    return mainType ? all.filter((series) => series.mainType === mainType) : all;
  });

  // The message's currently-selected series (re-hydrated against the domain list on load) — the
  // source of the numbering config that decides whether the short-id is hand-editable.
  readonly selectedSeries = computed<MessageSeries | undefined>(
    () => this._message()?.messageSeries,
  );

  readonly numberSequenceType = computed<string | undefined>(
    () => this.selectedSeries()?.numberSequenceType,
  );

  // Two distinct, mutually-exclusive editability predicates: both require a MANUAL series, split on
  // whether it has a shortFormat.
  readonly shortIdEditable = computed<boolean>(() => isShortIdEditable(this.selectedSeries()));
  readonly messageNumberEditable = computed<boolean>(() =>
    isMessageNumberEditable(this.selectedSeries()),
  );

  /**
   * Whether `type` is permitted by the current session domain's series for the active message.
   * A type is valid if ANY matching series permits it (a permissive series is never clobbered by a
   * restrictive sibling).
   */
  isTypeValid(type: string): boolean {
    return this.messageSeries().some((series) => seriesPermitsType(series, type));
  }

  /** Series (for the active mainType) that permit `type` — used for the single-series auto-deduce. */
  seriesAllowingType(type: string): readonly DomainMessageSeriesRef[] {
    return this.messageSeries().filter((series) => seriesPermitsType(series, type));
  }

  /** Whether the current user may create a message of `mainType` in the active domain. */
  canCreate(mainType: MainType): boolean {
    return this.isEditor() && this.domain.supportsMainType(mainType)();
  }

  /** Resolve a series dropdown selection to a full series object off the active domain list. */
  resolveSeries(seriesId: string): MessageSeries | undefined {
    return resolveDomainSeries(seriesId, this.domainSeriesList());
  }

  /**
   * Resolve a series id off the active domain, falling back to the working message's existing
   * series object when the id matches but the series belongs to another domain. This preserves a
   * foreign (read-only) series through unrelated field edits instead of wiping it to `undefined`
   * (the active domain list never carries it, so a plain `resolveSeries` would return nothing and
   * the form-sync re-map would corrupt the save payload).
   */
  resolveSeriesForPatch(seriesId: string): MessageSeries | undefined {
    const resolved = this.resolveSeries(seriesId);
    if (resolved) {
      return resolved;
    }
    const existing = this._message()?.messageSeries;
    return existing?.seriesId === seriesId ? existing : undefined;
  }

  // ── Load / create / copy ─────────────────────────────────────────────────────

  /** Dispatch the load path for a routed editor child (the route's path segment is the discriminator). */
  initForRoute(editType: EditorEditType, id?: string, referenceType?: string): void {
    switch (editType) {
      case 'edit':
        this.loadExisting(id);
        break;
      case 'copy':
        this.loadCopy(id, referenceType);
        break;
      case 'template':
        this.loadFromTemplate();
        break;
    }
  }

  /**
   * Path A — load an existing message for editing (pristine). Idempotent: if the working copy is
   * already the requested (saved) message it is kept, so switching to a sibling tab or returning
   * after a save does not refetch and discard in-flight edits. A deep-link / different id fetches.
   */
  loadExisting(id: string | undefined): void {
    if (!id) {
      return;
    }
    const current = this._message();
    if (current?.id === id && current.created) {
      this._loadStatus.set('loaded');
      return;
    }
    this.retry = () => this.loadExisting(id);
    this._loadStatus.set('loading');
    const token = ++this.loadToken;
    // The backend answers an unknown id with 200 + null body, not an HTTP error — treat both as a
    // failed load so the inline error panel renders instead of hanging on the progress bar.
    this.api.editableDetails(id).subscribe({
      next: (message) =>
        this.acceptLoad(token, () =>
          message ? this.applyLoaded(message) : this.failLoad('editor.load.error.load'),
        ),
      error: (err: unknown) =>
        this.acceptLoad(token, () => {
          if (isForbidden(err)) {
            this.failForbidden('editor.load.error.load', 'editor.load.error.otherDomain');
            return;
          }
          this.failLoad('editor.load.error.load');
        }),
    });
  }

  /**
   * Path B — copy an existing message into a new born-dirty draft, optionally carrying a
   * reference relationship to the original. An empty / unrecognised reference type degrades to a
   * plain copy (the relationship param is simply omitted). Always a fresh copy on entry.
   */
  loadCopy(id: string | undefined, referenceType?: string): void {
    if (!id) {
      return;
    }
    const refType = this.validReferenceType(referenceType);
    this.retry = () => this.loadCopy(id, referenceType);
    this._loadStatus.set('loading');
    const token = ++this.loadToken;
    this.api.copyMessageTemplate(id, refType).subscribe({
      next: (message) =>
        this.acceptLoad(token, () =>
          message
            ? this.applyLoaded(message, { dirty: true })
            : this.failLoad('editor.load.error.copy'),
        ),
      error: (err: unknown) =>
        this.acceptLoad(token, () => {
          if (isForbidden(err)) {
            this.failForbidden('editor.load.error.copy', 'editor.load.error.copyOtherDomain');
            return;
          }
          this.failLoad('editor.load.error.copy');
        }),
    });
  }

  /** Path C — create from the transient template store (born dirty). Guards a missing template. */
  loadFromTemplate(): void {
    const template = this.templateStore.consume();
    if (!template?.mainType) {
      this.retry = undefined;
      this.failLoad('editor.load.error.noTemplate');
      return;
    }
    this.createMessage(template.mainType, template);
  }

  /**
   * Paths C/D — seed a blank message for `mainType` from the server template, then (Path C) overlay
   * the supplied template's keys. The overlay runs AFTER the server template so the template wins
   * for mainType / parts / editorFields. Surfaces a refused or failed create as an inline error
   * rather than aborting silently.
   */
  createMessage(mainType: MainType, template?: Message): void {
    if (!this.canCreate(mainType)) {
      this.retry = undefined;
      this.failLoad('editor.load.error.cannotCreate');
      return;
    }
    this.retry = () => this.createMessage(mainType, template);
    this._loadStatus.set('loading');
    const token = ++this.loadToken;
    this.api.newMessageTemplate(mainType).subscribe({
      next: (base) =>
        this.acceptLoad(token, () =>
          base
            ? // The Start screen's seed carries `id: ''` only to satisfy the required Message.id
              // type; letting that blank win would POST an empty id, so it takes the server
              // template's freshly-minted UUID. A template that has an id of its own (the result of
              // Run template > Execute, or a draft re-seeded by its Cancel) keeps it: its repoPath
              // and the attachment and image paths are derived from that id, and the create POST
              // refuses an attachment outside the folder of the id it sends.
              this.applyLoaded(
                template ? { ...base, ...template, id: template.id || base.id } : base,
                { dirty: true },
              )
            : this.failLoad('editor.load.error.create'),
        ),
      error: () => this.acceptLoad(token, () => this.failLoad('editor.load.error.create')),
    });
  }

  /** Re-entry hook for the deferred category-template execution (born dirty). */
  templateExecuted(message: Message): void {
    this.applyLoaded(message, { dirty: true });
  }

  /** Replay the last failed load (the inline error panel's Retry button). */
  retryLoad(): void {
    this.retry?.();
  }

  /** Force a fresh resolve of the current message from the backend, discarding the working copy. */
  reload(): void {
    const id = this._message()?.id;
    if (!id) {
      return;
    }
    const token = ++this.loadToken;
    this.api.editableDetails(id).subscribe({
      next: (message) =>
        this.acceptLoad(token, () =>
          message ? this.applyLoaded(message) : this.notify.error('editor.load.error.load'),
        ),
      // A failed post-mutation refresh leaves the (server-committed) prior state on screen rather
      // than blanking the editor — surface a toast so the stale view is diagnosable.
      error: () => this.acceptLoad(token, () => this.notify.error('editor.load.error.load')),
    });
  }

  /**
   * Show the inline error panel with `panelKey` and toast `errorKey`. Retry is offered only when
   * there is a load to replay.
   */
  private failLoad(errorKey: string, panelKey = 'editor.load.errorPanel'): void {
    this._loadErrorPanel.set(panelKey);
    this._canRetryLoad.set(this.retry !== undefined);
    this._loadStatus.set('error');
    this.notify.error(errorKey);
  }

  /**
   * A 403 on a load or a copy: the message is outside the session domain, or the user may not open
   * it. Retry would only get the same answer, so the panel names the cause and offers no Retry; it
   * stays on the editor address, so choosing the message's domain in the header (which reloads the
   * application at the same address) then opens it. A domain switch away from an open message
   * lands here too: the reload makes a first load just as a followed link does. The working state
   * is dropped, so the way back to Start is not challenged by an unsaved-changes prompt.
   */
  private failForbidden(errorKey: string, panelKey: string): void {
    this.resetWorkingState();
    this.failLoad(errorKey, panelKey);
  }

  /**
   * Leave the message behind and go back to the editor's start screen (the error panel's button
   * when Retry cannot help). The working state is dropped first, which also clears `dirty` so the
   * unsaved-changes guard doesn't challenge the way out.
   */
  leaveToStart(): void {
    this.resetWorkingState();
    void this.router.navigate(['/editor']);
  }

  /** Run a load-completion callback only if its load is still the latest (drops superseded races). */
  private acceptLoad(token: number, apply: () => void): void {
    if (token === this.loadToken) {
      apply();
    }
  }

  // ── Mutation ─────────────────────────────────────────────────────────────────

  /**
   * Apply a freshly loaded or created message. Re-hydrates a pruned series against the domain and
   * guarantees the ≥1-part invariant; always resets `userTouched`; `dirty` and the not-stored flag
   * follow the load kind — `false` for edit-existing, reload and the post-save reload, `true` for
   * the born-dirty copy/new/template paths.
   */
  applyLoaded(message: Message, options?: { dirty?: boolean }): void {
    const rehydrated = rehydrateMessageSeries(message, this.domainSeriesList());
    // Reindex on every load so the message-wide `startCoordIndex` values are trustworthy from
    // first render, not only after the first geometry commit.
    const loaded: Message = {
      ...rehydrated,
      parts: this.reindexParts(ensureParts(rehydrated.parts)),
    };
    this._message.set(loaded);
    this._loadedBaseline.set(deepFreeze(structuredClone(loaded)));
    this._dirty.set(options?.dirty ?? false);
    this._unstoredLoad.set(options?.dirty ?? false);
    this._userTouched.set(false);
    this._loadStatus.set('loaded');
    this._loadSeq.update((seq) => seq + 1);
    // Invalidate any in-flight adjust so its (now stale) response can't overwrite the loaded state,
    // and drop any pending debounced adjust queued against the message we just replaced.
    this.adjustSeq++;
    this.cancelDebouncedAdjust();
    // Backstop for the one-shot guard bypass: an allow-listed navigation has reached its
    // destination, so drop any bypass a guard-LESS source route (status/history/comments) never
    // consumed — otherwise it would leak and skip a genuine unsaved-changes prompt on the next hop.
    this.guardBypass = false;
  }

  /**
   * Drop the working state entirely. The editor route's `providers` injector outlives its
   * activation (Angular scopes it to the route CONFIG, not the visit), so this facade instance
   * SURVIVES leaving the editor — without an explicit reset, a discarded working copy would
   * resurface on the next entry via the `loadExisting` same-id dedupe. The editor shell calls
   * this from `ngOnDestroy`; downstream session state (field expand/opt-ins) resets by keying
   * on the cleared baseline.
   */
  resetWorkingState(): void {
    this.loadToken++;
    this.adjustSeq++;
    this.cancelDebouncedAdjust();
    this._message.set(undefined);
    this._loadedBaseline.set(undefined);
    this._dirty.set(false);
    this._unstoredLoad.set(false);
    this._userTouched.set(false);
    this._saving.set(false);
    this._loadStatus.set('idle');
    this.retry = undefined;
    this.guardBypass = false;
  }

  /** Programmatic dirty — trips the `dirty` latch without marking a user edit. */
  setDirty(): void {
    this._dirty.set(true);
  }

  /** A genuine user edit — trips both the `dirty` latch and `userTouched`. */
  markUserTouched(): void {
    this._userTouched.set(true);
    this._dirty.set(true);
  }

  /** Merge form-driven field changes into the working message (callers mark touched separately). */
  patchMessage(patch: Partial<Message>): void {
    const message = this._message();
    if (!message) {
      return;
    }
    this._message.set({ ...message, ...patch });
  }

  // ── Message parts ────────────────────────────────────────────────────────────
  // Thin, editor-gated wrappers over the pure part-array algebra; the ≥1-part invariant and
  // re-indexing live in `../util/message-parts.util`. Every structural change re-derives server
  // fields via `adjust` and trips the latches.

  addPart(index: number): void {
    const message = this.editablePartsTarget();
    if (message) {
      this.commitParts(message, insertPartAfter(message.parts ?? [], index));
    }
  }

  removePart(index: number): void {
    const message = this.editablePartsTarget();
    if (message) {
      this.commitPartsIfChanged(message, removePartAt(message.parts ?? [], index));
    }
  }

  movePart(index: number, direction: 'up' | 'down'): void {
    const message = this.editablePartsTarget();
    if (message) {
      this.commitPartsIfChanged(message, swapPart(message.parts ?? [], index, direction));
    }
  }

  movePartTo(from: number, to: number): void {
    const message = this.editablePartsTarget();
    if (message) {
      this.commitPartsIfChanged(message, movePartToUtil(message.parts ?? [], from, to));
    }
  }

  setPartType(index: number, type: string): void {
    const message = this.editablePartsTarget();
    if (message) {
      this.commitPartsIfChanged(message, changePartType(message.parts ?? [], index, type));
    }
  }

  /** The working message when structural mutation is allowed (editable + editor), else undefined. */
  private editablePartsTarget(): Message | undefined {
    return this.editableTarget();
  }

  /** The working message when mutation is allowed (editable + editor), else undefined. */
  private editableTarget(): Message | undefined {
    return this.editableNow() ? this._message() : undefined;
  }

  private commitPartsIfChanged(message: Message, parts: MessagePart[] | null): void {
    if (parts) {
      this.commitParts(message, parts);
    }
  }

  private commitParts(message: Message, parts: MessagePart[]): void {
    this._message.set({ ...message, parts: this.reindexParts(parts) });
    this.markUserTouched();
    this.fireAdjust();
  }

  /**
   * Re-thread the message-wide `startCoordIndex` after any structural or geometry commit,
   * delegating each feature's coordinate count to the geometry serializer so the skip rules for
   * buffered / affected features stay in one place.
   */
  private reindexParts(parts: readonly MessagePart[]): MessagePart[] {
    return reindexStartCoordIndices(
      parts,
      (feature) => this.geometry.serializeReadableCoordinates(feature).length,
    );
  }

  /** Re-derive server-computed fields after a structural edit (fire-and-forget). */
  private fireAdjust(): void {
    // The working copy is never replaced on failure (applyAdjustment runs only on a fresh response),
    // so a failed adjust is non-corrupting — log it rather than nag the user mid-edit.
    this.runAdjust();
  }

  /** Fire an adjust round-trip, logging (not surfacing) a failure — the working copy is never lost. */
  private runAdjust(options?: { inclPromulgations?: boolean }): void {
    this.adjust(options).subscribe({
      error: (err: unknown) => console.error('Failed to re-derive message fields (adjust)', err),
    });
  }

  // ── Message references ─────────────────────────────────────────────────────────
  // Reference rows are structural state (add / delete / reorder / retype / autocomplete /
  // per-language description) mutated through these cohesive facade methods — never a second
  // form-model write path. Every mutation commits immutably and trips both latches; none adjust
  // (references never feed the auto-generated title/source).

  /**
   * Append a fresh reference (no target yet, plain `REFERENCE` relation, one empty description per
   * model language) and return its index so the caller can focus the new row. Returns -1 when
   * mutation is not allowed. The wire seeds the target `messageId` as an empty string (wire-
   * equivalent to undefined, and keeps the model's `messageId: string`).
   */
  addReference(): number {
    const message = this.editableTarget();
    if (!message) {
      return -1;
    }
    const reference: MessageReference = {
      messageId: '',
      type: 'REFERENCE',
      descs: this.modelLanguages().map((lang) => ({ lang, description: undefined })),
    };
    const references = [...(message.references ?? []), reference];
    this._message.set({ ...message, references });
    this.markUserTouched();
    return references.length - 1;
  }

  /** Remove the reference at `index`. */
  removeReference(index: number): void {
    this.commitReferences((references) => {
      if (index < 0 || index >= references.length) {
        return null;
      }
      const next = [...references];
      next.splice(index, 1);
      return next;
    });
  }

  /** Move the reference from `from` to `to` (drag-reorder parity). */
  moveReference(from: number, to: number): void {
    this.commitReferences((references) => {
      if (
        from < 0 ||
        from >= references.length ||
        to < 0 ||
        to >= references.length ||
        from === to
      ) {
        return null;
      }
      const next = [...references];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      return next;
    });
  }

  /** Patch a reference's scalar fields (autocomplete target `messageId` and/or relation `type`). */
  setReferenceField(index: number, patch: { messageId?: string; type?: ReferenceType }): void {
    this.commitReferences((references) =>
      this.mapReferenceAt(references, index, (reference) => ({ ...reference, ...patch })),
    );
  }

  /** Set (or clear) a reference's per-language description. */
  setReferenceDesc(index: number, lang: string, description: string | undefined): void {
    this.commitReferences((references) =>
      this.mapReferenceAt(references, index, (reference) => ({
        ...reference,
        descs: this.upsertReferenceDesc(reference.descs, lang, description),
      })),
    );
  }

  private mapReferenceAt(
    references: readonly MessageReference[],
    index: number,
    updater: (reference: MessageReference) => MessageReference,
  ): MessageReference[] | null {
    if (index < 0 || index >= references.length) {
      return null;
    }
    return references.map((reference, i) => (i === index ? updater(reference) : reference));
  }

  private upsertReferenceDesc(
    descs: MessageReference['descs'],
    lang: string,
    description: string | undefined,
  ): MessageReference['descs'] {
    const existing = descs ?? [];
    return existing.some((desc) => desc.lang === lang)
      ? existing.map((desc) => (desc.lang === lang ? { ...desc, description } : desc))
      : [...existing, { lang, description }];
  }

  /** Apply an immutable reference-array update (a null result = no-op) and trip the latches. */
  private commitReferences(
    update: (references: readonly MessageReference[]) => MessageReference[] | null,
  ): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const next = update(message.references ?? []);
    if (!next) {
      return;
    }
    this._message.set({ ...message, references: next });
    this.markUserTouched();
  }

  // ── Message-part value edits ─────────────────────────────────────────────────────
  // Per-part subject / hide-subject / event-date edits. Value edits mark dirty; none adjust here —
  // the subject-field's debounced adjust is driven by the caller's blur hook (adjust-trigger list).

  /** Set the DETAILS-part subject for one language. */
  setPartSubject(index: number, lang: string, value: string): void {
    this.commitPart(index, (part) => ({
      ...part,
      descs: this.upsertPartDesc(part.descs, lang, { subject: value }),
    }));
  }

  /**
   * Set the part details (body) for one language. A body edit marks dirty but NEVER adjusts —
   * details do not feed the server-derived title/source (only type/series/areas/number/vicinity/
   * subject drive re-derivation). Routing through `commitPart` structurally guarantees the no-adjust
   * cadence; callers must not queue an adjust for a body edit.
   */
  setPartDetails(index: number, lang: string, details: string): void {
    this.commitPart(index, (part) => ({
      ...part,
      descs: this.upsertPartDesc(part.descs, lang, { details }),
    }));
  }

  /** Toggle the DETAILS-part "hide subject" flag. */
  setPartHideSubject(index: number, hideSubject: boolean): void {
    this.commitPart(index, (part) => ({ ...part, hideSubject }));
  }

  /** Append a new event-date interval to the part (all-day defaults true for NM). */
  addPartEventDate(index: number): void {
    const allDay = this._message()?.mainType === 'NM';
    this.commitPart(index, (part) => ({
      ...part,
      eventDates: [...(part.eventDates ?? []), { allDay, fromDate: undefined, toDate: undefined }],
    }));
  }

  /** Remove the event-date interval at `dateIndex` from the part. */
  removePartEventDate(index: number, dateIndex: number): void {
    this.commitPart(index, (part) => {
      const dates = part.eventDates ?? [];
      if (dateIndex < 0 || dateIndex >= dates.length) {
        return part;
      }
      const next = [...dates];
      next.splice(dateIndex, 1);
      return { ...part, eventDates: next };
    });
  }

  /**
   * Duplicate the event-date interval at `dateIndex`, optionally shifting both ends by `plusDays`.
   *
   * A day shift means "the same wall clock a day later" for the domain, so it needs the domain's
   * zone; without one the shift is refused rather than computed in the browser's zone.
   */
  duplicatePartEventDate(index: number, dateIndex: number, plusDays = 0): void {
    const zone = this.sessionTimeZone();
    if (plusDays !== 0 && !zone) {
      this.notify.error('common.dateTime.noTimeZone');
      return;
    }
    this.commitPart(index, (part) => {
      const dates = part.eventDates ?? [];
      const source = dates[dateIndex];
      if (!source) {
        return part;
      }
      const copy: DateInterval = {
        allDay: source.allDay,
        fromDate: zone ? shiftIsoDays(source.fromDate, plusDays, zone) : source.fromDate,
        toDate: zone ? shiftIsoDays(source.toDate, plusDays, zone) : source.toDate,
      };
      return { ...part, eventDates: [...dates, copy] };
    });
  }

  /**
   * Patch an event-date interval. An all-day interval is snapped to the whole-day bounds of the
   * domain's zone (00:00:00.000 / 23:59:59.999), so the range spans exactly the day the reader of
   * the message is on, whatever time of day the endpoints carried.
   *
   * With no domain — and so no zone — the endpoints are left untouched and the user is told: a
   * day snapped to the editing browser's midnight would be the wrong day for the domain.
   */
  setPartEventDate(
    index: number,
    dateIndex: number,
    patch: { allDay?: boolean; fromDate?: string; toDate?: string },
  ): void {
    const zone = this.sessionTimeZone();
    const current = this._message()?.parts?.[index]?.eventDates?.[dateIndex];
    if (current && (patch.allDay ?? current.allDay) && !zone) {
      this.notify.error('common.dateTime.noTimeZone');
    }
    this.commitPart(index, (part) => {
      const dates = part.eventDates ?? [];
      const interval = dates[dateIndex];
      if (!interval) {
        return part;
      }
      const merged: DateInterval = { ...interval, ...patch };
      const normalized = merged.allDay && zone ? forceAllDayBounds(merged, zone) : merged;
      const next = dates.map((entry, i) => (i === dateIndex ? normalized : entry));
      return { ...part, eventDates: next };
    });
  }

  /** The zone the domain reads its dates in — the one every stored instant is reasoned about in. */
  private sessionTimeZone(): string | undefined {
    return this.domain.sessionDomain()?.timeZone;
  }

  private upsertPartDesc(
    descs: MessagePart['descs'],
    lang: string,
    patch: Partial<{ subject: string; details: string }>,
  ): MessagePart['descs'] {
    const existing = descs ?? [];
    return existing.some((desc) => desc.lang === lang)
      ? existing.map((desc) => (desc.lang === lang ? { ...desc, ...patch } : desc))
      : [...existing, { lang, ...patch }];
  }

  /** Apply an immutable single-part update (no-op if `index` is out of range) and trip the latches. */
  private commitPart(index: number, updater: (part: MessagePart) => MessagePart): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const parts = message.parts ?? [];
    if (index < 0 || index >= parts.length) {
      return;
    }
    const next = parts.map((part, i) => (i === index ? updater(part) : part));
    this._message.set({ ...message, parts: next });
    this.markUserTouched();
  }

  // ── Message-level publication text ───────────────────────────────────────────
  // The External / Internal publication bodies are per-language message `descs` content that is NOT
  // part of the form value model, so they flow through this cohesive mutator rather than a second
  // form write path. An immutable descs rebuild that marks dirty but NEVER adjusts (publication text
  // does not feed the server-derived title/source). Sparse descs are seeded — a language without an
  // entry yet gets one — so a fresh message never index-crashes. The server-owned `publication=<id>`
  // markup round-trips untouched: it lives inside the HTML string this only replaces wholesale.

  /** Set (or clear) a language's External (`publication`) / Internal (`internalPublication`) body. */
  setMessageDesc(
    lang: string,
    patch: Partial<Pick<MessageDescription, 'publication' | 'internalPublication'>>,
  ): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    this._message.set({ ...message, descs: this.upsertMessageDesc(message.descs, lang, patch) });
    this.markUserTouched();
  }

  private upsertMessageDesc(
    descs: readonly MessageDescription[] | undefined,
    lang: string,
    patch: Partial<MessageDescription>,
  ): MessageDescription[] {
    const existing = descs ?? [];
    return existing.some((desc) => desc.lang === lang)
      ? existing.map((desc) => (desc.lang === lang ? { ...desc, ...patch } : desc))
      : [...existing, { lang, ...patch }];
  }

  /**
   * Copy the server-re-rendered `publication` + `internalPublication` HTML from an
   * update-message-publications response into the working copy, per language present in the
   * response (the server owns that markup — it is never regenerated client-side), rebuilding the
   * descs immutably rather than mutating them in place.
   */
  applyPublicationsUpdate(updated: Message): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    let descs = message.descs;
    for (const desc of updated.descs ?? []) {
      if (!desc.lang) {
        continue;
      }
      // Upsert (create-or-update): a just-created blank message has no descs yet, so the returned
      // publication HTML must be able to CREATE the language's desc — not only patch an existing one.
      // A patch-only path would silently drop the returned HTML because the descs are not pre-seeded.
      descs = this.upsertMessageDesc(descs, desc.lang, {
        publication: desc.publication,
        internalPublication: desc.internalPublication,
      });
    }
    this._message.set({ ...message, descs });
    this.markUserTouched();
  }

  // ── Attachments / thumbnail ──────────────────────────────────────────────────
  // File surfaces are facade-driven (not form controls); the physical repo-file side effects live
  // here, gated by the current-revision guard — a file from an earlier saved revision is only
  // dropped from the model so revision history stays intact. None of these adjust; none of them
  // affect server-derived fields.

  /** Append freshly uploaded attachment VOs (the uploader's parsed response). Marks touched. */
  addAttachments(attachments: readonly MessageAttachment[]): void {
    const message = this.editableTarget();
    if (!message || attachments.length === 0) {
      return;
    }
    this._message.set({
      ...message,
      attachments: [...(message.attachments ?? []), ...attachments],
    });
    this.markUserTouched();
  }

  /**
   * Merge a patch into one attachment (caption descs / display / width / height). Selecting the
   * "don't display" option (`display === ''`) also deletes width/height — all three keys are
   * removed together so a hidden attachment carries no orphaned dimensions.
   */
  updateAttachment(index: number, patch: Partial<MessageAttachment>): void {
    const message = this.editableTarget();
    const attachments = message?.attachments ?? [];
    if (!message || index < 0 || index >= attachments.length) {
      return;
    }
    const next = attachments.map((attachment, i) => {
      if (i !== index) {
        return attachment;
      }
      if (patch.display === '') {
        const cleared = { ...attachment, ...patch } as Record<string, unknown>;
        delete cleared['display'];
        delete cleared['width'];
        delete cleared['height'];
        return cleared as MessageAttachment;
      }
      return { ...attachment, ...patch };
    });
    this._message.set({ ...message, attachments: next });
    this.markUserTouched();
  }

  /**
   * Move the attachment from `from` to `to` (the table's drag and arrow-key reorder; the backend
   * keeps the list order, which the public site and the PDF show). Out-of-range and no-op moves
   * change nothing. Marks touched.
   */
  moveAttachment(from: number, to: number): void {
    const message = this.editableTarget();
    const attachments = message?.attachments ?? [];
    if (
      !message ||
      from < 0 ||
      from >= attachments.length ||
      to < 0 ||
      to >= attachments.length ||
      from === to
    ) {
      return;
    }
    const next = [...attachments];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    this._message.set({ ...message, attachments: next });
    this.markUserTouched();
  }

  /**
   * Drop an attachment from the model; ADDITIONALLY delete the physical repo file when (and only
   * when) its path is under the current unsaved revision — the delete target is the re-built
   * `editRepoPath/revision/fileName` path.
   */
  removeAttachment(index: number): void {
    const message = this.editableTarget();
    const attachments = message?.attachments ?? [];
    const attachment = attachments[index];
    if (!message || !attachment) {
      return;
    }
    this._message.set({
      ...message,
      attachments: attachments.filter((_, i) => i !== index),
    });
    this.markUserTouched();
    if (isCurrentRevisionPath(message, attachment.path)) {
      this.api.deleteRepoFile(attachmentEditRepoPath(message, attachment)).subscribe({
        error: () => this.notify.error('editor.attachments.deleteError'),
      });
    }
  }

  /** Point the message at a freshly uploaded thumbnail (the map-image endpoint's response path). */
  setThumbnail(path: string): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    this._message.set({ ...message, thumbnailPath: path });
    this.markUserTouched();
  }

  /** Unset the thumbnail; physically delete the file only when it belongs to the current revision. */
  clearThumbnail(): void {
    const message = this.editableTarget();
    const thumbnailPath = message?.thumbnailPath;
    if (!message || !thumbnailPath) {
      return;
    }
    this._message.set({ ...message, thumbnailPath: undefined });
    this.markUserTouched();
    if (isCurrentRevisionPath(message, thumbnailPath)) {
      this.api.deleteRepoFile(thumbnailPath).subscribe({
        error: () => this.notify.error('editor.layout.thumbnailDeleteError'),
      });
    }
  }

  // ── Message areas / categories ───────────────────────────────────────────────
  // Area and category membership is structural (not in the form value model), so each flows through
  // a cohesive mutator rather than a second form write path. Both replace the whole list and trip
  // the latches. Areas re-derive server fields IMMEDIATELY (the area drives the auto-generated
  // title); categories only mark dirty (no adjust).

  /** Replace the message's area list (picker selection). Marks dirty and adjusts immediately. */
  setAreas(areas: readonly MessageArea[]): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    this._message.set({ ...message, areas: [...areas] });
    this.markUserTouched();
    this.runAdjust();
  }

  /** Replace the message's category list (picker selection). Marks dirty; no adjust. */
  setCategories(categories: readonly CategoryRef[]): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const next: Message = { ...message, categories: [...categories] };
    this._message.set(next);
    this.markUserTouched();
  }

  // ── Message charts ───────────────────────────────────────────────────────────
  // Chart membership is structural (not in the form value model), so it flows through this
  // mutator rather than a second form write path. Replaces the whole list — the picker selection
  // or the client-side scale sort. Marks dirty; never adjusts (charts never feed the auto-generated
  // title/source, so both the scale sort and the chart-field just mark dirty).

  /** Replace the message's chart list (picker selection or scale sort). Marks dirty; no adjust. */
  setCharts(charts: readonly MessageChart[]): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    this._message.set({ ...message, charts: [...charts] });
    this.markUserTouched();
  }

  // ── Promulgations ────────────────────────────────────────────────────────────
  // Per-channel promulgation edits are structural state on the working message (a polymorphic
  // array), mutated through these cohesive mutators rather than a form write path. Each edit patches
  // a single entry immutably and marks dirty; NONE adjust — only the type-select path re-derives
  // the promulgation array. Enabling a channel while the message type is unset
  // auto-deduces the type and routes through the SAME type-select adjust cadence.

  /**
   * Immutably patch the promulgation entry matched by `typeId` (no adjust). The patch carries
   * channel-specific fields (`text` / `tweet` / `transmitters` / …) beyond the `BasePromulgation`
   * contract, so it is widened with an index signature (the array is polymorphic).
   */
  patchPromulgation(
    typeId: string,
    partial: Partial<BasePromulgation> & Record<string, unknown>,
  ): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const promulgations = message.promulgations ?? [];
    const index = promulgations.findIndex((entry) => entry.type.typeId === typeId);
    if (index < 0) {
      return;
    }
    const next = promulgations.map((entry, i) => (i === index ? { ...entry, ...partial } : entry));
    this._message.set({ ...message, promulgations: next });
    this.markUserTouched();
  }

  /**
   * Toggle a channel's `promulgate` flag, enforcing the MANDATORY asymmetry: a MANDATORY channel
   * that is already promulgating can be checked on but never off. Enabling a channel while
   * `message.type` is unset auto-deduces the type when its promulgation type maps to exactly one
   * message type, routing through the same type-select cadence that fires
   * `adjust({ inclPromulgations: true })` — never a second adjust path.
   */
  setPromulgate(typeId: string, promulgate: boolean): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const entry = (message.promulgations ?? []).find((p) => p.type.typeId === typeId);
    if (!entry) {
      return;
    }
    // Refuse to uncheck a mandatory channel that is currently promulgating (double-enforced even if
    // a broken UI asks — the checkbox is disabled in that state).
    if (!promulgate && entry.type.requirement === 'MANDATORY' && entry.promulgate) {
      return;
    }
    this.patchPromulgation(typeId, { promulgate });
    if (promulgate && !message.type) {
      this.promulgationsApi.getPromulgationType(typeId).subscribe((promulgationType) => {
        const messageTypes = promulgationType?.messageTypes;
        if (messageTypes?.length === 1) {
          this.applyTypeSelection(messageTypes[0] as MessageType);
        }
      });
    }
  }

  /**
   * Apply a `generate/{typeId}` response into the matched entry via a selective immutable deep-merge
   * by `typeId`: present fields overwrite recursively, arrays wholesale, absent fields survive,
   * `@class` + `type.typeId` always survive, and sibling entries keep their (possibly unsaved-edited)
   * references untouched. No adjust — a generate never re-derives the array.
   */
  applyGeneratedPromulgation(typeId: string, generated: BasePromulgation): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const promulgations = message.promulgations ?? [];
    const index = promulgations.findIndex((entry) => entry.type.typeId === typeId);
    if (index < 0) {
      return;
    }
    const next = promulgations.map((entry, i) =>
      i === index ? mergeGeneratedPromulgation(entry, generated) : entry,
    );
    this._message.set({ ...message, promulgations: next });
    this.markUserTouched();
  }

  /**
   * Select `type` on the working message and auto-deduce the sole permitting series, then re-derive
   * the promulgation array via `adjust({ inclPromulgations: true })`. The facade embodiment of the
   * edit-form type-select cadence — reused (not duplicated) by the promulgation auto-deduce.
   */
  private applyTypeSelection(type: MessageType): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const candidates = this.seriesAllowingType(type);
    const messageSeries =
      candidates.length === 1
        ? (this.resolveSeries(candidates[0].seriesId) ?? message.messageSeries)
        : message.messageSeries;
    this._message.set({ ...message, type, messageSeries });
    this.markUserTouched();
    this.runAdjust({ inclPromulgations: true });
  }

  // ── Message geometry ─────────────────────────────────────────────────────────
  // Geometry lives on each part. A commit rebuilds the part immutably, re-threads the message-wide
  // coordinate numbering, and marks dirty — but does NOT adjust: geometry never feeds the
  // auto-generated title/source, unlike an area change.

  /**
   * Replace one part's geometry (the geometry editor's commit-on-OK, or copied-in area locations).
   * Marks dirty + re-threads `startCoordIndex` across the whole message; NO adjust round-trip.
   * A no-op when mutation is not allowed or the index is out of range.
   */
  setPartGeometry(partIndex: number, geometry: MessageGeometryFeatureCollection): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const parts = message.parts ?? [];
    if (partIndex < 0 || partIndex >= parts.length) {
      return;
    }
    const next = parts.map((part, i) => (i === partIndex ? { ...part, geometry } : part));
    this._message.set({ ...message, parts: this.reindexParts(next) });
    this.markUserTouched();
  }

  // ── Compute from locations ───────────────────────────────────────────────────
  // Three geometry-driven derivations (compute areas / compute charts / copy area locations).
  // Each fetches on demand and mutates only in its success callback, so a failure toasts and leaves
  // the working copy untouched. Areas re-derive server fields (adjust); charts and copied locations
  // do not — chart membership and geometry never feed the auto-generated title.

  /**
   * Replace the areas with those intersecting the message geometry. Excludes
   * `affected`-restriction features from the POSTed geometry; the computed selection adjusts + dirties
   * via {@link setAreas}. A no-op when no part defines geometry.
   */
  computeAreas(): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const features = this.geometry.getFeatures(message, { excludeAffectedAreas: true });
    if (features.length === 0) {
      return;
    }
    this.areasApi.intersectingAreas({ type: 'FeatureCollection', features }).subscribe({
      next: (areas) => this.setAreas(areas),
      error: () => this.notify.error('editor.areas.computeError'),
    });
  }

  /**
   * Replace the charts with those intersecting the message geometry.
   * Includes all features (affected too); the pruned selection dirties WITHOUT adjusting via
   * {@link setCharts}. A no-op when no part defines geometry.
   */
  computeCharts(): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const features = this.geometry.getFeatures(message);
    if (features.length === 0) {
      return;
    }
    this.chartsApi.intersectingCharts({ type: 'FeatureCollection', features }).subscribe({
      next: (charts) => this.setCharts(charts),
      error: () => this.notify.error('editor.charts.computeError'),
    });
  }

  /**
   * Append the selected areas' geometry to the first part. Fetches each
   * area with geometry in every language, flattens `descs` into `name:<lang>` feature properties, and
   * commits through {@link setPartGeometry} (dirty + reindex, no adjust). A no-op when no area is
   * selected.
   */
  copyAreaLocations(): void {
    const message = this.editableTarget();
    if (!message) {
      return;
    }
    const ids = (message.areas ?? []).map((area) => area.id);
    if (ids.length === 0) {
      return;
    }
    this.areasApi.getByIds(ids, { allLanguages: true, includeGeometry: true }).subscribe({
      next: (areas) => this.appendAreaLocationsToFirstPart(areas),
      error: () => this.notify.error('editor.areas.copyLocationsError'),
    });
  }

  /** Push the fetched areas' features onto `parts[0].geometry` via the geometry-commit path. */
  private appendAreaLocationsToFirstPart(areas: readonly AreaRef[]): void {
    const message = this.editableTarget();
    const firstPart = message?.parts?.[0];
    if (!message || !firstPart) {
      return;
    }
    const added = areas.flatMap((area) => this.areasApi.areaToFeatureCollection(area).features);
    if (added.length === 0) {
      return;
    }
    const existing = firstPart.geometry?.features ?? [];
    this.setPartGeometry(0, { type: 'FeatureCollection', features: [...existing, ...added] });
  }

  // ── Adjust round-trip ────────────────────────────────────────────────────────

  /**
   * Re-derive server-computed fields (title/source/shortId/promulgations) for the working message.
   * Fired on type-select and part add/remove/move. The merge (`applyAdjustment`) is selective —
   * never blindly replacing the user's working copy.
   */
  adjust(options?: { inclPromulgations?: boolean }): Observable<Message> {
    const message = this._message();
    if (!message) {
      return EMPTY;
    }
    const seq = ++this.adjustSeq;
    return this.api.adjustEditableMessage(message, options).pipe(
      tap((server) => {
        // Drop a stale/superseded response; otherwise merge into the CURRENT working copy (not the
        // snapshot captured at fire time) so edits made while the request was in flight survive.
        if (seq !== this.adjustSeq) {
          return;
        }
        const current = this._message();
        if (current) {
          this._message.set(applyAdjustment(current, server, options));
        }
      }),
    );
  }

  // ── Debounced adjust ─────────────────────────────────────────────────────────
  // Keystroke-driven triggers (number / vicinity / subject) coalesce into ONE trailing adjust so a
  // burst of edits fires a single round-trip; a blur `flushAdjust()` fires the pending call
  // immediately. `setTimeout` is zoneless-safe (no zone patching in play). The timer is cancelled on
  // every load and on destroy so a queued adjust can never land against a replaced/torn-down message.

  private adjustTimer: ReturnType<typeof setTimeout> | undefined;
  private pendingAdjustOptions: { inclPromulgations?: boolean } | undefined;

  /** Queue a trailing adjust (500 ms), collapsing repeated calls into the latest options. */
  adjustDebounced(options?: { inclPromulgations?: boolean }): void {
    this.pendingAdjustOptions = options;
    if (this.adjustTimer !== undefined) {
      clearTimeout(this.adjustTimer);
    }
    this.adjustTimer = setTimeout(() => {
      this.adjustTimer = undefined;
      const opts = this.pendingAdjustOptions;
      this.pendingAdjustOptions = undefined;
      this.runAdjust(opts);
    }, ADJUST_DEBOUNCE_MS);
  }

  /** Fire a pending debounced adjust now (blur hook); a no-op when nothing is queued. */
  flushAdjust(): void {
    if (this.adjustTimer === undefined) {
      return;
    }
    clearTimeout(this.adjustTimer);
    this.adjustTimer = undefined;
    const opts = this.pendingAdjustOptions;
    this.pendingAdjustOptions = undefined;
    this.runAdjust(opts);
  }

  /** Drop any queued debounced adjust without firing it (load / destroy). */
  private cancelDebouncedAdjust(): void {
    if (this.adjustTimer !== undefined) {
      clearTimeout(this.adjustTimer);
      this.adjustTimer = undefined;
    }
    this.pendingAdjustOptions = undefined;
  }

  ngOnDestroy(): void {
    this.cancelDebouncedAdjust();
  }

  // ── Save ─────────────────────────────────────────────────────────────────────

  /**
   * Synchronous pre-save validation: type+mainType required, series
   * required, and the publish interval must be ordered — but only when BOTH endpoints are set, so
   * a from-only interval is allowed.
   */
  validateForSave(): SaveValidation {
    const message = this._message();
    if (!message?.mainType || !message.type) {
      return { ok: false, errorKey: 'editor.validation.specifyType' };
    }
    if (!message.messageSeries) {
      return { ok: false, errorKey: 'editor.validation.specifySeries' };
    }
    const { publishDateFrom, publishDateTo } = message;
    if (publishDateFrom && publishDateTo && publishDateFrom > publishDateTo) {
      return { ok: false, errorKey: 'editor.validation.invalidInterval' };
    }
    return { ok: true };
  }

  /**
   * Persist the working message and reload the canonical post-save state. Dispatch is POST vs PUT
   * on `created` (handled by the adapter): the first save assigns the permanent id and moves the
   * temp edit repo to the permanent one server-side, so we re-resolve the saved id to pick up the
   * new revision / repo path. Resets to pristine on success. The double-submit lock spans the
   * whole roundtrip. Callers own validation, the VERIFIED confirm, navigation, and toasts.
   */
  save(): Observable<Message> {
    const message = this._message();
    if (!message) {
      return EMPTY;
    }
    this._saving.set(true);
    return this.api.saveMessage(message).pipe(
      switchMap((saved) => this.api.editableDetails(saved.id)),
      // A just-saved id resolving to a 200/null body means the reload leg failed — route it to
      // the error path (working copy kept, saving reset) rather than applying an empty message.
      map((reloaded) => {
        if (!reloaded) {
          throw new Error('post-save editable-message resolve returned an empty body');
        }
        return reloaded;
      }),
      tap((reloaded) => this.applyLoaded(reloaded)),
      finalize(() => this._saving.set(false)),
    );
  }

  // ── Navigation guard allow-list ──────────────────────────────────────────────
  // Programmatic copy/template/reload navigation (save→reload, clear, the status page's
  // cancel→copy re-entry) is allow-listed past the unsaved-changes guard one hop at a time.

  private guardBypass = false;

  bypassGuardOnce(): void {
    this.guardBypass = true;
  }

  consumeGuardBypass(): boolean {
    const bypass = this.guardBypass;
    this.guardBypass = false;
    return bypass;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private domainSeriesList(): readonly DomainMessageSeriesRef[] {
    return this.domain.sessionDomain()?.messageSeries ?? [];
  }

  /** The configured model languages — always N, never two. */
  private modelLanguages(): readonly string[] {
    return this.language.modelLanguages.map((option) => option.value);
  }

  private validReferenceType(referenceType?: string): ReferenceType | undefined {
    return referenceType && COPY_REFERENCE_TYPES.has(referenceType as ReferenceType)
      ? (referenceType as ReferenceType)
      : undefined;
  }
}

/**
 * Whether a failed load means "this message is out of reach here" rather than "something broke".
 * The backend answers 403 for a message outside the session domain (a followed link, a bookmark,
 * or a domain switch away from the open message), and also when there is no session domain or the
 * user lacks the role; the panel's text names both.
 */
function isForbidden(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 403;
}
