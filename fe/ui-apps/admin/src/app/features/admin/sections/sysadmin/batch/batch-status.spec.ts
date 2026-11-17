import { describe, expect, it } from 'vitest';

import { BatchExecutionStatus } from '../../../services/api/batch-api.service';
import { batchStatusClass, batchStatusKey } from './batch-status';

describe('batchStatusClass', () => {
  it.each([
    ['STARTING', 'is-batch-running'],
    ['STARTED', 'is-batch-running'],
    ['STOPPING', 'is-batch-stopped'],
    ['STOPPED', 'is-batch-stopped'],
    ['FAILED', 'is-batch-failed'],
    ['COMPLETED', 'is-batch-completed'],
    ['ABANDONED', 'is-batch-default'],
  ] as const)('maps %s → %s', (status, expected) => {
    expect(batchStatusClass(status as BatchExecutionStatus)).toBe(expected);
  });

  it('maps an undefined/unknown status to the neutral default', () => {
    expect(batchStatusClass(undefined)).toBe('is-batch-default');
  });
});

describe('batchStatusKey', () => {
  it('keys the status label (never the raw enum)', () => {
    expect(batchStatusKey('COMPLETED')).toBe('admin.batch.status.COMPLETED');
  });

  it('keys an undefined status to UNKNOWN', () => {
    expect(batchStatusKey(undefined)).toBe('admin.batch.status.UNKNOWN');
  });
});
