import { Component, signal, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { Autocomplete } from './autocomplete/autocomplete';
import { EntityMultiselectField } from './entity-multiselect-field';
import { LanguageService } from '../../core/main-shell/services/language.service';
import { pressWithMouse } from '../testing/button-testing';

interface Item {
  readonly id: number;
  readonly name: string;
}
const A: Item = { id: 1, name: 'A' };
const A2: Item = { id: 11, name: 'A2' };
const B: Item = { id: 2, name: 'B' };

// A concrete EntityMultiselectField wired to the CDK <app-autocomplete> primitive,
// mirroring the area/category/tag fields (multiple mode). Exercises the base ↔
// primitive contract: chips render from the CVA value, and a search pick coexists
// with a modal `appendOne` pick (no writeControlValue-style drop).
@Component({
  selector: 'app-test-entity-field',
  imports: [Autocomplete],
  template: `<app-autocomplete
    [value]="value()"
    [suggestions]="suggestions()"
    [itemLabel]="labelOf"
    [multiple]="true"
    (query)="onSearch($event)"
    (selectionChange)="onSelect($event)"
    (clear)="onClear()"
    (touched)="markTouched()"
  />`,
})
class TestEntityField extends EntityMultiselectField<Item, number> {
  override readonly optionLabel = 'name';
  protected searchEntities(): Observable<readonly Item[]> {
    return of<readonly Item[]>([]);
  }
  protected resolveByIds(): Observable<readonly Item[]> {
    return of<readonly Item[]>([]);
  }
  protected idOf(item: Item): number {
    return item.id;
  }
}

describe('EntityMultiselectField ↔ app-autocomplete sync', () => {
  function setup<T>(type: Type<T>) {
    TestBed.configureTestingModule({
      providers: [{ provide: LanguageService, useValue: { selectedLanguage: signal('en') } }],
    });
    const fixture = TestBed.createComponent(type);
    fixture.autoDetectChanges();
    return fixture;
  }

  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const chipLabels = (host: HTMLElement): string[] =>
    Array.from(host.querySelectorAll('.ac-chip .ac-chip-label')).map(
      (el) => el.textContent?.trim() ?? '',
    );

  it('renders one chip per selected value, labelled by optionLabel', async () => {
    const fixture = setup(TestEntityField);
    fixture.componentInstance.onSelect([A, B]);
    await fixture.whenStable();

    expect(chipLabels(fixture.nativeElement)).toEqual(['A', 'B']);
  });

  it('keeps a search pick AND a later modal pick (the reported bug, fixed)', async () => {
    const fixture = setup(TestEntityField);
    const field = fixture.componentInstance;

    field.suggestions.set([A, A2]); // user has searched; suggestions populated
    field.onSelect([A]); // pick A from the search results
    await fixture.whenStable();
    expect(field.value().map((i) => i.id)).toEqual([1]);

    field.appendOne(B); // pick B from the modal (not in suggestions)
    await fixture.whenStable();

    // Both are kept — the CVA value owns the chips, so the modal pick does not
    // overwrite the search pick.
    expect(field.value().map((i) => i.id)).toEqual([1, 2]);
    expect(chipLabels(fixture.nativeElement)).toEqual(['A', 'B']);
  });

  it('keeps a modal pick AND a later search pick', async () => {
    const fixture = setup(TestEntityField);
    const field = fixture.componentInstance;

    field.appendOne(A); // modal first
    await fixture.whenStable();
    expect(field.value().map((i) => i.id)).toEqual([1]);

    field.suggestions.set([B]);
    field.onSelect([A, B]); // search adds B (primitive emits the accumulated array)
    await fixture.whenStable();

    expect(field.value().map((i) => i.id)).toEqual([1, 2]);
    expect(chipLabels(fixture.nativeElement)).toEqual(['A', 'B']);
  });

  it('portals the suggestions listbox and picking a row adds it as a chip', async () => {
    const fixture = setup(TestEntityField);
    const field = fixture.componentInstance;
    field.suggestions.set([A, B]);

    // ArrowDown opens the panel (and activates the first row) without kicking off
    // the base's debounced search — keeps the manually-set suggestions stable.
    const input = fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();

    const options = document.querySelectorAll('.ac-panel .ac-option');
    expect(options.length).toBe(2);

    // Rows commit on mousedown (so the input never blurs mid-click).
    options[0].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(field.value().map((i) => i.id)).toEqual([1]);
    expect(chipLabels(fixture.nativeElement)).toEqual(['A']);
  });

  it('removing a chip writes the reduced value back through the CVA', async () => {
    const fixture = setup(TestEntityField);
    const field = fixture.componentInstance;
    field.onSelect([A, B]);
    await fixture.whenStable();

    const remove = fixture.nativeElement.querySelector('.ac-chip .ac-chip-remove') as HTMLElement;
    pressWithMouse(remove);
    await fixture.whenStable();

    expect(field.value().map((i) => i.id)).toEqual([2]);
    expect(chipLabels(fixture.nativeElement)).toEqual(['B']);
  });
});
