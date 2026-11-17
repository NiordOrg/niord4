import { MESSAGE_STATUSES } from '../../../../../shared/models/message-status.model';
import {
  AVAILABILITIES as WIRE_AVAILABILITIES,
  CRITERIA_MISS_CODES,
  TIME_RELATIONS,
  type Availability,
  type AuditActorKind,
  type CheckSeverity,
  type ComputedIssueStatus,
  type IssueStatus,
  type PublishCheck,
  type SeriesStatus,
} from '../../../services/api/publication-series-api.service';

/**
 * The stored vocabularies this section renders, and the i18n keys that name them.
 *
 * A persisted code is not a label. `RETIRED` on a screen is the database's word
 * for the state, in English, in every language — so every code that reaches the
 * DOM is mapped here to a key first, and the key space is closed rather than
 * assembled from whatever the server happened to send.
 *
 * Pure on purpose: no injector, no HTTP. The coverage spec walks these arrays
 * against the bundles, which is only possible while the vocabularies live in one
 * place instead of being spelled out in five templates.
 */

/** The fallback code, for a value outside the vocabulary it was read against. */
export const UNKNOWN_CODE = 'UNKNOWN';

export const SERIES_STATUSES: readonly SeriesStatus[] = ['DRAFT', 'ACTIVE', 'RETIRED'];

/**
 * The three answers to "who else may cite this", as the radios render them.
 *
 * Taken from the API's own list rather than restated, so a value added on the
 * wire is one this vocabulary already knows about and the coverage spec asks for
 * a label for. Every one of them reaches a screen, so all three are labelled.
 */
export const AVAILABILITIES: readonly Availability[] = WIRE_AVAILABILITIES;
export const ISSUE_STATUSES: readonly IssueStatus[] = ['OPEN', 'PUBLISHED', 'RETIRED'];

/**
 * What a period can be, once issues and the periods that have none are one list.
 *
 * The stored statuses plus the two synthesized kinds. A caller that renders a
 * merged list is asking one question — "what happened in this period" — and
 * three of the five answers are about a document while two are about its
 * absence, so they belong in one vocabulary rather than in two the caller has to
 * choose between.
 */
export const COMPUTED_ISSUE_STATUSES: readonly ComputedIssueStatus[] = [
  ...ISSUE_STATUSES,
  'MISSING',
  'UPCOMING',
];

export const ISSUE_ROW_KEY_PREFIX = 'admin.publications.row.';

/**
 * A member's frozen status is a MESSAGE status, not an issue one.
 *
 * It is what the message looked like when the issue was released, so the
 * vocabulary is the message one and is taken from there rather than restated —
 * a second copy would be a second thing to forget when a status is added.
 */
export const MEMBER_STATUSES: readonly string[] = MESSAGE_STATUSES;

export const AUDIT_ACTOR_KINDS: readonly AuditActorKind[] = ['USER', 'SYSTEM', 'IMPORT'];

/**
 * Every action the history panel can be handed, in the server's own order.
 *
 * Kept here rather than inferred from the bundles so the coverage spec can walk
 * it: three actions were written by the server for months with no label on this
 * side, and the panel rendered the key path in the middle of the trail. One of
 * them, `VISIBILITY_CAPPED`, is written on every routine weekly release — on the
 * exact screen somebody opens to ask why a publication left the public site.
 *
 * The list mirrors the server's `AuditAction` vocabulary. Adding one here
 * without a label fails the spec; receiving one that is NOT here renders the
 * bare code, which is still readable, rather than a key path that is not.
 */
export const AUDIT_ACTIONS: readonly string[] = [
  'CREATED',
  'CREATED_FROM_PREVIOUS_PUBLISH',
  'CREATED_RETROACTIVELY',
  'CREATED_NEW_EDITION',
  'PUBLISHED',
  'AMENDED',
  'RETIRED',
  'REACTIVATED',
  'SUPERSEDED_BY',
  'DELETED',
  'INTERVAL_CHANGED',
  'NAME_CHANGED',
  'EDITION_CHANGED',
  'NUMBERING_CHANGED',
  'FILE_NAME_CHANGED',
  'REPORT_CHANGED',
  'CRITERIA_OVERRIDDEN',
  'OVERRIDE_INCLUDED',
  'OVERRIDE_EXCLUDED',
  'OVERRIDE_REMOVED',
  'FILE_UPLOADED',
  'FILE_REPLACED_MANUALLY',
  'FILE_CLEARED',
  'LINK_SET',
  'LINK_CLEARED',
  'PREVIEW_GENERATED',
  'WINDOW_ADJUSTED',
  'VISIBILITY_CAPPED',
  'VISIBILITY_WINDOW_CHANGED',
  'IMPORTED',
  'SERIES_ACTIVATED',
  'SERIES_RETIRED',
  'OWNER_TRANSFERRED',
];

/**
 * Why the resolver dropped a candidate — one value per way the predicate can.
 *
 * Taken from the API's own list rather than restated, so a code added on the
 * wire is a code this vocabulary already knows about and the coverage spec asks
 * for a label for.
 */
export const MISS_CODES: readonly string[] = CRITERIA_MISS_CODES;

/**
 * The checklist rows this build has words for.
 *
 * A set rather than a prefix guess, so a row the server grows later renders as
 * its own code instead of as an unresolved translation key — which reads like a
 * broken build rather than like a check nobody has worded yet. Held here rather
 * than on the screen that shows the rail, because the release dialog names the
 * same rows and must not keep a second copy of the vocabulary.
 */
export const CHECK_CODES: readonly string[] = [
  'ISSUE_OPEN',
  'INTERVAL_PRESENT',
  'INTERVAL_CHAINED',
  'FILE_PRESENT_PER_LANGUAGE',
  'REPORT_CONFIGURED',
  'REFERENCE_FORMAT_COMPLETE',
  'CUTOFF_AFTER_PREVIOUS',
  'CUTOFF_BEFORE_SUCCESSOR',
  'CUTOFF_NOT_FUTURE',
  'MEMBERS_RESOLVED',
  'MEMBER_LIMIT',
  'NO_INEFFECTIVE_OVERRIDES',
  'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
  'OVERLAPPING_ISSUE',
  // Asked of a compilation alone: every stretch of the source series inside this
  // issue's period has a published issue behind it. It is the one row that can
  // fail because of a document nobody has released YET, which is why it warns
  // rather than blocks — an annual closed in early January before the last
  // weekly is out is a decision somebody may take on purpose.
  'SOURCE_ISSUES_COMPLETE',
];

const KNOWN_CHECK_CODES: ReadonlySet<string> = new Set(CHECK_CODES);

/**
 * The measured facts a row can report, each under a stable name.
 *
 * A row says WHICH question was asked; this says what the answer was measured to
 * be — "214 of 1000", "must be after 1 September 12:00" — and the server sends
 * the values apart from the sentence so the sentence can be written in the
 * reader's own language. The names are the server's; a code outside this set is
 * one nobody has worded yet, and the server's English stands for it rather than
 * a key path.
 *
 * The `NOT_APPLICABLE.*` half explains a question that was never asked. Those
 * rows are dropped from the rail, so the words exist for the reader who is told
 * elsewhere why a rule is silent, not for the rail itself.
 *
 * A `.one`, `.present` or `.absent` entry is this build's own, not the server's:
 * it is the same answer worded for one shape of the values, chosen where they
 * make it the right sentence. The unsuffixed entry stays registered as the
 * wording for values that pick no variant — and for a server that stops sending
 * the value the variant turns on.
 */
export const CHECK_DETAIL_CODES: readonly string[] = [
  'ISSUE_OPEN.status',
  'INTERVAL_PRESENT.under',
  'INTERVAL_PRESENT.under.present',
  'INTERVAL_PRESENT.under.absent',
  'INTERVAL_CHAINED.gap',
  'FILE_PRESENT_PER_LANGUAGE.bytes',
  'FILE_PRESENT_PER_LANGUAGE.link',
  'REPORT_CONFIGURED.reportId',
  'REFERENCE_FORMAT_COMPLETE.citable',
  'CUTOFF_AFTER_PREVIOUS.after',
  'CUTOFF_BEFORE_SUCCESSOR.before',
  'CUTOFF_NOT_FUTURE.at',
  'MEMBERS_RESOLVED.count',
  'MEMBERS_RESOLVED.count.one',
  'MEMBER_LIMIT.of',
  'NO_INEFFECTIVE_OVERRIDES.none',
  'NO_INEFFECTIVE_OVERRIDES.stale',
  'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count',
  'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count.one',
  'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.none',
  'OVERLAPPING_ISSUE.shared',
  'OVERLAPPING_ISSUE.shared.one',
  'OVERLAPPING_ISSUE.tile',
  'SOURCE_ISSUES_COMPLETE.complete',
  'SOURCE_ISSUES_COMPLETE.incomplete',
  'SOURCE_ISSUES_COMPLETE.incomplete.noOpen',
  'SOURCE_ISSUES_COMPLETE.incomplete.noGaps',
  'NOT_APPLICABLE.NOT_QUERY_BACKED',
  'NOT_APPLICABLE.NO_PREDECESSOR',
  'NOT_APPLICABLE.NO_SUCCESSOR',
  'NOT_APPLICABLE.PUBLISH_GENERATES_FILE',
  'NOT_APPLICABLE.NO_DOCUMENT',
  'NOT_APPLICABLE.NOTHING_RENDERED',
  'NOT_APPLICABLE.NOT_CITABLE',
  'NOT_APPLICABLE.FUTURE_ALLOWED',
  'NOT_APPLICABLE.NO_MEMBERSHIP',
  'NOT_APPLICABLE.IN_FORCE_SERIES',
  'NOT_APPLICABLE.BOUNDED_BY_SOURCES',
  'NOT_APPLICABLE.JUDGED_AT_SOURCE',
  'NOT_APPLICABLE.NOT_COMPILED',
];

const KNOWN_CHECK_DETAIL_CODES: ReadonlySet<string> = new Set(CHECK_DETAIL_CODES);

export const SERIES_STATUS_KEY_PREFIX = 'admin.publications.seriesStatus.';
export const ISSUE_STATUS_KEY_PREFIX = 'admin.publications.issueStatus.';
export const MEMBER_STATUS_KEY_PREFIX = 'admin.publications.memberStatus.';
export const ACTOR_KEY_PREFIX = 'admin.publications.actor.';
export const ACTION_KEY_PREFIX = 'admin.publications.action.';
export const MISS_KEY_PREFIX = 'admin.publications.miss.';
export const AVAILABILITY_KEY_PREFIX = 'admin.publications.availability.';
/**
 * Where the two selection regimes are named — the editors' own option labels.
 *
 * Deliberately the same keys the series and one-off editors build from their
 * option lists: a regime quoted back in a rail sentence has to read as the words
 * the reader picked it with, not as a second wording of the same choice.
 */
export const TIME_RELATION_KEY_PREFIX = 'admin.publications.series.timeRelation.';
export const CHECK_KEY_PREFIX = 'admin.publications.check.';
export const CHECK_DETAIL_KEY_PREFIX = 'admin.publications.checkDetail.';
export const CHECK_SUMMARY_KEY_PREFIX = 'admin.publications.checkSummary.';

/**
 * The label key for a checklist row, or `undefined` for a code with no wording.
 *
 * The caller renders the code itself in that case. Building the key blind turned
 * an unregistered check into the string `admin.publications.check.FOO` on the
 * release rail — which says nothing about the issue and looks like a translation
 * fault rather than like a check nobody has worded yet.
 */
export function checkKey(code: string): string | undefined {
  return KNOWN_CHECK_CODES.has(code) ? CHECK_KEY_PREFIX + code : undefined;
}

/**
 * The sentence that says what a failing row MEANS, or `undefined` for a code
 * this build has none for.
 *
 * A row's own label names the QUESTION ("the content period joins the previous
 * issue's"), which read as a failure says only that something about the period
 * is wrong. This is the answer in words, and the label stands on its own where
 * there is none.
 */
export function checkDetailKey(code: string): string | undefined {
  return KNOWN_CHECK_CODES.has(code) ? CHECK_DETAIL_KEY_PREFIX + code : undefined;
}

/**
 * What one row was measured to be, ready to render.
 *
 * `key` is present only where this build has words for the code; `text` is the
 * server's own English, which stands in when it has none. `params` is always an
 * object, so a template interpolates unconditionally.
 */
export interface CheckDetailText {
  readonly key?: string;
  readonly params: Record<string, unknown>;
  readonly text: string;
}

/**
 * The two names that carry the ends of a list of uncovered stretches, and the
 * one name the sentence reads them under.
 *
 * The wire sends the two ends as PARALLEL lists, which pair only by position:
 * read as two lists a sentence says "from A, B, C to D, E, F", which is three
 * periods written as two and leaves the reader to pair them. So the two are
 * zipped here into one list of periods, and the sentence names that one.
 */
const STRETCH_FROM = 'missingFrom';
const STRETCH_TO = 'missingTo';
const STRETCHES = 'missing';

/** What separates the two ends of one stretch, spaced because both ends carry a clock. */
const STRETCH_DASH = ' – ';

/**
 * Which parameter names carry an instant.
 *
 * The wire sends epoch milliseconds — never a rendered date — so that the moment
 * is shown on the same clock as every other instant on the screen rather than in
 * whatever zone and layout the server happened to format it with.
 */
function isInstantParam(name: string): boolean {
  return name === 'at' || name.endsWith('At');
}

/** One end of a stretch, on the screen's clock where it is a moment at all. */
function stretchEnd(value: unknown, formatInstant: (value: number) => string): string {
  return typeof value === 'number' ? formatInstant(value) : String(value ?? '');
}

/**
 * The uncovered stretches as one readable list, or `undefined` where the row
 * does not name them as two lists.
 *
 * Every end goes through the same clock a single instant does. Where one list is
 * shorter than the other — which the wire should never send and a row can still
 * arrive with — the stretch is printed by the end it has rather than trailing a
 * dash into nothing.
 */
function stretchList(
  params: Readonly<Record<string, unknown>> | undefined,
  formatInstant: (value: number) => string,
): string | undefined {
  const from = params?.[STRETCH_FROM];
  const to = params?.[STRETCH_TO];
  if (!Array.isArray(from) || !Array.isArray(to)) {
    return undefined;
  }
  return from
    .map((start, index) => {
      const end = index < to.length ? stretchEnd(to[index], formatInstant) : '';
      const opens = stretchEnd(start, formatInstant);
      return end === '' ? opens : `${opens}${STRETCH_DASH}${end}`;
    })
    .join(', ');
}

/**
 * Which parameters carry a stored code rather than a value, and what names it.
 *
 * A status and a selection regime arrive as the server's own enum — `OPEN`,
 * `PUBLISHED_IN_INTERVAL` — which is English in every language. Dropped raw into
 * a translated sentence they are the one untranslated token in it, and each of
 * them already has a label two lines further up the same screen, so the sentence
 * uses that label instead of the code behind it.
 */
const VOCABULARY_PARAMS: Readonly<Record<string, (value: string) => string | undefined>> = {
  status: issueStatusKey,
  seriesStatus: seriesStatusKey,
  timeRelation: timeRelationKey,
};

/**
 * The wordings a set of values can select, in the order they are tried.
 *
 * A sentence written for `{{count}} messages` reads as "1 messages" for the one
 * number it is most likely to be given, and a sentence that quietly drops the
 * boolean its check turns on says nothing about why the check failed. Both are
 * answered the same way: the vocabulary carries the variant, and the values pick
 * it. A variant nobody has worded is simply not taken, so the base sentence
 * stands — which is also what a server that stops sending the value gets.
 */
function variantSuffixes(params: Readonly<Record<string, unknown>>): readonly string[] {
  const out: string[] = [];
  if (params['count'] === 1) {
    out.push('one');
  }
  if (typeof params['present'] === 'boolean') {
    out.push(params['present'] ? 'present' : 'absent');
  }
  // A coverage row that fails on one side only: naming an empty list of weeks or
  // stretches would print an empty pair of brackets in the middle of the sentence.
  if (params['openCount'] === 0) {
    out.push('noOpen');
  }
  if (params['missingCount'] === 0) {
    out.push('noGaps');
  }
  return out;
}

/**
 * The measured answer for one rail row, or `undefined` when it has nothing to say.
 *
 * The values arrive apart from the sentence, so the sentence can be written in
 * the reader's language and still carry the server's numbers. Instants are
 * handed to the caller's formatter — the screen's one clock — and a list is
 * joined here rather than reaching a template as `[object Object]`. A pair of
 * lists naming the two ends of the same periods is zipped into one, under the
 * name the sentence reads it by.
 *
 * A count of exactly one takes the `.one` wording where the vocabulary carries
 * it: "1 message(s)" is the shape of a sentence nobody wrote for the number it
 * ended up with. A boolean picks its wording the same way.
 *
 * A stored code among the values — a status, a selection regime — is named
 * through the label the rest of the screen uses for it, so the sentence has no
 * English enum sitting in the middle of it. `translate` is what does that; a
 * caller with nothing to translate with leaves the codes as they came, which is
 * what the server's own English does too.
 *
 * Falls back to the server's English only where the code is one this build has
 * no words for: a sentence nobody has translated yet is still worth reading, and
 * an unresolved key path is not.
 */
export function checkDetailText(
  row: Pick<PublishCheck, 'detail' | 'detailCode' | 'detailParams'>,
  formatInstant: (value: number) => string,
  translate?: (key: string) => string,
): CheckDetailText | undefined {
  const code = row.detailCode;
  const text = row.detail ?? '';
  let name = code && KNOWN_CHECK_DETAIL_CODES.has(code) ? code : undefined;
  if (!name && !text) {
    return undefined;
  }
  const params: Record<string, unknown> = {};
  // The two ends of the uncovered stretches are read as one list of periods, so
  // they are taken out of the loop rather than joined separately: a sentence
  // that named both lists would leave the reader pairing dates by position.
  const missing = stretchList(row.detailParams, formatInstant);
  for (const [param, value] of Object.entries(row.detailParams ?? {})) {
    if (missing !== undefined && (param === STRETCH_FROM || param === STRETCH_TO)) {
      continue;
    }
    const vocabularyKey =
      translate && typeof value === 'string' ? VOCABULARY_PARAMS[param]?.(value) : undefined;
    if (isInstantParam(param) && typeof value === 'number') {
      params[param] = formatInstant(value);
    } else if (vocabularyKey && translate) {
      params[param] = translate(vocabularyKey);
    } else if (Array.isArray(value)) {
      params[param] = value.join(', ');
    } else {
      params[param] = value;
    }
  }
  if (missing !== undefined) {
    params[STRETCHES] = missing;
  }
  if (name) {
    for (const suffix of variantSuffixes(params)) {
      if (KNOWN_CHECK_DETAIL_CODES.has(`${name}.${suffix}`)) {
        name = `${name}.${suffix}`;
        break;
      }
    }
  }
  return { key: name ? CHECK_DETAIL_KEY_PREFIX + name : undefined, params, text };
}

/**
 * The same answer as one short clause, or `undefined` where this build has none.
 *
 * A rail with room for a paragraph says what a failing check means and what to
 * do about it. A card's closing clause has room for none of that — it sits at
 * the end of a line already carrying a cut-off and a member count — so it names
 * only WHICH thing is wrong and leaves the sentence to the screen somebody would
 * act on it from.
 */
export function checkSummaryKey(code: string): string | undefined {
  return KNOWN_CHECK_CODES.has(code) ? CHECK_SUMMARY_KEY_PREFIX + code : undefined;
}

/**
 * The label for one availability, or the unknown one for a value outside it.
 *
 * The radios are rendered FROM the vocabulary rather than spelled out one by
 * one, so a value the server grows is a control that appears with a label the
 * spec asked for — not three hard-coded options and a fourth state nothing can
 * express.
 */
export function availabilityKey(availability: string | undefined): string {
  return AVAILABILITY_KEY_PREFIX + knownOrUnknown(AVAILABILITIES, availability);
}

/**
 * The key that says why a message was left out.
 *
 * Falls back to an "unknown reason" label rather than to the bare code: unlike a
 * history line, the row beside it already names the message, so a reason nobody
 * has words for is a missing explanation and not a missing fact.
 */
export function criteriaMissKey(code: string | undefined): string {
  return MISS_KEY_PREFIX + knownOrUnknown(MISS_CODES, code);
}

/**
 * The key that names an audit action, or `undefined` to render the code itself.
 *
 * Unlike the status vocabularies there is no "unknown action" label to fall back
 * to: a trail line saying only "unknown" removes the one fact the line carried.
 * The raw code is imperfect English but it is still the name of what happened,
 * and a caller renders it as-is when this answers `undefined`.
 */
export function auditActionKey(action: string | undefined): string | undefined {
  return action !== undefined && AUDIT_ACTIONS.includes(action)
    ? ACTION_KEY_PREFIX + action
    : undefined;
}

/** The action whose detail is a pair of domains rather than a sentence. */
export const OWNER_TRANSFERRED = 'OWNER_TRANSFERRED';

/**
 * The two desks a transfer moved a publication between, or `undefined`.
 *
 * The action name alone says a publication moved and nothing about where it went
 * — which is the only thing anybody reads that line to find out. The pair
 * travels in the entry's `detail`, and is read defensively for two reasons: the
 * field is a string on every other action, and an older server may write the
 * object as JSON text rather than as an object.
 *
 * `undefined` unless BOTH ends are present. Half an answer reads worse than the
 * bare action: "moved from Annexes to" describes a move to nowhere.
 */
export function ownerTransfer(entry: {
  readonly action?: string;
  readonly detail?: string | Readonly<Record<string, unknown>>;
}): { readonly from: string; readonly to: string } | undefined {
  if (entry.action !== OWNER_TRANSFERRED) {
    return undefined;
  }
  const detail = parseDetail(entry.detail);
  const from = typeof detail?.['from'] === 'string' ? detail['from'] : undefined;
  const to = typeof detail?.['to'] === 'string' ? detail['to'] : undefined;
  return from && to ? { from, to } : undefined;
}

/**
 * One row of the checklist a release was actually taken against.
 *
 * The record, not the rail. The rail re-asks its questions of the issue as it
 * stands now; this is the answer set that was true in the publish transaction,
 * kept on the entry that recorded the release — including which failing warnings
 * somebody ticked to get past, which is the half nothing else preserves.
 */
export interface PublishedCheck {
  readonly code: string;
  readonly severity: CheckSeverity;
  readonly passed: boolean;
  /** Whether the release was taken past this row on purpose. */
  readonly acknowledged: boolean;
}

/**
 * The checklist a history line preserved, or an empty list where it has none.
 *
 * Read by SHAPE rather than by which action the line records: the key is written
 * by the release and by nothing else, so a line that carries a well-formed one
 * has a record worth showing whatever it is called. Every row is read
 * defensively — the detail is ordinary prose on most actions, an older server
 * may write the object as JSON text, and a row that cannot be read is dropped
 * rather than rendered half-empty.
 *
 * An unreadable severity is taken as `BLOCK`, the stricter reading: it withholds
 * the acknowledgement clause rather than claiming a failing row was waved
 * through by somebody.
 */
export function publishedChecklist(entry: {
  readonly detail?: string | Readonly<Record<string, unknown>>;
}): readonly PublishedCheck[] {
  const rows = parseDetail(entry.detail)?.['checklist'];
  if (!Array.isArray(rows)) {
    return [];
  }
  return rows.flatMap((row: unknown) => {
    const read = publishedCheck(row);
    return read ? [read] : [];
  });
}

function publishedCheck(row: unknown): PublishedCheck | undefined {
  if (typeof row !== 'object' || row === null || Array.isArray(row)) {
    return undefined;
  }
  const record = row as Record<string, unknown>;
  const code = record['code'];
  if (typeof code !== 'string' || code === '') {
    return undefined;
  }
  const severity = record['severity'];
  return {
    code,
    severity: severity === 'WARN' || severity === 'OK' ? severity : 'BLOCK',
    passed: record['passed'] === true,
    acknowledged: record['acknowledged'] === true,
  };
}

function parseDetail(
  detail: string | Readonly<Record<string, unknown>> | undefined,
): Readonly<Record<string, unknown>> | undefined {
  if (detail === undefined) {
    return undefined;
  }
  if (typeof detail !== 'string') {
    return detail;
  }
  try {
    const parsed: unknown = JSON.parse(detail);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    // A detail that is ordinary prose, which is what every other action writes.
    return undefined;
  }
}

export function seriesStatusKey(status: string | undefined): string {
  return SERIES_STATUS_KEY_PREFIX + knownOrUnknown(SERIES_STATUSES, status);
}

export function issueStatusKey(status: string | undefined): string {
  return ISSUE_STATUS_KEY_PREFIX + knownOrUnknown(ISSUE_STATUSES, status);
}

export function memberStatusKey(status: string | undefined): string {
  return MEMBER_STATUS_KEY_PREFIX + knownOrUnknown(MEMBER_STATUSES, status);
}

/**
 * The label for a selection regime, or `undefined` for a value outside the three.
 *
 * The editors that OFFER the regimes build this key from their own option list,
 * so nothing is invented for a regime this build does not carry: it reads as the
 * server's own token, which is at least the thing an operator would quote.
 */
export function timeRelationKey(relation: string | undefined): string | undefined {
  return relation !== undefined && (TIME_RELATIONS as readonly string[]).includes(relation)
    ? TIME_RELATION_KEY_PREFIX + relation
    : undefined;
}

/**
 * The key that names a period, whether or not an issue came out in it.
 *
 * Two key spaces behind one call: a synthesized period is named for what it IS
 * ("missing", "upcoming") and a real issue for the status it holds. A caller
 * choosing between them itself is a caller that will forget the synthesized half
 * — which is the half a reader is looking for.
 */
export function computedIssueStatusKey(status: string | undefined): string {
  return status === 'MISSING' || status === 'UPCOMING'
    ? ISSUE_ROW_KEY_PREFIX + status
    : issueStatusKey(status);
}

/**
 * A state class a badge or chip can wear, or none.
 *
 * `''` rather than a colour for anything unrecognised: a widget left in its own
 * neutral fallback says nothing, where a tint says something specific about a
 * status nobody identified.
 */
export type BadgeClass = 'is-issue-open' | 'is-issue-published' | 'is-cancelled' | '';

/**
 * The tint for an issue's own lifecycle.
 *
 * Open is blue and published is green, taken from the message-status palette —
 * the accent is never used for "published", because it is what the application
 * means by "selected" everywhere else. A withdrawn issue is a document that was
 * taken back, which is what the cancelled tint says throughout.
 */
export function issueBadgeClass(status: string | undefined): BadgeClass {
  switch (status) {
    case 'OPEN':
      return 'is-issue-open';
    case 'PUBLISHED':
      return 'is-issue-published';
    case 'RETIRED':
      return 'is-cancelled';
    default:
      return '';
  }
}

/**
 * The tint for a SERIES' state — and the answer is often no badge at all.
 *
 * An active series is the ordinary case, and badging every row with "active"
 * spends the reader's attention on the thing that needs none. Only the two
 * states that change what the reader may do carry a badge: a draft is not
 * publishing yet (blue, the same "open for work" tint an open issue wears) and a
 * retired one is finished (the cancelled tint).
 */
export function seriesBadgeClass(status: string | undefined): BadgeClass {
  switch (status) {
    case 'DRAFT':
      return 'is-issue-open';
    case 'RETIRED':
      return 'is-cancelled';
    // ACTIVE, and anything unrecognised: no badge.
    default:
      return '';
  }
}

/**
 * The status tint for a period, as an existing state class.
 *
 * The classes only set the `--status-*` custom properties; the widget the caller
 * puts them on decides the shape. Two vocabularies behind one call: a period
 * that produced no issue is tinted for what it IS, and a real issue for the
 * status it holds — which is {@link issueBadgeClass}, so the two can never
 * disagree about the same status.
 */
export function computedIssueStatusClass(status: string | undefined): string {
  if (status === 'MISSING' || status === 'UPCOMING') {
    return `is-gap-${status}`;
  }
  return issueBadgeClass(status);
}

/**
 * What the release rail adds up to, once the rows that do not apply are dropped.
 *
 * The counts, not the gate. `canPublish` is the server's answer and the only one
 * that decides whether the button works; this is the SENTENCE above the rail —
 * "ready", "two warnings, publishable", "one thing stops this" — and it must
 * count exactly the rows the reader can see, or the line and the list disagree.
 */
export interface ChecklistVerdict {
  readonly state: 'ready' | 'warnings' | 'blocked';
  /** Failing rows nobody can wave through. */
  readonly blocking: number;
  /** Failing rows that may be acknowledged and released past. */
  readonly warnings: number;
  readonly passed: number;
  /** How many rows were asked at all — the denominator the other three sit under. */
  readonly applicable: number;
}

/**
 * Add up a checklist the way its reader does.
 *
 * Rows the server marked `applicable: false` are not counted at all: a question
 * that was never asked has no answer, and a rail padded with rules that do not
 * apply to this series is one nobody reads to the end. A row that does not say
 * is counted — "the server did not mention it" is not the same statement as
 * "this does not apply", and guessing the stricter reading is the safe one.
 *
 * A failing BLOCK row blocks; every other failing row is a warning, whether it
 * arrived as WARN or as an acknowledgeable row of some other severity. There is
 * deliberately no third bucket for a failure that fits neither: a failing row
 * that lands nowhere disappears from the count while still sitting in the list.
 */
export function checklistVerdict(rows: readonly PublishCheck[]): ChecklistVerdict {
  const applicable = rows.filter((row) => row.applicable !== false);
  const failing = applicable.filter((row) => !row.passed);
  const blocking = failing.filter((row) => row.severity === 'BLOCK').length;
  const warnings = failing.length - blocking;
  return {
    state: blocking > 0 ? 'blocked' : warnings > 0 ? 'warnings' : 'ready',
    blocking,
    warnings,
    passed: applicable.length - failing.length,
    applicable: applicable.length,
  };
}

/**
 * The key that names who did something, or `undefined` to render the name.
 *
 * Three answers, not two. A machine actor has no name to show and gets a label
 * saying which kind of machine it was; a person is shown by name. An entry
 * recorded for a person whose name did not survive is the third case, and it
 * must not fall through to either machine label — reading "imported" over a
 * change somebody made by hand rewrites what the history says happened.
 */
export function auditActorKey(entry: {
  readonly actorKind?: AuditActorKind;
  readonly actorLabel?: string;
}): string | undefined {
  if (entry.actorKind === 'IMPORT' || entry.actorKind === 'SYSTEM') {
    return ACTOR_KEY_PREFIX + entry.actorKind;
  }
  return entry.actorLabel ? undefined : ACTOR_KEY_PREFIX + UNKNOWN_CODE;
}

function knownOrUnknown(vocabulary: readonly string[], value: string | undefined): string {
  return value !== undefined && vocabulary.includes(value) ? value : UNKNOWN_CODE;
}
