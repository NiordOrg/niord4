import { describe, expect, it } from 'vitest';

import { parseMessageSortOrder } from './domain.model';

describe('parseMessageSortOrder', () => {
  it.each([
    ['AREA ASC', { sortBy: 'AREA', sortOrder: 'ASC' }],
    ['AREA DESC', { sortBy: 'AREA', sortOrder: 'DESC' }],
    ['ID ASC', { sortBy: 'ID', sortOrder: 'ASC' }],
    ['ID DESC', { sortBy: 'ID', sortOrder: 'DESC' }],
    ['DATE ASC', { sortBy: 'DATE', sortOrder: 'ASC' }],
    ['DATE DESC', { sortBy: 'DATE', sortOrder: 'DESC' }],
    ['area asc', { sortBy: 'AREA', sortOrder: 'ASC' }],
  ])('parses %s correctly', (input, expected) => {
    expect(parseMessageSortOrder(input)).toEqual(expected);
  });

  it('falls back to AREA ASC when input is undefined', () => {
    expect(parseMessageSortOrder(undefined)).toEqual({ sortBy: 'AREA', sortOrder: 'ASC' });
  });

  it('falls back to defaults when input is unrecognised', () => {
    expect(parseMessageSortOrder('NONSENSE')).toEqual({ sortBy: 'AREA', sortOrder: 'ASC' });
  });
});
