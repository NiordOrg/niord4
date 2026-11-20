import type { Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type Observable, of, Subject, throwError } from 'rxjs';

import { GeometryApiService } from '../../../api/geometry-api.service';
import type { GeometryFeatureCollection } from '../../../models/geometry.model';
import { DiscardConfirmService } from '../../../services/discard-confirm.service';
import { provideTestTransloco } from '../../../testing/transloco-testing';
import type { HierarchicalEntity } from '../../entity-tree/hierarchical-entity.model';
import { AREA_GEOMETRY_SOURCE, type AreaGeometrySource } from '../area-geometry-source.token';
import { GeometryImportDialog } from './geometry-import-dialog';

/** A one-point collection; `lon` tells the collections apart. */
function fcAt(lon: number): GeometryFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [lon, 56] } },
    ],
  };
}

function area(id: number): HierarchicalEntity {
  return { id, descs: [{ lang: 'en', name: `Area ${id}` }] };
}

/**
 * Every backend call answers through a Subject the test holds, so the test decides when (and in
 * what order) the answers arrive.
 */
function setup(
  opts: {
    withAreaSource?: boolean;
    getRoots?: () => Observable<readonly HierarchicalEntity[]>;
  } = {},
) {
  const textCalls: { text: string; answer: Subject<GeometryFeatureCollection> }[] = [];
  const utmCalls: { text: string; answer: Subject<GeometryFeatureCollection> }[] = [];
  const areaCalls: { id: number | string; answer: Subject<GeometryFeatureCollection> }[] = [];

  const geometryApi = {
    parseGeometry: vi.fn((text: string) => {
      const answer = new Subject<GeometryFeatureCollection>();
      textCalls.push({ text, answer });
      return answer;
    }),
    parseUtm: vi.fn((text: string) => {
      const answer = new Subject<GeometryFeatureCollection>();
      utmCalls.push({ text, answer });
      return answer;
    }),
  };
  const areaSource: AreaGeometrySource = {
    getRoots: opts.getRoots ?? (() => of([area(1), area(2)])),
    getGeometry: (a) => {
      const answer = new Subject<GeometryFeatureCollection>();
      areaCalls.push({ id: a.id, answer });
      return answer;
    },
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: GeometryApiService, useValue: geometryApi },
      { provide: DiscardConfirmService, useValue: { confirmDiscard: async () => true } },
      ...(opts.withAreaSource ? [{ provide: AREA_GEOMETRY_SOURCE, useValue: areaSource }] : []),
    ],
  });

  const fixture = TestBed.createComponent(GeometryImportDialog);
  fixture.detectChanges();
  const comp = fixture.componentInstance;
  const imported: GeometryFeatureCollection[] = [];
  comp.imported.subscribe((fc) => imported.push(fc));

  return { fixture, comp, textCalls, utmCalls, areaCalls, imported };
}

/** Answers the call's Subject once and completes it, as an HTTP call does. */
function answer(
  call: { answer: Subject<GeometryFeatureCollection> },
  fc: GeometryFeatureCollection,
) {
  call.answer.next(fc);
  call.answer.complete();
}

// Protected members reached through a cast (same pattern as sibling specs).
interface Internals {
  reset: () => void;
  onTabChange: (value: unknown) => void;
  onTextInput: (text: string) => void;
  onUtmInput: (text: string) => void;
  onAreaSelected: (a: HierarchicalEntity) => void;
  confirm: () => void;
  areaRootsFailed: Signal<boolean>;
  areaPickFailed: Signal<boolean>;
  importDisabled: Signal<boolean>;
  featureCollection: Signal<GeometryFeatureCollection>;
}

function internals(comp: GeometryImportDialog): Internals {
  return comp as unknown as Internals;
}

/** The longitude of the working set's one point, or null when it is empty. */
function lonOf(fc: GeometryFeatureCollection): number | null {
  const g = fc.features[0]?.geometry;
  return g?.type === 'Point' ? g.coordinates[0] : null;
}

describe('GeometryImportDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('From Text: Import waits for the parse of the text on screen', () => {
    it('is disabled from a change until its parse answers, and then imports the new positions', () => {
      const { comp, textCalls, imported } = setup();
      const d = internals(comp);
      d.reset();

      d.onTextInput('first');
      vi.advanceTimersByTime(500);
      answer(textCalls[0], fcAt(10));
      expect(d.importDisabled()).toBe(false);

      // A change: the working set still holds the first text's point, but Import must wait.
      d.onTextInput('second');
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);

      // The debounce has run and the call is out: still waiting.
      vi.advanceTimersByTime(500);
      expect(textCalls.map((c) => c.text)).toEqual(['first', 'second']);
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);

      answer(textCalls[1], fcAt(20));
      expect(d.importDisabled()).toBe(false);
      d.confirm();
      expect(imported.map(lonOf)).toEqual([20]);
    });

    it('an answer for the text before a change, landing after it, neither ends the wait nor counts', () => {
      const { comp, textCalls, imported } = setup();
      const d = internals(comp);
      d.reset();

      d.onTextInput('first');
      vi.advanceTimersByTime(500);
      // The first parse is out; the user changes the text before it answers.
      d.onTextInput('second');
      answer(textCalls[0], fcAt(10));

      expect(lonOf(d.featureCollection())).toBeNull();
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);

      vi.advanceTimersByTime(500);
      answer(textCalls[1], fcAt(20));
      d.confirm();
      expect(imported.map(lonOf)).toEqual([20]);
    });

    it('a change to a text that does not parse never imports the previous text', () => {
      const { comp, textCalls, imported } = setup();
      const d = internals(comp);
      d.reset();

      d.onTextInput('good');
      vi.advanceTimersByTime(500);
      answer(textCalls[0], fcAt(10));

      d.onTextInput('not a geometry');
      d.confirm();
      vi.advanceTimersByTime(500);
      d.confirm();
      textCalls[1].answer.error(new Error('400'));

      expect(lonOf(d.featureCollection())).toBeNull();
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);
    });

    it('a late text answer after a switch to another tab is ignored', () => {
      const { comp, textCalls } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();
      d.onTabChange('text');

      d.onTextInput('late');
      vi.advanceTimersByTime(500);
      d.onTabChange('area');
      answer(textCalls[0], fcAt(10));

      expect(lonOf(d.featureCollection())).toBeNull();
      expect(d.importDisabled()).toBe(true);
    });

    it('the Import button itself is disabled while the parse is pending', () => {
      const { fixture, comp, textCalls } = setup();
      comp.visible.set(true);
      fixture.detectChanges();

      const textarea = document.querySelector<HTMLTextAreaElement>('.dialog-pane textarea');
      const button = document.querySelector<HTMLButtonElement>('.dialog-pane button.btn-primary');
      expect(textarea).not.toBeNull();
      expect(button).not.toBeNull();

      textarea!.value = 'first';
      textarea!.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(500);
      answer(textCalls[0], fcAt(10));
      fixture.detectChanges();
      expect(button!.disabled).toBe(false);

      textarea!.value = 'second';
      textarea!.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(button!.disabled).toBe(true);

      vi.advanceTimersByTime(500);
      answer(textCalls[1], fcAt(20));
      fixture.detectChanges();
      expect(button!.disabled).toBe(false);

      comp.visible.set(false);
      fixture.detectChanges();
    });
  });

  describe('From UTM: Import waits for the parse of the text on screen', () => {
    it('is disabled from a change until its parse answers', () => {
      const { comp, utmCalls, imported } = setup();
      const d = internals(comp);
      d.reset();
      d.onTabChange('utm');

      d.onUtmInput('32V 624726 6207884');
      vi.advanceTimersByTime(500);
      answer(utmCalls[0], fcAt(9));
      expect(d.importDisabled()).toBe(false);

      d.onUtmInput('32V 624726 6207999');
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);

      vi.advanceTimersByTime(500);
      answer(utmCalls[1], fcAt(9.5));
      d.confirm();
      expect(imported.map(lonOf)).toEqual([9.5]);
    });
  });

  describe('From Area: only the last area picked counts', () => {
    it('two quick picks end on the second, whatever order the answers arrive in', () => {
      const { comp, areaCalls, imported } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();

      d.onAreaSelected(area(1));
      d.onAreaSelected(area(2));
      answer(areaCalls[1], fcAt(2));
      answer(areaCalls[0], fcAt(1));

      expect(lonOf(d.featureCollection())).toBe(2);
      d.confirm();
      expect(imported.map(lonOf)).toEqual([2]);
    });

    it('Import waits while the picked area loads, so the previous area is not imported', () => {
      const { comp, areaCalls, imported } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();

      d.onAreaSelected(area(1));
      answer(areaCalls[0], fcAt(1));
      expect(d.importDisabled()).toBe(false);

      d.onAreaSelected(area(2));
      expect(d.importDisabled()).toBe(true);
      d.confirm();
      expect(imported).toEqual([]);

      answer(areaCalls[1], fcAt(2));
      d.confirm();
      expect(imported.map(lonOf)).toEqual([2]);
    });

    it('an area answer that lands after a switch to another tab is ignored', () => {
      const { comp, areaCalls } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();

      d.onAreaSelected(area(1));
      d.onTabChange('text');
      answer(areaCalls[0], fcAt(1));

      expect(lonOf(d.featureCollection())).toBeNull();
      expect(d.importDisabled()).toBe(true);
    });

    it('an area answer for a pick made before a switch away and back is ignored', () => {
      const { comp, areaCalls } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();

      d.onAreaSelected(area(1));
      d.onTabChange('text');
      d.onTabChange('area');
      answer(areaCalls[0], fcAt(1));

      expect(lonOf(d.featureCollection())).toBeNull();
    });

    it('an area whose geometry fails to load imports nothing, and the next pick still works', () => {
      const { comp, areaCalls, imported } = setup({ withAreaSource: true });
      const d = internals(comp);
      d.reset();

      d.onAreaSelected(area(1));
      answer(areaCalls[0], fcAt(1));
      d.onAreaSelected(area(2));
      areaCalls[1].answer.error(new Error('500'));

      expect(lonOf(d.featureCollection())).toBeNull();
      expect(d.importDisabled()).toBe(true);
      expect(d.areaPickFailed()).toBe(true);

      d.onAreaSelected(area(1));
      expect(d.areaPickFailed()).toBe(false);
      answer(areaCalls[2], fcAt(1));
      d.confirm();
      expect(imported.map(lonOf)).toEqual([1]);
    });

    it('a failed read of the picked area says so, and Retry reads that area again', () => {
      const { fixture, comp, areaCalls, imported } = setup({ withAreaSource: true });
      comp.visible.set(true);
      fixture.detectChanges();
      const d = internals(comp);

      d.onAreaSelected(area(2));
      areaCalls[0].answer.error(new Error('503'));
      fixture.detectChanges();
      const banner = document.querySelector('[data-testid="geometry-import-area-pick-error"]');
      expect(banner?.textContent).toContain('common.fetchError');

      banner!.querySelector<HTMLButtonElement>('button')!.click();
      fixture.detectChanges();
      expect(areaCalls.map((c) => c.id)).toEqual([2, 2]);
      expect(document.querySelector('[data-testid="geometry-import-area-pick-error"]')).toBeNull();
      answer(areaCalls[1], fcAt(2));
      d.confirm();
      expect(imported.map(lonOf)).toEqual([2]);
    });

    it('a failed read of the area tree says so in its place, and Retry reads it again', () => {
      let reads = 0;
      const getRoots = vi.fn(() =>
        reads++ === 0
          ? throwError(() => new Error('503'))
          : of<readonly HierarchicalEntity[]>([area(1), area(2)]),
      );
      const { fixture, comp } = setup({ withAreaSource: true, getRoots });
      comp.visible.set(true);
      fixture.detectChanges();
      const d = internals(comp);
      expect(d.areaRootsFailed()).toBe(true);
      const banner = document.querySelector('[data-testid="geometry-import-area-roots-error"]');
      expect(banner?.textContent).toContain('common.fetchError');
      expect(document.querySelector('.dialog-pane app-entity-tree')).toBeNull();

      banner!.querySelector<HTMLButtonElement>('button')!.click();
      fixture.detectChanges();
      expect(getRoots).toHaveBeenCalledTimes(2);
      expect(d.areaRootsFailed()).toBe(false);
      expect(document.querySelector('[data-testid="geometry-import-area-roots-error"]')).toBeNull();
      expect(document.querySelector('.dialog-pane app-entity-tree')).not.toBeNull();

      comp.visible.set(false);
      fixture.detectChanges();
    });
  });
});
