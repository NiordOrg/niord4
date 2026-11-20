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
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { TreePicker } from '../../../../../shared/components/tree-picker/tree-picker';
import { Icon } from '../../../../../shared/icons/icon';
import { listRead } from '../../../../../shared/utils/list-read';
import { MAIN_TYPES, type MainType } from '../../../../../shared/models/main-type.model';
import {
  MESSAGE_TYPES_BY_MAIN_TYPE,
  type CategoryRef,
} from '../../../../messages/models/message.model';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { AreasApiService } from '../../../../messages/services/api/areas-api.service';
import { CategoriesApiService } from '../../../../messages/services/api/categories-api.service';
import { MessageSeriesApiService } from '../../../../messages/services/api/message-series-api.service';
import { ChartsAdminApiService, type Chart } from '../../../services/api/charts-admin-api.service';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';
import {
  PublicationSeriesApiService,
  type CriterionKind,
  type IssueCriteria,
  type IssueCriterion,
  type ResolvePreview,
  type TimeRelation,
} from '../../../services/api/publication-series-api.service';
import { MessageRef } from './message-ref';

/** One editable row. The operator is not here: see the class doc. */
interface CriterionRow {
  readonly kind: CriterionKind;
  readonly values: readonly string[];
}

/** An option in a flat vocabulary. `labelKey` translates; `label` is already text. */
interface ValueOption {
  readonly value: string;
  readonly label: string;
  readonly labelKey?: string;
}

/**
 * A chosen operand, rendered.
 *
 * `value` is what the document stores; `label`/`detail` are what it means. An
 * operand the catalogue cannot account for keeps its raw key and says so —
 * silently hiding it would hide a criterion that still runs on the server.
 */
interface OperandChip {
  readonly value: string;
  readonly label: string;
  readonly detail: string;
  readonly resolved: boolean;
}

/**
 * The minimum a hierarchy node has to carry to be indexed by MRN.
 *
 * `AreaRef` and `CategoryRef` conform structurally, which is what lets one walk
 * serve both trees.
 */
interface TreeNodeLike {
  readonly id: number;
  readonly mrn?: string;
  readonly descs?: readonly { readonly name?: string }[];
  readonly children?: readonly TreeNodeLike[];
}

/** A hierarchy node, flattened under its MRN. */
interface TreeEntry {
  readonly id: number;
  readonly label: string;
  /** The ancestor names, root first — the path that disambiguates two "Nord". */
  readonly path: string;
}

/** How a kind's operands are chosen. */
type OperandUi = 'checkbox' | 'tree' | 'catalogue';

/** Kinds whose operands are a short flat vocabulary: every option fits on screen. */
const CHECKBOX_KINDS = ['messageSeries', 'messageMainType', 'messageType', 'domain'] as const;

/** Kinds whose operands are hierarchy nodes, stored as MRNs (C-9). */
const TREE_KINDS = ['area', 'category'] as const;
type TreeKind = (typeof TREE_KINDS)[number];

/** Kinds whose operands come from one long flat catalogue: filtered, not browsed whole. */
const CATALOGUE_KINDS = ['chart'] as const;

const ALL_KINDS: readonly CriterionKind[] = [...CHECKBOX_KINDS, ...TREE_KINDS, ...CATALOGUE_KINDS];

const KIND_UI: Readonly<Record<CriterionKind, OperandUi>> = {
  messageSeries: 'checkbox',
  messageMainType: 'checkbox',
  messageType: 'checkbox',
  domain: 'checkbox',
  area: 'tree',
  category: 'tree',
  chart: 'catalogue',
};

/** C-1: the document is capped at eight nodes. */
const MAX_NODES = 8;

/**
 * C-9: an area/category operand is an MRN, never a surrogate id.
 *
 * The picker only ever offers real entities, so the rule bites in exactly one
 * place — an entity that carries no MRN at all. Enforced here rather than left
 * to the save, because the click that adds it looks like every other click.
 */
const NUMERIC_ONLY = /^\d+$/;

/** How many catalogue rows are rendered before the filter has to narrow them. */
const CATALOGUE_PAGE = 50;

/**
 * The resolver's warning vocabulary, all six.
 *
 * Each gets its own message because they do NOT mean the same thing, and a shared
 * one has to guess. Only NULL_PUBLISH_FROM_DROPPED describes something dropped;
 * CANCELLED_BUT_DATE_ALIVE members are INCLUDED and want a decision, and
 * LIMIT_EXCEEDED is about the size of the result rather than about any message.
 * A generic "skipped" would have been wrong for four of the six.
 */
const KNOWN_WARNINGS = new Set([
  'CANCELLED_BUT_DATE_ALIVE',
  'TYPE_DRIFT',
  'NULL_PUBLISH_FROM_DROPPED',
  'STALE_OVERRIDE',
  'OVERLAPPING_ISSUE',
  'LIMIT_EXCEEDED',
]);

/** Long enough that a burst of checkbox clicks does not fire a probe each. */
const PREVIEW_DEBOUNCE_MS = 400;

/**
 * The criteria document editor, with a live count of what it would select.
 *
 * A criteria document decides what goes INTO a publication, and the only honest
 * way to judge one is to run it. So every edit re-probes `/resolve-preview`,
 * which resolves against real messages and persists nothing.
 *
 * THREE SERVER RULES SHAPE THIS SCREEN, and they are worth naming because each
 * removes a control a reader might expect:
 *
 * - **There is no operator control.** `NOT_IN` is reserved and refused (C-2), so
 *   every node is `IN`. A dropdown with one option is a dropdown that teaches the
 *   reader nothing and invites a bug report.
 * - **There is no match control.** `CriteriaMatch` has exactly one value, `ALL`.
 *   Nodes are ANDed, which the header states in words instead.
 * - **A kind appears at most once.** Two same-kind `IN` nodes under `ALL` are
 *   either redundant or empty (C-3), so a used kind leaves the add menu.
 *
 * EVERY KIND IS PICKED, none is typed. The operand of an `area` node is an MRN
 * and of a `chart` node a chart number — stable keys that read as nothing at all,
 * so a field that takes them by hand is a field that takes typos. Each kind gets
 * the surface its vocabulary deserves: a checkbox row where the whole vocabulary
 * fits, the shared tree popover for the two hierarchies, a filtered list for the
 * chart catalogue. The stored key is never what the row shows — chips resolve it
 * back to a name (and, for a hierarchy, the path that tells two same-named nodes
 * apart) against the very list the picker offered.
 *
 * The catalogues load only while a row needs them: the area and category trees
 * and the chart list are each a request this screen has no business making for a
 * series whose criteria are two message series. Nothing at all is loaded, and no
 * count is probed, until {@link active} says the panel is on screen — this
 * editor is one tab of several, and all of them are built when the drawer opens.
 *
 * The count is the point of the screen, not decoration. `total` is the answer;
 * `sample` is a sample and says so, because a list of fifty capped silently reads
 * as "fifty matches" when it might be nine hundred. Each sampled row is named the
 * one way this section names a message, so the panel can be read.
 */
@Component({
  selector: 'app-series-criteria-editor',
  imports: [ErrorBanner, Icon, MessageRef, TranslocoPipe, TreePicker],
  templateUrl: './series-criteria-editor.html',
  host: { class: 'flex min-w-0 flex-col gap-4' },
})
export class SeriesCriteriaEditor {
  /** The document being edited. `undefined` means the series has none yet. */
  readonly criteria = input<IssueCriteria | undefined>(undefined);
  /** Shapes the preview: an in-force probe runs with no lower bound. */
  readonly timeRelation = input<TimeRelation>('PUBLISHED_IN_INTERVAL');
  readonly aliveAtCutoff = input(false);
  /** The window the preview runs over. `undefined` lower bound = unbounded. */
  readonly intervalFrom = input<number | undefined>(undefined);
  readonly cutoff = input.required<number>();
  readonly disabled = input(false);
  /**
   * Whether the panel is on screen.
   *
   * Defaults to true, so a host that always shows the panel needs no binding; a
   * tabbed host passes its own tab predicate.
   */
  readonly active = input(true);

  readonly criteriaChange = output<IssueCriteria | undefined>();

  private readonly api = inject(PublicationSeriesApiService);
  private readonly messageSeriesApi = inject(MessageSeriesApiService);
  private readonly domainsApi = inject(DomainsAdminApiService);
  private readonly areasApi = inject(AreasApiService);
  private readonly categoriesApi = inject(CategoriesApiService);
  private readonly chartsApi = inject(ChartsAdminApiService);
  private readonly language = inject(LanguageService);

  protected readonly maxNodes = MAX_NODES;

  /** The working rows. Seeded from the input on open, then owned here. */
  protected readonly rows = signal<readonly CriterionRow[]>([]);

  /**
   * Entities the picker offered but the document cannot hold, keyed by kind.
   *
   * Only ever an MRN-less area or category (C-9). Named rather than counted:
   * "Nord could not be added" is actionable, "1 could not be added" is not.
   */
  protected readonly unusable = signal<Record<string, readonly string[]>>({});

  /** Narrows the chart catalogue. One entry is enough — C-3 allows one chart row. */
  protected readonly chartFilter = signal('');

  private readonly areaPicker = viewChild<TreePicker<AreaRef>>('areaPicker');
  private readonly categoryPicker = viewChild<TreePicker<CategoryRef>>('categoryPicker');

  /**
   * Whether the panel has ever been on screen.
   *
   * Latched: once it has been shown, its catalogues and its count stay live.
   * Tabbing away and back is not a reason to re-read the lists and re-run a
   * resolve whose answer the reader has already seen.
   */
  private readonly shown = signal(false);

  constructor() {
    effect(() => {
      const criteria = this.criteria();
      // untracked: reseed when the document identity changes (a different series,
      // or a reload), never in response to this component's own emissions.
      untracked(() => {
        this.rows.set(toRows(criteria));
        this.unusable.set({});
        this.chartFilter.set('');
      });
    });

    effect(() => {
      if (this.active()) {
        untracked(() => this.shown.set(true));
      }
    });
  }

  // ---------------------------------------------------------------- catalogues

  /**
   * The language to load the two operand lists in, or `null` while the panel
   * has not been shown. A primitive for the same reason as the tree languages
   * below: `toObservable` re-emits on every distinct value.
   */
  private readonly operandLoadLang = computed(() =>
    this.shown() ? this.language.selectedLanguage() : null,
  );

  private readonly messageSeriesRead = listRead<ValueOption, string | null>(
    toObservable(this.operandLoadLang),
    (lang) =>
      lang === null
        ? of<ValueOption[]>([])
        : this.messageSeriesApi
            .getAll()
            .pipe(
              map((series) =>
                series.map((s) => ({ value: s.seriesId, label: s.seriesId }) satisfies ValueOption),
              ),
            ),
  );
  private readonly messageSeriesOptions = this.messageSeriesRead.value;

  // Read without the Keycloak state, which the server otherwise computes by
  // asking Keycloak once per domain: the picker shows an id and a name, so the
  // wait would be spent on a field nothing here renders.
  private readonly domainRead = listRead<ValueOption, string | null>(
    toObservable(this.operandLoadLang),
    (lang) =>
      lang === null
        ? of<ValueOption[]>([])
        : this.domainsApi
            .getAll(lang, { keycloakState: false })
            .pipe(
              map((domains) =>
                domains.map(
                  (d) => ({ value: d.domainId, label: d.name || d.domainId }) satisfies ValueOption,
                ),
              ),
            ),
  );
  private readonly domainOptions = this.domainRead.value;

  /**
   * The language to load a tree in, or `null` while nothing needs it.
   *
   * A primitive on purpose: `toObservable` re-emits on every distinct value, so
   * an object would refetch the whole tree on each checkbox click. The language
   * itself stays inside the branch: while no row asks for a tree there is no
   * tree to reload when the language changes.
   */
  private readonly areaLoadLang = computed(() => {
    const shown = this.shown();
    const used = this.usesKind('area');
    return shown && used ? this.language.selectedLanguage() : null;
  });

  private readonly categoryLoadLang = computed(() => {
    const shown = this.shown();
    const used = this.usesKind('category');
    return shown && used ? this.language.selectedLanguage() : null;
  });

  private readonly areaRootsRead = listRead<AreaRef, string | null>(
    toObservable(this.areaLoadLang),
    (lang) =>
      lang === null ? of<readonly AreaRef[]>([]) : this.areasApi.getRoots({ language: lang }),
  );
  protected readonly areaRoots = this.areaRootsRead.value;

  private readonly categoryRootsRead = listRead<CategoryRef, string | null>(
    toObservable(this.categoryLoadLang),
    (lang) =>
      lang === null
        ? of<readonly CategoryRef[]>([])
        : this.categoriesApi.getRoots({ language: lang }),
  );
  protected readonly categoryRoots = this.categoryRootsRead.value;

  /** Charts carry no descs, so the catalogue does not reload on a language switch. */
  private readonly chartsNeeded = computed(() => {
    const shown = this.shown();
    const used = this.usesKind('chart');
    return shown && used;
  });

  private readonly chartsRead = listRead<Chart, boolean>(
    toObservable(this.chartsNeeded),
    (needed) => (needed ? this.chartsApi.getAll() : of<Chart[]>([])),
  );
  private readonly charts = this.chartsRead.value;

  /**
   * The lists the operand pickers are filled from. One of them failing is said at the
   * top of the panel, with a retry, so an empty picker is never taken for one with
   * nothing to offer.
   */
  private readonly catalogueReads = [
    this.messageSeriesRead,
    this.domainRead,
    this.areaRootsRead,
    this.categoryRootsRead,
    this.chartsRead,
  ];
  protected readonly cataloguesFailed = computed(() =>
    this.catalogueReads.some((read) => read.failed()),
  );

  /** Read again every catalogue that failed. */
  protected retryCatalogues(): void {
    for (const read of this.catalogueReads) {
      if (read.failed()) read.retry();
    }
  }

  /** Every message type, both main types, flattened — a series may span them. */
  private readonly messageTypeOptions: readonly ValueOption[] = MAIN_TYPES.flatMap((mainType) =>
    MESSAGE_TYPES_BY_MAIN_TYPE[mainType as MainType].map((type) => ({
      value: type,
      label: type,
    })),
  );

  private readonly mainTypeOptions: readonly ValueOption[] = MAIN_TYPES.map((mainType) => ({
    value: mainType,
    label: mainType,
    labelKey: `admin.publications.criteria.mainType.${mainType}`,
  }));

  protected optionsFor(kind: CriterionKind): readonly ValueOption[] {
    switch (kind) {
      case 'messageSeries':
        return this.messageSeriesOptions();
      case 'domain':
        return this.domainOptions();
      case 'messageMainType':
        return this.mainTypeOptions;
      case 'messageType':
        return this.messageTypeOptions;
      default:
        return [];
    }
  }

  private readonly areaIndex = computed(() => indexTree(this.areaRoots()));
  private readonly categoryIndex = computed(() => indexTree(this.categoryRoots()));
  private readonly chartIndex = computed(
    () => new Map(this.charts().map((chart) => [chart.chartNumber, chart])),
  );

  private treeIndex(kind: TreeKind): ReadonlyMap<string, TreeEntry> {
    return kind === 'area' ? this.areaIndex() : this.categoryIndex();
  }

  /**
   * The chart rows to render, and how many matched.
   *
   * Capped: the catalogue runs to four figures and a thousand checkboxes is not
   * a list, it is a wall. The count is reported so a filter that hides the wanted
   * chart says so instead of looking like "no such chart".
   */
  protected readonly chartMatches = computed(() => {
    const query = this.chartFilter().trim().toLowerCase();
    const all = this.charts();
    const matched =
      query === ''
        ? all
        : all.filter(
            (chart) =>
              chart.chartNumber.toLowerCase().includes(query) ||
              (chart.name ?? '').toLowerCase().includes(query),
          );
    return { total: matched.length, shown: matched.slice(0, CATALOGUE_PAGE) };
  });

  // ------------------------------------------------------------------ rendering

  /**
   * The i18n key for a warning code, falling back to a generic one.
   *
   * The resolver owns this vocabulary and can grow it; a code with no key of its
   * own must still read as a sentence rather than as a dotted key, because the
   * whole point of showing warnings is that a bare count misleads.
   */
  protected warningKey(code: string): string {
    const base = 'admin.publications.criteria.preview.warning.';
    return KNOWN_WARNINGS.has(code) ? base + code : base + 'UNKNOWN';
  }

  protected uiFor(kind: CriterionKind): OperandUi {
    return KIND_UI[kind];
  }

  protected usesKind(kind: CriterionKind): boolean {
    return this.rows().some((row) => row.kind === kind);
  }

  private valuesOf(kind: CriterionKind): readonly string[] {
    return this.rows().find((row) => row.kind === kind)?.values ?? [];
  }

  /**
   * The chosen operands of a picked kind, as names.
   *
   * An unresolved chip is not an error — an area can be renamed away or the tree
   * can have failed to load — but it must look different from a resolved one, or
   * a stale MRN reads as a real area right up until the save refuses it (C-4).
   */
  protected chipsFor(kind: CriterionKind): readonly OperandChip[] {
    const values = this.valuesOf(kind);
    if (kind === 'area' || kind === 'category') {
      const index = this.treeIndex(kind);
      return values.map((value) => {
        const entry = index.get(value);
        return entry
          ? { value, label: entry.label, detail: entry.path, resolved: true }
          : { value, label: value, detail: '', resolved: false };
      });
    }
    if (kind === 'chart') {
      const index = this.chartIndex();
      return values.map((value) => {
        const chart = index.get(value);
        return chart
          ? { value, label: chart.chartNumber, detail: chart.name ?? '', resolved: true }
          : { value, label: value, detail: '', resolved: false };
      });
    }
    return [];
  }

  /** The ids the tree popover ticks — the stored MRNs, mapped through the tree. */
  protected readonly selectedAreaIds = computed(() => this.treeIdsOf('area'));
  protected readonly selectedCategoryIds = computed(() => this.treeIdsOf('category'));

  private treeIdsOf(kind: TreeKind): readonly number[] {
    const index = this.treeIndex(kind);
    const ids: number[] = [];
    for (const value of this.valuesOf(kind)) {
      const entry = index.get(value);
      if (entry) {
        ids.push(entry.id);
      }
    }
    return ids;
  }

  protected unusableNames(kind: CriterionKind): string {
    return (this.unusable()[kind] ?? []).join(', ');
  }

  /** Kinds not already used — C-3 allows each at most once. */
  protected readonly addableKinds = computed(() => {
    const used = new Set(this.rows().map((r) => r.kind));
    return this.rows().length >= MAX_NODES ? [] : ALL_KINDS.filter((k) => !used.has(k));
  });

  /**
   * Whether the document is scoped (C-6).
   *
   * An unscoped query resolves across every message series in the installation,
   * which is the foot-gun the rule exists to close. Surfaced here rather
   * than left to the save, because it is the one criteria mistake whose preview
   * looks plausible — a big number that is big for the wrong reason.
   */
  protected readonly isScoped = computed(() =>
    this.rows().some((r) => r.kind === 'messageSeries' || r.kind === 'domain'),
  );

  protected readonly isEmpty = computed(() => this.rows().length === 0);

  // -------------------------------------------------------------------- edits

  protected addKind(kind: CriterionKind): void {
    if (this.disabled() || this.rows().length >= MAX_NODES) {
      return;
    }
    this.rows.update((rows) => [...rows, { kind, values: [] }]);
    this.emit();
  }

  protected removeKind(kind: CriterionKind): void {
    if (this.disabled()) {
      return;
    }
    this.rows.update((rows) => rows.filter((r) => r.kind !== kind));
    this.unusable.update((current) => ({ ...current, [kind]: [] }));
    this.emit();
  }

  protected isChecked(kind: CriterionKind, value: string): boolean {
    return this.valuesOf(kind).includes(value);
  }

  protected toggleValue(kind: CriterionKind, value: string): void {
    if (this.disabled()) {
      return;
    }
    const values = this.valuesOf(kind);
    this.setValues(
      kind,
      values.includes(value) ? values.filter((v) => v !== value) : [...values, value],
    );
  }

  protected removeValue(kind: CriterionKind, value: string): void {
    if (this.disabled()) {
      return;
    }
    this.setValues(
      kind,
      this.valuesOf(kind).filter((v) => v !== value),
    );
  }

  protected setChartFilter(event: Event): void {
    this.chartFilter.set((event.target as HTMLInputElement).value);
  }

  /** Open (or re-close) the hierarchy popover, anchored at the button that asked. */
  protected openTreePicker(kind: CriterionKind, event: MouseEvent): void {
    if (this.disabled() || !(event.currentTarget instanceof HTMLElement)) {
      return;
    }
    const picker = kind === 'area' ? this.areaPicker() : this.categoryPicker();
    picker?.toggle(event.currentTarget);
  }

  /**
   * Take the popover's full selection, as MRNs.
   *
   * Two things this does that a plain map would not. It drops an entity with no
   * usable MRN (C-9) and names it, because the click otherwise does nothing at
   * all. And it KEEPS an operand the loaded tree does not contain: the popover
   * never saw it, so its absence from the emitted set is not the user removing
   * it — that is what the chip's own remove button is for.
   */
  protected onTreeSelection(kind: TreeKind, entities: readonly TreeNodeLike[]): void {
    if (this.disabled()) {
      return;
    }
    const picked: string[] = [];
    const rejected: string[] = [];
    for (const entity of entities) {
      if (entity.mrn && !NUMERIC_ONLY.test(entity.mrn)) {
        picked.push(entity.mrn);
      } else {
        rejected.push(entity.descs?.[0]?.name ?? String(entity.id));
      }
    }
    this.unusable.update((current) => ({ ...current, [kind]: rejected }));

    const index = this.treeIndex(kind);
    const pickedSet = new Set(picked);
    const current = this.valuesOf(kind);
    const kept = current.filter((value) => pickedSet.has(value) || !index.has(value));
    const added = picked.filter((value) => !current.includes(value));
    this.setValues(kind, [...kept, ...added]);
  }

  private setValues(kind: CriterionKind, values: readonly string[]): void {
    this.rows.update((rows) =>
      rows.map((row) => (row.kind === kind ? { ...row, values: [...values] } : row)),
    );
    this.emit();
  }

  /**
   * A document, or `undefined` when there are no nodes.
   *
   * The distinction is load-bearing: S-1 reads a null document as "no query",
   * which is not the same as an empty one, and an empty `criteria` array would
   * claim a query that selects nothing.
   */
  private emit(): void {
    const rows = this.rows();
    this.criteriaChange.emit(
      rows.length === 0
        ? undefined
        : {
            schemaVersion: 1,
            match: 'ALL',
            criteria: rows.map(
              (row) =>
                ({
                  kind: row.kind,
                  operator: 'IN',
                  values: [...row.values],
                }) satisfies IssueCriterion,
            ),
          },
    );
  }

  // ------------------------------------------------------------------ preview

  /**
   * What the current document would select.
   *
   * Keyed on a serialized snapshot so an edit that does not change the document
   * — clicking a value on and off again — does not re-probe. A node with no
   * values is dropped from the probe rather than sent: C-1 refuses an empty
   * operand list, and a 400 mid-edit is noise, not an answer.
   *
   * The key of a panel nobody has looked at is the JSON literal `null` rather
   * than the empty string, because `JSON.parse('')` throws. It reads as idle,
   * which is what a panel out of sight shows anyway.
   */
  private readonly probeKey = computed(() => {
    if (!this.shown()) {
      return 'null';
    }
    const nodes = this.rows().filter((r) => r.values.length > 0);
    return JSON.stringify({
      nodes,
      timeRelation: this.timeRelation(),
      aliveAtCutoff: this.aliveAtCutoff(),
      intervalFrom: this.intervalFrom(),
      cutoff: this.cutoff(),
      // Part of the key, not read at request time: the sampled rows are titled
      // in this language, so switching it while the panel is open has to fetch
      // the sample again or the names below the count stay in the old one.
      lang: this.language.selectedLanguage(),
    });
  });

  protected readonly preview = toSignal(
    toObservable(this.probeKey).pipe(
      debounceTime(PREVIEW_DEBOUNCE_MS),
      distinctUntilChanged(),
      switchMap((key) => {
        const snapshot = JSON.parse(key) as {
          nodes: CriterionRow[];
          timeRelation: TimeRelation;
          aliveAtCutoff: boolean;
          intervalFrom?: number;
          cutoff: number;
          lang: string;
        } | null;
        if (!snapshot || snapshot.nodes.length === 0) {
          return of<PreviewState>({ status: 'idle' });
        }
        return this.api
          .resolvePreview(
            {
              criteria: {
                schemaVersion: 1,
                match: 'ALL',
                criteria: snapshot.nodes.map((n) => ({
                  kind: n.kind,
                  operator: 'IN',
                  values: n.values,
                })),
              },
              cutoff: snapshot.cutoff,
              intervalFrom: snapshot.intervalFrom,
              timeRelation: snapshot.timeRelation,
              aliveAtCutoff: snapshot.aliveAtCutoff,
            },
            snapshot.lang,
          )
          .pipe(
            map((result): PreviewState => ({ status: 'ready', result })),
            catchError(() => of<PreviewState>({ status: 'error' })),
            startWith<PreviewState>({ status: 'loading' }),
          );
      }),
    ),
    { initialValue: { status: 'idle' } as PreviewState },
  );

  /** The result, or undefined in every other state — narrowed here, not in the template. */
  protected readonly previewResult = computed(() => {
    const state = this.preview();
    return state.status === 'ready' ? state.result : undefined;
  });

  protected readonly previewStatus = computed(() => this.preview().status);
}

type PreviewState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; result: ResolvePreview };

/** The stored document as editable rows, dropping anything the editor cannot show. */
function toRows(criteria: IssueCriteria | undefined): readonly CriterionRow[] {
  return (criteria?.criteria ?? [])
    .filter((node) => ALL_KINDS.includes(node.kind))
    .map((node) => ({ kind: node.kind, values: [...node.values] }));
}

/**
 * A loaded hierarchy, flattened under each node's MRN.
 *
 * Keyed by MRN because that is what the document stores; the numeric id rides
 * along only so the popover — which selects by id — can be told what is ticked.
 * A node with no MRN is not indexed: it cannot be an operand, so an index entry
 * for it would only ever produce a value the server refuses.
 */
function indexTree(roots: readonly TreeNodeLike[]): ReadonlyMap<string, TreeEntry> {
  const index = new Map<string, TreeEntry>();
  const walk = (nodes: readonly TreeNodeLike[], ancestors: readonly string[]): void => {
    for (const node of nodes) {
      const label = node.descs?.[0]?.name ?? '';
      if (node.mrn) {
        index.set(node.mrn, {
          id: node.id,
          label: label || node.mrn,
          path: ancestors.join(' / '),
        });
      }
      if (node.children?.length) {
        walk(node.children, label ? [...ancestors, label] : ancestors);
      }
    }
  };
  walk(roots, []);
  return index;
}
