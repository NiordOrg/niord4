import { BatchExecutionStatus } from '../../../services/api/batch-api.service';

/**
 * Exhaustive batch-status → Layer-3 `.status-badge` variant class map.
 * STARTING/STARTED read as in-progress (primary), the
 * stop family as a caution (warn), FAILED as danger, COMPLETED as success, and
 * ABANDONED / any unknown-or-undefined status falls to the neutral default —
 * never the raw enum.
 */
export function batchStatusClass(status: BatchExecutionStatus | undefined): string {
  switch (status) {
    case 'STARTING':
    case 'STARTED':
      return 'is-batch-running';
    case 'STOPPING':
    case 'STOPPED':
      return 'is-batch-stopped';
    case 'FAILED':
      return 'is-batch-failed';
    case 'COMPLETED':
      return 'is-batch-completed';
    case 'ABANDONED':
    default:
      return 'is-batch-default';
  }
}

/**
 * i18n key for the translated status label. Undefined/unknown statuses resolve
 * to a keyed "unknown" label (never the raw lowercased enum).
 */
export function batchStatusKey(status: BatchExecutionStatus | undefined): string {
  return `admin.batch.status.${status ?? 'UNKNOWN'}`;
}
