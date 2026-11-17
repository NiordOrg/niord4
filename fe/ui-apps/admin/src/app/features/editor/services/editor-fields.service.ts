import { computed, effect, inject, Injectable, linkedSignal, untracked } from '@angular/core';

import type { Message, MessageDescription, MessagePart } from '../../messages/models/message.model';
import {
  canonicalKey,
  normalizeDate,
  partContent,
  stripCoordIndex,
} from '../util/message-change.util';
import { EditorFacade } from './editor-facade';

/**
 * The four editor fields whose edit-state and edited-dot are addressed per message part (the row
 * appears once per part). Their `editMode` arrays hold one boolean per part and are spliced in
 * lock-step with the working message's parts on add / remove / move. Every other field is "flat"
 * and carries a single-element `[false]` array.
 */
const PART_INDEXED_FIELD_IDS = ['event_dates', 'positions', 'subject', 'description'] as const;

/**
 * Every opt-in-able editor field id in the order the edit form lays them out.
 * This is the STATIC FALLBACK UNIVERSE the "+ Add optional field" picker unions with the per-message
 * backend `editorFields` map: it supplies the opt-in candidates for keys the map omits, and pins the
 * picker's deterministic ordering. The always-shown core fields are intentionally absent — `type`,
 * `id`, `title`, `areas`, `categories` and `publish_date` render unconditionally, so they are never
 * opt-in-able. `promulgation` is not opt-in-able either (its visibility is server-computed per
 * message).
 */
const FIELD_UNIVERSE = [
  'orig_info',
  'references',
  'event_dates',
  'positions',
  'charts',
  'subject',
  'description',
  'attachments',
  'publication',
  'source',
  'prohibition',
  'signals',
  'layout',
] as const;

const FIELD_UNIVERSE_SET = new Set<string>(FIELD_UNIVERSE);

/**
 * Keys that must never surface in the "+ Add optional field" picker even when the backend
 * `editorFields` map carries them present-but-false. `note` is the case: NOTE is a message-part
 * *type* chosen via the part-type switcher (gated by `part_types`), not a standalone field — so a
 * "Note" opt-in would reveal no row (a phantom). The part-type NOTE button rides the `part_types`
 * gate instead — there is no standalone `note` field row.
 */
const NON_OPTIN_FIELD_IDS = new Set<string>(['note']);

/**
 * The core fields that render unconditionally ("always shown"). They are never opt-in-able,
 * so they are kept out of the "+ Add optional field" picker even when the backend `editorFields`
 * map carries them as present-but-false (which would otherwise leak them in via the extras path).
 */
const ALWAYS_SHOWN_FIELD_IDS = new Set<string>([
  'type',
  'id',
  'title',
  'areas',
  'categories',
  'publish_date',
]);

/**
 * The flat (non-part-indexed) fields seeded into the `editMode` map so expand-all / collapse-all
 * cover them: every flat opt-in-able field plus the always-shown core rows (`type`, `id`, `title`,
 * `areas`, `categories`, `publish_date`) that render unconditionally but still have an expandable
 * row (so are absent from the opt-in universe). Order is immaterial here (every entry is seeded); it
 * tracks the field-layout order for readability.
 */
const FLAT_FIELD_IDS = [
  'type',
  'orig_info',
  'id',
  'title',
  'references',
  'publish_date',
  'areas',
  'categories',
  'charts',
  'attachments',
  'note',
  'publication',
  'source',
  'prohibition',
  'signals',
  'layout',
] as const;

// The backend field-visibility map spells the event-dates gate `time`, while every call site (and
// this service) spells it `event_dates`. The two are accepted interchangeably for every map / opt-in
// lookup so the gate works regardless of which spelling the server sends.
const EVENT_DATE_ALIASES = ['event_dates', 'time'] as const;

/** Alias set for a gate key — both event-date spellings collapse together; every other key stands alone. */
function fieldAliases(fieldId: string): readonly string[] {
  return fieldId === 'event_dates' || fieldId === 'time' ? EVENT_DATE_ALIASES : [fieldId];
}

/** Canonical spelling stored in the opt-in set so the two event-date spellings never double-count. */
function canonicalFieldId(fieldId: string): string {
  return fieldId === 'time' ? 'event_dates' : fieldId;
}

/**
 * A message part's content identity — its editable content only, excluding `indexNo` (order) and the
 * derived `startCoordIndex` geometry stamp, BOTH of which are re-threaded on every reorder. Two parts
 * with the same identity are "the same part" wherever they moved, so a pure reorder never reads as a
 * per-field change (see {@link partContent}).
 */
function partIdentity(part: MessagePart): string {
  return canonicalKey(partContent(part));
}

/**
 * A per-language map of one desc text field, keyed by language and DROPPING empty/absent values.
 * Order-, padding-, and empty-normalisation-tolerant: a `''` vs `undefined`, a re-ordered `descs`
 * array, or a language padded in with a blank value (as `mergeDescs` does on every form change) all
 * compare equal — so an unrelated edit never lights a per-language field's dot (issue: separatePage
 * flipping Source/Publication).
 */
function perLangText(
  descs: readonly MessageDescription[] | undefined,
  key: keyof MessageDescription,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const desc of descs ?? []) {
    const value = desc[key];
    if (desc.lang && typeof value === 'string' && value) {
      out[desc.lang] = value;
    }
  }
  return out;
}

/**
 * Owns the per-field editor UI state that has no home on the message model: which field rows are
 * expanded into their editors, the edited-dot baseline compare, and the roving-tabindex focus
 * registry that lets keyboard navigation move between field labels that are NOT DOM siblings
 * (message-part rows interleave them). Route-scoped alongside `EditorFacade` so it shares the
 * editor's lifetime and reads the same working `message`.
 *
 * `editMode` is a `Record<fieldId, boolean[]>`: flat fields carry `[false]`; the four
 * part-indexed fields (`event_dates`, `positions`, `subject`, `description`) carry one boolean per
 * message part. The part arrays are kept correct two ways: the explicit `partAdded/partRemoved/
 * partMoved` splices give positional correctness (wired at the part-mutation call sites), and a
 * defensive effect resizes the arrays whenever `facade.message().parts` length drifts as a
 * length-safety net. Expansion is only meaningful for an editor — a plain user sees viewers only,
 * so `toggle/expand/collapse` no-op for non-editors.
 */
@Injectable()
export class EditorFieldsService {
  private readonly facade = inject(EditorFacade);

  // Keyed on the loaded message's identity: switching to a DIFFERENT message (or entering the
  // editor fresh after the shell reset cleared the baseline) collapses every row, while a
  // post-save reload of the SAME message keeps the user's open rows. Writable — the toggle /
  // splice methods update it like a plain signal.
  private readonly _editMode = linkedSignal<string | undefined, Record<string, boolean[]>>({
    source: () => this.facade.loadedBaseline()?.id,
    computation: (id, previous) =>
      previous !== undefined && id === previous.source ? previous.value : {},
  });
  readonly editMode = this._editMode.asReadonly();

  // Session opt-ins: fields the user has revealed via "+ Add optional field" that the per-message
  // `editorFields` map hides. Unioned with the map by `showField`. Session-scoped: linked to the
  // loaded-baseline identity, so every fresh load (and save-reload) resets to an empty set while an
  // adjust round-trip leaves opt-ins intact (adjust never touches the baseline). A user opt-in
  // survives a server-driven hide.
  private readonly _optIns = linkedSignal<unknown, ReadonlySet<string>>({
    source: () => this.facade.loadedBaseline(),
    computation: () => new Set<string>(),
  });

  // The label elements participating in roving-tabindex keyboard navigation, and the one that
  // currently holds the tab stop (tabindex 0). Document order is computed at call time from the
  // live DOM, so no manual ordering is maintained here.
  private readonly labels = new Set<HTMLElement>();
  private activeLabel: HTMLElement | undefined;

  constructor() {
    // Length-safety net: keep the part-indexed arrays sized to the working message's part count.
    // Positional correctness on a specific add/remove/move comes from the explicit splice methods;
    // this only fixes drift (e.g. a fresh load with a different part count).
    effect(() => {
      const partCount = this.facade.message()?.parts?.length ?? 1;
      untracked(() => this.ensurePartArrayLengths(partCount));
    });
  }

  // ── Field-visibility engine ──────────────────────────────────────────────────
  // A field row renders when the per-message backend `editorFields` map turns it on OR the user has
  // opted it in this session. Both spellings of the event-dates gate (`time`/`event_dates`) are
  // accepted. The map re-arrives on the `message` signal after every adjust, so reading it here
  // makes re-derivation automatic; opt-ins are a separate signal reset only on load.

  /** Whether `fieldId` should be shown: server map turns it on, or the user opted it in this session. */
  showField(fieldId: string): boolean {
    return this.mapShows(fieldId) || this.optedIn(fieldId);
  }

  /** References stay visible whenever the message already carries references (sticky-visible). */
  readonly showReferencesField = computed<boolean>(
    () => this.showField('references') || (this.facade.message()?.references?.length ?? 0) > 0,
  );

  /** Charts stay visible whenever the message already carries charts (sticky-visible). */
  readonly showChartsField = computed<boolean>(
    () => this.showField('charts') || (this.facade.message()?.charts?.length ?? 0) > 0,
  );

  /**
   * The "Manage optional fields" list: the NON-DEFAULT optional fields, in field-layout order — the
   * static `FIELD_UNIVERSE` unioned with any present-but-false keys the backend map carries beyond
   * it, minus the always-shown core rows and the part-type gates (`note`), and minus every field the
   * map already shows by default. A field the user opts in STAYS in the list (marked shown, so it
   * can be removed again) because opting in does not flip the backend map.
   */
  readonly manageableFields = computed<readonly string[]>(() => {
    const map = this.facade.message()?.editorFields ?? {};
    // Present-but-false map keys not already covered by the ordered universe (appended after it).
    const extras: string[] = [];
    for (const key of Object.keys(map)) {
      if (map[key]) {
        continue;
      }
      const canonical = canonicalFieldId(key);
      if (
        !FIELD_UNIVERSE_SET.has(canonical) &&
        !ALWAYS_SHOWN_FIELD_IDS.has(canonical) &&
        !NON_OPTIN_FIELD_IDS.has(canonical) &&
        !extras.includes(canonical)
      ) {
        extras.push(canonical);
      }
    }
    // Drop fields the backend map turns ON by default — those are not optional here.
    return [...FIELD_UNIVERSE, ...extras].filter((fieldId) => !this.mapShows(fieldId));
  });

  /** Flip a field's session opt-in (the "Manage optional fields" toggle). Stored under its canonical key. */
  toggleOptIn(fieldId: string): void {
    const canonical = canonicalFieldId(fieldId);
    this._optIns.update((current) => {
      const next = new Set(current);
      if (next.has(canonical)) {
        next.delete(canonical);
      } else {
        next.add(canonical);
      }
      return next;
    });
  }

  private mapShows(fieldId: string): boolean {
    const map = this.facade.message()?.editorFields;
    return map ? fieldAliases(fieldId).some((key) => map[key] === true) : false;
  }

  private optedIn(fieldId: string): boolean {
    const set = this._optIns();
    return fieldAliases(fieldId).some((key) => set.has(key));
  }

  /** Whether the map carries `fieldId` at all (either value) — distinct from it being turned on. */
  private mapHasKey(fieldId: string): boolean {
    const map = this.facade.message()?.editorFields;
    return map ? fieldAliases(fieldId).some((key) => key in map) : false;
  }

  // ── Part-field visibility predicates ─────────────────────────────────────────
  // Consumed by the per-part rows. A part-type selector shows when its gate field is on OR the part
  // already carries that type (so an existing type is always visible / changeable).

  /**
   * Whether the part-type selector gated by `gateKey` should render. The DETAILS/TIME/POSITIONS/NOTE
   * group gates on `part_types`; PROHIBITION on `prohibition`; SIGNALS on `signals`. A part already
   * of the gated type always shows its own selector. When the backend omits the `part_types` key
   * entirely the group renders anyway, so the type switcher is never unreachable.
   */
  showPartType(part: MessagePart, gateKey: string): boolean {
    if ((part.type ?? '').toLowerCase() === gateKey) {
      return true;
    }
    if (gateKey === 'part_types' && !this.mapHasKey('part_types')) {
      return true;
    }
    return this.showField(gateKey);
  }

  /** Event dates show when the part already has intervals, or the gate is on for a DETAILS/TIME part. */
  showPartEventDates(part: MessagePart): boolean {
    if ((part.eventDates?.length ?? 0) > 0) {
      return true;
    }
    return this.showField('event_dates') && (part.type === 'DETAILS' || part.type === 'TIME');
  }

  /** Positions show for DETAILS/POSITIONS/SIGNALS parts, or any part that already carries geometry. */
  showPartPositions(part: MessagePart): boolean {
    if (part.type === 'DETAILS' || part.type === 'POSITIONS' || part.type === 'SIGNALS') {
      return true;
    }
    return (part.geometry?.features?.length ?? 0) > 0;
  }

  /** The subject line is a DETAILS-part field only. */
  showPartSubject(part: MessagePart): boolean {
    return part.type === 'DETAILS';
  }

  /** The description body renders on every part. */
  showPartDescription(): boolean {
    return true;
  }

  // ── Expand / collapse state ──────────────────────────────────────────────────

  /** Whether the field row (at `index` for part-indexed fields) is expanded into its editor. */
  isExpanded(fieldId: string, index = 0): boolean {
    return this._editMode()[fieldId]?.[index] ?? false;
  }

  /** Flip a single field's edit-state (no-op for a non-editor). */
  toggle(fieldId: string, index = 0): void {
    this.setEntry(fieldId, index, !this.isExpanded(fieldId, index));
  }

  /** Expand a single field into its editor (no-op for a non-editor). */
  expand(fieldId: string, index = 0): void {
    this.setEntry(fieldId, index, true);
  }

  /** Collapse a single field back to its viewer (no-op for a non-editor). */
  collapse(fieldId: string, index = 0): void {
    this.setEntry(fieldId, index, false);
  }

  /** Expand every field (the action menu's "Expand all"). */
  expandAll(): void {
    this.setAllFields(true);
  }

  /** Collapse every field (the action menu's "Collapse all"). */
  collapseAll(): void {
    this.setAllFields(false);
  }

  private setEntry(fieldId: string, index: number, value: boolean): void {
    if (!this.facade.isEditor()) {
      return;
    }
    this._editMode.update((current) => {
      const arr = [...(current[fieldId] ?? [])];
      while (arr.length <= index) {
        arr.push(false);
      }
      arr[index] = value;
      return { ...current, [fieldId]: arr };
    });
  }

  private setAllFields(value: boolean): void {
    const partCount = this.facade.message()?.parts?.length ?? 1;
    const next: Record<string, boolean[]> = {};
    for (const id of FLAT_FIELD_IDS) {
      next[id] = [value];
    }
    for (const id of PART_INDEXED_FIELD_IDS) {
      next[id] = new Array<boolean>(partCount).fill(value);
    }
    this._editMode.set(next);
  }

  // ── Part lock-step ───────────────────────────────────────────────────────────
  // Called from the part-mutation seam so the part-indexed arrays track the working message's
  // parts positionally. The defensive effect above only guarantees length, not insertion position.

  /** A part was inserted at `index` — splice a collapsed entry into each part-indexed array. */
  partAdded(index: number): void {
    this.splicePartArrays((arr) => {
      const next = [...arr];
      next.splice(this.clamp(index, 0, next.length), 0, false);
      return next;
    });
  }

  /** A part was removed at `index` — drop that entry from each part-indexed array. */
  partRemoved(index: number): void {
    this.splicePartArrays((arr) => {
      if (index < 0 || index >= arr.length) {
        return arr;
      }
      const next = [...arr];
      next.splice(index, 1);
      // The working message keeps at least one part; never leave an empty array behind.
      return next.length > 0 ? next : [false];
    });
  }

  /** A part moved from `from` to `to` — move that entry within each part-indexed array. */
  partMoved(from: number, to: number): void {
    this.splicePartArrays((arr) => {
      if (from < 0 || from >= arr.length || to < 0 || to >= arr.length) {
        return arr;
      }
      const next = [...arr];
      const [item] = next.splice(from, 1);
      next.splice(to, 0, item);
      return next;
    });
  }

  private splicePartArrays(fn: (arr: boolean[]) => boolean[]): void {
    this._editMode.update((current) => {
      const next = { ...current };
      for (const id of PART_INDEXED_FIELD_IDS) {
        next[id] = fn(current[id] ?? []);
      }
      return next;
    });
  }

  private ensurePartArrayLengths(partCount: number): void {
    const current = this._editMode();
    const needsResize = PART_INDEXED_FIELD_IDS.some(
      (id) => (current[id]?.length ?? -1) !== partCount,
    );
    if (!needsResize) {
      return;
    }
    this._editMode.update((map) => {
      const next = { ...map };
      for (const id of PART_INDEXED_FIELD_IDS) {
        next[id] = this.resizeArray(map[id], partCount);
      }
      return next;
    });
  }

  private resizeArray(arr: boolean[] | undefined, length: number): boolean[] {
    const source = arr ?? [];
    const out = new Array<boolean>(length);
    for (let i = 0; i < length; i++) {
      out[i] = source[i] ?? false;
    }
    return out;
  }

  private clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), max);
  }

  // ── Edited-dot baseline compare ──────────────────────────────────────────────
  // The single field → message-slice map. A field is "edited" when its slice of the working
  // message differs (deep) from the same slice of the loaded baseline. Per-language `descs` and
  // `parts[i]` slices compare deep via a stable serialisation.

  private readonly fieldSlice: Record<string, (m: Message) => unknown> = {
    type: (m) => m.type ?? null,
    id: (m) => ({
      seriesId: m.messageSeries?.seriesId ?? null,
      shortId: m.shortId ?? null,
      number: m.number ?? null,
    }),
    title: (m) => ({
      autoTitle: m.autoTitle ?? null,
      title: perLangText(m.descs, 'title'),
    }),
    references: (m) => m.references ?? [],
    publish_date: (m) => ({
      // Compare dates as instants — the form round-trips ISO strings ('…Z' → '….000Z').
      from: normalizeDate(m.publishDateFrom),
      to: normalizeDate(m.publishDateTo),
      followUp: normalizeDate(m.followUpDate),
    }),
    areas: (m) => ({
      areas: m.areas ?? [],
      vicinity: perLangText(m.descs, 'vicinity'),
    }),
    categories: (m) => m.categories ?? [],
    charts: (m) => m.charts ?? [],
    orig_info: (m) => m.originalInformation ?? null,
    source: (m) => perLangText(m.descs, 'source'),
    publication: (m) => ({
      publication: perLangText(m.descs, 'publication'),
      internalPublication: perLangText(m.descs, 'internalPublication'),
    }),
    layout: (m) => ({
      separatePage: m.separatePage ?? null,
      thumbnail: m.thumbnailPath ?? null,
    }),
  };

  private readonly partFieldSlice: Record<string, (p: MessagePart) => unknown> = {
    // Compare event-date intervals as instants (the form/facade round-trips ISO strings).
    event_dates: (p) =>
      (p.eventDates ?? []).map((interval) => ({
        allDay: interval.allDay,
        from: normalizeDate(interval.fromDate),
        to: normalizeDate(interval.toDate),
      })),
    // Strip the derived `startCoordIndex` — a reorder re-threads it without the geometry changing.
    positions: (p) => stripCoordIndex(p.geometry) ?? null,
    subject: (p) => perLangText(p.descs, 'subject'),
    description: (p) => perLangText(p.descs, 'details'),
  };

  /**
   * Pairs each CURRENT message part to the BASELINE part it corresponds to (or `null` for a brand-new
   * part) so a part-field's edited-dot follows the part across a reorder, not the array index. Pass 1
   * claims exact content matches (an unchanged part keeps its baseline identity wherever it moved);
   * pass 2 pairs the leftover (edited / new) current parts with the leftover baseline parts in their
   * relative order. A pure reorder therefore leaves every part exactly matched → no field shows a dot
   * (the message is still dirty via the whole-message compare, since the ordering changed).
   */
  private readonly partMatching = computed<readonly (MessagePart | null)[]>(() => {
    const current = this.facade.message()?.parts ?? [];
    const baseline = this.facade.loadedBaseline()?.parts ?? [];
    const baseKeys = baseline.map((part) => partIdentity(part));
    const claimed = new Array<boolean>(baseline.length).fill(false);
    const result: (MessagePart | null)[] = new Array<MessagePart | null>(current.length).fill(null);
    for (let i = 0; i < current.length; i++) {
      const key = partIdentity(current[i]);
      for (let j = 0; j < baseline.length; j++) {
        if (!claimed[j] && baseKeys[j] === key) {
          claimed[j] = true;
          result[i] = baseline[j];
          break;
        }
      }
    }
    const leftoverBaseline = baseline.filter((_, j) => !claimed[j]);
    let b = 0;
    for (let i = 0; i < current.length; i++) {
      if (result[i] === null && b < leftoverBaseline.length) {
        result[i] = leftoverBaseline[b++];
      }
    }
    return result;
  });

  /** Whether the field (at `index` for part-indexed fields) differs from the loaded baseline. */
  edited(fieldId: string, index = 0): boolean {
    const current = this.facade.message();
    const baseline = this.facade.loadedBaseline();
    if (!current || !baseline) {
      return false;
    }
    const partSlice = this.partFieldSlice[fieldId];
    if (partSlice) {
      // Compare against the part's baseline COUNTERPART (identity-matched), not `baseline.parts[index]`,
      // so a reorder alone never lights a field dot. `canonicalKey` normalises both sides so the
      // form sync's `mergeDescs` rewrites (blank-language padding, `''`/`undefined`) never diff.
      const cur = current.parts?.[index];
      const base = this.partMatching()[index] ?? null;
      return (
        canonicalKey(cur ? partSlice(cur) : null) !== canonicalKey(base ? partSlice(base) : null)
      );
    }
    const slice = this.fieldSlice[fieldId];
    if (!slice) {
      return false;
    }
    return canonicalKey(slice(current)) !== canonicalKey(slice(baseline));
  }

  // ── Roving-tabindex focus registry ───────────────────────────────────────────
  // Field labels are not DOM siblings (part rows interleave them), so keyboard navigation between
  // them can't rely on the DOM tree. Rows register their label here; exactly one registered label
  // holds the tab stop (tabindex 0) and the rest are removed from the tab order (tabindex -1).
  // Move order is the live document order, recomputed at call time.

  /** A row registers its focusable label; the first one to register receives the tab stop. */
  registerLabel(el: HTMLElement): void {
    this.labels.add(el);
    if (!this.activeLabel) {
      this.setActive(el);
    } else if (el !== this.activeLabel) {
      el.setAttribute('tabindex', '-1');
    }
  }

  /** A row unregisters its label; if it held the tab stop, hand it to a DOM neighbour. */
  unregisterLabel(el: HTMLElement): void {
    if (!this.labels.has(el)) {
      return;
    }
    const wasActive = this.activeLabel === el;
    // Pick the neighbour BEFORE removing `el` (ordering needs it still in the set).
    let neighbour: HTMLElement | undefined;
    if (wasActive) {
      const ordered = this.orderedLabels();
      const i = ordered.indexOf(el);
      neighbour = ordered[i + 1] ?? ordered[i - 1];
    }
    this.labels.delete(el);
    if (wasActive) {
      this.activeLabel = undefined;
      if (neighbour) {
        this.setActive(neighbour);
      }
    }
  }

  /** Move the tab stop and focus to the next label in document order. */
  focusNext(current: HTMLElement): void {
    this.moveFocus(current, 1);
  }

  /** Move the tab stop and focus to the previous label in document order. */
  focusPrevious(current: HTMLElement): void {
    this.moveFocus(current, -1);
  }

  private moveFocus(current: HTMLElement, delta: 1 | -1): void {
    const ordered = this.orderedLabels();
    const i = ordered.indexOf(current);
    if (i === -1) {
      return;
    }
    const target = ordered[i + delta];
    if (target) {
      this.setActive(target);
      target.focus();
    }
  }

  private setActive(el: HTMLElement): void {
    if (this.activeLabel && this.activeLabel !== el) {
      this.activeLabel.setAttribute('tabindex', '-1');
    }
    el.setAttribute('tabindex', '0');
    this.activeLabel = el;
  }

  private orderedLabels(): HTMLElement[] {
    return [...this.labels].sort((a, b) =>
      a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1,
    );
  }
}
