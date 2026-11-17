import type { Message } from '../../messages/models/message.model';

/**
 * Result of the publish/verify validation. `missingFieldKeys` are Transloco
 * keys for the structural fields; `missingPromulgations` are the display names of MANDATORY
 * promulgations not set to promulgate. The caller builds the toast from both.
 */
export interface PublishValidation {
  readonly ok: boolean;
  readonly missingFieldKeys: readonly string[];
  readonly missingPromulgations: readonly string[];
}

/**
 * Whether a message may be verified/published (the same gate governs BOTH actions):
 * a type, a message series, ≥1 non-empty title, and every MANDATORY-requirement promulgation set
 * to promulgate. Pure — the caller resolves field keys + appends promulgation names for the toast.
 */
export function canPublish(message: Message): PublishValidation {
  const missingFieldKeys: string[] = [];
  if (!message.type) {
    missingFieldKeys.push('editor.status.field.type');
  }
  if (!message.messageSeries) {
    missingFieldKeys.push('editor.status.field.series');
  }
  if (!hasNonEmptyTitle(message)) {
    missingFieldKeys.push('editor.status.field.title');
  }

  const missingPromulgations: string[] = [];
  for (const promulgation of message.promulgations ?? []) {
    if (promulgation.type.requirement === 'MANDATORY' && !promulgation.promulgate) {
      missingPromulgations.push(promulgation.type.name);
    }
  }

  return {
    ok: missingFieldKeys.length === 0 && missingPromulgations.length === 0,
    missingFieldKeys,
    missingPromulgations,
  };
}

function hasNonEmptyTitle(message: Message): boolean {
  return (message.descs ?? []).some((desc) => (desc.title?.trim().length ?? 0) > 0);
}
