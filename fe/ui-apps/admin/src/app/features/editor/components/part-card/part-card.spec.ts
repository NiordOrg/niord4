import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, output, signal } from '@angular/core';
import { DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { GeometryEditor } from '../../../../shared/components/geometry-editor/geometry-editor';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type {
  Message,
  MessageDescription,
  MessagePart,
} from '../../../messages/models/message.model';
import { MessageDateFormatterService } from '../../../messages/services/message-date-formatter.service';
import { MessageGeometryService } from '../../../messages/services/message-geometry.service';
import { MessageDescService } from '../../services/message-desc.service';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { PartCard } from './part-card';

// jsdom lacks ResizeObserver, which ol.Map's constructor references (the expanded positions editor
// renders a read-only preview map).
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

const positionsPart: MessagePart = {
  type: 'POSITIONS',
  descs: [{ lang: 'en', details: '' }],
  geometry: {
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', geometry: { type: 'Point', coordinates: [9.5, 53.1] }, properties: {} },
    ],
  },
};

const emptyPositionsPart: MessagePart = {
  type: 'POSITIONS',
  descs: [{ lang: 'en', details: '' }],
};

const pointFc = (lon: number, lat: number): GeometryFeatureCollection => ({
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: {} },
  ],
});

/** A light stand-in for the shared geometry editor so the modal mounts without an OL map / @turf. */
@Component({ selector: 'app-geometry-editor', template: '' })
class StubGeometryEditor {
  readonly geometry = input<GeometryFeatureCollection | undefined>(undefined);
  readonly editType = input('features');
  readonly geometryChange = output<GeometryFeatureCollection>();
  readonly cancelled = output<void>();
}

class StubFacade {
  readonly editableNow = signal(true);
  readonly isEditor = signal(true);
  readonly message = signal<Message>({ id: 'm', parts: [positionsPart] });
  movePart = vi.fn();
  addPart = vi.fn();
  removePart = vi.fn();
  setPartType = vi.fn();
  setPartGeometry = vi.fn();
}

class StubFields {
  readonly showEventDates = signal(false);
  readonly showSubject = signal(false);
  readonly showPositions = signal(true);
  // Real signal so `isExpanded` is reactive — the row's inline editor is driven from it.
  private readonly positionsExpanded = signal(false);
  showPartEventDates = vi.fn(() => this.showEventDates());
  showPartSubject = vi.fn(() => this.showSubject());
  showPartPositions = vi.fn(() => this.showPositions());
  showPartType = vi.fn(() => true);
  isExpanded = vi.fn((fieldId: string) =>
    fieldId === 'positions' ? this.positionsExpanded() : false,
  );
  expand = vi.fn((fieldId: string) => {
    if (fieldId === 'positions') this.positionsExpanded.set(true);
  });
  collapse = vi.fn((fieldId: string) => {
    if (fieldId === 'positions') this.positionsExpanded.set(false);
  });
  toggle = vi.fn();
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
  partMoved = vi.fn();
  partAdded = vi.fn();
  partRemoved = vi.fn();
}

const languageStub = {
  selectedLanguage: signal('en'),
  modelLanguages: [{ value: 'en', label: 'English', flagPath: 'en.png' }],
};

const descStub = {
  descForLangOrDefault: (descs: MessageDescription[] | undefined, lang: string) =>
    descs?.find((d) => d.lang === lang) ?? descs?.[0],
};

@Component({
  imports: [PartCard],
  template: `<app-part-card [partIndex]="0" [part]="part()" [first]="true" [last]="true" />`,
})
class HostComponent {
  readonly part = signal<MessagePart>(positionsPart);
}

interface PartCardInternals {
  positionsModalOpen: () => boolean;
  openPositionsEditor: () => void;
  openPositionsAsText: () => void;
  onPositionsCommit: (fc: GeometryFeatureCollection) => void;
  onPositionsCancel: () => void;
}

async function setup() {
  const facade = new StubFacade();
  const fields = new StubFields();
  const geometry = {
    serializeReadableCoordinates: vi.fn(() => [{ lonLat: [9.5, 53.1], index: 0, name: 'Buoy' }]),
  };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    // Manual so the positions-editor @defer block is rendered explicitly (deterministic) in the
    // overlay-portal test; the other tests never trigger it.
    deferBlockBehavior: DeferBlockBehavior.Manual,
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideNiordIcons(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: LanguageService, useValue: languageStub },
      { provide: MessageDescService, useValue: descStub },
      { provide: MessageGeometryService, useValue: geometry },
      { provide: MessageDateFormatterService, useValue: { formatDateInterval: () => 'INTERVAL' } },
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
    ],
  });
  // Swap the heavy geometry editor for a light stub so the modal mounts without an OL map / @turf.
  TestBed.overrideComponent(PartCard, {
    remove: { imports: [GeometryEditor] },
    add: { imports: [StubGeometryEditor] },
  });
  // Overriding component metadata forces a JIT recompile — resolve it before creating the fixture.
  await TestBed.compileComponents();
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const host = fixture.componentInstance;
  const el = fixture.nativeElement as HTMLElement;
  const row = (fieldId: string) => el.querySelector(`[data-field-row="${fieldId}"]`);
  const partCard = fixture.debugElement.children[0]
    .componentInstance as unknown as PartCardInternals;
  return { fixture, host, facade, fields, geometry, el, row, partCard };
}

describe('PartCard', () => {
  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('collapses the positions row to the first serialized coordinate line, never the feature count', async () => {
    const { fixture, geometry, row } = await setup();
    await fixture.whenStable();
    const viewer = row('positions')!;
    // Collapsed: the one-line summary (first coordinate), no mini-map.
    expect(viewer.querySelector('app-ol-map')).toBeNull();
    expect(geometry.serializeReadableCoordinates).toHaveBeenCalled();
    expect(viewer.textContent).toContain('1)');
    expect(viewer.textContent).toContain('53°');
    expect(viewer.textContent).toContain('009°');
    // The "N feature(s)" count text is intentionally absent from the summary.
    expect(viewer.textContent).not.toContain('feature(s)');
    expect(viewer.textContent).not.toContain('editor.field.positions.count');
    // A single coordinate → no "(more…)" suffix.
    expect(viewer.textContent).not.toContain('editor.row.more');
  });

  it('appends the "(more…)" suffix when the part has more than one coordinate line', async () => {
    const { fixture, host, geometry, row } = await setup();
    geometry.serializeReadableCoordinates = vi.fn(() => [
      { lonLat: [9.5, 53.1], index: 0, name: 'Buoy' },
      { lonLat: [10.5, 54.1], index: 1, name: 'Wreck' },
    ]);
    // New part reference invalidates the coordinate computeds so the fresh mock is read.
    host.part.set({ ...positionsPart });
    await fixture.whenStable();
    const viewer = row('positions')!;
    expect(viewer.textContent).toContain('1)');
    expect(viewer.textContent).toContain('editor.row.more');
  });

  it('numbers the collapsed line from the stamped message-wide startCoordIndex, not per part', async () => {
    // A later part's features carry startCoordIndex > 1 from the facade's message-wide reindex —
    // the summary must continue that numbering, not restart at 1.
    const { fixture, host, row } = await setup();
    host.part.set({
      ...positionsPart,
      geometry: {
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [9.5, 53.1] },
            properties: { startCoordIndex: 5 },
          },
        ],
      },
    });
    await fixture.whenStable();
    const viewer = row('positions')!;
    expect(viewer.textContent).toContain('5)');
    expect(viewer.textContent).not.toContain('1)');
  });

  it('expands the positions row to the mini-map + coordinate table with Edit / Edit-as-text', async () => {
    const { fixture, fields, partCard, row } = await setup();
    fields.expand('positions');
    await fixture.whenStable();
    const rowEl = row('positions')!;
    // Expanded editor: the mini-map preview + the mono coordinate table.
    expect(rowEl.querySelector('app-ol-map')).not.toBeNull();
    expect(rowEl.querySelector('.ed-coords')).not.toBeNull();
    expect(rowEl.textContent).toContain('1)');
    expect(rowEl.textContent).toContain('Buoy');
    // Both geometry affordances are present, but expanding alone does NOT open the modal.
    expect(rowEl.textContent).toContain('editor.field.positions.edit');
    expect(rowEl.textContent).toContain('editor.field.positions.editAsText');
    expect(partCard.positionsModalOpen()).toBe(false);
    expect(document.querySelector('app-geometry-editor')).toBeNull();
  });

  it('shows the empty state (no map, empty label + head button) when the part has no geometry', async () => {
    const { fixture, host, row } = await setup();
    host.part.set(emptyPositionsPart);
    await fixture.whenStable();
    const viewer = row('positions')!;
    expect(viewer.querySelector('app-ol-map')).toBeNull();
    // The row head is a button for an editor, so clicking it expands to the editor and an empty part
    // can still gain geometry.
    expect(viewer.querySelector('[role="button"]')).not.toBeNull();
  });

  it('renders the header actions (move up/down, remove) but no per-card add button', async () => {
    const { fixture, el } = await setup();
    await fixture.whenStable();
    expect(el.querySelector('[aria-label="editor.part.moveUp"]')).not.toBeNull();
    expect(el.querySelector('[aria-label="editor.part.moveDown"]')).not.toBeNull();
    expect(el.querySelector('[aria-label="editor.part.remove"]')).not.toBeNull();
    // Add moved to the Parts panel head — no add affordance on the card.
    expect(el.querySelector('[aria-label="editor.part.add"]')).toBeNull();
  });

  it('wires the header grip as a decorative cdkDragHandle', async () => {
    const { fixture, el } = await setup();
    await fixture.whenStable();
    // The directive applies its `cdk-drag-handle` host class at runtime — proof the handle is active.
    const handle = el.querySelector('.cdk-drag-handle');
    expect(handle).not.toBeNull();
    // Stays hidden from assistive tech (keyboard reorder remains the move buttons).
    expect(handle?.getAttribute('aria-hidden')).toBe('true');
  });

  it('always renders the description row; gates event-dates / subject / positions on the predicates', async () => {
    const { fixture, fields, row } = await setup();
    await fixture.whenStable();
    expect(row('description')).not.toBeNull();
    expect(row('positions')).not.toBeNull();
    expect(row('event_dates')).toBeNull();
    expect(row('subject')).toBeNull();

    fields.showEventDates.set(true);
    fields.showSubject.set(true);
    await fixture.whenStable();
    expect(row('event_dates')).not.toBeNull();
    expect(row('subject')).not.toBeNull();
  });

  it('orders the body rows: event dates, positions, subject, description', async () => {
    const { fixture, fields, el } = await setup();
    fields.showEventDates.set(true);
    fields.showSubject.set(true);
    await fixture.whenStable();
    const order = Array.from(el.querySelectorAll('[data-field-row]')).map((e) =>
      e.getAttribute('data-field-row'),
    );
    expect(order.indexOf('event_dates')).toBeLessThan(order.indexOf('positions'));
    expect(order.indexOf('positions')).toBeLessThan(order.indexOf('subject'));
    expect(order.indexOf('subject')).toBeLessThan(order.indexOf('description'));
  });

  it('opens the geometry editor modal from the Edit affordance and portals it to the body overlay', async () => {
    const { fixture, partCard } = await setup();
    await fixture.whenStable();

    // Nothing portalled before Edit is triggered.
    expect(document.querySelector('app-geometry-editor')).toBeNull();

    partCard.openPositionsEditor();
    expect(partCard.positionsModalOpen()).toBe(true);
    await fixture.whenStable();
    const [deferBlock] = await fixture.getDeferBlocks();
    await deferBlock.render(DeferBlockState.Complete);
    await fixture.whenStable();

    // The editor lands in the CDK overlay (portalled to the body), not the component's light DOM.
    expect(document.querySelector('app-geometry-editor')).not.toBeNull();
  });

  it('commits the edited geometry through the facade and closes the modal', async () => {
    const { fixture, facade, partCard } = await setup();
    await fixture.whenStable();

    partCard.openPositionsEditor();
    const committed = pointFc(10, 56);
    partCard.onPositionsCommit(committed);
    await fixture.whenStable();

    expect(facade.setPartGeometry).toHaveBeenCalledWith(0, committed);
    // Committing closes the modal.
    expect(partCard.positionsModalOpen()).toBe(false);
  });

  it('cancel closes the modal and writes nothing through the facade', async () => {
    const { fixture, facade, partCard } = await setup();
    await fixture.whenStable();

    partCard.openPositionsEditor();
    partCard.onPositionsCancel();
    await fixture.whenStable();

    expect(facade.setPartGeometry).not.toHaveBeenCalled();
    expect(partCard.positionsModalOpen()).toBe(false);
  });
});
