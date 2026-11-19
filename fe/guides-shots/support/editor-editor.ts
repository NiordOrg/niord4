import type { Locator, Page } from '@playwright/test';

import { psql } from './backend';
import { fixClock } from './clock';
import { expect } from './fixtures';
import { loginAs, type TestRole } from './login';

/*
 * Helpers of the editor manual's Editor chapter (editor-manual-editor.shots.ts): the example
 * messages it opens, picked by a rule from the copy's database (read only), and the editor's field
 * rows. Nothing here saves a message: a picture of a form shows it filled in and unsaved.
 */

/** The English live messages of a domain in a status: the copy's own rule (english.sql). */
function english(domain: string, status: string): string {
  return (
    `guides.english_messages('${domain}', '${status}') x join "Message" m on m.id = x.message_id ` +
    `where x.english_ok and not x.promulgation_danish`
  );
}

const PARTS = `(select string_agg(p.type, '+' order by p."indexNo") from "MessagePart" p where p.message_id = m.id)`;

/**
 * The example messages, each the first (by id) the rule accepts, so the same data gives the same
 * message on every run. Every rule starts from the English messages of guides.english_messages.
 */
const RULES = {
  /** A draft NW made from a template, with NAVTEX on and an event-date interval. */
  nwDraft:
    `select m.uid from ${english('niord-nw', 'DRAFT')}` +
    ` and exists (select 1 from "Message_Category" mc join "Category" c on c.id = mc."categories_id"` +
    ` where mc."Message_id" = m.id and c.type = 'TEMPLATE')` +
    ` and exists (select 1 from "BaseMessagePromulgation" b join "PromulgationType" t on t.id = b.type_id` +
    ` where b.message_id = m.id and t."typeId" = 'navtex' and b.promulgate)` +
    ` and exists (select 1 from "DateInterval" di join "MessagePart" p on p.id = di."messagePart_id"` +
    ` where p.message_id = m.id) order by m.id`,
  /**
   * A draft NW with two event-date intervals in one part (the seed's exercise draft). The parts are
   * counted in one pass: a count per part of every message ran past the two minutes psql gets.
   */
  nwTwoIntervals:
    `select m.uid from ${english('niord-nw', 'DRAFT')}` +
    ` and m.id in (select p.message_id from "DateInterval" di join "MessagePart" p` +
    ` on p.id = di."messagePart_id" group by p.id, p.message_id having count(*) >= 2) order by m.id`,
  /** A draft NM with a details part and a note part, a reference, charts, a source and an end date. */
  nmDraft:
    `select m.uid from ${english('niord-nm', 'DRAFT')} and ${PARTS} = 'DETAILS+NOTE'` +
    ` and exists (select 1 from "Reference" r where r.message_id = m.id)` +
    ` and (select count(*) from "Message_Chart" c where c."Message_id" = m.id) >= 3` +
    ` and exists (select 1 from "MessageDesc" d where d.entity_id = m.id and d.lang = 'en'` +
    ` and coalesce(d.source, '') <> '') and m."publishDateTo" is not null order by m.id`,
  /** A draft NM of two details parts, each with a subject. */
  nmTwoDetails:
    `select m.uid from ${english('niord-nm', 'DRAFT')} and ${PARTS} = 'DETAILS+DETAILS'` +
    ` and not exists (select 1 from "MessagePart" p left join "MessagePartDesc" pd` +
    ` on pd.entity_id = p.id and pd.lang = 'en' where p.message_id = m.id` +
    ` and coalesce(pd.subject, '') = '') order by m.id`,
  /**
   * A draft NM with an attachment whose file the copy holds (a file missing from the repository
   * makes its icon answer 404).
   */
  nmAttachments:
    `select m.uid from ${english('niord-nm', 'DRAFT')}` +
    ` and exists (select 1 from "Attachment" a join "RepoFile" f on f.path = a.path` +
    ` where a.message_id = m.id) order by m.id`,
  /** A draft NM citing a publication, whose internal publication text names no case system. */
  nmPublication:
    `select m.uid from ${english('niord-nm', 'DRAFT')} and exists (select 1 from "MessageDesc" d` +
    ` where d.entity_id = m.id and d.lang = 'en' and coalesce(d.publication, '') like '%publication=%'` +
    ` and coalesce(d."internalPublication", '') not like '%case%'` +
    ` and coalesce(d."internalPublication", '') not like '%esdh%') order by m.id`,
  /** A verified NW with NAVTEX on, an unacknowledged comment and no scheduled publish date. */
  nwVerified:
    `select m.uid from ${english('niord-nw', 'VERIFIED')}` +
    ` and exists (select 1 from "BaseMessagePromulgation" b join "PromulgationType" t on t.id = b.type_id` +
    ` where b.message_id = m.id and t."typeId" = 'navtex' and b.promulgate)` +
    ` and exists (select 1 from "Comment" c where c.message_id = m.id and c."acknowledgeDate" is null)` +
    ` and m."publishDateFrom" is null order by m.id`,
  /**
   * A verified NW that cancels a published message (a reference of the type CANCELLATION, stored
   * as its ordinal 3), so the publish dialog offers to cancel that one too.
   */
  nwCancelling:
    `select m.uid from ${english('niord-nw', 'VERIFIED')}` +
    ` and exists (select 1 from "Reference" r join "Message" p on p."shortId" = r."messageId"` +
    ` where r.message_id = m.id and r.type = 3 and p.status = 'PUBLISHED') order by m.id`,
  /** A draft NW with two or more comments, one of them not acknowledged. */
  nwCommented:
    `select m.uid from ${english('niord-nw', 'DRAFT')}` +
    ` and (select count(*) from "Comment" c where c.message_id = m.id) >= 2` +
    ` and exists (select 1 from "Comment" c where c.message_id = m.id and c."acknowledgeDate" is null)` +
    ` order by m.id`,
  /**
   * A verified NM saved at least twice. A notice has no promulgations, whose stored data names the
   * classes of a country's channels in the history's data view.
   */
  nmVerified:
    `select m.uid from ${english('niord-nm', 'VERIFIED')}` +
    ` and (select count(*) from "MessageHistory" h where h.message_id = m.id) >= 2` +
    ` and not exists (select 1 from "BaseMessagePromulgation" b where b.message_id = m.id)` +
    ` order by m.id`,
  /** The most recently published NM of a details part and a note part that is in publications. */
  nmPublished:
    `select m.uid from ${english('niord-nm', 'PUBLISHED')} and ${PARTS} = 'DETAILS+NOTE'` +
    ` and exists (select 1 from "IssueMember" im where im."messageUid" = m.uid)` +
    ` order by m."publishDateFrom" desc, m.id`,
  /**
   * As nmPublished, but in no open issue that holds a frozen list of members: the copy has such
   * issues (imported weeks never released), which the Publications panel once labelled Published
   * without a date; it now answers them by their rule, and the picture keeps to the message it was
   * taken on.
   */
  nmInPublications:
    `select m.uid from ${english('niord-nm', 'PUBLISHED')} and ${PARTS} = 'DETAILS+NOTE'` +
    ` and exists (select 1 from "IssueMember" im join "PublicationIssue" i on i.id = im.issue_id` +
    ` where im."messageUid" = m.uid and i.status = 'PUBLISHED')` +
    ` and not exists (select 1 from "IssueMember" im join "PublicationIssue" i on i.id = im.issue_id` +
    ` where im."messageUid" = m.uid and i.status = 'OPEN' and i."snapshotFrozenAt" is not null)` +
    ` order by m."publishDateFrom" desc, m.id`,
  /** A published NM with a PDF attachment. */
  nmPublishedAttachments:
    `select m.uid from ${english('niord-nm', 'PUBLISHED')}` +
    ` and exists (select 1 from "Attachment" a where a.message_id = m.id and a.type = 'application/pdf')` +
    ` order by m.id`,
  /** A draft NM with an image attachment (the copy's is made by data/seed-examples.mjs). */
  nmImage:
    `select m.uid from ${english('niord-nm', 'DRAFT')}` +
    ` and exists (select 1 from "Attachment" a where a.message_id = m.id and a.type like 'image/%')` +
    ` order by m.id`,
} as const;

export type ExampleMessage = keyof typeof RULES;

const found = new Map<ExampleMessage, string>();

/** The uid of an example message (see RULES), read once per run from the copy's database. */
export function example(name: ExampleMessage): string {
  let uid = found.get(name);
  if (!uid) {
    uid = psql(RULES[name], `the example message "${name}"`).split(/\r?\n/)[0]?.trim();
    if (!uid) throw new Error(`The copy holds no message for the example "${name}".`);
    found.set(name, uid);
  }
  return uid;
}

/** The session domain an example message lives in. */
export function domainOf(name: ExampleMessage): string {
  return name.startsWith('nw') ? 'niord-nw' : 'niord-nm';
}

/**
 * The page's clock for a picture of an example message's comments: two hours after its last comment
 * or acknowledgement, so each shows a time that has passed ("2 hours ago"). The copy's example
 * comments are made by data/seed-examples.mjs when the copy is built, after the rig's fixed day.
 */
export function afterLastComment(name: ExampleMessage): string {
  const at = psql(
    `select to_char(max(greatest(c.created, coalesce(c."acknowledgeDate", c.created)))` +
      ` + interval '2 hours', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') from "Comment" c` +
      ` join "Message" m on m.id = c.message_id where m.uid = '${example(name)}'`,
    `the last comment of the example message "${name}"`,
  ).trim();
  if (!at) throw new Error(`The example message "${name}" has no comment.`);
  return at;
}

/**
 * Signs in, fixes the clock (at the rig's fixed instant, or `at`) and opens an example message on
 * one of the editor's views. Opening a message reads it; nothing is saved.
 */
export async function openExample(
  page: Page,
  name: ExampleMessage,
  view: 'edit' | 'status' | 'comments' | 'history' = 'edit',
  role: TestRole = 'editor',
  at?: string,
): Promise<string> {
  const uid = example(name);
  await fixClock(page, at);
  await loginAs(page, role, domainOf(name));
  await page.goto(`/editor/${view}/${encodeURIComponent(uid)}`);
  await expect(page.locator('app-editor-view-header header')).toBeVisible();
  if (view === 'edit') await expect(page.locator('.ed-row').first()).toBeVisible();
  return uid;
}

/** A field row of the edit form by its label ("Areas"), the nth when the parts repeat it. */
export function fieldRow(page: Page, label: string, nth = 0): Locator {
  return page
    .locator('.ed-row')
    .filter({ has: page.locator('.ed-row-label > span', { hasText: new RegExp(`^${label}$`) }) })
    .nth(nth);
}

/** Expands a field row (a click on its head, as a reader does) and returns the row. */
export async function expandField(page: Page, label: string, nth = 0): Promise<Locator> {
  const row = fieldRow(page, label, nth);
  await row.locator('.ed-row-head').click();
  await expect(row.locator('.ed-row-editor')).toBeVisible();
  // The row head says "editing…" while it is open; the mouse is moved away from its hover style.
  await page.mouse.move(1, 1);
  return row;
}

/** The card of a message part ("Part 1"). */
export function partCard(page: Page, index: number): Locator {
  return page.locator('app-part-card').nth(index);
}
