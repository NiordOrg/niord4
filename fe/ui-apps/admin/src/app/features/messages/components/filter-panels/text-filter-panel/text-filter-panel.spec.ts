import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

import { TextFilterPanel } from './text-filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';

function setup(focusRequest?: 'text') {
  const textSlice = signal<{ query: string }>({ query: '' });
  const setSliceValue = vi.fn((...args: ['text', { query: string }]) => textSlice.set(args[1]));
  const clearFilter = vi.fn(() => textSlice.set({ query: '' }));
  const panelFocusRequest = signal<string | undefined>(focusRequest);

  const filter = {
    slice: (name: 'text') => {
      if (name !== 'text') throw new Error(`unexpected slice ${name}`);
      return textSlice;
    },
    setSliceValue,
    clearFilter,
    panelFocusRequest,
  };

  TestBed.configureTestingModule({
    providers: [{ provide: MessagesFacade, useValue: { filter } }, provideTestTransloco()],
  });

  const fixture = TestBed.createComponent(TextFilterPanel);
  fixture.detectChanges();
  TestBed.tick();
  return {
    fixture,
    comp: fixture.componentInstance,
    textSlice,
    setSliceValue,
    clearFilter,
    panelFocusRequest,
    input: fixture.nativeElement.querySelector('input') as HTMLInputElement,
  };
}

describe('TextFilterPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('reflects external stored query in localQuery', () => {
    const { comp, textSlice } = setup();
    expect(comp.localQuery()).toBe('');

    textSlice.set({ query: 'mariner' });
    TestBed.tick();

    expect(comp.localQuery()).toBe('mariner');
  });

  it('debounces input and pushes once after 500ms', () => {
    const { comp, setSliceValue } = setup();

    comp.onInput('a');
    TestBed.tick();
    comp.onInput('ab');
    TestBed.tick();
    comp.onInput('abc');
    TestBed.tick();

    expect(setSliceValue).not.toHaveBeenCalled();

    vi.advanceTimersByTime(500);

    expect(setSliceValue).toHaveBeenCalledTimes(1);
    expect(setSliceValue).toHaveBeenCalledWith('text', { query: 'abc' });
  });

  it('does not push when localQuery equals stored (URL apply path)', () => {
    const { comp, textSlice, setSliceValue } = setup();

    textSlice.set({ query: 'foo' });
    TestBed.tick();
    expect(comp.localQuery()).toBe('foo');

    vi.advanceTimersByTime(1000);
    expect(setSliceValue).not.toHaveBeenCalled();
  });

  it('clear() cancels a pending debounced push', () => {
    const { comp, setSliceValue, clearFilter } = setup();

    comp.onInput('typing');
    TestBed.tick();

    comp.clear();
    TestBed.tick();

    vi.advanceTimersByTime(1000);

    expect(setSliceValue).not.toHaveBeenCalled();
    expect(clearFilter).toHaveBeenCalledWith('text');
    expect(comp.localQuery()).toBe('');
  });

  it('external store update during typing cancels the pending push', () => {
    const { comp, textSlice, setSliceValue } = setup();

    comp.onInput('foo');
    TestBed.tick();

    textSlice.set({ query: 'bar' });
    TestBed.tick();

    vi.advanceTimersByTime(1000);

    expect(setSliceValue).not.toHaveBeenCalled();
    expect(comp.localQuery()).toBe('bar');
  });

  it('takes the caret when the menu switched the filter on, and consumes the request', () => {
    const { input, panelFocusRequest } = setup('text');

    expect(document.activeElement).toBe(input);
    // Consumed, so a later re-render of the panel does not grab focus again.
    expect(panelFocusRequest()).toBeUndefined();
  });

  it('leaves focus alone when the panel is restored from a URL rather than the menu', () => {
    const { input } = setup();

    expect(document.activeElement).not.toBe(input);
  });

  it('marks the panel as a menu caret hand-off target so the menu survives the focus move', () => {
    const { input } = setup();

    // The marker sits on the panel, not the input: the menu matches it with closest() and the
    // input event bubbles, so one attribute covers however deeply the field nests its input.
    expect(input.closest('[data-menu-caret-handoff]')).not.toBeNull();
  });
});
