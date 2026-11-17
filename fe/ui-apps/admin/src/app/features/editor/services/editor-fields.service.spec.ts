import { signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  emptyFeatureCollection,
  type Message,
  type MessagePart,
} from '../../messages/models/message.model';
import { EditorFacade } from './editor-facade';
import { EditorFieldsService } from './editor-fields.service';

interface FacadeStub {
  message: WritableSignal<Message | undefined>;
  loadedBaseline: WritableSignal<Message | undefined>;
  isEditor: WritableSignal<boolean>;
}

function setup(opts?: { editor?: boolean; message?: Message; baseline?: Message }) {
  const facade: FacadeStub = {
    message: signal<Message | undefined>(opts?.message),
    loadedBaseline: signal<Message | undefined>(opts?.baseline),
    isEditor: signal(opts?.editor ?? true),
  };

  TestBed.configureTestingModule({
    providers: [EditorFieldsService, { provide: EditorFacade, useValue: facade }],
  });

  return { service: TestBed.inject(EditorFieldsService), facade };
}

const partsMsg = (count: number): Message => ({
  id: 'm',
  status: 'DRAFT',
  parts: Array.from({ length: count }, (_, i) => ({ type: 'DETAILS', indexNo: i })),
});

const fieldsMsg = (editorFields: Record<string, boolean>, extra?: Partial<Message>): Message => ({
  id: 'm',
  status: 'DRAFT',
  parts: [{ type: 'DETAILS', indexNo: 0 }],
  editorFields,
  ...extra,
});

const partWithPoint = (): MessagePart => ({
  type: 'NOTE',
  indexNo: 0,
  geometry: {
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [1, 2] } },
    ],
  },
});

// The static opt-in field universe, in the exact field-layout order the opt-in picker must
// reproduce. The always-shown core rows (type / id / title / areas / categories / publish_date) are
// absent — they render unconditionally and are never opt-in-able.
const FIELD_UNIVERSE_ORDER = [
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
];

describe('EditorFieldsService', () => {
  describe('per-field expand state', () => {
    it('toggle / expand / collapse flip a single field for an editor', () => {
      const { service } = setup({ message: partsMsg(1) });
      TestBed.tick();

      expect(service.isExpanded('title')).toBe(false);
      service.toggle('title');
      expect(service.isExpanded('title')).toBe(true);
      service.collapse('title');
      expect(service.isExpanded('title')).toBe(false);
      service.expand('title');
      expect(service.isExpanded('title')).toBe(true);
    });

    it('toggle / expand / collapse are no-ops for a non-editor', () => {
      const { service } = setup({ editor: false, message: partsMsg(1) });
      TestBed.tick();

      service.toggle('title');
      service.expand('subject', 0);
      expect(service.isExpanded('title')).toBe(false);
      expect(service.isExpanded('subject', 0)).toBe(false);
    });

    it('collapses everything when a DIFFERENT message loads; keeps state for a same-id reload', () => {
      const first: Message = { ...partsMsg(1), id: 'm-1' };
      const { service, facade } = setup({ message: first, baseline: first });
      TestBed.tick();

      service.expand('title');
      expect(service.isExpanded('title')).toBe(true);

      // Post-save reload: same id, new baseline object — open rows survive.
      facade.loadedBaseline.set({ ...first });
      TestBed.tick();
      expect(service.isExpanded('title')).toBe(true);

      // A different message (or the shell reset clearing the baseline) starts collapsed.
      facade.loadedBaseline.set({ ...partsMsg(1), id: 'm-2' });
      TestBed.tick();
      expect(service.isExpanded('title')).toBe(false);

      service.expand('title');
      facade.loadedBaseline.set(undefined);
      TestBed.tick();
      expect(service.isExpanded('title')).toBe(false);
    });

    it('expandAll / collapseAll flip every field, including part-indexed ones', () => {
      const { service } = setup({ message: partsMsg(2) });
      TestBed.tick();

      service.expandAll();
      expect(service.isExpanded('type')).toBe(true);
      expect(service.isExpanded('description', 0)).toBe(true);
      expect(service.isExpanded('description', 1)).toBe(true);

      service.collapseAll();
      expect(service.isExpanded('type')).toBe(false);
      expect(service.isExpanded('description', 0)).toBe(false);
      expect(service.isExpanded('description', 1)).toBe(false);
    });
  });

  describe('part-indexed lock-step', () => {
    it('partAdded / partRemoved / partMoved splice the part-indexed arrays positionally', () => {
      const { service } = setup({ message: partsMsg(3) });
      TestBed.tick();

      service.expand('subject', 1);
      expect(service.editMode()['subject']).toEqual([false, true, false]);

      service.partAdded(0);
      expect(service.editMode()['subject']).toEqual([false, false, true, false]);

      service.partRemoved(0);
      expect(service.editMode()['subject']).toEqual([false, true, false]);

      service.partMoved(1, 2);
      expect(service.editMode()['subject']).toEqual([false, false, true]);

      // All four part-indexed fields move in lock-step.
      expect(service.editMode()['positions']).toHaveLength(3);
      expect(service.editMode()['event_dates']).toHaveLength(3);
      expect(service.editMode()['description']).toHaveLength(3);
    });

    it('the defensive effect resizes the arrays when the message part count drifts', () => {
      const { service, facade } = setup({ message: partsMsg(1) });
      TestBed.tick();

      service.expand('positions', 0);
      expect(service.editMode()['positions']).toEqual([true]);

      // A reload with more parts, bypassing the explicit splice seam — only the effect fires.
      facade.message.set(partsMsg(3));
      TestBed.tick();
      expect(service.editMode()['positions']).toEqual([true, false, false]);

      // …and back down again, preserving the surviving entry.
      facade.message.set(partsMsg(1));
      TestBed.tick();
      expect(service.editMode()['positions']).toEqual([true]);
    });
  });

  describe('edited() baseline compare', () => {
    it('is false while the working copy matches the baseline', () => {
      const base: Message = {
        id: 'm',
        descs: [{ lang: 'en', title: 'A' }],
        parts: [{ indexNo: 0, descs: [{ lang: 'en', subject: 'S0' }] }],
      };
      const { service } = setup({ message: structuredClone(base), baseline: base });
      TestBed.tick();

      expect(service.edited('title')).toBe(false);
      expect(service.edited('subject', 0)).toBe(false);
      expect(service.edited('positions', 0)).toBe(false);
    });

    it('flips a flat per-language field when one language desc changes', () => {
      const base: Message = {
        id: 'm',
        descs: [
          { lang: 'en', title: 'A', source: 'Src' },
          { lang: 'da', title: 'B' },
        ],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      facade.message.set({
        id: 'm',
        descs: [
          { lang: 'en', title: 'CHANGED', source: 'Src' },
          { lang: 'da', title: 'B' },
        ],
      });
      expect(service.edited('title')).toBe(true);
      // `source` slices the same descs but is unchanged — its dot must stay off.
      expect(service.edited('source')).toBe(false);
    });

    it('flips a part-indexed per-language field for the changed part only', () => {
      const base: Message = {
        id: 'm',
        parts: [
          { indexNo: 0, descs: [{ lang: 'en', subject: 'S0' }] },
          { indexNo: 1, descs: [{ lang: 'en', subject: 'S1' }] },
        ],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      facade.message.set({
        id: 'm',
        parts: [
          { indexNo: 0, descs: [{ lang: 'en', subject: 'CHANGED' }] },
          { indexNo: 1, descs: [{ lang: 'en', subject: 'S1' }] },
        ],
      });
      expect(service.edited('subject', 0)).toBe(true);
      expect(service.edited('subject', 1)).toBe(false);
    });

    it('flips positions when a part geometry changes', () => {
      const base: Message = {
        id: 'm',
        parts: [{ indexNo: 0, geometry: emptyFeatureCollection() }],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();
      expect(service.edited('positions', 0)).toBe(false);

      facade.message.set({
        id: 'm',
        parts: [
          {
            indexNo: 0,
            geometry: {
              type: 'FeatureCollection',
              features: [
                {
                  type: 'Feature',
                  properties: {},
                  geometry: { type: 'Point', coordinates: [1, 2] },
                },
              ],
            },
          },
        ],
      });
      expect(service.edited('positions', 0)).toBe(true);
    });

    it('does NOT flag any part field when parts are only REORDERED (identity follows the part)', () => {
      const base: Message = {
        id: 'm',
        parts: [
          {
            indexNo: 0,
            descs: [{ lang: 'en', subject: 'S0' }],
            geometry: emptyFeatureCollection(),
          },
          {
            indexNo: 1,
            descs: [{ lang: 'en', subject: 'S1' }],
            geometry: emptyFeatureCollection(),
          },
        ],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      // Swap the two parts and renumber indexNo — exactly what movePartTo + reindexParts produces.
      facade.message.set({
        id: 'm',
        parts: [
          {
            indexNo: 0,
            descs: [{ lang: 'en', subject: 'S1' }],
            geometry: emptyFeatureCollection(),
          },
          {
            indexNo: 1,
            descs: [{ lang: 'en', subject: 'S0' }],
            geometry: emptyFeatureCollection(),
          },
        ],
      });

      // No field lights up — the values only moved, none changed.
      expect(service.edited('subject', 0)).toBe(false);
      expect(service.edited('subject', 1)).toBe(false);
      expect(service.edited('positions', 0)).toBe(false);
      expect(service.edited('positions', 1)).toBe(false);
    });

    it('flags only the CHANGED field on a part that was both reordered and edited', () => {
      const base: Message = {
        id: 'm',
        parts: [
          { indexNo: 0, descs: [{ lang: 'en', subject: 'S0' }] },
          { indexNo: 1, descs: [{ lang: 'en', subject: 'S1' }] },
        ],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      // Move part 1 to the front AND edit its subject; the old part 0 slides to index 1 unchanged.
      facade.message.set({
        id: 'm',
        parts: [
          { indexNo: 0, descs: [{ lang: 'en', subject: 'S1-EDITED' }] },
          { indexNo: 1, descs: [{ lang: 'en', subject: 'S0' }] },
        ],
      });

      expect(service.edited('subject', 0)).toBe(true); // the moved + edited part
      expect(service.edited('subject', 1)).toBe(false); // the untouched (only moved) part
    });

    it('does NOT dot a reordered part whose geometry startCoordIndex was re-threaded (the REAL reorder)', () => {
      // commitParts runs reindexStartCoordIndices on every commit, so a reorder re-threads the
      // message-wide `startCoordIndex` across BOTH parts' geometry even though nothing else changed.
      const feat = (idx: number, x: number) => ({
        type: 'Feature' as const,
        properties: { startCoordIndex: idx },
        geometry: { type: 'Point' as const, coordinates: [x, 55] },
      });
      const base: Message = {
        id: 'm',
        parts: [
          {
            type: 'DETAILS',
            indexNo: 0,
            descs: [{ lang: 'en', subject: 'A' }],
            geometry: { type: 'FeatureCollection', features: [feat(1, 10)] },
          },
          {
            type: 'DETAILS',
            indexNo: 1,
            descs: [{ lang: 'en', subject: 'B' }],
            geometry: { type: 'FeatureCollection', features: [feat(2, 11)] },
          },
        ],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      // Swap the parts AND re-thread startCoordIndex (feat idx 1↔2) — exactly what the facade does.
      facade.message.set({
        id: 'm',
        parts: [
          {
            type: 'DETAILS',
            indexNo: 0,
            descs: [{ lang: 'en', subject: 'B' }],
            geometry: { type: 'FeatureCollection', features: [feat(1, 11)] },
          },
          {
            type: 'DETAILS',
            indexNo: 1,
            descs: [{ lang: 'en', subject: 'A' }],
            geometry: { type: 'FeatureCollection', features: [feat(2, 10)] },
          },
        ],
      });

      expect(service.edited('subject', 0)).toBe(false);
      expect(service.edited('subject', 1)).toBe(false);
      expect(service.edited('positions', 0)).toBe(false);
      expect(service.edited('positions', 1)).toBe(false);
    });

    it('does NOT flip a per-language field when an unrelated change pads descs with a blank language', () => {
      // mergeDescs rebuilds descs on EVERY form change (e.g. toggling separatePage), padding a blank
      // `da` desc. Per-language slices must ignore that empty padding so Source/Publication stay clean.
      const base: Message = {
        id: 'm',
        descs: [{ lang: 'en', source: 'EMA', publication: 'PubX' }],
      };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      facade.message.set({
        id: 'm',
        separatePage: true,
        descs: [
          { lang: 'en', source: 'EMA', publication: 'PubX' },
          { lang: 'da', source: undefined, publication: '' },
        ],
      });

      expect(service.edited('source')).toBe(false);
      expect(service.edited('publication')).toBe(false);
    });

    it('clears once the baseline is re-set to the working copy (save-reload semantics)', () => {
      const base: Message = { id: 'm', descs: [{ lang: 'en', title: 'A' }] };
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      const editedState: Message = { id: 'm', descs: [{ lang: 'en', title: 'A2' }] };
      facade.message.set(editedState);
      expect(service.edited('title')).toBe(true);

      // applyLoaded re-sets both the working message and the baseline to the saved state.
      facade.loadedBaseline.set(editedState);
      facade.message.set(structuredClone(editedState));
      expect(service.edited('title')).toBe(false);
    });

    it('is false with no message or no baseline', () => {
      const { service } = setup();
      TestBed.tick();
      expect(service.edited('title')).toBe(false);
    });
  });

  describe('roving-tabindex focus registry', () => {
    let a: HTMLElement;
    let b: HTMLElement;
    let c: HTMLElement;

    beforeEach(() => {
      a = document.createElement('button');
      b = document.createElement('button');
      c = document.createElement('button');
      document.body.append(a, b, c);
    });

    afterEach(() => {
      document.body.replaceChildren();
    });

    it('gives the first registered label the tab stop and removes the rest from the tab order', () => {
      const { service } = setup();
      service.registerLabel(a);
      service.registerLabel(b);
      service.registerLabel(c);

      expect(a.getAttribute('tabindex')).toBe('0');
      expect(b.getAttribute('tabindex')).toBe('-1');
      expect(c.getAttribute('tabindex')).toBe('-1');
    });

    it('focusNext / focusPrevious move the tab stop and DOM focus in document order', () => {
      const { service } = setup();
      [a, b, c].forEach((el) => service.registerLabel(el));

      service.focusNext(a);
      expect(a.getAttribute('tabindex')).toBe('-1');
      expect(b.getAttribute('tabindex')).toBe('0');
      expect(document.activeElement).toBe(b);

      service.focusNext(b);
      expect(document.activeElement).toBe(c);

      // No wrap past the ends.
      service.focusNext(c);
      expect(document.activeElement).toBe(c);

      service.focusPrevious(c);
      expect(document.activeElement).toBe(b);
    });

    it('navigates by document order regardless of registration order', () => {
      const { service } = setup();
      service.registerLabel(c);
      service.registerLabel(a);
      service.registerLabel(b);

      service.focusNext(a);
      expect(document.activeElement).toBe(b);
    });

    it('hands the tab stop to a DOM neighbour when the active label unregisters', () => {
      const { service } = setup();
      [a, b, c].forEach((el) => service.registerLabel(el));
      service.focusNext(a); // b is active

      expect(b.getAttribute('tabindex')).toBe('0');
      service.unregisterLabel(b);
      expect(c.getAttribute('tabindex')).toBe('0');
    });
  });

  describe('showField engine — map / opt-in truth table', () => {
    it('shows a field the message map turns on', () => {
      const { service } = setup({ message: fieldsMsg({ source: true }) });
      TestBed.tick();
      expect(service.showField('source')).toBe(true);
    });

    it('hides a field the map turns off, with no session opt-in', () => {
      const { service } = setup({ message: fieldsMsg({ source: false }) });
      TestBed.tick();
      expect(service.showField('source')).toBe(false);
    });

    it('hides a field the map omits entirely, with no session opt-in', () => {
      const { service } = setup({ message: fieldsMsg({ title: true }) });
      TestBed.tick();
      expect(service.showField('source')).toBe(false);
    });

    it('shows a map-off field once it is opted in this session', () => {
      const { service } = setup({ message: fieldsMsg({ source: false }) });
      TestBed.tick();
      expect(service.showField('source')).toBe(false);
      service.toggleOptIn('source');
      expect(service.showField('source')).toBe(true);
    });

    it('shows a map-missing field once it is opted in this session', () => {
      const { service } = setup({ message: fieldsMsg({ title: true }) });
      TestBed.tick();
      service.toggleOptIn('source');
      expect(service.showField('source')).toBe(true);
    });

    it('toggleOptIn flips an opt-in off again', () => {
      const { service } = setup({ message: fieldsMsg({ source: false }) });
      TestBed.tick();
      service.toggleOptIn('source');
      service.toggleOptIn('source');
      expect(service.showField('source')).toBe(false);
    });

    it('treats an absent map defensively as everything hidden', () => {
      const { service } = setup({ message: { id: 'm', status: 'DRAFT' } });
      TestBed.tick();
      expect(service.showField('source')).toBe(false);
    });
  });

  describe('showField engine — time ⇔ event_dates aliasing', () => {
    it('accepts a map `time` key for an `event_dates` gate and vice versa', () => {
      const { service } = setup({ message: fieldsMsg({ time: true }) });
      TestBed.tick();
      expect(service.showField('event_dates')).toBe(true);
      expect(service.showField('time')).toBe(true);
    });

    it('accepts a map `event_dates` key for a `time` gate', () => {
      const { service } = setup({ message: fieldsMsg({ event_dates: true }) });
      TestBed.tick();
      expect(service.showField('time')).toBe(true);
      expect(service.showField('event_dates')).toBe(true);
    });

    it('opting in either spelling satisfies both gates and stores one canonical entry', () => {
      const { service } = setup({ message: fieldsMsg({ time: false }) });
      TestBed.tick();
      service.toggleOptIn('time');
      expect(service.showField('event_dates')).toBe(true);
      expect(service.showField('time')).toBe(true);
      // Canonical storage means one more toggle (via the other spelling) turns it back off.
      service.toggleOptIn('event_dates');
      expect(service.showField('time')).toBe(false);
    });
  });

  describe('opt-in session lifecycle', () => {
    it('resets opt-ins on a fresh load (new loaded-baseline identity)', () => {
      const base = fieldsMsg({ note: false });
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      service.toggleOptIn('note');
      expect(service.showField('note')).toBe(true);

      // A fresh load stamps a new baseline identity — the reset boundary.
      facade.loadedBaseline.set(fieldsMsg({ note: false }));
      TestBed.tick();
      expect(service.showField('note')).toBe(false);
    });

    it('preserves an opt-in across an adjust that still hides the field', () => {
      const base = fieldsMsg({ note: false });
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();

      service.toggleOptIn('note');
      expect(service.showField('note')).toBe(true);

      // Adjust re-sends the map on the message signal WITHOUT touching the baseline: the opt-in lives.
      facade.message.set(fieldsMsg({ note: false }));
      TestBed.tick();
      expect(service.showField('note')).toBe(true);
    });

    it('hides a server-driven field the adjust map no longer turns on (never opted in)', () => {
      const base = fieldsMsg({ source: true });
      const { service, facade } = setup({ message: base, baseline: base });
      TestBed.tick();
      expect(service.showField('source')).toBe(true);

      facade.message.set(fieldsMsg({ source: false }));
      TestBed.tick();
      expect(service.showField('source')).toBe(false);
    });
  });

  describe('sticky-visible references / charts', () => {
    it('shows references via the gate, or whenever the message already carries references', () => {
      const { service, facade } = setup({ message: fieldsMsg({ references: false }) });
      TestBed.tick();
      expect(service.showReferencesField()).toBe(false);

      facade.message.set(
        fieldsMsg({ references: false }, { references: [{ messageId: 'x', type: 'REFERENCE' }] }),
      );
      expect(service.showReferencesField()).toBe(true);

      // Gate alone still shows it with an empty collection.
      facade.message.set(fieldsMsg({ references: true }));
      expect(service.showReferencesField()).toBe(true);
    });

    it('shows charts via the gate, or whenever the message already carries charts', () => {
      const { service, facade } = setup({ message: fieldsMsg({ charts: false }) });
      TestBed.tick();
      expect(service.showChartsField()).toBe(false);

      facade.message.set(fieldsMsg({ charts: false }, { charts: [{ chartNumber: '101' }] }));
      expect(service.showChartsField()).toBe(true);

      facade.message.set(fieldsMsg({ charts: true }));
      expect(service.showChartsField()).toBe(true);
    });
  });

  describe('part-field predicates', () => {
    describe('showPartType', () => {
      it('shows the DETAILS/TIME/POSITIONS/NOTE group when `part_types` is on', () => {
        const { service } = setup({ message: fieldsMsg({ part_types: true }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'DETAILS' }, 'part_types')).toBe(true);
      });

      it('hides the group when `part_types` is present but off (and the part is not that type)', () => {
        const { service } = setup({ message: fieldsMsg({ part_types: false }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'DETAILS' }, 'part_types')).toBe(false);
      });

      it('tolerates a missing `part_types` key by rendering the group anyway', () => {
        const { service } = setup({ message: fieldsMsg({ title: true }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'DETAILS' }, 'part_types')).toBe(true);
      });

      it('always shows a selector for the type the part already has (PROHIBITION / SIGNALS)', () => {
        const { service } = setup({ message: fieldsMsg({ prohibition: false, signals: false }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'PROHIBITION' }, 'prohibition')).toBe(true);
        expect(service.showPartType({ type: 'SIGNALS' }, 'signals')).toBe(true);
      });

      it('shows an individually-gated selector when its gate is on for another part type', () => {
        const { service } = setup({ message: fieldsMsg({ prohibition: true }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'DETAILS' }, 'prohibition')).toBe(true);
      });

      it('hides an individually-gated selector when its gate is off and the part is another type', () => {
        const { service } = setup({ message: fieldsMsg({ prohibition: false }) });
        TestBed.tick();
        expect(service.showPartType({ type: 'DETAILS' }, 'prohibition')).toBe(false);
      });
    });

    describe('showPartEventDates', () => {
      it('shows whenever the part already carries intervals, regardless of gate/type', () => {
        const { service } = setup({ message: fieldsMsg({ event_dates: false }) });
        TestBed.tick();
        expect(
          service.showPartEventDates({ type: 'POSITIONS', eventDates: [{ fromDate: '2026' }] }),
        ).toBe(true);
      });

      it('shows for a DETAILS or TIME part when the gate is on (via either spelling)', () => {
        const { service } = setup({ message: fieldsMsg({ time: true }) });
        TestBed.tick();
        expect(service.showPartEventDates({ type: 'DETAILS' })).toBe(true);
        expect(service.showPartEventDates({ type: 'TIME' })).toBe(true);
      });

      it('hides for a non-DETAILS/TIME part even when the gate is on and no intervals exist', () => {
        const { service } = setup({ message: fieldsMsg({ event_dates: true }) });
        TestBed.tick();
        expect(service.showPartEventDates({ type: 'POSITIONS' })).toBe(false);
      });

      it('hides for a DETAILS part when the gate is off and no intervals exist', () => {
        const { service } = setup({ message: fieldsMsg({ event_dates: false }) });
        TestBed.tick();
        expect(service.showPartEventDates({ type: 'DETAILS' })).toBe(false);
      });
    });

    describe('showPartPositions', () => {
      it('shows for DETAILS, POSITIONS and SIGNALS parts', () => {
        const { service } = setup({ message: partsMsg(1) });
        TestBed.tick();
        expect(service.showPartPositions({ type: 'DETAILS' })).toBe(true);
        expect(service.showPartPositions({ type: 'POSITIONS' })).toBe(true);
        expect(service.showPartPositions({ type: 'SIGNALS' })).toBe(true);
      });

      it('shows for any other part type that already carries geometry features', () => {
        const { service } = setup({ message: partsMsg(1) });
        TestBed.tick();
        expect(service.showPartPositions(partWithPoint())).toBe(true);
      });

      it('hides for another part type with empty geometry', () => {
        const { service } = setup({ message: partsMsg(1) });
        TestBed.tick();
        expect(
          service.showPartPositions({ type: 'NOTE', geometry: emptyFeatureCollection() }),
        ).toBe(false);
        expect(service.showPartPositions({ type: 'TIME' })).toBe(false);
      });
    });

    describe('showPartSubject / showPartDescription', () => {
      it('shows the subject only for a DETAILS part', () => {
        const { service } = setup({ message: partsMsg(1) });
        TestBed.tick();
        expect(service.showPartSubject({ type: 'DETAILS' })).toBe(true);
        expect(service.showPartSubject({ type: 'TIME' })).toBe(false);
        expect(service.showPartSubject({ type: 'POSITIONS' })).toBe(false);
      });

      it('always shows the description', () => {
        const { service } = setup({ message: partsMsg(1) });
        TestBed.tick();
        expect(service.showPartDescription()).toBe(true);
      });
    });
  });

  describe('manage optional fields (the non-default field list)', () => {
    it('lists the full universe in field-layout order when the map shows nothing by default', () => {
      const { service } = setup({ message: fieldsMsg({}) });
      TestBed.tick();
      expect(service.manageableFields()).toEqual(FIELD_UNIVERSE_ORDER);
    });

    it('never lists the always-shown core fields, even when the map carries them present-but-false', () => {
      // The backend map explicitly turning the core fields off must NOT leak them into the list
      // via the present-but-false extras path — they render unconditionally regardless.
      const { service } = setup({
        message: fieldsMsg({
          type: false,
          id: false,
          title: false,
          areas: false,
          categories: false,
          publish_date: false,
        }),
      });
      TestBed.tick();
      const manageable = service.manageableFields();
      for (const core of ['type', 'id', 'title', 'areas', 'categories', 'publish_date']) {
        expect(manageable).not.toContain(core);
      }
    });

    it('excludes fields the backend map shows by default — only non-default fields are listed', () => {
      const { service } = setup({ message: fieldsMsg({ references: true, charts: false }) });
      TestBed.tick();
      const manageable = service.manageableFields();
      expect(manageable).not.toContain('references'); // shown by default (map true) → not optional here
      expect(manageable).toContain('charts'); // present-but-false → non-default, listed
      expect(manageable).toContain('source'); // map-absent → non-default, listed
      // Order still follows the universe (charts before source, source before layout).
      expect(manageable.indexOf('charts')).toBeLessThan(manageable.indexOf('source'));
      expect(manageable.indexOf('source')).toBeLessThan(manageable.indexOf('layout'));
    });

    it('KEEPS an opted-in field in the list (so it can be removed) and marks it shown', () => {
      const { service } = setup({ message: fieldsMsg({}) });
      TestBed.tick();
      expect(service.manageableFields()).toContain('source');
      expect(service.showField('source')).toBe(false);

      service.toggleOptIn('source'); // add
      expect(service.manageableFields()).toContain('source'); // stays in the list…
      expect(service.showField('source')).toBe(true); // …now marked shown (the dot)

      service.toggleOptIn('source'); // remove
      expect(service.showField('source')).toBe(false);
      expect(service.manageableFields()).toContain('source');
    });

    it('collapses a map `time` key into the single `event_dates` entry (no duplicate)', () => {
      const { service } = setup({ message: fieldsMsg({ time: false }) });
      TestBed.tick();
      const manageable = service.manageableFields();
      expect(manageable).toContain('event_dates');
      expect(manageable).not.toContain('time');
      expect(manageable.filter((id) => id === 'event_dates')).toHaveLength(1);
    });

    it('appends present-but-false keys the universe does not cover, after the ordered universe', () => {
      const { service } = setup({ message: fieldsMsg({ mailing_list: false }) });
      TestBed.tick();
      const manageable = service.manageableFields();
      expect(manageable).toContain('mailing_list');
      expect(manageable.indexOf('mailing_list')).toBe(manageable.length - 1);
      expect(manageable.indexOf('mailing_list')).toBeGreaterThan(manageable.indexOf('layout'));
    });

    it('never offers `note` — the NOTE part type is reached via the part-type switcher, not a field', () => {
      // Even when the backend map carries `note` present-but-false (the extras path), it must not
      // leak into the list: a "Note" opt-in would reveal no field row (a phantom).
      const { service } = setup({ message: fieldsMsg({ note: false }) });
      TestBed.tick();
      expect(service.manageableFields()).not.toContain('note');
    });
  });
});
