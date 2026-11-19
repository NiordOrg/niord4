import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { type Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  DictionariesApiService,
  type DictionaryEntry,
} from '../../../services/api/dictionaries-api.service';
import { ListValuesEditor } from './list-values-editor';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ModelLanguageSelection } from '../../../services/model-language-selection';

const entry = (key: string, value = key.toUpperCase()): DictionaryEntry => ({
  key,
  descs: [{ lang: 'en', value }],
});

const entriesByName: Record<string, DictionaryEntry[]> = {
  template: [entry('alpha'), entry('bravo'), entry('charlie')],
  other: [entry('xray'), entry('yankee')],
};

interface Internals {
  available: () => DictionaryEntry[];
  selectedRows: () => DictionaryEntry[];
  currentDictionaryName: () => string;
  dictionaryNames: () => string[];
  dictSearch: { set: (v: string) => void };
  onDictionaryChange: (name: string) => void;
  addValue: (e: DictionaryEntry) => void;
  removeValue: (e: DictionaryEntry) => void;
  clearAll: () => void;
  moveUp: (i: number) => void;
  moveDown: (i: number) => void;
}

function setup(
  names: string[] = ['template', 'other'],
  entries: (name: string) => Observable<DictionaryEntry[]> = (name) =>
    of(entriesByName[name] ?? []),
) {
  const getDictionaryEntries = vi.fn(entries);
  const dictionaries = {
    getDictionaryNames: vi.fn(() => of(names)),
    getDictionaryEntries,
  };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      ModelLanguageSelection,
      { provide: DictionariesApiService, useValue: dictionaries },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: signal('en'),
          defaultLanguage: 'en',
          modelLanguages: [{ value: 'en' }, { value: 'da' }, { value: 'kl' }],
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(ListValuesEditor);
  const component = fixture.componentInstance;
  const internals = component as unknown as Internals;
  let changes = 0;
  component.valuesChanged.subscribe(() => (changes += 1));
  return { fixture, component, internals, getDictionaryEntries, changes: () => changes };
}

/** Let the dictionary-name → entries toObservable pipeline emit into the entries signal. */
async function settle(fixture: ComponentFixture<ListValuesEditor>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

function valueCell(fixture: ComponentFixture<ListValuesEditor>, key: string): HTMLElement {
  const rendered = fixture.nativeElement as HTMLElement;
  const row = [...rendered.querySelectorAll('tbody tr')].find(
    (row) => row.querySelector('td')?.textContent?.trim() === key,
  );
  expect(row, `expected a rendered row for ${key}`).toBeDefined();
  return row!.querySelector<HTMLElement>('td:nth-child(2) .truncate')!;
}

describe('ListValuesEditor', () => {
  it('says so when the entries could not be read, and Retry reads them again', async () => {
    let calls = 0;
    const { fixture, internals, getDictionaryEntries } = setup(['template'], (name) =>
      calls++ === 0 ? throwError(() => new Error('offline')) : of(entriesByName[name] ?? []),
    );
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('common.fetchError');
    expect(internals.available()).toEqual([]);

    host.querySelector<HTMLButtonElement>('[role="alert"] button')?.click();
    await settle(fixture);
    expect(getDictionaryEntries).toHaveBeenLastCalledWith('template');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(internals.available().map((e) => e.key)).toEqual(['alpha', 'bravo', 'charlie']);
  });

  it('changes the displayed language without emitting or modifying selected values', async () => {
    const { fixture, component, changes } = setup();
    const values: DictionaryEntry[] = [
      {
        key: 'alpha',
        descs: [
          { lang: 'en', value: 'First' },
          { lang: 'kl', value: 'Siulleq' },
        ],
      },
    ];
    fixture.componentRef.setInput('values', values);
    await settle(fixture);
    const original = structuredClone(component.values());
    expect(valueCell(fixture, 'alpha').textContent?.trim()).toBe('First');
    expect(valueCell(fixture, 'alpha').lang).toBe('en');
    TestBed.inject(ModelLanguageSelection).select('kl');
    await settle(fixture);
    expect(valueCell(fixture, 'alpha').textContent?.trim()).toBe('Siulleq');
    expect(valueCell(fixture, 'alpha').lang).toBe('kl');
    expect(valueCell(fixture, 'bravo').textContent?.trim()).toBe('BRAVO');
    expect(valueCell(fixture, 'bravo').lang).toBe('en');
    expect(component.values()).toEqual(original);
    expect(changes()).toBe(0);
  });

  it('loads the default `template` dictionary; available = all its entries when nothing is selected', async () => {
    const { fixture, internals } = setup();
    await settle(fixture);
    expect(internals.currentDictionaryName()).toBe('template');
    expect(internals.available().map((e) => e.key)).toEqual(['alpha', 'bravo', 'charlie']);
  });

  it('available excludes the already-selected entries (deduped by key)', async () => {
    const { fixture, component, internals } = setup();
    fixture.componentRef.setInput('values', [entry('alpha')]);
    await settle(fixture);
    expect(component.values().map((e) => e.key)).toEqual(['alpha']);
    expect(internals.available().map((e) => e.key)).toEqual(['bravo', 'charlie']);
  });

  it('the key filter narrows the available pane, case-insensitively', async () => {
    const { fixture, internals } = setup();
    await settle(fixture);
    internals.dictSearch.set('AR'); // matches chARlie
    expect(internals.available().map((e) => e.key)).toEqual(['charlie']);
    internals.dictSearch.set('a'); // alpha, bravo, charlie all contain 'a'
    expect(internals.available().map((e) => e.key)).toEqual(['alpha', 'bravo', 'charlie']);
  });

  it('switching dictionaries loads its entries and clears the filter', async () => {
    const { fixture, internals, getDictionaryEntries } = setup();
    await settle(fixture);
    internals.dictSearch.set('alpha');

    internals.onDictionaryChange('other');
    await settle(fixture);

    expect(getDictionaryEntries).toHaveBeenCalledWith('other');
    expect(internals.currentDictionaryName()).toBe('other');
    // Filter was reset by the switch, so both `other` entries show.
    expect(internals.available().map((e) => e.key)).toEqual(['xray', 'yankee']);
  });

  it('falls back to the first available dictionary when `template` is absent', async () => {
    const { fixture, internals } = setup(['zulu', 'other']);
    await settle(fixture);
    expect(internals.currentDictionaryName()).toBe('zulu');
    expect(internals.dictionaryNames()).toEqual(['zulu', 'other']);
  });

  it('renders selected values with their language and leaves empty values unmarked', async () => {
    const { fixture } = setup();
    fixture.componentRef.setInput('values', [
      entry('alpha', 'Alpha label'),
      { key: 'k', descs: [] },
    ]);
    await settle(fixture);
    expect(valueCell(fixture, 'alpha').textContent?.trim()).toBe('Alpha label');
    expect(valueCell(fixture, 'alpha').lang).toBe('en');
    expect(valueCell(fixture, 'k').textContent?.trim()).toBe('');
    expect(valueCell(fixture, 'k').hasAttribute('lang')).toBe(false);
  });

  it('add appends to the ordered selection and emits once', () => {
    const { component, internals, changes } = setup();
    internals.addValue(entry('alpha'));
    internals.addValue(entry('bravo'));
    expect(component.values().map((e) => e.key)).toEqual(['alpha', 'bravo']);
    expect(changes()).toBe(2);
  });

  it('remove drops the matching key and emits', () => {
    const { fixture, component, internals, changes } = setup();
    fixture.componentRef.setInput('values', [entry('alpha'), entry('bravo')]);
    internals.removeValue(entry('alpha'));
    expect(component.values().map((e) => e.key)).toEqual(['bravo']);
    expect(changes()).toBe(1);
  });

  it('clearAll empties the selection and emits', () => {
    const { fixture, component, internals, changes } = setup();
    fixture.componentRef.setInput('values', [entry('alpha'), entry('bravo')]);
    internals.clearAll();
    expect(component.values()).toEqual([]);
    expect(changes()).toBe(1);
  });

  it('moveUp / moveDown reorder within bounds and emit; the edge guards are no-ops', () => {
    const { fixture, component, internals, changes } = setup();
    fixture.componentRef.setInput('values', [entry('alpha'), entry('bravo'), entry('charlie')]);

    internals.moveDown(0); // [bravo, alpha, charlie]
    expect(component.values().map((e) => e.key)).toEqual(['bravo', 'alpha', 'charlie']);
    internals.moveUp(2); // [bravo, charlie, alpha]
    expect(component.values().map((e) => e.key)).toEqual(['bravo', 'charlie', 'alpha']);
    expect(changes()).toBe(2);

    internals.moveUp(0); // already at the top → no change, no emit
    internals.moveDown(2); // already at the bottom → no change, no emit
    expect(component.values().map((e) => e.key)).toEqual(['bravo', 'charlie', 'alpha']);
    expect(changes()).toBe(2);
  });
});
