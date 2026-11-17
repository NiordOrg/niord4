import { describe, expect, it } from 'vitest';

import enBundle from '../../../../../../../../../languages/en/i18n/admin/en.json';
import {
  S124_DATASET_STATUSES,
  SECOM_OUTBOX_OPERATIONS,
  SECOM_OUTBOX_STATUSES,
} from '../../../services/api/secom-admin-api.service';
import {
  S124_DATASET_STATUS_KEYS,
  SECOM_OPERATION_KEYS,
  SECOM_OUTBOX_STATUS_KEYS,
  datasetStatusClass,
  datasetStatusKey,
  operationKey,
  outboxStatusClass,
  outboxStatusKey,
} from './secom-labels';

/** Resolve a dotted Transloco key against the shipped bundle. */
function lookup(key: string): unknown {
  return key.split('.').reduce<unknown>((node, segment) => {
    if (typeof node !== 'object' || node === null) {
      return undefined;
    }
    return (node as Record<string, unknown>)[segment];
  }, enBundle);
}

describe('secom label keys', () => {
  it('names every outbox status, dataset status and operation the API model declares', () => {
    expect(Object.keys(SECOM_OUTBOX_STATUS_KEYS).sort()).toEqual([...SECOM_OUTBOX_STATUSES].sort());
    expect(Object.keys(S124_DATASET_STATUS_KEYS).sort()).toEqual([...S124_DATASET_STATUSES].sort());
    expect(Object.keys(SECOM_OPERATION_KEYS).sort()).toEqual([...SECOM_OUTBOX_OPERATIONS].sort());
  });

  it('resolves every declared key in the shipped en bundle', () => {
    const keys = [
      ...Object.values(SECOM_OUTBOX_STATUS_KEYS),
      ...Object.values(S124_DATASET_STATUS_KEYS),
      ...Object.values(SECOM_OPERATION_KEYS),
      'admin.secom.unknownValue',
    ];
    for (const key of keys) {
      expect(typeof lookup(key), `missing translation for ${key}`).toBe('string');
    }
  });

  it('maps known wire values to their key', () => {
    expect(outboxStatusKey('FAILED')).toBe('admin.secom.outboxStatus.failed');
    expect(datasetStatusKey('ACTIVE')).toBe('admin.secom.datasetStatus.active');
    expect(operationKey('SUBSCRIPTION_NOTIFICATION')).toBe(
      'admin.secom.operation.subscriptionNotification',
    );
  });

  it('falls back to a readable label for a value this build does not know', () => {
    expect(outboxStatusKey('WARP_SPEED')).toBe('admin.secom.unknownValue');
    expect(datasetStatusKey('')).toBe('admin.secom.unknownValue');
    expect(operationKey('TELEPORT')).toBe('admin.secom.unknownValue');
  });
});

describe('secom status classes', () => {
  it('maps each known status to its Layer-3 state class', () => {
    expect(outboxStatusClass('PENDING')).toBe('is-secom-pending');
    expect(outboxStatusClass('ACKED')).toBe('is-secom-acked');
    expect(datasetStatusClass('CANCELLED')).toBe('is-secom-dataset-cancelled');
  });

  it('leaves an unknown status on the neutral .status-badge default', () => {
    expect(outboxStatusClass('WARP_SPEED')).toBe('');
    expect(datasetStatusClass('WARP_SPEED')).toBe('');
  });
});
