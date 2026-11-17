import type { APIRequestContext } from '@playwright/test';

import { scenarioEnv } from './env';

/*
 * The mails the backend sent, read from MailHog's API. They reach MailHog only when the backend's
 * `mailValidRecipients` is ALL (the scenarios' README starts the backend with
 * -DmailValidRecipients=ALL); the default, LOG, only logs a mail. The backend's queue sends ten
 * mails a minute, so a mail can take a few minutes.
 */

export interface Mail {
  readonly id: string;
  readonly subject: string;
  readonly to: readonly string[];
  /** The body as text: the parts decoded, markup removed. */
  readonly text: string;
}

interface MailHogItem {
  readonly ID: string;
  readonly Content: {
    readonly Headers: Record<string, string[] | undefined>;
    readonly Body: string;
  };
  readonly MIME?: { readonly Parts?: readonly MailHogPart[] } | null;
  readonly Raw: { readonly To: readonly string[] };
}

interface MailHogPart {
  readonly Headers: Record<string, string[] | undefined>;
  readonly Body: string;
  readonly MIME?: { readonly Parts?: readonly MailHogPart[] } | null;
}

const utf8 = (binary: string): string =>
  new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));

/** A header's MIME encoded words ('=?UTF-8?Q?...?=') decoded. */
function decodeHeader(text: string): string {
  return text
    .replace(/\?=\s+=\?/g, '?==?')
    .replace(/=\?[^?]+\?([QqBb])\?([^?]*)\?=/g, (_all, encoding: string, data: string) =>
      encoding.toUpperCase() === 'B'
        ? utf8(atob(data))
        : utf8(
            data
              .replace(/_/g, ' ')
              .replace(/=([0-9A-Fa-f]{2})/g, (_hex, hex: string) =>
                String.fromCharCode(parseInt(hex, 16)),
              ),
          ),
    );
}

function decodeBody(headers: Record<string, string[] | undefined>, body: string): string {
  const encoding = (headers['Content-Transfer-Encoding']?.[0] ?? '').toLowerCase();
  if (encoding === 'base64') return utf8(atob(body.replace(/\s+/g, '')));
  if (encoding === 'quoted-printable') {
    return utf8(
      body
        .replace(/=\r?\n/g, '')
        .replace(/=([0-9A-Fa-f]{2})/g, (_hex, hex: string) =>
          String.fromCharCode(parseInt(hex, 16)),
        ),
    );
  }
  return body;
}

function textOfParts(parts: readonly MailHogPart[]): string {
  return parts
    .map((part) =>
      part.MIME?.Parts?.length ? textOfParts(part.MIME.Parts) : decodeBody(part.Headers, part.Body),
    )
    .join(' ');
}

function toMail(item: MailHogItem): Mail {
  const body = item.MIME?.Parts?.length
    ? textOfParts(item.MIME.Parts)
    : decodeBody(item.Content.Headers, item.Content.Body);
  return {
    id: item.ID,
    subject: decodeHeader(item.Content.Headers['Subject']?.[0] ?? ''),
    to: item.Raw.To,
    text: body
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/&#39;|&apos;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&amp;/g, '&')
      .replace(/\s+/g, ' ')
      .trim(),
  };
}

function mailhogUrl(): string {
  if (!scenarioEnv.mailhogUrl) throw new Error('E2E_MAILHOG_URL is not set');
  return scenarioEnv.mailhogUrl;
}

/** Every mail MailHog holds to the address. */
export async function mailsTo(request: APIRequestContext, address: string): Promise<Mail[]> {
  const mails: Mail[] = [];
  for (let start = 0; ; start += 250) {
    const response = await request.get(`${mailhogUrl()}/api/v2/search`, {
      params: { kind: 'to', query: address, start, limit: 250 },
    });
    if (!response.ok()) {
      throw new Error(`MailHog answered ${response.status()} at ${mailhogUrl()}`);
    }
    const page = (await response.json()) as { total: number; items: MailHogItem[] };
    mails.push(...page.items.map(toMail));
    if (page.items.length === 0 || mails.length >= page.total) return mails;
  }
}

/**
 * Waits until MailHog holds `count` mails to the address that match, then waits a little longer
 * and returns them all, so that a mail sent twice is seen. Fails when fewer arrive in time.
 */
export async function waitForMails(
  request: APIRequestContext,
  address: string,
  matches: (mail: Mail) => boolean,
  count: number,
  timeoutMs = 180_000,
): Promise<Mail[]> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const found = (await mailsTo(request, address)).filter(matches);
    if (found.length >= count) {
      await new Promise((resolve) => setTimeout(resolve, 5000));
      return (await mailsTo(request, address)).filter(matches);
    }
    if (Date.now() > deadline) {
      throw new Error(
        `${found.length} of ${count} mail(s) to ${address} reached MailHog within ` +
          `${timeoutMs / 1000} s. The backend sends to MailHog only when mailValidRecipients is ` +
          'ALL (-DmailValidRecipients=ALL; the default LOG only logs a mail); the sysadmin mail ' +
          'queue shows whether the mail was queued.',
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
}
