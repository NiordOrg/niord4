import { describe, expect, it } from 'vitest';

import { deriveViewMode } from './messages-shell.facade';

describe('deriveViewMode', () => {
  it.each([
    ['/messages/table', 'table'],
    ['/messages/grid', 'grid'],
    ['/messages/details', 'details'],
    ['/messages/map', 'map'],
    ['/messages/selected', 'selected'],
    ['/messages/table?domain=niord-dk', 'table'],
    ['/messages/map#frag', 'map'],
    ['/messages/', undefined],
    ['/other', undefined],
    // No view mode off the messages area — this is what drives `requiresSearch` false
    // while the user is away, so returning re-runs the search on an unchanged filter.
    ['/editor/edit/123', undefined],
    ['/editor/execute-template', undefined],
    ['/admin/areas', undefined],
    ['/', undefined],
  ])('derives %s -> %s', (url, expected) => {
    expect(deriveViewMode(url)).toBe(expected);
  });
});
