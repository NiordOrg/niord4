import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { MessageDateColumnHeader } from './message-date-column-header';
import type { MessageDateColumnType } from '../../models/message-date-column-type.model';
import type { SortBy, SortOrder } from '../../models/messages-filter-params.model';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(opts: {
  dateColumnType: MessageDateColumnType;
  sortBy: SortBy;
  sortOrder: SortOrder;
}) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(MessageDateColumnHeader);
  fixture.componentRef.setInput('dateColumnType', opts.dateColumnType);
  fixture.componentRef.setInput('sortBy', opts.sortBy);
  fixture.componentRef.setInput('sortOrder', opts.sortOrder);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance };
}

describe('MessageDateColumnHeader', () => {
  it('renders the current type label', () => {
    const { component } = setup({ dateColumnType: 'DATE', sortBy: 'AREA', sortOrder: 'ASC' });
    expect(component.currentLabel()).toBe('messages.table.dates.event');
  });

  it('main button click emits (sortClicked)', () => {
    const { component } = setup({ dateColumnType: 'DATE', sortBy: 'AREA', sortOrder: 'ASC' });
    const events: void[] = [];
    component.sortClicked.subscribe(() => events.push(undefined));
    component.onSort();
    expect(events).toHaveLength(1);
  });

  it('each menu item emits (typeChanged) with the right type', () => {
    const { component } = setup({ dateColumnType: 'DATE', sortBy: 'AREA', sortOrder: 'ASC' });
    const emitted: MessageDateColumnType[] = [];
    component.typeChanged.subscribe((t) => emitted.push(t));

    const items = component.typeOptions();
    items[0].command?.({ originalEvent: new MouseEvent('click'), item: items[0] });
    items[1].command?.({ originalEvent: new MouseEvent('click'), item: items[1] });
    items[2].command?.({ originalEvent: new MouseEvent('click'), item: items[2] });

    expect(emitted).toEqual(['DATE', 'PUBLISH_DATE', 'FOLLOW_UP_DATE']);
  });

  it('isSorted true when sortBy matches dateColumnType', () => {
    const sorted = setup({
      dateColumnType: 'PUBLISH_DATE',
      sortBy: 'PUBLISH_DATE',
      sortOrder: 'ASC',
    });
    expect(sorted.component.isSorted()).toBe(true);
  });

  it('isSorted false when sortBy does not match dateColumnType', () => {
    const notSorted = setup({ dateColumnType: 'DATE', sortBy: 'PUBLISH_DATE', sortOrder: 'ASC' });
    expect(notSorted.component.isSorted()).toBe(false);
  });

  it('icon is lucideArrowUp when sortOrder is ASC', () => {
    const asc = setup({ dateColumnType: 'DATE', sortBy: 'DATE', sortOrder: 'ASC' });
    expect(asc.component.directionIconClass()).toBe('lucideArrowUp');
  });

  it('icon is lucideArrowDown when sortOrder is DESC', () => {
    const desc = setup({ dateColumnType: 'DATE', sortBy: 'DATE', sortOrder: 'DESC' });
    expect(desc.component.directionIconClass()).toBe('lucideArrowDown');
  });
});
