import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  CriterionKind,
  IssueCriteria,
} from '../../../services/api/publication-series-api.service';
import { SeriesCriteriaEditor } from './series-criteria-editor';

@Component({
  imports: [SeriesCriteriaEditor],
  template: `
    <app-series-criteria-editor
      [criteria]="criteria()"
      [timeRelation]="'PUBLISHED_IN_INTERVAL'"
      [intervalFrom]="1000"
      [cutoff]="2000"
      (criteriaChange)="last = $event"
    />
  `,
})
class Host {
  readonly criteria = signal<IssueCriteria | undefined>(undefined);
  last: IssueCriteria | undefined | 'never' = 'never';
}

interface Chip {
  readonly value: string;
  readonly label: string;
  readonly detail: string;
  readonly resolved: boolean;
}

interface PickedNode {
  readonly id: number;
  readonly mrn?: string;
  readonly descs?: readonly { readonly name?: string }[];
}

interface EditorInternals {
  rows: () => readonly { kind: CriterionKind; values: readonly string[] }[];
  addableKinds: () => readonly CriterionKind[];
  isScoped: () => boolean;
  isEmpty: () => boolean;
  addKind: (kind: CriterionKind) => void;
  removeKind: (kind: CriterionKind) => void;
  toggleValue: (kind: CriterionKind, value: string) => void;
  removeValue: (kind: CriterionKind, value: string) => void;
  uiFor: (kind: CriterionKind) => 'checkbox' | 'tree' | 'catalogue';
  optionsFor: (
    kind: CriterionKind,
  ) => readonly { value: string; label: string; labelKey?: string }[];
  chipsFor: (kind: CriterionKind) => readonly Chip[];
  onTreeSelection: (kind: 'area' | 'category', entities: readonly PickedNode[]) => void;
  unusableNames: (kind: CriterionKind) => string;
  selectedAreaIds: () => readonly number[];
  selectedCategoryIds: () => readonly number[];
  chartFilter: { set: (value: string) => void };
  chartMatches: () => { total: number; shown: readonly { chartNumber: string }[] };
  previewResult: () => { total: number } | undefined;
}

const SCOPED: IssueCriteria = {
  schemaVersion: 1,
  match: 'ALL',
  criteria: [{ kind: 'messageSeries', operator: 'IN', values: ['dma-nm'] }],
};

/**
 * A host that says whether the panel is on screen — the tabbed drawer's shape.
 *
 * Tab panels are all built when the drawer opens, so a criteria editor on an
 * unread tab is constructed exactly like one being looked at. Only this host
 * binds `active`; the plain `Host` above leaves it at its default, which is what
 * every host that always shows the panel does.
 */
@Component({
  imports: [SeriesCriteriaEditor],
  template: `
    <app-series-criteria-editor
      [active]="active()"
      [criteria]="criteria()"
      [timeRelation]="'PUBLISHED_IN_INTERVAL'"
      [intervalFrom]="1000"
      [cutoff]="2000"
    />
  `,
})
class TabbedHost {
  readonly active = signal(false);
  readonly criteria = signal<IssueCriteria | undefined>(SCOPED);
}

const ANCHORLAND = 'urn:mrn:iho:country:xa';
const KATTEGAT = 'urn:mrn:iho:area:xa:kattegat';

/** A root, a child with an MRN, and a child WITHOUT one — the C-9 case. */
const AREA_ROOTS = [
  {
    id: 1,
    mrn: ANCHORLAND,
    active: true,
    descs: [{ lang: 'en', name: 'Anchorland' }],
    children: [
      { id: 2, mrn: KATTEGAT, active: true, descs: [{ lang: 'en', name: 'Kattegat' }] },
      { id: 3, active: true, descs: [{ lang: 'en', name: 'Legacy area' }] },
    ],
  },
];

const FIRING = 'urn:mrn:iho:category:xa:firing';

const CATEGORY_ROOTS = [
  {
    id: 10,
    mrn: 'urn:mrn:iho:category:xa',
    active: true,
    descs: [{ lang: 'en', name: 'Anchorland' }],
    children: [{ id: 11, mrn: FIRING, active: true, descs: [{ lang: 'en', name: 'Firing' }] }],
  },
];

const CHARTS = [
  { chartNumber: '101', name: 'Skagerrak', active: true },
  { chartNumber: '102', name: 'Kattegat Nord', active: true },
];

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([]),
      {
        provide: LanguageService,
        useValue: { selectedLanguage: () => 'en', modelLanguages: [] },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  drainLookups(httpTesting);
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting,
    editor: fixture.debugElement.children[0].componentInstance as unknown as EditorInternals,
  };
}

/** The same providers, hosted by the tabbed shape so `active` can be moved. */
function setupTabbed() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([]),
      {
        provide: LanguageService,
        useValue: { selectedLanguage: () => 'en', modelLanguages: [] },
      },
    ],
  });
  const fixture = TestBed.createComponent(TabbedHost);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

/** The two operand lists, which the panel asks for as soon as it is on screen. */
function drainLookups(httpTesting: HttpTestingController): void {
  for (const req of httpTesting.match(
    (r) => r.url.includes('/domains/all') || r.url.includes('/message-series'),
  )) {
    req.flush([]);
  }
}

/** Flush the one catalogue a newly-added row asked for. */
function flushCatalogue(
  httpTesting: HttpTestingController,
  url: string,
  body: readonly object[],
): void {
  httpTesting.expectOne((r) => r.url.includes(url)).flush([...body]);
}

describe('SeriesCriteriaEditor', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.useRealTimers();
    TestBed.inject(HttpTestingController).verify();
  });

  it('emits every node with the IN operator', () => {
    // C-2: NOT_IN is reserved and refused, so there is no operator to choose and
    // nothing may emit one.
    const { host, editor } = setup();

    editor.addKind('messageSeries');
    editor.toggleValue('messageSeries', 'dma-nm');

    const emitted = host.last as IssueCriteria;
    expect(emitted.criteria.every((n) => n.operator === 'IN')).toBe(true);
    expect(emitted.match).toBe('ALL');
    expect(emitted.schemaVersion).toBe(1);
  });

  it('takes a kind out of the add menu once it is used', () => {
    // C-3: two same-kind IN nodes under match:ALL are redundant or empty, so
    // offering the kind twice offers a document that cannot be saved.
    const { editor } = setup();

    expect(editor.addableKinds()).toContain('domain');
    editor.addKind('domain');
    expect(editor.addableKinds()).not.toContain('domain');

    editor.removeKind('domain');
    expect(editor.addableKinds()).toContain('domain');
  });

  it('offers all seven kinds, each with a picked operand rather than a typed one', () => {
    // D6: the full vocabulary must work end to end, so no kind may be missing
    // from the menu and none may fall back to a free-text key.
    const { editor } = setup();

    expect(editor.addableKinds()).toEqual([
      'messageSeries',
      'messageMainType',
      'messageType',
      'domain',
      'area',
      'category',
      'chart',
    ]);
    expect(editor.uiFor('area')).toBe('tree');
    expect(editor.uiFor('category')).toBe('tree');
    expect(editor.uiFor('chart')).toBe('catalogue');
    expect(editor.uiFor('messageMainType')).toBe('checkbox');
  });

  it('emits undefined rather than an empty document when the last node goes', () => {
    // S-1 reads a null document as "no query", which is not the same as a query
    // that selects nothing.
    const { host, editor } = setup();

    editor.addKind('messageSeries');
    expect(host.last).toBeDefined();

    editor.removeKind('messageSeries');
    expect(host.last).toBeUndefined();
    expect(editor.isEmpty()).toBe(true);
  });

  it('reports a document with neither a message series nor a domain as unscoped', () => {
    // C-6: an unscoped query resolves across every message series in the
    // installation — the legacy foot-gun the rule exists to close.
    const { host, editor } = setup();

    host.criteria.set(undefined);
    editor.addKind('messageType');
    expect(editor.isScoped()).toBe(false);

    editor.addKind('domain');
    expect(editor.isScoped()).toBe(true);
  });

  it('seeds its rows from a stored document', () => {
    const { fixture, host, editor } = setup();
    host.criteria.set(SCOPED);
    fixture.detectChanges();

    expect(editor.rows()).toEqual([{ kind: 'messageSeries', values: ['dma-nm'] }]);
    expect(editor.isScoped()).toBe(true);
  });

  it('probes what the document would select, once the typing settles', () => {
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('messageSeries');
    editor.toggleValue('messageSeries', 'dma-nm');
    fixture.detectChanges();

    // Nothing yet: the debounce is what keeps a probe off every keystroke.
    httpTesting.expectNone((r) => r.url.includes('/resolve-preview'));

    vi.advanceTimersByTime(500);
    const req = httpTesting.expectOne(
      (r) => r.method === 'POST' && r.url === '/rest/publication-series/resolve-preview',
    );
    const body = req.request.body as { criteria: IssueCriteria; cutoff: number };
    expect(body.criteria.criteria).toEqual([
      { kind: 'messageSeries', operator: 'IN', values: ['dma-nm'] },
    ]);
    expect(body.cutoff).toBe(2000);

    // The names in the sample are read in the language the panel is being read
    // in; the document itself selects the same messages either way.
    expect(req.request.params.get('lang')).toBe('en');

    req.flush({ source: 'PROBE', total: 42, candidates: 90, sample: [], truncated: false });
    fixture.detectChanges();
    expect(editor.previewResult()?.total).toBe(42);
  });

  /**
   * The sample exists to be recognised.
   *
   * A uuid names nothing to the admin judging the document: it cannot be quoted,
   * and a row of them says only "fifty of something". So each sampled message is
   * named by its short id, or — where nobody has numbered it yet — by its title,
   * with the uid carried as the link target and nowhere else.
   */
  it('names every sampled message, and never prints a uid', () => {
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('messageSeries');
    editor.toggleValue('messageSeries', 'dma-nm');
    fixture.detectChanges();
    vi.advanceTimersByTime(500);

    httpTesting
      .expectOne((r) => r.url.includes('/resolve-preview'))
      .flush({
        source: 'PROBE',
        total: 2,
        candidates: 5,
        truncated: false,
        sample: [
          { messageUid: 'uid-numbered', messageId: 'NM-815-26', title: 'Buoy off Skagen' },
          { messageUid: 'uid-unnumbered', messageId: null, title: 'Cable work in the Sound' },
        ],
      });
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('NM-815-26');
    expect(text).toContain('Cable work in the Sound');
    expect(text).not.toContain('uid-numbered');
    expect(text).not.toContain('uid-unnumbered');

    // The uid opens the message in the editor, numbered or not.
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')].map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toContain('/editor/edit/uid-numbered');
    expect(links).toContain('/editor/edit/uid-unnumbered');
  });

  it('does not probe a node that has no values yet', () => {
    // C-1 refuses an empty operand list, so sending one buys a 400 in the middle
    // of typing instead of an answer.
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('messageSeries');
    fixture.detectChanges();
    vi.advanceTimersByTime(500);

    httpTesting.expectNone((r) => r.url.includes('/resolve-preview'));
    expect(editor.previewResult()).toBeUndefined();
  });

  // ------------------------------------------------------------------- areas

  it('fetches the area tree only once an area criterion exists', () => {
    // Three catalogues on a screen whose usual document is two message series is
    // three requests nobody asked for.
    const { fixture, httpTesting, editor } = setup();

    httpTesting.expectNone((r) => r.url.includes('/areas/area-roots'));

    editor.addKind('area');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);
  });

  it('stores an area pick as its MRN, never as its id', () => {
    // The contract is explicit: values[] holds stable string keys, and a
    // surrogate id is neither portable across export/import nor auditable.
    const { fixture, httpTesting, host, editor } = setup();

    editor.addKind('area');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    editor.onTreeSelection('area', [{ id: 2, mrn: KATTEGAT, descs: [{ name: 'Kattegat' }] }]);

    expect(editor.rows()).toEqual([{ kind: 'area', values: [KATTEGAT] }]);
    expect((host.last as IssueCriteria).criteria[0]).toEqual({
      kind: 'area',
      operator: 'IN',
      values: [KATTEGAT],
    });
  });

  it('renders an area operand as its name and its path, not as its MRN', () => {
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('area');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);
    editor.onTreeSelection('area', [{ id: 2, mrn: KATTEGAT, descs: [{ name: 'Kattegat' }] }]);

    expect(editor.chipsFor('area')).toEqual([
      { value: KATTEGAT, label: 'Kattegat', detail: 'Anchorland', resolved: true },
    ]);
  });

  it('marks an operand the loaded tree cannot account for instead of hiding it', () => {
    // A stale MRN still runs on the server and is refused at save (C-4); a chip
    // that looked like every other one would make that refusal a surprise.
    const { fixture, httpTesting, host, editor } = setup();

    host.criteria.set({
      schemaVersion: 1,
      match: 'ALL',
      criteria: [{ kind: 'area', operator: 'IN', values: ['urn:mrn:iho:area:xa:gone'] }],
    });
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    expect(editor.chipsFor('area')).toEqual([
      {
        value: 'urn:mrn:iho:area:xa:gone',
        label: 'urn:mrn:iho:area:xa:gone',
        detail: '',
        resolved: false,
      },
    ]);
    // It is not ticked in the popover either — the tree has no such node.
    expect(editor.selectedAreaIds()).toEqual([]);
  });

  it('keeps an unresolvable operand when the popover reports its selection', () => {
    // The popover never saw that operand, so its absence from the emitted set is
    // not the user removing it. Only the chip's own button removes it.
    const { fixture, httpTesting, host, editor } = setup();
    const gone = 'urn:mrn:iho:area:xa:gone';

    host.criteria.set({
      schemaVersion: 1,
      match: 'ALL',
      criteria: [{ kind: 'area', operator: 'IN', values: [gone, KATTEGAT] }],
    });
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    // The user unticks Kattegat: the popover emits the empty selection.
    editor.onTreeSelection('area', []);
    expect(editor.rows()[0].values).toEqual([gone]);

    // Ticking Anchorland appends to what survived rather than replacing it.
    editor.onTreeSelection('area', [{ id: 1, mrn: ANCHORLAND, descs: [{ name: 'Anchorland' }] }]);
    expect(editor.rows()[0].values).toEqual([gone, ANCHORLAND]);

    // And the chip's own button is what actually drops it.
    editor.removeValue('area', gone);
    expect(editor.rows()[0].values).toEqual([ANCHORLAND]);
  });

  it('refuses an area that carries no MRN, and says which one', () => {
    // C-9: the operand is an MRN, never a surrogate id — so a node without one
    // has no expressible key at all.
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('area');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    editor.onTreeSelection('area', [
      { id: 3, descs: [{ name: 'Legacy area' }] },
      { id: 2, mrn: KATTEGAT, descs: [{ name: 'Kattegat' }] },
    ]);

    expect(editor.rows()[0].values).toEqual([KATTEGAT]);
    expect(editor.unusableNames('area')).toBe('Legacy area');
  });

  it('refuses a purely numeric area operand', () => {
    // V-C9 rejects it server-side; letting it be picked here only delays the 400.
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('area');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    editor.onTreeSelection('area', [{ id: 7, mrn: '7', descs: [{ name: 'Numeric' }] }]);

    expect(editor.rows()[0].values).toEqual([]);
    expect(editor.unusableNames('area')).toBe('Numeric');
  });

  it('ticks the popover for the areas the document already holds', () => {
    const { fixture, httpTesting, host, editor } = setup();

    host.criteria.set({
      schemaVersion: 1,
      match: 'ALL',
      criteria: [{ kind: 'area', operator: 'IN', values: [KATTEGAT] }],
    });
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);

    expect(editor.selectedAreaIds()).toEqual([2]);
  });

  // -------------------------------------------------------------- categories

  it('picks a category through its own tree, as an MRN', () => {
    const { fixture, httpTesting, host, editor } = setup();

    httpTesting.expectNone((r) => r.url.includes('/categories/category-roots'));

    editor.addKind('category');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/categories/category-roots', CATEGORY_ROOTS);

    editor.onTreeSelection('category', [{ id: 11, mrn: FIRING, descs: [{ name: 'Firing' }] }]);

    expect((host.last as IssueCriteria).criteria[0]).toEqual({
      kind: 'category',
      operator: 'IN',
      values: [FIRING],
    });
    expect(editor.chipsFor('category')).toEqual([
      { value: FIRING, label: 'Firing', detail: 'Anchorland', resolved: true },
    ]);
    expect(editor.selectedCategoryIds()).toEqual([11]);
  });

  // ------------------------------------------------------------------ charts

  it('offers the installation catalogue for charts, filtered by number or name', () => {
    const { fixture, httpTesting, editor } = setup();

    httpTesting.expectNone((r) => r.url.includes('/charts/all'));

    editor.addKind('chart');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/charts/all', CHARTS);

    expect(editor.chartMatches().total).toBe(2);

    editor.chartFilter.set('kattegat');
    expect(editor.chartMatches().shown.map((c) => c.chartNumber)).toEqual(['102']);

    editor.chartFilter.set('101');
    expect(editor.chartMatches().shown.map((c) => c.chartNumber)).toEqual(['101']);
  });

  it('stores a chart pick as its chart number and renders it with its name', () => {
    const { fixture, httpTesting, host, editor } = setup();

    editor.addKind('chart');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/charts/all', CHARTS);

    editor.toggleValue('chart', '102');

    expect((host.last as IssueCriteria).criteria[0]).toEqual({
      kind: 'chart',
      operator: 'IN',
      values: ['102'],
    });
    expect(editor.chipsFor('chart')).toEqual([
      { value: '102', label: '102', detail: 'Kattegat Nord', resolved: true },
    ]);
  });

  // --------------------------------------------------------------- main type

  it('offers the two main types with names rather than bare enum keys', () => {
    const { host, editor } = setup();

    const options = editor.optionsFor('messageMainType');
    expect(options.map((o) => o.value)).toEqual(['NW', 'NM']);
    expect(options.map((o) => o.labelKey)).toEqual([
      'admin.publications.criteria.mainType.NW',
      'admin.publications.criteria.mainType.NM',
    ]);

    editor.addKind('messageMainType');
    editor.toggleValue('messageMainType', 'NM');
    expect((host.last as IssueCriteria).criteria[0]).toEqual({
      kind: 'messageMainType',
      operator: 'IN',
      values: ['NM'],
    });
  });

  // ------------------------------------------------------------------- probe

  it('sends the four newer kinds to the probe in the shape the contract names', () => {
    // D6 again: the vocabulary is only implemented if the probe carries it, and
    // the operand of each kind is the stable key, not a label or an id.
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('area');
    editor.addKind('category');
    editor.addKind('chart');
    editor.addKind('messageMainType');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/areas/area-roots', AREA_ROOTS);
    flushCatalogue(httpTesting, '/categories/category-roots', CATEGORY_ROOTS);
    flushCatalogue(httpTesting, '/charts/all', CHARTS);

    editor.onTreeSelection('area', [{ id: 2, mrn: KATTEGAT, descs: [{ name: 'Kattegat' }] }]);
    editor.onTreeSelection('category', [{ id: 11, mrn: FIRING, descs: [{ name: 'Firing' }] }]);
    editor.toggleValue('chart', '101');
    editor.toggleValue('messageMainType', 'NW');
    fixture.detectChanges();

    vi.advanceTimersByTime(500);
    const req = httpTesting.expectOne(
      (r) => r.method === 'POST' && r.url === '/rest/publication-series/resolve-preview',
    );
    const body = req.request.body as { criteria: IssueCriteria };
    expect(body.criteria.criteria).toEqual([
      { kind: 'area', operator: 'IN', values: [KATTEGAT] },
      { kind: 'category', operator: 'IN', values: [FIRING] },
      { kind: 'chart', operator: 'IN', values: ['101'] },
      { kind: 'messageMainType', operator: 'IN', values: ['NW'] },
    ]);

    req.flush({ source: 'PROBE', total: 3, candidates: 12, sample: [], truncated: false });
  });

  it('leaves a new kind out of the probe until it has an operand', () => {
    // C-1 refuses an empty operand list for every kind, the new ones included.
    const { fixture, httpTesting, editor } = setup();

    editor.addKind('messageSeries');
    editor.toggleValue('messageSeries', 'dma-nm');
    editor.addKind('chart');
    fixture.detectChanges();
    flushCatalogue(httpTesting, '/charts/all', CHARTS);

    vi.advanceTimersByTime(500);
    const req = httpTesting.expectOne((r) => r.url.includes('/resolve-preview'));
    const body = req.request.body as { criteria: IssueCriteria };
    expect(body.criteria.criteria).toEqual([
      { kind: 'messageSeries', operator: 'IN', values: ['dma-nm'] },
    ]);

    req.flush({ source: 'PROBE', total: 1, candidates: 1, sample: [], truncated: false });
  });

  // --------------------------------------------------------- the on-screen gate

  /**
   * A panel built out of sight asks for nothing.
   *
   * Every tab of the settings drawer is built when the drawer opens, so a
   * criteria editor the reader never looks at would otherwise read two operand
   * lists and run a resolve over the corpus for a panel nobody is on.
   */
  it('reads no operand list and runs no probe while it is off screen', () => {
    const { httpTesting } = setupTabbed();

    vi.advanceTimersByTime(500);

    httpTesting.expectNone((r) => r.url.includes('/message-series'));
    httpTesting.expectNone((r) => r.url.includes('/domains/all'));
    httpTesting.expectNone((r) => r.url.includes('/resolve-preview'));
  });

  it('reads them, and probes, the moment it is shown', () => {
    const { fixture, host, httpTesting } = setupTabbed();

    host.active.set(true);
    fixture.detectChanges();

    httpTesting.expectOne((r) => r.url.includes('/message-series')).flush([]);
    const domains = httpTesting.expectOne((r) => r.url.includes('/domains/all'));
    // Without the per-domain Keycloak lookup: the picker shows an id and a name,
    // so the state the server would ask Keycloak for is never rendered.
    expect(domains.request.params.get('keycloakState')).toBe('false');
    domains.flush([]);

    vi.advanceTimersByTime(500);
    httpTesting
      .expectOne((r) => r.url.includes('/resolve-preview'))
      .flush({ source: 'PROBE', total: 7, candidates: 9, sample: [], truncated: false });
  });

  /**
   * Shown once is shown for good.
   *
   * Tabbing away and back is not a reason to re-read two lists and re-run a
   * resolve whose answer the reader has already seen — which is exactly what
   * tearing the panel down on the tab switch would do.
   */
  it('keeps reading and probing once it has been shown, even off screen again', () => {
    const { fixture, host, httpTesting } = setupTabbed();

    host.active.set(true);
    fixture.detectChanges();
    drainLookups(httpTesting);
    vi.advanceTimersByTime(500);
    httpTesting
      .expectOne((r) => r.url.includes('/resolve-preview'))
      .flush({ source: 'PROBE', total: 7, candidates: 9, sample: [], truncated: false });

    host.active.set(false);
    fixture.detectChanges();

    // Nothing is re-read on the way out ...
    httpTesting.expectNone((r) => r.url.includes('/message-series'));
    httpTesting.expectNone((r) => r.url.includes('/domains/all'));

    // ... and a document edited elsewhere in the drawer still gets its count.
    host.criteria.set({
      schemaVersion: 1,
      match: 'ALL',
      criteria: [{ kind: 'messageSeries', operator: 'IN', values: ['dma-nm', 'dma-nw'] }],
    });
    fixture.detectChanges();
    vi.advanceTimersByTime(500);

    const req = httpTesting.expectOne((r) => r.url.includes('/resolve-preview'));
    expect((req.request.body as { criteria: IssueCriteria }).criteria.criteria).toEqual([
      { kind: 'messageSeries', operator: 'IN', values: ['dma-nm', 'dma-nw'] },
    ]);
    req.flush({ source: 'PROBE', total: 9, candidates: 12, sample: [], truncated: false });
  });
});
