import type { Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, Subject } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { GeometryApiService } from '../../../api/geometry-api.service';
import type { GeometryFeatureCollection } from '../../../models/geometry.model';
import { DiscardConfirmService } from '../../../services/discard-confirm.service';
import { provideTestTransloco } from '../../../testing/transloco-testing';
import { EditAsTextDialog } from './edit-as-text-dialog';

/** A one-point collection; `lon` tells the collections apart. */
function fcAt(lon: number): GeometryFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [lon, 56] } },
    ],
  };
}

/**
 * Every parse answers through a Subject the test holds, so the test decides when (and in what
 * order) the answers arrive.
 */
function setup() {
  const textCalls: { text: string; answer: Subject<GeometryFeatureCollection> }[] = [];
  const geometryApi = {
    parseGeometry: vi.fn((text: string) => {
      const answer = new Subject<GeometryFeatureCollection>();
      textCalls.push({ text, answer });
      return answer;
    }),
    formatGeometry: () => of(''),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: GeometryApiService, useValue: geometryApi },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: DiscardConfirmService, useValue: { confirmDiscard: async () => true } },
    ],
  });

  // Render nothing: the tests drive the class (parse, pending, save); the dialog's map has no place
  // in a test without layout.
  TestBed.overrideComponent(EditAsTextDialog, { set: { template: '' } });
  const fixture = TestBed.createComponent(EditAsTextDialog);
  fixture.detectChanges();
  const comp = fixture.componentInstance;
  const saved: GeometryFeatureCollection[] = [];
  comp.saved.subscribe((fc) => saved.push(fc));

  return { comp, textCalls, saved };
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
  initFromInput: () => void;
  onTextInput: (text: string) => void;
  save: () => void;
  parsePending: Signal<boolean>;
  workingFc: Signal<GeometryFeatureCollection>;
}

function internals(comp: EditAsTextDialog): Internals {
  return comp as unknown as Internals;
}

/** The longitude of the collection's one point, or null when it is empty. */
function lonOf(fc: GeometryFeatureCollection): number | null {
  const g = fc.features[0]?.geometry;
  return g?.type === 'Point' ? g.coordinates[0] : null;
}

describe('EditAsTextDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('an answer for the text before a change, landing after it, neither ends the wait nor is saved', () => {
    const { comp, textCalls, saved } = setup();
    const d = internals(comp);
    d.initFromInput();

    d.onTextInput('first');
    vi.advanceTimersByTime(500);
    // The first parse is out; the user changes the text before it answers.
    d.onTextInput('second');
    answer(textCalls[0], fcAt(10));

    expect(d.parsePending()).toBe(true);
    expect(lonOf(d.workingFc())).toBeNull();
    d.save();
    expect(saved).toEqual([]);

    vi.advanceTimersByTime(500);
    expect(textCalls.map((c) => c.text)).toEqual(['first', 'second']);
    answer(textCalls[1], fcAt(20));
    expect(d.parsePending()).toBe(false);
    d.save();
    expect(saved.map(lonOf)).toEqual([20]);
  });

  it('Save waits from a change until its parse answers', () => {
    const { comp, textCalls, saved } = setup();
    const d = internals(comp);
    d.initFromInput();

    d.onTextInput('first');
    vi.advanceTimersByTime(500);
    answer(textCalls[0], fcAt(10));
    expect(d.parsePending()).toBe(false);

    // A change: the working set still holds the first text's point, but Save must wait.
    d.onTextInput('second');
    expect(d.parsePending()).toBe(true);
    d.save();
    vi.advanceTimersByTime(500);
    d.save();
    expect(saved).toEqual([]);

    answer(textCalls[1], fcAt(20));
    d.save();
    expect(saved.map(lonOf)).toEqual([20]);
  });

  it('an answer from an earlier open is ignored', () => {
    const { comp, textCalls } = setup();
    const d = internals(comp);
    d.initFromInput();

    d.onTextInput('x');
    vi.advanceTimersByTime(500);
    d.initFromInput();
    answer(textCalls[0], fcAt(10));

    expect(lonOf(d.workingFc())).toBeNull();
    expect(d.parsePending()).toBe(false);
  });
});
