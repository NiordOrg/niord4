import type { SeriesFieldError } from '../../../services/api/publication-series-api.service';

/**
 * The refusal every screen answers with a re-read.
 *
 * Named here rather than spelled out at each of the call sites: a refusal whose
 * handling is "show it AND re-read the row" is one a caller can only get right
 * by recognising the code, and a code recognised by a string literal in five
 * places is one that goes wrong in the sixth. The pages re-read what they show;
 * the two editors re-read only the base under the typed values, and say so
 * (`STALE_NOT_SHOWN_KEY`).
 */
export const STALE_VERSION = 'STALE_VERSION';

/**
 * What the two editors (series and one-off) say on `STALE_VERSION`.
 *
 * Not the shared sentence ("it has been reloaded — check what moved"): an
 * editor re-reads only the series under the typed values, so the other admin's
 * change is not shown, and the next Save carries the new revision and writes the
 * typed values over it in every field the form owns (last write wins). The
 * sentence says exactly that, and how to see the change: reload the page.
 * Closing and reopening the drawer would not show it: closing refreshes
 * nothing, and the drawer is seeded again from the page's own, older copy.
 */
export const STALE_NOT_SHOWN_KEY = 'admin.publications.error.staleNotShown';

/**
 * The refusal a transfer answers with the server's own sentence.
 *
 * The code alone says a domain is wrong; the sentence says WHICH of the two —
 * the desk the caller is sitting at, or the one they are moving the publication
 * to — and that is the whole of what the reader has to act on. Named here for
 * the same reason `STALE_VERSION` is: a code whose handling differs from every
 * other one is a code recognised by a literal in three places and forgotten in
 * the fourth.
 */
export const NOT_IN_DOMAIN = 'NOT_IN_DOMAIN';

/**
 * The refusal that names the messages standing in the way.
 *
 * A withdrawn issue may be removed only while nothing points at it, and the
 * server answers with the citing messages rather than with a count — "3 messages
 * still cite it" leaves an admin to go and find which three. Named here because
 * its handling differs from every other refusal's: the sentence is not the whole
 * answer, the list under it is.
 */
export const ISSUE_CITED = 'ISSUE_CITED';

/**
 * The refusal that says a release or an amend is already running on the issue.
 *
 * Named here beside `STALE_VERSION` for the same reason: its handling is not a
 * sentence but an ACTION. The screen that meets it did not know work was in
 * flight — that is why it asked — so it re-reads the issue, which is what
 * fetches the fact the refusal is about and puts the page into the state the
 * refusal describes. Shown as a sentence as well, but a sentence alone would
 * leave the page looking idle beside a message saying it is not.
 */
export const ISSUE_BUSY = 'ISSUE_BUSY';

/**
 * The refusal of a write to an issue that is no longer open.
 *
 * Named here because the preview maps it to a sentence of its own ("Only an
 * open issue can be previewed"), while every other write it refuses (an edit,
 * a curation, an upload, a file clear, a release) reads the neutral one in the
 * table below.
 */
export const ISSUE_NOT_OPEN = 'ISSUE_NOT_OPEN';

/**
 * The refusal that lists the series rules a request failed, in `fieldErrors`.
 *
 * Named here because its sentence is a save's ("could not be saved as it
 * stands"), and the series page also meets it on an activation or a
 * reinstatement, where nothing was saved: there the page says which step
 * failed and lists the rules instead.
 */
export const SERIES_INVALID = 'SERIES_INVALID';

/**
 * The refusal of a read for a publication that is not there.
 *
 * Named here because the series page and the one-off page answer it with a
 * sentence and no Retry: the same read can only meet the same 404
 * again. See {@link seriesNotFound}.
 */
export const SERIES_NOT_FOUND = 'SERIES_NOT_FOUND';

/**
 * Server refusals this section can say something specific about.
 *
 * A refusal that reaches a toast as "could not be saved" tells the admin nothing
 * they can act on, and the codes below name mistakes with an obvious fix. Every
 * other code keeps the caller's generic message rather than being spelled out
 * raw: a code is the server's word for a rule, not a sentence.
 *
 * Pure on purpose — the mapping is data, and the component owns the translation.
 */
const CODE_KEYS: Readonly<Record<string, string>> = {
  PRINT_SETTING_NOT_ALLOWED: 'admin.publications.reportParams.serverRejected',
  CUTOFF_IN_FUTURE: 'admin.publications.publish.cutoffFuture',
  WARNING_NOT_ACKNOWLEDGED: 'admin.publications.publish.acknowledgeToContinue',
  REPORT_NOT_CONFIGURED: 'admin.publications.preview.noReport',
  // Neutral, because an edit, a curation, an upload, a file clear and a release
  // all meet it on a page that has gone stale. The preview keeps its own
  // sentence: the issue page maps its preview refusal itself.
  [ISSUE_NOT_OPEN]: 'admin.publications.error.issueNotOpen',
  REASON_REQUIRED: 'admin.publications.retire.reasonRequired',

  // A write aimed at a series another domain is accountable for.
  //
  // Named rather than left to the generic sentence because it is the one refusal
  // here the caller can lift by themselves: the series belongs to a domain, and
  // switching to it grants the right. It is deliberately NOT the same branch as a
  // bare 403 — that one carries no code at all and means the caller lacks the
  // role, which switching domain cannot fix, so offering that remedy would send
  // somebody to ask for a permission they already hold.
  [NOT_IN_DOMAIN]: 'admin.publications.error.notInDomain',

  // A write composed against a revision somebody else has since moved on.
  //
  // Named rather than left generic because the remedy is specific and unlike
  // every other conflict's: the write changed NOTHING, and pressing the same
  // button again is exactly the wrong response — what is wanted is to see what
  // moved underneath and decide whether the edit still applies. The pages that
  // answer it with this sentence (the issue page, the series page) refetch what
  // they show, so it can say "it has been reloaded" and mean it. The two
  // editors (series and one-off) do NOT: they re-read only the base under the
  // typed values, so they say STALE_NOT_SHOWN_KEY instead.
  [STALE_VERSION]: 'admin.publications.error.staleVersion',

  // Curation. Every one of these names a mistake with an obvious next step, and
  // all of them are answered inside the dialog that caused them.
  OVERRIDE_REASON_REQUIRED: 'admin.publications.curation.reasonRequired',
  OVERRIDE_MESSAGE_NOT_FOUND: 'admin.publications.curation.messageNotFound',
  OVERRIDE_MESSAGE_NOT_PUBLIC: 'admin.publications.curation.messageNotPublic',
  OVERRIDE_ALREADY_A_MEMBER: 'admin.publications.curation.alreadyMember',
  OVERRIDE_NOT_FOUND: 'admin.publications.curation.overrideNotFound',
  NO_MESSAGES: 'admin.publications.curation.noMessages',
  TOO_MANY_MESSAGES: 'admin.publications.curation.tooMany',

  // Amend and new edition.
  ISSUE_NOT_PUBLISHED: 'admin.publications.amend.notPublished',
  ISSUE_IMPORTED: 'admin.publications.amend.imported',
  ARCHIVE_FAILED: 'admin.publications.amend.archiveFailed',
  RENDER_FAILED: 'admin.publications.amend.renderFailed',
  PREDECESSOR_NOT_PUBLISHED: 'admin.publications.newEdition.notPublished',
  ISSUE_INTERVAL_OVERLAP: 'admin.publications.newEdition.overlap',
  SERIES_IS_ONE_OFF: 'admin.publications.newEdition.oneOff',

  // The document.
  FILE_NAME_NOT_DISTINCT: 'admin.publications.issue.fileNameNotDistinct',
  // A link that is neither an http(s) address nor a repository path. The link
  // boxes have no pattern of their own, so this sentence is the whole answer.
  LINK_INVALID: 'admin.publications.error.linkInvalid',

  // What tailoring one edition can be refused over.
  //
  // Each of these names a box on the tailoring drawer and says what is wrong
  // with what is in it, which is the whole reason they are worth a sentence: the
  // generic "could not be saved" leaves an admin re-pressing Save on a drawer
  // whose refusal is one keystroke away.
  //
  // NUMBERING_LABEL_INVALID is deliberately NOT a wording about numbers. The
  // labels are free text — `36+37` is a real week — and the only two things that
  // can be wrong with one are its length and a control character, so the
  // sentence says that rather than inviting somebody to type a number.
  NUMBERING_LABEL_INVALID: 'admin.publications.error.numberingLabelInvalid',
  FILE_NAME_INVALID: 'admin.publications.error.fileNameInvalid',
  // A file name change aimed at a document that has already gone out. Its own
  // sentence because the remedy is not "try again": renaming a released document
  // changes the address every citation resolves to, so the answer is that this
  // is not a correction the drawer can make.
  FILE_NAME_IMMUTABLE: 'admin.publications.error.fileNameImmutable',
  // An upload to an issue that is no longer open, under a name whose file the
  // issue still keeps at an address citations may point at. That file stays for
  // the issue's lifetime, so the same upload never succeeds and the sentence
  // says the one thing that does: another name.
  FILE_NAME_TAKEN: 'admin.publications.error.fileNameTaken',
  REPORT_NOT_FOUND: 'admin.publications.error.reportNotFound',
  // A report parameter answering something the numbering already decides. The
  // drawer refuses the four reserved keys in place, so this arrives only from a
  // map that carried one before the drawer existed — which is why the sentence
  // names the parameter's own remedy rather than pointing at a box.
  REPORT_PARAM_RESERVED: 'admin.publications.error.reportParamReserved',

  // A superseded document the history line offers and the server will not hand
  // back. Worth its own sentence because the generic one invites the retry that
  // cannot work: the entry, the language, the stored location and the file
  // itself are all resolved server-side, so nothing the reader does here changes
  // the answer — the panel is describing an archive that is no longer reachable.
  ARCHIVE_ENTRY_NOT_FOUND: 'admin.publications.issue.archiveNotFound',

  // The rest of the catalogue.
  //
  // Not a wish list: a code the server can return and this section cannot name
  // reaches the reader as an identifier, which is the failure mode the whole
  // mapping exists to prevent. Every entry below is a code the publication API
  // declares, so the only way one goes missing again is the server growing a new
  // one — and then the generic sentence still stands rather than the code.
  CRITERIA_INVALID: 'admin.publications.error.criteriaInvalid',
  UNKNOWN_TOKEN: 'admin.publications.error.criteriaUnknownToken',
  EMPTY_OPERAND: 'admin.publications.error.criteriaEmptyOperand',
  UNRESOLVABLE_OPERAND: 'admin.publications.error.criteriaUnresolvableOperand',
  CRITERIA_NOT_APPLICABLE: 'admin.publications.error.criteriaNotApplicable',
  NO_SUCH_LANGUAGE: 'admin.publications.error.noSuchLanguage',
  RETRO_CREATE_NOT_APPLICABLE: 'admin.publications.error.retroCreateNotApplicable',
  [SERIES_INVALID]: 'admin.publications.error.seriesInvalid',
  SERIES_ID_IMMUTABLE: 'admin.publications.error.seriesIdImmutable',
  SERIES_ID_TAKEN: 'admin.publications.error.seriesIdTaken',
  SERIES_HAS_ISSUES: 'admin.publications.error.seriesHasIssues',
  // A deletion refused because another publication compiles this one. Its own
  // sentence beside the issues refusal because the obstacle is not on this
  // publication at all: nothing here has to change, and the way forward is to
  // point the compilation at another source or remove that one first.
  SERIES_IS_COMPILED: 'admin.publications.error.seriesIsCompiled',
  SERIES_NOT_ONE_OFF: 'admin.publications.error.seriesNotOneOff',
  NOT_ONE_OFF: 'admin.publications.error.seriesNotOneOff',
  SERIES_NOT_ACTIVE: 'admin.publications.error.seriesNotActive',

  // A category save. The id is already taken, or the server's own checks
  // refused the id, the name or the Order. The categories tab's form holds back
  // what it can see, so either one arriving names something the form could not
  // know (another admin's category) or a rule it does not repeat. CATEGORY_IN_USE
  // is deliberately absent: the tab words it itself, with the counts.
  CATEGORY_INVALID: 'admin.publications.error.categoryInvalid',
  CATEGORY_ID_TAKEN: 'admin.publications.error.categoryIdTaken',

  // Activating a publication whose only closed state is an expired public
  // window. Worth its own sentence because the generic one invites the retry
  // that cannot work: the series is ACTIVE and the issue PUBLISHED, so the
  // transition has nothing to change — what has to move is the window, and
  // re-opening it is a decision of its own.
  PUBLIC_WINDOW_CLOSED: 'admin.publications.error.publicWindowClosed',

  // A public period that cannot stand: a start after the end, or a published
  // publication with no start at all. Worth its own sentence because it names
  // exactly which of the two dates has to move — the generic one leaves an admin
  // re-pressing Save on a period the server will refuse every time.
  PUBLIC_WINDOW_INVALID: 'admin.publications.error.publicWindowInvalid',

  // An edition that is blank, or longer than the column can hold.
  EDITION_INVALID: 'admin.publications.error.editionInvalid',
  MESSAGE_PUBLICATION_IMMUTABLE: 'admin.publications.error.messagePublicationImmutable',
  PUBLICATION_UNRESOLVABLE: 'admin.publications.error.publicationUnresolvable',
  CITATION_FORMAT_MISSING: 'admin.publications.error.citationFormatMissing',
  INTERVAL_INVERTED: 'admin.publications.error.intervalInverted',
  NAME_BLANK: 'admin.publications.error.nameBlank',
  NAME_INVALID: 'admin.publications.error.nameInvalid',
  NO_FILE: 'admin.publications.error.noFile',
  TOO_MANY_FILES: 'admin.publications.error.tooManyFiles',
  NO_FILE_NAME: 'admin.publications.error.noFileName',
  BAD_FILE_NAME: 'admin.publications.error.badFileName',
  [SERIES_NOT_FOUND]: 'admin.publications.error.seriesNotFound',
  ISSUE_NOT_FOUND: 'admin.publications.error.issueNotFound',
  ISSUE_ALREADY_PUBLISHED: 'admin.publications.error.issueAlreadyPublished',
  ISSUE_NOT_RETIRED: 'admin.publications.error.issueNotRetired',

  // The two refusals a removal can meet, and they are not one rule with two
  // wordings. A published issue is never removable at all and the remedy is to
  // withdraw it first; a withdrawn one is removable the moment nothing cites it,
  // and the remedy is to look at what still does — which is why the second
  // arrives with the messages named. Collapsed into one sentence, an admin
  // withdrawing an issue in order to remove it would be told the same thing
  // twice and learn nothing from the second.
  ISSUE_PUBLISHED_NOT_DELETABLE: 'admin.publications.error.issuePublishedNotDeletable',
  [ISSUE_CITED]: 'admin.publications.error.issueCited',

  // A second release or amend asked for while the first is still running.
  //
  // Worth its own sentence because the remedy is neither a fix nor a retry but
  // waiting: the document is being written right now, by this admin in another
  // tab or by somebody else entirely, and the generic "could not be published"
  // reads as a failure where nothing has failed.
  [ISSUE_BUSY]: 'admin.publications.error.issueBusy',
  NO_PREVIEW: 'admin.publications.error.noPreview',
  INVALID_STATUS_TRANSITION: 'admin.publications.error.invalidStatusTransition',
  INVALID_STATUS: 'admin.publications.error.invalidStatus',
  NOT_YET_SUPPORTED: 'admin.publications.error.notYetSupported',
  FILE_WRITE_FAILED: 'admin.publications.error.fileWriteFailed',

  // What a release refuses over, once it stops asking and starts stamping.
  //
  // Each of these is the VIOLATION, not the rail row that spotted it — the two
  // are named differently on purpose, so a refusal cannot be mistaken for the
  // check that produced it. Every one of them names something an admin can go
  // and change, which is why they are worth a sentence rather than the generic
  // "could not be published".
  INTERVAL_INVALID: 'admin.publications.error.intervalInvalid',
  MISSING_FILE_FOR_LANGUAGE: 'admin.publications.error.missingFileForLanguage',
  REFERENCE_FORMAT_MISSING_LANGUAGE: 'admin.publications.error.referenceFormatMissingLanguage',
  CUTOFF_BEFORE_PREVIOUS: 'admin.publications.error.cutoffBeforePrevious',
  CUTOFF_AFTER_SUCCESSOR: 'admin.publications.error.cutoffAfterSuccessor',
  MEMBER_LIMIT_EXCEEDED: 'admin.publications.error.memberLimitExceeded',

  // The bounds the read endpoints place on a request. Each names a limit on what
  // was ASKED rather than a state of anything, so the same request never
  // succeeds later — which is worth saying, because the generic sentence invites
  // exactly the retry that cannot work.
  NO_SERIES_IDS: 'admin.publications.error.noSeriesIds',
  TOO_MANY_SERIES_IDS: 'admin.publications.error.tooManySeriesIds',
  TOO_MANY_IDS: 'admin.publications.error.tooManyIds',
  INVALID_FILTER_VALUE: 'admin.publications.error.invalidFilterValue',
};

/**
 * The sentence for a resolution warning, in the ONE vocabulary that goes out.
 *
 * Keyed on the resolver's codes rather than the rail's. The rail names its rows
 * after the QUESTION it asked — "are cancelled members still counted as in
 * force" — while the release gate compares the resolver's own warning
 * vocabulary, and the correspondence between the two is stated by the row's
 * `acknowledgeCode`. A caller resolves that first and asks here second, so the
 * same warning reads the same whether it arrived from the rail or from the
 * server's refusal of the release that ignored it.
 */
const WARNING_KEYS: Readonly<Record<string, string>> = {
  CANCELLED_BUT_DATE_ALIVE: 'admin.publications.warning.CANCELLED_BUT_DATE_ALIVE',
  TYPE_DRIFT: 'admin.publications.warning.TYPE_DRIFT',
  NULL_PUBLISH_FROM_DROPPED: 'admin.publications.warning.NULL_PUBLISH_FROM_DROPPED',
  STALE_OVERRIDE: 'admin.publications.warning.STALE_OVERRIDE',
  OVERLAPPING_ISSUE: 'admin.publications.warning.OVERLAPPING_ISSUE',
  LIMIT_EXCEEDED: 'admin.publications.warning.LIMIT_EXCEEDED',
  SOURCE_ISSUES_INCOMPLETE: 'admin.publications.warning.SOURCE_ISSUES_INCOMPLETE',
};

/**
 * The label key for a warning, or `undefined` where there is none.
 *
 * `undefined` rather than a made-up key: the caller renders the raw code in that
 * case, which is ugly and honest, where a key that does not resolve renders as
 * the key itself — a string that looks like a bug in the translation rather than
 * a warning the server invented after this screen was written.
 */
export function warningKey(code: string): string | undefined {
  return WARNING_KEYS[code];
}

/**
 * What a draft issue warns about before anybody creates it.
 *
 * A warning is not a refusal — the draft still comes back and the create is
 * still offered — so each of these has to say what an admin would want to know
 * rather than what went wrong. The server's own English sentence travels beside
 * the code and is deliberately never rendered: untranslated server prose in a
 * Danish UI is the defect this mapping exists to avoid.
 */
const DRAFT_WARNING_KEYS: Readonly<Record<string, string>> = {
  INTERVAL_NOT_CHAINED: 'admin.publications.issueDraft.warning.INTERVAL_NOT_CHAINED',
  SERIES_NOT_ACTIVE: 'admin.publications.issueDraft.warning.SERIES_NOT_ACTIVE',
  IN_FORCE_HAS_NO_LOWER_BOUND: 'admin.publications.issueDraft.warning.IN_FORCE_HAS_NO_LOWER_BOUND',
  NO_NOMINAL_CLOSE: 'admin.publications.issueDraft.warning.NO_NOMINAL_CLOSE',
  NO_ANCHOR: 'admin.publications.issueDraft.warning.NO_ANCHOR',
  NO_MEMBERSHIP_CRITERIA: 'admin.publications.issueDraft.warning.NO_MEMBERSHIP_CRITERIA',
  CRITERIA_UNRESOLVABLE: 'admin.publications.issueDraft.warning.CRITERIA_UNRESOLVABLE',
  ONE_OFF_ALREADY_HAS_ITS_ISSUE:
    'admin.publications.issueDraft.warning.ONE_OFF_ALREADY_HAS_ITS_ISSUE',
};

/**
 * The sentence for a draft warning, or `undefined` where there is none.
 *
 * `undefined` rather than a constructed key, for the same reason the resolution
 * warnings do it: the caller then renders the raw code, which is ugly and
 * honest, where an unresolved key renders as itself and reads as a broken
 * translation.
 */
export function issueDraftWarningKey(code: string): string | undefined {
  return DRAFT_WARNING_KEYS[code];
}

// --------------------------------------------------------------- the envelope

/**
 * One rule a request failed, named so a form can place it beside its control.
 *
 * The SAME shape the validation endpoint returns, and deliberately not a second
 * declaration of it: a refusal's `fieldErrors` and a dry run's report are the
 * same rows from the same validator, so a form renders them through one path.
 */
export type PublicationFieldError = SeriesFieldError;

/**
 * The label key for the field a rule refused, and the language it belongs to.
 *
 * The server names the field by its WIRE path (`categoryId`, `descs.da.name`),
 * which is not a thing an admin has ever seen on the screen. The leaf is the
 * name the section already has a label for, and the middle segment of a `descs`
 * path is the language that row belongs to.
 *
 * Two paths need more than their leaf. A criteria rule names the position
 * INSIDE the criteria document it refused over — `criteria/criteria/0/operator`
 * — and the form has one criteria control, so the pointer names nothing on the
 * screen and is dropped. A report parameter is named by the key somebody typed,
 * which no bundle can carry: the field is labelled and the key travels beside
 * it, so the reader can tell which row of the table is meant.
 *
 * `undefined` where the path is none of those shapes: the caller then shows the
 * raw path, which is ugly and honest, where a constructed key that does not
 * resolve renders as itself and reads as a broken translation.
 */
export function seriesFieldLabel(
  field: string,
): { key: string; lang?: string; param?: string } | undefined {
  const parts = field.split('.');
  if (parts.length === 1) {
    // Everything up to the JSON pointer, which is the control's own name.
    const leaf = parts[0].split('/')[0];
    return leaf === '' ? undefined : { key: `admin.publications.series.field.${leaf}` };
  }
  if (parts.length === 3 && parts[0] === 'descs') {
    return { key: `admin.publications.series.field.${parts[2]}`, lang: parts[1] };
  }
  if (parts.length === 2 && parts[0] === 'reportParams' && parts[1] !== '') {
    return { key: 'admin.publications.series.field.reportParams', param: parts[1] };
  }
  return undefined;
}

/**
 * The sentence that says why a rule refused, in the reader's own language.
 *
 * A rule arrives as an id — `S-6`, `C-4` — and an id is the server's name for a
 * rule, not a reason: "Refused by rule S-6" names nothing an admin can go and
 * change. The bundle carries one sentence per rule under this prefix, saying
 * what is wrong and what to do about it.
 *
 * Built rather than mapped, because the id IS the discriminator and a second
 * table of the same thirty-odd names would only drift from the validator's. A
 * rule the bundle has no sentence for is the one case a caller has to handle —
 * see {@link seriesRuleText}, which falls back to the generic line and keeps
 * the id.
 */
export function seriesRuleKey(rule: string): string {
  return `admin.publications.rule.${rule}`;
}

/** A translation lookup, as `TranslocoService.translate` answers for a plain string. */
export type TranslateText = (key: string, params?: Record<string, unknown>) => string;

/**
 * Why a rule refused, with its id kept beside it.
 *
 * The id on its own is the server's name for a rule and not a reason anybody
 * can act on — "a yearly cadence needs a month" is. So the sentence is what
 * gets read, and the id follows in brackets because it is what an operator
 * quotes when they ask about a rule.
 *
 * A rule with no sentence yet falls back to the generic line, which already
 * carries the id: it is never lost, and never printed twice. The brackets are
 * assembled here rather than as a translated format: parentheses around an
 * identifier are punctuation, not prose.
 *
 * One function for every screen that lists a refused rule (the two editors and
 * the series page), so a rule reads the same wherever it is met.
 */
export function seriesRuleText(translate: TranslateText, rule: string): string {
  const key = seriesRuleKey(rule);
  const sentence = translate(key);
  return sentence === key || sentence === ''
    ? translate('admin.publications.series.ruleFailed', { rule })
    : `${sentence} (${rule})`;
}

/**
 * The rules a refusal named, each worded once, in the order the server named
 * them.
 *
 * A rule the validator applies per language (a name, say) arrives once per
 * field. A screen that lists the rules without their fields says each one
 * once.
 */
export function seriesRuleTexts(
  translate: TranslateText,
  errors: readonly PublicationFieldError[],
): readonly string[] {
  const rules = [...new Set(errors.map((error) => error.rule))];
  return rules.map((rule) => seriesRuleText(translate, rule));
}

/** A refusal the publication API stated in its own envelope. */
export interface PublicationErrorEnvelope {
  readonly kind: 'publication';
  /** The server's word for the rule. Never rendered raw — see {@link publicationErrorKey}. */
  readonly code: string;
  /**
   * The server's English sentence, for a log.
   *
   * Never shown: untranslated server prose in a Danish UI is the defect the code
   * mapping exists to avoid.
   */
  readonly message: string;
  /** `0` where the failure was not an HTTP response — a synthesized envelope. */
  readonly status: number;
  /**
   * Everything the envelope carried beyond the four fields above.
   *
   * A bag rather than a field per code: the mapper attaches whatever the refusal
   * knows — the stamp a lost publish race recorded, the unknown token a pattern
   * named — and a reader that wants one of them knows which one it is asking for.
   */
  readonly details: Readonly<Record<string, unknown>>;
  /** Empty rather than absent, so a caller can iterate without a guard. */
  readonly fieldErrors: readonly PublicationFieldError[];
  readonly unacknowledgedWarnings: readonly string[];
}

/**
 * A failure that is not one of ours.
 *
 * The publication endpoints are the only ones behind the mapper; everything else
 * in the application still fails as a bare status with a text body, and so does a
 * publication request that never reached the resource — a 502 from in front of
 * it, a dropped connection. Kept as its own shape rather than as an envelope with
 * empty fields, because "the server refused, and said why" and "something went
 * wrong on the way" are different things to tell somebody.
 */
export interface BarePublicationFailure {
  readonly kind: 'bare';
  readonly status: number;
  /** Whatever text came back, if any. Diagnostic only. */
  readonly message?: string;
}

export type DecodedPublicationError = PublicationErrorEnvelope | BarePublicationFailure;

/**
 * One reading of a failed publication request, for every caller.
 *
 * The envelope is not one shape on the wire: a refusal arrives as a coded object
 * from the mapped endpoints and as a message beginning with the same code from
 * the ones that still throw plainly, and a caller should not have to know which
 * it is looking at. Decoding it once is also what keeps `fieldErrors` and the
 * unacknowledged warnings from being dug out of `response.error` by hand at each
 * call site, where the optional chain is written differently every time.
 *
 * Narrow on `kind`. A bare failure is NOT an envelope with empty fields.
 */
export function decodePublicationError(response: unknown): DecodedPublicationError {
  const status = statusOf(response);
  const body = (response as { readonly error?: unknown } | undefined)?.error;

  if (typeof body === 'string') {
    const code = knownCodeIn(body);
    return code ? envelope(code, body, status, {}) : { kind: 'bare', status, message: body };
  }
  if (!isRecord(body)) {
    return { kind: 'bare', status };
  }

  const message = typeof body['message'] === 'string' ? body['message'] : undefined;
  const declared =
    typeof body['code'] === 'string' && body['code'] !== '' ? body['code'] : undefined;
  const code = declared ?? (message === undefined ? undefined : knownCodeIn(message));
  if (code === undefined) {
    return { kind: 'bare', status, message };
  }
  return envelope(code, message ?? '', status, body);
}

/**
 * The warning codes a refused publish is still waiting on.
 *
 * Read from the refusal rather than from the checklist: the checklist was
 * fetched before the attempt and the server re-resolves at publish time, so a
 * warning can appear between the two — which is exactly the case this list
 * exists for.
 */
export function unacknowledgedWarningsOf(response: unknown): readonly string[] {
  const decoded = decodePublicationError(response);
  return decoded.kind === 'publication' ? decoded.unacknowledgedWarnings : [];
}

/**
 * One message that stands in the way of removing an issue.
 *
 * The same three facts every other row that names a message carries: the key
 * that addresses it, the short id it is written under, and what it is called.
 * `messageId` is absent on a message that has not been numbered yet — such a
 * row is named by its `title` and opened by its `uid`, because a uid put on the
 * screen names nothing to the person reading it.
 */
export interface CitingMessage {
  /** The short id — `NM-815-26` — where the message has one. */
  readonly messageId?: string;
  /** The message's own identifier, for a caller that wants to address it. */
  readonly uid?: string;
  /** What the message is called, in the language the refusal was asked in. */
  readonly title?: string;
}

/**
 * What still cites an issue: the messages the refusal named, and how many there
 * are in all.
 *
 * The list is CAPPED server-side and the count is the real answer — a withdrawn
 * weekly issue can be cited by hundreds, and a panel reading `messages.length`
 * would report twenty of them as the whole obstacle.
 */
export interface IssueCitations {
  readonly messages: readonly CitingMessage[];
  readonly count: number;
}

/**
 * The messages a removal was refused over, or `undefined` for any other failure.
 *
 * `undefined` rather than an empty list, so a caller can tell "this refusal was
 * not about citations" from "it was, and the server named none" — the second
 * would be a server bug worth showing as one rather than rendering as a blank
 * space under a sentence promising a list.
 */
export function issueCitationsOf(response: unknown): IssueCitations | undefined {
  const decoded = decodePublicationError(response);
  if (decoded.kind !== 'publication' || decoded.code !== ISSUE_CITED) {
    return undefined;
  }
  const messages = citingMessagesOf(decoded.details['citingMessages']);
  const declared = decoded.details['citingCount'];
  // The count is the whole answer and the rows are a sample of it, so a server
  // that sent no count is taken at the length of what it did send rather than
  // reporting zero obstacles beside a list of them.
  const count = typeof declared === 'number' ? declared : messages.length;
  return { messages, count };
}

/**
 * A row that neither names nor addresses a message is dropped.
 *
 * The uid is deliberately NOT promoted into `messageId`: it would be printed as
 * the message's name, and a uuid is not a name anybody can look up. A row with
 * only a uid still travels — it can be opened, and the title names it.
 */
function citingMessagesOf(raw: unknown): readonly CitingMessage[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: CitingMessage[] = [];
  for (const row of raw) {
    if (!isRecord(row)) {
      continue;
    }
    const uid = typeof row['uid'] === 'string' ? row['uid'] : undefined;
    const messageId = typeof row['messageId'] === 'string' ? row['messageId'] : undefined;
    const title = typeof row['title'] === 'string' ? row['title'] : undefined;
    if (messageId === undefined && uid === undefined) {
      continue;
    }
    out.push({
      ...(messageId === undefined ? {} : { messageId }),
      ...(uid === undefined ? {} : { uid }),
      ...(title === undefined ? {} : { title }),
    });
  }
  return out;
}

/**
 * The rules a refused save named, for a form to render in place.
 *
 * Empty for a bare failure and for a refusal that named no field — a save
 * refused for a reason that is not about one control has nothing to put beside
 * one.
 */
export function publicationFieldErrors(response: unknown): readonly PublicationFieldError[] {
  const decoded = decodePublicationError(response);
  return decoded.kind === 'publication' ? decoded.fieldErrors : [];
}

/**
 * The i18n key to show for a failed request.
 *
 * The code is read from the envelope's own field where there is one, and looked
 * for in the message otherwise — the backend reports some refusals as a message
 * that begins with the code, and a caller should not have to know which.
 */
export function publicationErrorKey(response: unknown, fallbackKey: string): string {
  const decoded = decodePublicationError(response);
  return decoded.kind === 'publication' ? (CODE_KEYS[decoded.code] ?? fallbackKey) : fallbackKey;
}

/**
 * The server's own sentence, for the refusals whose detail no key can carry.
 *
 * `undefined` for everything else, and callers pass it through the same
 * `errorDetail` slot the curation dialogs use — a line of English under the
 * translated one, never instead of it. The one refusal that needs it here is a
 * domain mismatch: the sentence names which of the two domains is missing, and
 * a key cannot, because the answer differs per request.
 */
export function publicationErrorMessage(response: unknown): string | undefined {
  const decoded = decodePublicationError(response);
  return decoded.kind === 'publication' && decoded.message !== '' ? decoded.message : undefined;
}

/**
 * The refusal's own code, where the section recognises it.
 *
 * For the handful of refusals a caller must answer with an ACTION rather than a
 * sentence — a stale revision is answered with a re-read of the row, a domain
 * mismatch with the server's own words beneath the mapped ones — and no shared
 * map can choose either of those.
 */
export function publicationErrorCode(response: unknown): string | undefined {
  const decoded = decodePublicationError(response);
  return decoded.kind === 'publication' && CODE_KEYS[decoded.code] ? decoded.code : undefined;
}

/**
 * Whether a failed read said the publication is not there: a 404 with
 * `SERIES_NOT_FOUND`.
 *
 * The status is checked as well as the code, because only the 404 means the id
 * names nothing. Any other failure (a timeout, a 500, a refusal in front of the
 * server) may pass on a second try, so its screen keeps Retry.
 */
export function seriesNotFound(response: unknown): boolean {
  const decoded = decodePublicationError(response);
  return (
    decoded.kind === 'publication' && decoded.code === SERIES_NOT_FOUND && decoded.status === 404
  );
}

/** The four fields the envelope names itself; everything else is a detail. */
const ENVELOPE_KEYS: ReadonlySet<string> = new Set([
  'code',
  'message',
  'fieldErrors',
  'unacknowledgedWarnings',
]);

function envelope(
  code: string,
  message: string,
  status: number,
  body: Readonly<Record<string, unknown>>,
): PublicationErrorEnvelope {
  return {
    kind: 'publication',
    code,
    message,
    status,
    details: detailsOf(body),
    fieldErrors: fieldErrorsOf(body['fieldErrors']),
    unacknowledgedWarnings: stringsOf(body['unacknowledgedWarnings']),
  };
}

/**
 * The extras, whether they arrived nested or beside the code.
 *
 * Both, because both ship: the contract writes them under `details` and the
 * mapper attaches the ones it knows at the top level. Flattening a nested
 * `details` here means a reader asks for `details.publicId` once rather than
 * looking in two places.
 */
function detailsOf(body: Readonly<Record<string, unknown>>): Readonly<Record<string, unknown>> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (key !== 'details' && !ENVELOPE_KEYS.has(key)) {
      out[key] = value;
    }
  }
  const nested = body['details'];
  return isRecord(nested) ? { ...out, ...nested } : out;
}

/** A malformed row is dropped rather than rendered as three undefineds. */
function fieldErrorsOf(raw: unknown): readonly PublicationFieldError[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: PublicationFieldError[] = [];
  for (const row of raw) {
    if (
      isRecord(row) &&
      typeof row['rule'] === 'string' &&
      typeof row['field'] === 'string' &&
      typeof row['message'] === 'string'
    ) {
      out.push({ rule: row['rule'], field: row['field'], message: row['message'] });
    }
  }
  return out;
}

function stringsOf(raw: unknown): readonly string[] {
  return Array.isArray(raw)
    ? raw.filter((value): value is string => typeof value === 'string')
    : [];
}

function statusOf(response: unknown): number {
  const status = (response as { readonly status?: unknown } | undefined)?.status;
  return typeof status === 'number' ? status : 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The first catalogue code the text names, for the endpoints that still throw plainly. */
function knownCodeIn(text: string): string | undefined {
  return Object.keys(CODE_KEYS).find((code) => text.includes(code));
}
