import {
  S124_DATASET_STATUSES,
  SECOM_OUTBOX_OPERATIONS,
  SECOM_OUTBOX_STATUSES,
  type S124DatasetStatus,
  type SecomOutboxOperation,
  type SecomOutboxStatus,
} from '../../../services/api/secom-admin-api.service';

/**
 * Backend enum → Transloco key. The backend values are wire constants, so they
 * are mapped to an explicit, closed key space here rather than interpolated into
 * a key at the render site — a value the frontend does not know about then falls
 * back to a readable label instead of painting a missing-key placeholder.
 *
 * The maps are exhaustive by construction (`Record<Union, string>`), so adding a
 * status or operation to the API model is a compile error until it is named here.
 */
export const SECOM_OUTBOX_STATUS_KEYS: Readonly<Record<SecomOutboxStatus, string>> = {
  PENDING: 'admin.secom.outboxStatus.pending',
  SENT: 'admin.secom.outboxStatus.sent',
  ACKED: 'admin.secom.outboxStatus.acked',
  OPENED: 'admin.secom.outboxStatus.opened',
  FAILED: 'admin.secom.outboxStatus.failed',
  CANCELLED: 'admin.secom.outboxStatus.cancelled',
};

export const S124_DATASET_STATUS_KEYS: Readonly<Record<S124DatasetStatus, string>> = {
  ACTIVE: 'admin.secom.datasetStatus.active',
  CANCELLED: 'admin.secom.datasetStatus.cancelled',
};

export const SECOM_OPERATION_KEYS: Readonly<Record<SecomOutboxOperation, string>> = {
  UPLOAD: 'admin.secom.operation.upload',
  SUBSCRIPTION_NOTIFICATION: 'admin.secom.operation.subscriptionNotification',
};

const KNOWN_OUTBOX_STATUSES: ReadonlySet<string> = new Set(SECOM_OUTBOX_STATUSES);
const KNOWN_DATASET_STATUSES: ReadonlySet<string> = new Set(S124_DATASET_STATUSES);
const KNOWN_OPERATIONS: ReadonlySet<string> = new Set(SECOM_OUTBOX_OPERATIONS);

/** Shown verbatim when the backend sends a value this build does not know. */
const UNKNOWN_KEY = 'admin.secom.unknownValue';

export function outboxStatusKey(status: string): string {
  return KNOWN_OUTBOX_STATUSES.has(status)
    ? SECOM_OUTBOX_STATUS_KEYS[status as SecomOutboxStatus]
    : UNKNOWN_KEY;
}

export function datasetStatusKey(status: string): string {
  return KNOWN_DATASET_STATUSES.has(status)
    ? S124_DATASET_STATUS_KEYS[status as S124DatasetStatus]
    : UNKNOWN_KEY;
}

export function operationKey(operation: string): string {
  return KNOWN_OPERATIONS.has(operation)
    ? SECOM_OPERATION_KEYS[operation as SecomOutboxOperation]
    : UNKNOWN_KEY;
}

/**
 * Layer-3 `--status-*` class for an outbox status. Unknown values fall through
 * to the `.status-badge` default (neutral muted), which is the intended
 * "undefined status" treatment.
 */
export function outboxStatusClass(status: string): string {
  return KNOWN_OUTBOX_STATUSES.has(status) ? `is-secom-${status.toLowerCase()}` : '';
}

export function datasetStatusClass(status: string): string {
  return KNOWN_DATASET_STATUSES.has(status) ? `is-secom-dataset-${status.toLowerCase()}` : '';
}
