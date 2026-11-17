import { describe, expect, it } from 'vitest';

import { VALID_STATUS_TRANSITIONS, validTransitionsFor } from './message-status-transitions';

describe('VALID_STATUS_TRANSITIONS', () => {
  it('defines the manual-transition map (no user-initiated PUBLISHED → EXPIRED)', () => {
    expect(VALID_STATUS_TRANSITIONS.DRAFT).toEqual(['VERIFIED', 'DELETED']);
    expect(VALID_STATUS_TRANSITIONS.VERIFIED).toEqual(['PUBLISHED', 'DRAFT', 'DELETED']);
    expect(VALID_STATUS_TRANSITIONS.PUBLISHED).toEqual(['CANCELLED']);
    // The system expires published messages — the user never does it manually.
    expect(VALID_STATUS_TRANSITIONS.PUBLISHED).not.toContain('EXPIRED');
  });

  it('terminal statuses offer no transitions', () => {
    expect(VALID_STATUS_TRANSITIONS.CANCELLED).toEqual([]);
    expect(VALID_STATUS_TRANSITIONS.EXPIRED).toEqual([]);
    expect(VALID_STATUS_TRANSITIONS.DELETED).toEqual([]);
  });

  it('validTransitionsFor handles undefined and terminal statuses', () => {
    expect(validTransitionsFor(undefined)).toEqual([]);
    expect(validTransitionsFor('PUBLISHED')).toEqual(['CANCELLED']);
    expect(validTransitionsFor('EXPIRED')).toEqual([]);
  });
});
