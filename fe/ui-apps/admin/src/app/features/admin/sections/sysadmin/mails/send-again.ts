import {
  ScheduledMail,
  SendAgainItem,
  SendAgainStatus,
} from '../../../services/api/scheduled-mails-api.service';

/** The most distinct addresses a group's line names before it says how many more there are. */
export const SEND_AGAIN_ADDRESSES_SHOWN = 10;

/**
 * Whether a mail can be sent again: a held, sent or failed mail. A PENDING mail is on its way
 * already, and a mail in ERROR with no attempt is a failure record (a mailing-list trigger's "Not
 * sent: …" line, made in ERROR with 0 attempts), which never was a mail; the backend refuses both.
 */
export function canSendAgain(mail: ScheduledMail): boolean {
  switch (mail.status) {
    case 'HELD':
    case 'SENT':
      return true;
    case 'ERROR':
      return (mail.attempts ?? 0) > 0;
    default:
      return false;
  }
}

/** The request item of a mail: what the page showed, so the backend can tell a stale page. */
export function sendAgainItem(mail: ScheduledMail): SendAgainItem {
  return {
    id: mail.id,
    status: mail.status as SendAgainStatus,
    sentAgainAt: mail.sentAgainAt ?? null,
  };
}

/** Mails of one confirmation line: the same subject, status and creation minute. */
export interface SendAgainGroup {
  readonly subject: string;
  readonly status: string;
  /** Epoch millis of the group's oldest mail; undefined when no mail of it has one. */
  readonly created: number | undefined;
  readonly mails: readonly ScheduledMail[];
  /** Every distinct recipient address of the group's mails, in the order first met. */
  readonly addresses: readonly string[];
}

const MINUTE_MS = 60_000;

/**
 * Groups the mails a confirmation names, oldest first: a mailing list's run makes many mails with
 * one subject in the same minute, and one line for them reads better than a line each. The minute
 * is counted in epoch time, which every zone with a whole-minute offset agrees on.
 */
export function groupForSendAgain(mails: readonly ScheduledMail[]): SendAgainGroup[] {
  const groups = new Map<string, ScheduledMail[]>();
  for (const mail of mails) {
    const minute =
      mail.created === undefined || mail.created === null
        ? ''
        : String(Math.floor(mail.created / MINUTE_MS));
    const key = JSON.stringify([mail.subject, mail.status, minute]);
    const group = groups.get(key);
    if (group) {
      group.push(mail);
    } else {
      groups.set(key, [mail]);
    }
  }
  return [...groups.values()]
    .map((members): SendAgainGroup => {
      const created = members
        .map((mail) => mail.created)
        .filter((value): value is number => value !== undefined && value !== null);
      return {
        subject: members[0].subject,
        status: members[0].status,
        created: created.length > 0 ? Math.min(...created) : undefined,
        mails: members,
        addresses: [...new Set(members.flatMap((mail) => mail.recipients.map((r) => r.address)))],
      };
    })
    .sort((a, b) => byCreated(a.created, b.created));
}

/** Oldest first; a group without a creation time last. Array.sort is stable, so ties keep order. */
function byCreated(a: number | undefined, b: number | undefined): number {
  if (a === undefined || b === undefined) {
    return a === b ? 0 : a === undefined ? 1 : -1;
  }
  return a - b;
}
