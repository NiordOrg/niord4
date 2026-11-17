/**
 * Mailing-list domain model shared by the API adapter, the list/editor, the trigger cards, and
 * the recipients + test surfaces. Wire-facing shapes (VO ↔ domain mapping) live in the API
 * service; this file owns the value shapes and the single-source-of-truth literal unions.
 */

import { formatWallClockTime, parseWallClockTime } from '../../../../../shared/utils/zoned-date';

/** Trigger kinds. `as const` array is the single source — the union derives from it. */
export const MAILING_LIST_TRIGGER_TYPES = ['STATUS_CHANGE', 'SCHEDULED'] as const;
export type MailingListTriggerType = (typeof MAILING_LIST_TRIGGER_TYPES)[number];

/** The fixed set of message statuses a STATUS_CHANGE trigger can fire on. */
export const TRIGGER_STATUS_CHANGES = ['PUBLISHED', 'CANCELLED', 'EXPIRED'] as const;
export type TriggerStatusChange = (typeof TRIGGER_STATUS_CHANGES)[number];

/** Schedule cadence for a SCHEDULED trigger (DAILY + the seven weekdays). */
export const TRIGGER_SCHEDULE_TYPES = [
  'DAILY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;
export type TriggerScheduleType = (typeof TRIGGER_SCHEDULE_TYPES)[number];

/** Per-language mailing-list desc (name required for the first language; description optional). */
export interface MailingListDesc {
  readonly lang: string;
  readonly name: string;
  readonly description: string;
}

/** Per-language trigger desc — the mail subject for one language. */
export interface MailingListTriggerDesc {
  readonly lang: string;
  readonly subject: string;
}

interface MailingListTriggerBase {
  readonly type: MailingListTriggerType;
  readonly descs: readonly MailingListTriggerDesc[];
  /** Ordered FTL/JS script-resource paths — the "Mail Body". */
  readonly scriptResourcePaths: readonly string[];
}

export interface StatusChangeTrigger extends MailingListTriggerBase {
  readonly type: 'STATUS_CHANGE';
  readonly statusChanges: readonly TriggerStatusChange[];
  readonly messageFilter?: string;
}

export interface ScheduledTrigger extends MailingListTriggerBase {
  readonly type: 'SCHEDULED';
  readonly scheduleType: TriggerScheduleType;
  /** 24h `HH:mm`. */
  readonly scheduledExecutionTime?: string;
  /** IANA time-zone name. */
  readonly scheduledExecutionTimeZone?: string;
  readonly messageQuery?: string;
  readonly publicReport?: boolean;
}

export type MailingListTrigger = StatusChangeTrigger | ScheduledTrigger;

// The public mailing-list REPORT type + its read API live in the messages feature (which owns the
// reports dialog) — `messages/services/api/mailing-list-reports-api.service.ts` — so the
// admin→messages feature dependency stays one-way. The admin section does not consume reports.

/** A mailing list. List rows carry `recipientNo`; full details additionally carry `triggers`. */
export interface MailingList {
  /** The user-entered string PK — immutable after create (the PUT/DELETE path id). */
  readonly mailingListId: string;
  readonly active: boolean;
  readonly descs: readonly MailingListDesc[];
  /** Recipient count — present on list rows. */
  readonly recipientNo?: number;
  /** Present on the full details fetch; the list summary omits it. */
  readonly triggers?: readonly MailingListTrigger[];
}

// ── Editor form models ────────────────────────────────────────────────────────
// A trigger is edited through a FLAT form model holding BOTH variants' fields (the type toggle
// just re-renders / re-serializes). This keeps the trigger array a single Signal Forms value and
// avoids destructive field deletion on every type switch — the field-deletion is realized
// at serialization time (only the active variant's fields are written).

/** One per-language subject row with the enable flag (UI-only). */
export interface TriggerSubjectForm {
  lang: string;
  subject: string;
  enabled: boolean;
}

export interface TriggerFormModel {
  type: MailingListTriggerType;
  subjects: TriggerSubjectForm[];
  scriptResourcePaths: string[];
  // STATUS_CHANGE
  statusPublished: boolean;
  statusCancelled: boolean;
  statusExpired: boolean;
  messageFilter: string;
  // SCHEDULED
  scheduleType: TriggerScheduleType;
  /** A Date carrying only hours/minutes (the shared time-field's value shape); null when unset. */
  scheduledExecutionTime: Date | null;
  scheduledExecutionTimeZone: string;
  messageQuery: string;
  publicReport: boolean;
  // UI-only (never serialized): the card's expand/collapse.
  expanded: boolean;
}

/** `HH:mm` (24h) → a Date carrying those hours/minutes on a fixed epoch day; invalid input → null. */
export function timeStringToDate(value: string | undefined): Date | null {
  return parseWallClockTime(value);
}

/** A Date carrying hours/minutes → `HH:mm` (24h); null → undefined. */
export function dateToTimeString(value: Date | null): string | undefined {
  return formatWallClockTime(value);
}

/**
 * Seed a trigger form model from a wire trigger, padding subjects to every configured language
 * (a language is "enabled" iff the wire trigger carried a desc for it — absence ⇔ disabled).
 * Unconfigured-language subjects are preserved at the end.
 */
export function triggerToForm(
  trigger: MailingListTrigger,
  languages: readonly string[],
): TriggerFormModel {
  const bySubjectLang = new Map(trigger.descs.map((desc) => [desc.lang, desc.subject]));
  const known = new Set(languages);
  const subjects: TriggerSubjectForm[] = [
    ...languages.map((lang) => ({
      lang,
      subject: bySubjectLang.get(lang) ?? '',
      enabled: bySubjectLang.has(lang),
    })),
    ...trigger.descs
      .filter((desc) => !known.has(desc.lang))
      .map((desc) => ({ lang: desc.lang, subject: desc.subject, enabled: true })),
  ];
  const statusChanges =
    trigger.type === 'STATUS_CHANGE'
      ? trigger.statusChanges
      : ([] as readonly TriggerStatusChange[]);
  return {
    type: trigger.type,
    subjects,
    scriptResourcePaths: [...trigger.scriptResourcePaths],
    statusPublished: statusChanges.includes('PUBLISHED'),
    statusCancelled: statusChanges.includes('CANCELLED'),
    statusExpired: statusChanges.includes('EXPIRED'),
    messageFilter: trigger.type === 'STATUS_CHANGE' ? (trigger.messageFilter ?? '') : '',
    scheduleType: trigger.type === 'SCHEDULED' ? trigger.scheduleType : 'DAILY',
    scheduledExecutionTime:
      trigger.type === 'SCHEDULED' ? timeStringToDate(trigger.scheduledExecutionTime) : null,
    scheduledExecutionTimeZone:
      trigger.type === 'SCHEDULED' ? (trigger.scheduledExecutionTimeZone ?? '') : '',
    messageQuery: trigger.type === 'SCHEDULED' ? (trigger.messageQuery ?? '') : '',
    publicReport: trigger.type === 'SCHEDULED' ? (trigger.publicReport ?? false) : false,
    // A scheduled trigger missing its zone cannot be saved, and a collapsed card would leave
    // Save shut with nothing on screen naming the reason — so the card opens on the fault.
    expanded: trigger.type === 'SCHEDULED' && !trigger.scheduledExecutionTimeZone,
  };
}

/** A fresh trigger form: STATUS_CHANGE, PUBLISHED pre-checked, expanded, all languages enabled. */
export function newTriggerForm(languages: readonly string[]): TriggerFormModel {
  return {
    type: 'STATUS_CHANGE',
    subjects: languages.map((lang) => ({ lang, subject: '', enabled: true })),
    scriptResourcePaths: [],
    statusPublished: true,
    statusCancelled: false,
    statusExpired: false,
    messageFilter: '',
    scheduleType: 'DAILY',
    scheduledExecutionTime: null,
    scheduledExecutionTimeZone: '',
    messageQuery: '',
    publicReport: false,
    expanded: true,
  };
}

/**
 * Serialize a trigger form model to the wire union — the disabled-language subjects and the
 * inactive variant's fields (and the UI-only `expanded`) are dropped here, keeping the payload
 * clean.
 */
export function formToTrigger(model: TriggerFormModel): MailingListTrigger {
  const descs: MailingListTriggerDesc[] = model.subjects
    .filter((subject) => subject.enabled)
    .map((subject) => ({ lang: subject.lang, subject: subject.subject }));
  if (model.type === 'STATUS_CHANGE') {
    const statusChanges: TriggerStatusChange[] = [];
    if (model.statusPublished) statusChanges.push('PUBLISHED');
    if (model.statusCancelled) statusChanges.push('CANCELLED');
    if (model.statusExpired) statusChanges.push('EXPIRED');
    return {
      type: 'STATUS_CHANGE',
      descs,
      scriptResourcePaths: [...model.scriptResourcePaths],
      statusChanges,
      messageFilter: model.messageFilter,
    };
  }
  return {
    type: 'SCHEDULED',
    descs,
    scriptResourcePaths: [...model.scriptResourcePaths],
    scheduleType: model.scheduleType,
    scheduledExecutionTime: dateToTimeString(model.scheduledExecutionTime),
    scheduledExecutionTimeZone: model.scheduledExecutionTimeZone || undefined,
    messageQuery: model.messageQuery,
    publicReport: model.publicReport,
  };
}
