import { Component, Directive, computed, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { EntityMultiselectField } from './entity-multiselect-field';
import { LanguageService } from '../../core/main-shell/services/language.service';

interface Item {
  readonly id: number;
  readonly name: string;
}

@Directive({
  selector: '[appTestMultiselect]',
  standalone: true,
})
class TestMultiselect extends EntityMultiselectField<Item, number> {
  resolveImpl: (ids: readonly number[]) => Observable<readonly Item[]> = () =>
    of<readonly Item[]>([]);
  protected searchEntities(): Observable<readonly Item[]> {
    return of<readonly Item[]>([]);
  }
  protected resolveByIds(ids: readonly number[]): Observable<readonly Item[]> {
    return this.resolveImpl(ids);
  }
  protected idOf(item: Item): number {
    return item.id;
  }
  appendFromDialog(item: Item): void {
    this.appendOne(item);
  }
}

// Mirrors the filter panel binding exactly: the slice is the source of truth; the
// field is bound [initialIds]=slice ids + [value]=slice items and (valueChange)
// writes the field's value back to the slice — with the same content-equality guard
// the real EntityFilterPanelBase uses to swallow the model's one-way re-push echo.
@Component({
  standalone: true,
  imports: [TestMultiselect],
  template: `<div
    appTestMultiselect
    [initialIds]="ids()"
    [value]="sliceItems()"
    (valueChange)="onFieldChange($event)"
  ></div>`,
})
class PanelHost {
  readonly slice = signal<Item[]>([]);
  readonly sliceItems = computed(() => this.slice());
  readonly ids = computed(() => this.slice().map((i) => i.id));
  readonly field = viewChild.required(TestMultiselect);
  onFieldChange(items: readonly Item[]): void {
    const current = this.slice();
    if (current.length === items.length && current.every((c, i) => c.id === items[i].id)) {
      return;
    }
    this.slice.set([...items]);
  }
}

describe('EntityMultiselectField — search + modal mixing (panel loop)', () => {
  function createPanel() {
    TestBed.configureTestingModule({
      imports: [PanelHost],
      providers: [{ provide: LanguageService, useValue: { selectedLanguage: signal('en') } }],
    });
    const fixture = TestBed.createComponent(PanelHost);
    fixture.detectChanges();
    return { fixture, host: fixture.componentInstance };
  }

  const A = { id: 1, name: 'A' };
  const B = { id: 2, name: 'B' };

  it('keeps the search item when a modal item is appended', async () => {
    const { fixture, host } = createPanel();
    const field = host.field();
    field.resolveImpl = (ids) => of([A, B].filter((x) => ids.includes(x.id)));

    field.onSelect([A]); // search picks A
    fixture.detectChanges();
    await fixture.whenStable();
    expect(host.slice()).toEqual([A]);

    field.appendFromDialog(B); // modal picks B
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.value()).toEqual([A, B]);
    expect(host.slice()).toEqual([A, B]);
  });

  it('keeps the modal item when a search item is added afterwards', async () => {
    const { fixture, host } = createPanel();
    const field = host.field();
    field.resolveImpl = (ids) => of([A, B].filter((x) => ids.includes(x.id)));

    field.appendFromDialog(A); // modal picks A
    fixture.detectChanges();
    await fixture.whenStable();
    expect(host.slice()).toEqual([A]);

    field.onSelect([A, B]); // search adds B (autocomplete emits full array)
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.value()).toEqual([A, B]);
    expect(host.slice()).toEqual([A, B]);
  });
});
