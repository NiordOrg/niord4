import { MessageStatus } from './message-status.model';

/**
 * The single source of truth for valid manual (user-initiated) message status
 * transitions. Terminal statuses (CANCELLED / EXPIRED / DELETED) have no outgoing
 * transitions.
 *
 * NOTE: `PUBLISHED → EXPIRED` is intentionally NOT offered. Per the editor manual
 * the PUBLISHED → EXPIRED transition is performed by the SYSTEM (on the message's
 * expiry date), never manually by a user, so the user-facing map stops at
 * `PUBLISHED → CANCELLED`.
 */
export const VALID_STATUS_TRANSITIONS: Readonly<Record<MessageStatus, readonly MessageStatus[]>> = {
  DRAFT: ['VERIFIED', 'DELETED'],
  VERIFIED: ['PUBLISHED', 'DRAFT', 'DELETED'],
  PUBLISHED: ['CANCELLED'],
  CANCELLED: [],
  EXPIRED: [],
  DELETED: [],
};

/** Valid target statuses for a current status (empty for terminal/unknown statuses). */
export function validTransitionsFor(status: MessageStatus | undefined): readonly MessageStatus[] {
  return status ? (VALID_STATUS_TRANSITIONS[status] ?? []) : [];
}
