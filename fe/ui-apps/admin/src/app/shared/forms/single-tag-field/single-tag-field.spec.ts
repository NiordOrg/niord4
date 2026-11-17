import { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { SingleTagField } from './single-tag-field';
import { MESSAGE_TAGS_DATA_SOURCE } from '../message-tags-field/message-tags-data.token';
import type { MessageTagsSearchParams } from '../message-tags-field/message-tags-data.token';
import { provideFakeAuthFacade } from '../../testing/fake-auth';
import { provideTestTransloco } from '../../testing/transloco-testing';
import type { MessageTag, MessageTagType } from '../../models/message-tag.model';

function mkTag(tagId: string, name: string, type: MessageTagType = 'PRIVATE'): MessageTag {
  return { tagId, name, type, messageCount: 0, locked: false };
}

interface SetupOptions {
  readonly resolveResult?: readonly MessageTag[];
  readonly searchResult?: readonly MessageTag[];
}

function setup(opts: SetupOptions = {}) {
  const search = vi.fn(() => of(opts.searchResult ?? []));
  const searchScoped = vi.fn(() => of(opts.searchResult ?? []));
  const resolveByIds = vi.fn(() => of(opts.resolveResult ?? []));

  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({ isAuthenticated: true }),
      provideTestTransloco(),
      { provide: MESSAGE_TAGS_DATA_SOURCE, useValue: { search, searchScoped, resolveByIds } },
    ],
  });

  const fixture = TestBed.createComponent(SingleTagField);
  fixture.detectChanges();
  const comp = fixture.componentInstance;

  return { fixture, comp, search, searchScoped, resolveByIds };
}

// Protected/private members reached through a cast (same pattern as siblings).
interface Internals {
  choose: (tag: MessageTag) => void;
  clear: () => void;
  isSelected: (tag: MessageTag) => boolean;
  selected: WritableSignal<MessageTag | null>;
  selectedScopes: WritableSignal<readonly MessageTagType[]>;
  search: WritableSignal<string>;
  openTick: WritableSignal<number>;
}

function internals(comp: SingleTagField): Internals {
  return comp as unknown as Internals;
}

describe('SingleTagField', () => {
  it('an external tagId resolves via resolveByIds and sets the selected tag', () => {
    const tag = mkTag('t1', 'Tag 1');
    const { fixture, comp, resolveByIds } = setup({ resolveResult: [tag] });

    comp.value.set('t1');
    fixture.detectChanges();

    expect(resolveByIds).toHaveBeenCalledWith(['t1']);
    expect(internals(comp).selected()).toEqual(tag);
  });

  it('an external null value clears the selection without calling resolveByIds', () => {
    const tag = mkTag('t1', 'Tag 1');
    const { fixture, comp, resolveByIds } = setup({ resolveResult: [tag] });
    comp.value.set('t1');
    fixture.detectChanges();
    resolveByIds.mockClear();

    comp.value.set(null);
    fixture.detectChanges();

    expect(resolveByIds).not.toHaveBeenCalled();
    expect(internals(comp).selected()).toBeNull();
  });

  it('an unresolvable id leaves the selection null', () => {
    const { fixture, comp } = setup({ resolveResult: [] });

    comp.value.set('missing');
    fixture.detectChanges();

    expect(internals(comp).selected()).toBeNull();
  });

  it('choose selects the tag and emits its tagId via valueChange', () => {
    const tag = mkTag('t1', 'Tag 1');
    const { comp } = setup();
    const changes: (string | null)[] = [];
    comp.value.subscribe((v) => changes.push(v));

    internals(comp).choose(tag);

    expect(internals(comp).selected()).toEqual(tag);
    expect(changes).toEqual(['t1']);
  });

  it('clear resets the selection and emits null', () => {
    const tag = mkTag('t1', 'Tag 1');
    const { comp } = setup();
    internals(comp).choose(tag);
    const changes: (string | null)[] = [];
    comp.value.subscribe((v) => changes.push(v));

    internals(comp).clear();

    expect(internals(comp).selected()).toBeNull();
    expect(changes).toEqual([null]);
  });

  it('isSelected reflects the currently selected tag', () => {
    const tag = mkTag('t1', 'Tag 1');
    const other = mkTag('t2', 'Tag 2');
    const { comp } = setup();

    expect(internals(comp).isSelected(tag)).toBe(false);

    internals(comp).selected.set(tag);
    expect(internals(comp).isSelected(tag)).toBe(true);
    expect(internals(comp).isSelected(other)).toBe(false);
  });

  describe('tagsResource searchScoped params', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('passes name=undefined for an empty search once the picker has been opened', async () => {
      const { fixture, comp, searchScoped } = setup();

      // Simulate openPicker(): reset filters and bump the tick so the resource fetches.
      internals(comp).selectedScopes.set([]);
      internals(comp).search.set('');
      internals(comp).openTick.update((n) => n + 1);

      // The resource reacts in an effect; flush it and the timer(0) for empty search.
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);

      expect(searchScoped).toHaveBeenCalledWith({ name: undefined, types: [] });
    });

    it('forwards the trimmed search term and selected scopes after the debounce', async () => {
      const { fixture, comp, searchScoped } = setup();

      internals(comp).selectedScopes.set(['DOMAIN']);
      internals(comp).search.set('  buoy  ');
      internals(comp).openTick.update((n) => n + 1);

      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);

      expect(searchScoped).toHaveBeenCalledWith({
        name: 'buoy',
        types: ['DOMAIN'],
      } satisfies MessageTagsSearchParams);
    });
  });
});
