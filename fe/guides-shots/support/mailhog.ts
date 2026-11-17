import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

import { authHeaders } from './backend';
import { danishReason } from './danish';
import { BACKEND_URL, DOMAIN, MAILHOG_URL } from './env';

/** The address the rig's mails go to: inside the scrubbed data's domain, caught by MailHog. */
export const GUIDES_MAIL_TO = 'guides@example.org';

interface MailHogSearch {
  readonly total: number;
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** How many mails MailHog holds whose text contains `subject`. */
export async function mailCount(request: APIRequestContext, subject: string): Promise<number> {
  const response = await request.get(`${MAILHOG_URL}/api/v2/search`, {
    params: { kind: 'containing', query: subject },
  });
  if (!response.ok()) throw new Error(`MailHog answered ${response.status()} at ${MAILHOG_URL}.`);
  return ((await response.json()) as MailHogSearch).total;
}

/**
 * Has the backend mail one message to GUIDES_MAIL_TO, as an editor does from the message list's
 * mail action: the backend's own template, queued and sent by its mail job.
 */
export async function sendMessageMail(
  request: APIRequestContext,
  messageId: string,
  subject: string,
  domain: string = DOMAIN,
): Promise<void> {
  const response = await request.get(`${BACKEND_URL}/rest/message-mail/send`, {
    params: { mailTo: GUIDES_MAIL_TO, mailSubject: subject, messageId, lang: 'en' },
    headers: await authHeaders('editor', domain),
  });
  if (!response.ok()) {
    throw new Error(`Sending the mail failed: ${response.status()} ${await response.text()}`);
  }
}

/**
 * Waits until MailHog holds more than `before` mails with the subject. The backend's
 * `mailValidRecipients` setting decides whether a queued mail is sent at all: LOG, the default,
 * only logs it, and then none ever arrives.
 */
export async function waitForMail(
  request: APIRequestContext,
  subject: string,
  before: number,
  timeoutMs = 150_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await mailCount(request, subject)) > before) return;
    await pause(3000);
  }
  throw new Error(
    `No mail "${subject}" reached MailHog within ${timeoutMs / 1000} s. The database's ` +
      'mailValidRecipients setting must be ALL (MailHog catches every mail of the local backend); ' +
      'LOG only logs a mail. The sysadmin mail queue shows whether it was queued.',
  );
}

interface MailHogItem {
  readonly ID: string;
  readonly Content: { readonly Headers: Record<string, string[] | undefined> };
  readonly Raw: { readonly To: readonly string[] };
}

/** A header's MIME encoded words ('=?UTF-8?Q?Gr=C3=B8nland?=') decoded, as UTF-8. */
function decodeHeader(text: string): string {
  const utf8 = (binary: string): string =>
    new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));
  return text.replace(/=\?[^?]+\?([QqBb])\?([^?]*)\?=/g, (_all, encoding: string, data: string) =>
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

/**
 * Deletes from MailHog the mails the copy sent in Danish: a Danish subject (the guard's rules,
 * danish.ts) and every recipient on example.org, the scrubbed data's domain. They were sent
 * before english.sql made every contact English (the daily 'Aktive navigationsadvarsler' of the
 * mailing lists), and MailHog's list would show their subjects. Another local stack's mails (no
 * example.org recipient) are left alone. Returns how many were deleted.
 */
export async function deleteDanishMail(request: APIRequestContext): Promise<number> {
  const items: MailHogItem[] = [];
  for (let start = 0; ; start += 250) {
    const response = await request.get(`${MAILHOG_URL}/api/v2/messages`, {
      params: { start, limit: 250 },
    });
    if (!response.ok()) throw new Error(`MailHog answered ${response.status()} at ${MAILHOG_URL}.`);
    const page = (await response.json()) as { total: number; items: MailHogItem[] };
    items.push(...page.items);
    if (page.items.length === 0 || items.length >= page.total) break;
  }
  let deleted = 0;
  for (const item of items) {
    const subject = decodeHeader(item.Content.Headers['Subject']?.[0] ?? '');
    const copyOnly =
      item.Raw.To.length > 0 && item.Raw.To.every((to) => /@example\.org>?\s*$/i.test(to));
    if (!copyOnly || !danishReason(subject)) continue;
    const response = await request.delete(`${MAILHOG_URL}/api/v1/messages/${item.ID}`);
    if (response.ok()) deleted++;
  }
  return deleted;
}

/**
 * Opens MailHog's web page on the newest mail with the subject, its HTML part shown. MailHog lists
 * the newest first, and also holds what the backend's mailing lists sent meanwhile to their
 * invented recipients, so the mail is picked by its subject; the copy's Danish mails are deleted
 * first (deleteDanishMail), so the list beside the mail shows none. Returns the mail's own view
 * (its headers and body) for shot() to frame: MailHog's side column counts the mails it holds,
 * which changes with every run.
 */
export async function openNewestMail(page: Page, subject: string): Promise<Locator> {
  await deleteDanishMail(page.request);
  await page.goto(MAILHOG_URL);
  const newest = page.locator('.msglist-message', { hasText: subject }).first();
  await expect(newest).toBeVisible();
  await newest.click();
  const preview = page.locator('iframe').first();
  await expect(preview).toBeVisible();
  await expect(preview.contentFrame().locator('body')).toBeVisible();
  return page.locator('div.preview');
}
