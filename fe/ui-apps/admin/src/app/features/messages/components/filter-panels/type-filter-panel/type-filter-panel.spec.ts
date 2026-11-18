import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { TypeSliceValue } from '../../../models/messages-filter-params.model';
import { MessagesFacade } from '../../../services/messages-facade.service';
import { TypeFilterPanel } from './type-filter-panel';

const CHIP_KEY = 'messages.filter.type.cancellationWarnings';

function setup(opts: { nw?: boolean; nm?: boolean; slice?: Partial<TypeSliceValue> } = {}) {
  const nw = opts.nw ?? true;
  const nm = opts.nm ?? true;
  const typeSlice = signal<TypeSliceValue>({
    mainType: '',
    types: [],
    cancellationWarnings: false,
    ...opts.slice,
  });
  const setSliceValue = vi.fn((_name: 'type', value: TypeSliceValue) => typeSlice.set(value));
  const filter = {
    slice: (name: string) => {
      if (name !== 'type') throw new Error(`unexpected slice ${name}`);
      return typeSlice;
    },
    setSliceValue,
    clearFilter: vi.fn(),
  };
  const domain = {
    supportsNw: (exclusively = false) => computed(() => nw && (!exclusively || !nm)),
    supportsNm: (exclusively = false) => computed(() => nm && (!exclusively || !nw)),
    supportsType: () => signal(true),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: MessagesFacade, useValue: { filter } },
      { provide: DomainFacade, useValue: domain },
    ],
  });
  const fixture = TestBed.createComponent(TypeFilterPanel);
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance, typeSlice, setSliceValue };
}

function chip(fixture: ReturnType<typeof setup>['fixture']): HTMLButtonElement | undefined {
  const buttons = Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
  ) as HTMLButtonElement[];
  return buttons.find((button) => (button.textContent ?? '').includes(CHIP_KEY));
}

describe('TypeFilterPanel — the Cancellation warnings chip', () => {
  it('sits beside the NW types and turns the choice on and off', () => {
    const { fixture, typeSlice } = setup({ slice: { mainType: 'NW' } });
    const button = chip(fixture);
    expect(button).toBeDefined();
    expect(button!.getAttribute('aria-pressed')).toBe('false');

    button!.click();
    fixture.detectChanges();
    expect(typeSlice().cancellationWarnings).toBe(true);
    expect(chip(fixture)!.getAttribute('aria-pressed')).toBe('true');

    chip(fixture)!.click();
    fixture.detectChanges();
    expect(typeSlice().cancellationWarnings).toBe(false);
  });

  it('is offered in a domain of Navigational Warnings alone, with no main type picked', () => {
    const { fixture } = setup({ nm: false });
    expect(chip(fixture)).toBeDefined();
  });

  it('is not offered beside the NM types, nor under All in a domain of both', () => {
    expect(chip(setup({ slice: { mainType: 'NM' } }).fixture)).toBeUndefined();
    TestBed.resetTestingModule();
    expect(chip(setup({ slice: { mainType: '' } }).fixture)).toBeUndefined();
  });

  it('shows whenever the choice is on, so a list narrowed to cancellation warnings can be widened again', () => {
    const { fixture, typeSlice } = setup({ slice: { mainType: '', cancellationWarnings: true } });
    const button = chip(fixture);
    expect(button).toBeDefined();
    button!.click();
    fixture.detectChanges();
    expect(typeSlice().cancellationWarnings).toBe(false);
  });

  it('keeps the choice when a type is picked or left out, and with All and Reset', () => {
    const { comp, typeSlice } = setup({ slice: { mainType: 'NW', cancellationWarnings: true } });
    comp.onTypeToggle('LOCAL_WARNING', true);
    expect(typeSlice()).toEqual({
      mainType: 'NW',
      types: ['LOCAL_WARNING'],
      cancellationWarnings: true,
    });
    comp.onTypeToggle('LOCAL_WARNING', false);
    expect(typeSlice().cancellationWarnings).toBe(true);
    comp.selectAll('NW');
    expect(typeSlice().cancellationWarnings).toBe(true);
    comp.reset('NW');
    expect(typeSlice().cancellationWarnings).toBe(true);
  });

  it('turns the choice off when the main type changes to NM, or to All in a domain of both', () => {
    const { comp, typeSlice } = setup({ slice: { mainType: 'NW', cancellationWarnings: true } });
    comp.onMainTypeChange('NM');
    expect(typeSlice().cancellationWarnings).toBe(false);

    typeSlice.set({ mainType: 'NW', types: [], cancellationWarnings: true });
    comp.onMainTypeChange('');
    expect(typeSlice().cancellationWarnings).toBe(false);

    typeSlice.set({ mainType: 'NW', types: [], cancellationWarnings: true });
    comp.onMainTypeChange('NW');
    expect(typeSlice().cancellationWarnings).toBe(true);
  });

  it('keeps the choice under All in a domain of Navigational Warnings alone', () => {
    const { comp, typeSlice } = setup({
      nm: false,
      slice: { mainType: 'NW', cancellationWarnings: true },
    });
    comp.onMainTypeChange('');
    expect(typeSlice().cancellationWarnings).toBe(true);
  });
});
