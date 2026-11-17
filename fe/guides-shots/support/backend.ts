import { execFileSync } from 'node:child_process';

import type { APIRequestContext, APIResponse } from '@playwright/test';

import { BACKEND_URL, DB, DOMAIN } from './env';
import { tokenFor, type TestRole } from './login';

const englishByQuery = new Map<string, ReadonlySet<string>>();

/**
 * One query's output (unaligned, tuples only) from the copy's database, through psql in the
 * database container. The rig only reads the database; `what` names what was read, for the error.
 */
export function psql(query: string, what: string): string {
  try {
    return execFileSync(
      'docker',
      [
        'exec',
        DB.container,
        'psql',
        '-X',
        '-At',
        '-v',
        'ON_ERROR_STOP=1',
        '-U',
        DB.user,
        '-d',
        DB.name,
        '-c',
        query,
      ],
      { encoding: 'utf8', timeout: 120_000, windowsHide: true, maxBuffer: 1 << 28 },
    );
  } catch (error) {
    throw new Error(`The rig could not read ${what} from ${DB.name}: ${String(error)}`, {
      cause: error,
    });
  }
}

/**
 * The messages of a domain in a status that are English throughout, by their uid (the id the REST
 * answers carry): the copy's own rule, guides.english_messages, which english.sql makes. A message
 * qualifies when it has an English description, no field is blank in English while the Danish one
 * is filled (the administration app would show the Danish one), no English field holds a Danish
 * word (apart from the live messages read as names only), no name of its positions does, it is
 * not listed as no English text at all (guides.not_english), and no audio broadcast or NAVTEX
 * text of it is Danish. Read through psql in the database container, once per domain and status;
 * the rig only reads the database.
 */
export function englishMessageIds(domain: string, status: string): ReadonlySet<string> {
  if (!/^[\w-]+$/.test(domain) || !/^[A-Z_]+$/.test(status)) {
    throw new Error(`Not a domain and a status: ${domain}, ${status}`);
  }
  const query = `${domain}/${status}`;
  let ids = englishByQuery.get(query);
  if (!ids) {
    const output = psql(
      `SELECT uid FROM guides.english_messages('${domain}', '${status}') ` +
        `WHERE english_ok AND NOT promulgation_danish ORDER BY message_id`,
      'which messages are English throughout (guides.english_messages, made by data/english.sql)',
    );
    ids = new Set(output.split(/\r?\n/).filter(Boolean));
    englishByQuery.set(query, ids);
  }
  return ids;
}

/** A message as the searches below return it: the fields the rig reads. */
export interface MessageRef {
  readonly id: string;
  readonly parts?: readonly {
    readonly geometry?: { readonly features?: readonly unknown[] };
    readonly descs?: readonly { readonly lang: string; readonly subject?: string | null }[];
  }[];
}

/** The headers of a backend call made as a role, in a domain, the way the application makes it. */
export async function authHeaders(
  role: TestRole,
  domain: string = DOMAIN,
): Promise<Record<string, string>> {
  const bundle = await tokenFor(role);
  return { Authorization: `Bearer ${bundle.accessToken}`, NiordDomain: domain };
}

async function json<T>(response: APIResponse): Promise<T> {
  if (!response.ok()) {
    throw new Error(`${response.url()} answered ${response.status()} ${response.statusText()}`);
  }
  return (await response.json()) as T;
}

/** A message's English texts as an editor's search returns them: the publication lines. */
interface PublicationLines {
  readonly descs?: readonly {
    readonly lang: string;
    readonly publication?: string | null;
    readonly internalPublication?: string | null;
  }[];
}

/**
 * A published message of the domain that is English throughout (englishMessageIds), as an editor
 * searches them, the same one on every run of the same data: the first that has a geometry (so a
 * details view shows a map) and no publication line and no internal publication line in English
 * (the copy's publication lines cite the case numbers of an internal case system, and link to it);
 * else the first with a geometry; else the first.
 */
export async function publishedMessageId(
  request: APIRequestContext,
  domain: string = DOMAIN,
): Promise<string> {
  const english = englishMessageIds(domain, 'PUBLISHED');
  const result = await json<{ readonly data?: readonly (MessageRef & PublicationLines)[] }>(
    await request.get(`${BACKEND_URL}/rest/messages/search`, {
      params: { domain, status: 'PUBLISHED', lang: 'en', maxSize: 200 },
      headers: await authHeaders('editor', domain),
    }),
  );
  const messages = (result.data ?? []).filter((message) => english.has(message.id));
  const isDrawn = (message: MessageRef): boolean =>
    (message.parts ?? []).some((part) => (part.geometry?.features?.length ?? 0) > 0);
  const isPlain = (message: PublicationLines): boolean => {
    const desc = message.descs?.find((entry) => entry.lang === 'en');
    return !(desc?.publication ?? '').trim() && !(desc?.internalPublication ?? '').trim();
  };
  const message =
    messages.find((candidate) => isDrawn(candidate) && isPlain(candidate)) ??
    messages.find(isDrawn) ??
    messages[0];
  if (!message) {
    throw new Error(`The domain ${domain} has no published message that is English throughout.`);
  }
  return message.id;
}

/** A setting's stored value, read as a sysadmin (a password-typed value comes back masked). */
export async function settingValue(request: APIRequestContext, key: string): Promise<unknown> {
  const setting = await json<{ readonly value?: unknown }>(
    await request.get(`${BACKEND_URL}/rest/settings/setting/${encodeURIComponent(key)}`, {
      headers: await authHeaders('sysadmin'),
    }),
  );
  return setting.value;
}

/**
 * Whether a message reads as a finished example: every part has an English subject, so its title
 * says what happened ("Denmark. The Baltic Sea. Object adrift.") and not only where.
 */
export function hasSubject(message: MessageRef): boolean {
  const parts = message.parts ?? [];
  return (
    parts.length > 0 &&
    parts.every((part) =>
      part.descs?.some((desc) => desc.lang === 'en' && (desc.subject ?? '').trim() !== ''),
    )
  );
}

/**
 * The first message of the domain in a status that is English throughout (englishMessageIds) and
 * that `where` accepts (any, by default), searched as a role (a draft needs an editor), in the
 * search's own order, so the same data gives the same one.
 */
export async function messageIdWithStatus(
  request: APIRequestContext,
  role: TestRole,
  status: string,
  where: (message: MessageRef) => boolean = () => true,
  domain: string = DOMAIN,
): Promise<string> {
  const english = englishMessageIds(domain, status);
  const result = await json<{ readonly data?: readonly MessageRef[] }>(
    await request.get(`${BACKEND_URL}/rest/messages/search`, {
      params: { domain, status, lang: 'en', maxSize: 100 },
      headers: await authHeaders(role, domain),
    }),
  );
  const message = result.data?.find((candidate) => english.has(candidate.id) && where(candidate));
  if (!message) {
    throw new Error(
      `The domain ${domain} has no message in the status ${status} that is English throughout and fits.`,
    );
  }
  return message.id;
}
