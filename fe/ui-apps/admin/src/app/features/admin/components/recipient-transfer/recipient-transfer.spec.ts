import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  RecipientTransfer,
  type RecipientSelection,
  type TransferItem,
} from './recipient-transfer';

const item = (email: string, name = ''): TransferItem => ({ key: email, email, name });

interface Internals {
  selectedFilter: { set: (v: string) => void };
  availableFilter: { set: (v: string) => void };
  filteredAvailable: () => TransferItem[];
  add: (item: TransferItem) => void;
  remove: (item: TransferItem) => void;
  addAllFiltered: () => void;
  removeAllFiltered: () => void;
}

function setup(value: RecipientSelection) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(RecipientTransfer);
  fixture.componentRef.setInput('value', value);
  fixture.detectChanges();
  return {
    fixture,
    component: fixture.componentInstance,
    internals: fixture.componentInstance as unknown as Internals,
  };
}

describe('RecipientTransfer', () => {
  it('add moves one item selected→... keeping both panes email-sorted', () => {
    const { component, internals } = setup({
      selected: [item('b@x.dk')],
      available: [item('a@x.dk'), item('c@x.dk')],
    });
    internals.add(item('c@x.dk'));
    expect(component.value().selected.map((i) => i.email)).toEqual(['b@x.dk', 'c@x.dk']);
    expect(component.value().available.map((i) => i.email)).toEqual(['a@x.dk']);
  });

  it('remove moves one item back to available', () => {
    const { component, internals } = setup({
      selected: [item('a@x.dk'), item('b@x.dk')],
      available: [],
    });
    internals.remove(item('a@x.dk'));
    expect(component.value().selected.map((i) => i.email)).toEqual(['b@x.dk']);
    expect(component.value().available.map((i) => i.email)).toEqual(['a@x.dk']);
  });

  it('Add all is FILTER-AWARE — moves only the filtered subset, not the whole set', () => {
    const { component, internals } = setup({
      selected: [],
      available: [
        item('nav@x.dk', 'Navwarn'),
        item('fire@x.dk', 'Firing'),
        item('nav2@x.dk', 'Nav two'),
      ],
    });
    internals.availableFilter.set('nav');
    expect(internals.filteredAvailable().map((i) => i.email)).toEqual(['nav@x.dk', 'nav2@x.dk']);
    internals.addAllFiltered();
    // Only the two matching 'nav' rows moved; the Firing row stayed available. Order is
    // locale-collated (localeCompare) — '@' sorts before the digit '2'.
    expect(component.value().selected.map((i) => i.email)).toEqual(['nav@x.dk', 'nav2@x.dk']);
    expect(component.value().available.map((i) => i.email)).toEqual(['fire@x.dk']);
  });

  it('Remove all is filter-aware too', () => {
    const { component, internals } = setup({
      selected: [item('a@x.dk', 'Alpha'), item('b@x.dk', 'Bravo')],
      available: [],
    });
    internals.selectedFilter.set('alpha');
    internals.removeAllFiltered();
    expect(component.value().selected.map((i) => i.email)).toEqual(['b@x.dk']);
    expect(component.value().available.map((i) => i.email)).toEqual(['a@x.dk']);
  });

  it('filter matches email OR name, case-insensitive', () => {
    const { internals } = setup({
      selected: [],
      available: [item('x@x.dk', 'Coastal'), item('firing@x.dk', 'Ranges')],
    });
    internals.availableFilter.set('COAST');
    expect(internals.filteredAvailable().map((i) => i.email)).toEqual(['x@x.dk']);
  });
});
