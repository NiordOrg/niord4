import { HttpClient, type HttpResourceRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { withEpochDates } from '../../../../shared/utils/epoch-dates';

/**
 * The vocabularies below are declared array-first, union-second.
 *
 * Every one of them is a server enum, and the two halves have to agree: a value
 * the backend can send and this file does not list reaches the screen as a raw
 * identifier, and a value listed here that the backend does not have is a control
 * offering something every save will refuse. Deriving the union from the array
 * means there is one list to keep, and the spec walks each array against the Java
 * enum transcribed beside it — so a value added on one side and not the other is
 * red rather than discovered on a screen.
 */

/**
 * Who, besides the owner, may see and cite a publication.
 *
 * A SECOND property from the owner, and the two answer different questions. The
 * owner is the one desk the publication is listed in, administered from and
 * whose timezone its cut-offs are read in; this says who else may reference it,
 * read-only, from their own domain — the journal number every desk cites, and
 * nothing else about it.
 *
 * `SELECTED_DOMAINS` is the only value the list beside it means anything for,
 * and the owner is never in that list: it is already the owner.
 */
export const AVAILABILITIES = ['OWNER_ONLY', 'SELECTED_DOMAINS', 'ALL_DOMAINS'] as const;
export type Availability = (typeof AVAILABILITIES)[number];

/**
 * The three states a series moves between.
 *
 * The array that renders them lives beside the labels, in `publication-status`,
 * because a status only reaches a screen through a key — this is the type the
 * wire carries.
 */
export type SeriesStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';

/**
 * Where a series' cut-off falls by default when an issue is released.
 *
 * The cut-off is the END OF THE CONTENT PERIOD, which is not the moment somebody
 * pressed publish. A weekly series closes its content when the release runs
 * (`RELEASE_MOMENT`); a yearly in-force list closes at the boundary its public
 * window names (`PERIOD_START`), an accumulated one at the end of the period it
 * covers (`PERIOD_END`) — however late the document actually went out.
 *
 * A default, never a rule: the admin may choose any past instant at publish time.
 */
export const CUTOFF_DEFAULTS = ['RELEASE_MOMENT', 'PERIOD_START', 'PERIOD_END'] as const;
export type CutoffDefault = (typeof CUTOFF_DEFAULTS)[number];

/** The three states an issue moves between. Labelled in `publication-status`. */
export type IssueStatus = 'OPEN' | 'PUBLISHED' | 'RETIRED';

export const SERIES_CADENCES = ['NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'] as const;
export type SeriesCadence = (typeof SERIES_CADENCES)[number];

/**
 * What kind of publication a series is, as opposed to how often it comes out.
 *
 * Separate from cadence because `cadence: 'NONE'` covers two different things:
 * a series with no calendar (eleven NCAGS editions, eight ice-service notices,
 * four editions of Dansk Fyrliste) and a publication that came out once and
 * stopped. Reading NONE as "one-off" merges them, which is what once put an
 * eleven-issue series in the one-off list.
 *
 * A STORED FACT, never derived here. The issue count that produced it was spent
 * once, during the import; the frontend reads the answer and never recomputes
 * it, so a publication cannot change kind because somebody uploaded a second
 * file.
 */
export const SERIES_KINDS = ['SCHEDULED', 'UNSCHEDULED', 'ONE_OFF'] as const;
export type SeriesKind = (typeof SERIES_KINDS)[number];
/**
 * What an issue IS, and so whether it has membership semantics at all.
 *
 * NONE is not a placeholder: roughly 48 legacy publications have no membership of
 * any kind, and eight imported series carry it. Leaving it out of this union makes
 * the content-mode picker unable to represent them, and a save would quietly
 * convert one into a query-backed series.
 */
export const CONTENT_MODES = [
  'GENERATED_FROM_QUERY',
  'UPLOADED_FILE',
  'EXTERNAL_LINK',
  'NONE',
] as const;
export type ContentMode = (typeof CONTENT_MODES)[number];

/**
 * How a period selects its content — the membership regime, in one field.
 *
 * The first two ask the messages themselves: published inside the issue's
 * interval, or standing at its cut-off. The third asks another publication:
 * a compilation carries the union of what the source series' own published
 * issues contained, so it reproduces what went out rather than re-running a
 * query over it — and it therefore carries a source series instead of criteria
 * and a liveness flag.
 */
export const TIME_RELATIONS = [
  'PUBLISHED_IN_INTERVAL',
  'IN_FORCE_AT_CUTOFF',
  'COMPILED_FROM_SOURCE',
] as const;
export type TimeRelation = (typeof TIME_RELATIONS)[number];

export const INTERVAL_BOUND_SOURCES = ['STAMPED', 'NOMINAL', 'RECOVERED', 'MANUAL'] as const;
export type IntervalBoundSource = (typeof INTERVAL_BOUND_SOURCES)[number];

/**
 * How an issue arrived at its cut-off — a DIFFERENT vocabulary from the above.
 *
 * The interval bounds say where a content period's edges came from; these say how
 * the instant membership was frozen at was arrived at, and the two are not
 * interchangeable even where they read alike. The first two are stamps this
 * application wrote: `STAMPED_AT_PUBLISH` is the ordinary release, closed at the
 * moment the action ran, and `STAMPED_MANUAL_TIME` is the one worth seeing — an
 * admin named a past instant, so the content closes somewhere other than where
 * the release happened.
 *
 * The rest name what an imported issue's cut-off was recovered FROM, and they are
 * kept apart rather than collapsed into one "reconstructed" because they are not
 * equally good evidence: a row's own last-write stamp is a weaker witness of a
 * release than the following issue closing it, and neither is a witness at all
 * where the answer is the period's nominal close or the boundary of the public
 * window. `NOT_RELEASED` is not a recovery failure — nobody published the issue,
 * so there is no instant to find.
 */
export const CUTOFF_SOURCES = [
  'STAMPED_AT_PUBLISH',
  'STAMPED_MANUAL_TIME',
  'RECOVERED_FROM_UPDATED',
  'RECOVERED_FROM_NEXT_TAG',
  'RECOVERED_FROM_COVER',
  'NOMINAL_CLOSE',
  'PUBLIC_WINDOW',
  'MANUAL',
  'NOT_RELEASED',
] as const;
export type CutoffSource = (typeof CUTOFF_SOURCES)[number];

/**
 * One rule a series fails, named so the form can render it against its input.
 *
 * `field` follows the VO's own naming, including the dotted desc paths
 * (`descs.da.name`), so a form keyed the same way can place every error without
 * a translation table in between.
 */
/**
 * A publication category, as the picker needs it.
 *
 * `publish` is the flag that decides whether the category's published issues are
 * exposed on the public site, so it is shown beside the name rather than left to
 * a separate screen — picking a category is picking that behaviour.
 */
export interface PublicationCategory {
  readonly categoryId: string;
  /**
   * Where the category sorts on the public page. Every category has one: the
   * column cannot hold "no order". The categories tab always sends a number;
   * on update the server keeps an omitted priority as it is and refuses an
   * explicit `null`.
   */
  readonly priority?: number | null;
  readonly publish?: boolean;
  readonly descs: readonly PublicationCategoryDesc[];
}

export interface PublicationCategoryDesc {
  readonly lang: string;
  readonly name: string;
  readonly description?: string | null;
}

export interface SeriesFieldError {
  /** The rule id, e.g. `S-1`. */
  readonly rule: string;
  readonly field: string;
  readonly message: string;
}

/** Whether an issue goes out on a click or on the clock. Per-series, and editable only in settings. */
export const RELEASE_MODES = ['MANUAL_GATE', 'AUTO_RELEASE'] as const;
export type ReleaseMode = (typeof RELEASE_MODES)[number];

export const NEXT_ISSUE_CREATIONS = ['AUTO_ON_PUBLISH', 'MANUAL'] as const;
export type NextIssueCreation = (typeof NEXT_ISSUE_CREATIONS)[number];

/** Whether a message may cite this series, and whether the citation is public. */
export const MESSAGE_PUBLICATIONS = ['NONE', 'INTERNAL', 'EXTERNAL'] as const;
export type MessagePublication = (typeof MESSAGE_PUBLICATIONS)[number];

/** How an issue names itself. NONE = the series does not number its issues. */
export const NUMBERING_SCHEMES = [
  'ISO_WEEK_YEAR',
  'YEAR_EDITION',
  'MONTH_YEAR',
  'EDITION_SEQUENCE',
  'NONE',
] as const;
export type NumberingScheme = (typeof NUMBERING_SCHEMES)[number];

export const PAGE_SIZES = ['A3', 'A4', 'A5', 'B4', 'B5', 'LETTER', 'LEGAL', 'LEDGER'] as const;
export type PageSize = (typeof PAGE_SIZES)[number];

export const PAGE_ORIENTATIONS = ['PORTRAIT', 'LANDSCAPE'] as const;
export type PageOrientation = (typeof PAGE_ORIENTATIONS)[number];

export const SORT_DIRECTIONS = ['ASC', 'DESC'] as const;
export type SortDirection = (typeof SORT_DIRECTIONS)[number];

export const CUTOFF_DAYS = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;
export type CutoffDay = (typeof CUTOFF_DAYS)[number];

/**
 * What a criterion selects on.
 *
 * These are the server's WIRE names, which are camel-case and not the Java
 * constant names — the enum carries the wire form as a field of its own, so a
 * client that guessed at `MESSAGE_SERIES` would be refused on every save.
 */
export const CRITERION_KINDS = [
  'messageSeries',
  'messageMainType',
  'messageType',
  'domain',
  'area',
  'category',
  'chart',
] as const;
export type CriterionKind = (typeof CRITERION_KINDS)[number];

export const CRITERION_OPERATORS = ['IN', 'NOT_IN'] as const;
export type CriterionOperator = (typeof CRITERION_OPERATORS)[number];

/**
 * How the criteria in one document combine.
 *
 * One value, and that is the contract rather than a placeholder: the server's
 * enum has a single constant, so a document sent with anything else fails
 * deserialization before any rule gets to look at it.
 */
export const CRITERIA_MATCHES = ['ALL'] as const;
export type CriteriaMatch = (typeof CRITERIA_MATCHES)[number];

/** One node of a criteria document. `values` are stable string keys, never ids. */
export interface IssueCriterion {
  readonly kind: CriterionKind;
  readonly operator: CriterionOperator;
  readonly values: readonly string[];
}

/**
 * What a series selects.
 *
 * Read before trusting anything else on a series: it decides what goes INTO a
 * publication, and S-1 refuses to activate a query-backed series without one.
 */
export interface IssueCriteria {
  readonly schemaVersion?: number;
  readonly match?: CriteriaMatch;
  readonly criteria: readonly IssueCriterion[];
}

/**
 * A message named the one way every list in this API names one.
 *
 * The uid is the key and is unreadable; the short id is what the message is
 * written under and a message nobody has numbered yet has none; the title is
 * what it is called, read in the language the list was asked for. All three
 * travel together so no screen has to choose between showing a uuid and showing
 * nothing — and they are filled for a whole list in one query, never per row.
 */
export interface NamedMessage {
  readonly messageUid: string;
  /** `NM-815-26` where the message has a number; null where it has none yet. */
  readonly messageId?: string | null;
  /** Null where the message carries no titled description in any language. */
  readonly title?: string | null;
}

/**
 * A candidate the resolver dropped, and why.
 *
 * Grouped by code and counted, because the interesting question is "how many
 * and what kind", not which uid. A preview reporting 0 selected reads as "the
 * query is wrong" when the truth may be "19 candidates had no publish date" --
 * a different problem with a different fix.
 */
export interface PreviewWarning {
  readonly code: string;
  readonly count: number;
  readonly messageUids?: readonly string[];
  readonly acknowledgeable?: boolean;
}

/**
 * Every way the predicate can drop a candidate, in the server's own words.
 *
 * Left open to any other string on purpose: a build older than the server's
 * vocabulary must still render the omission, and naming an unrecognised code as
 * an unknown reason beats failing to compile against a response it can show.
 */
export const CRITERIA_MISS_CODES = [
  'BEFORE_INTERVAL',
  'AFTER_CUTOFF',
  'NO_PUBLISH_DATE',
  'NOT_ALIVE_AT_CUTOFF',
  'STATUS_NOT_PUBLIC',
  'CRITERION_MISMATCH',
] as const;
export type CriteriaMissCode = (typeof CRITERIA_MISS_CODES)[number];

/**
 * One dropped message, with the code that dropped it.
 *
 * `detail` carries whatever makes THAT code understandable — a date comparison
 * carries both dates, a criterion mismatch carries what was expected against
 * what was found — so its keys differ by code and it is typed as the open bag
 * it is rather than as a union nothing on the wire guarantees.
 */
export interface PreviewMiss {
  readonly messageUid: string;
  /**
   * The short id the message is known by — `NM-815-26` — where it has one.
   *
   * Absent on a message that carries none, and on a server older than the field.
   * The uid is what addresses the row and stays; this is what a reader can
   * recognise, and it is filled for the sampled rows in one query rather than
   * per row.
   */
  readonly messageId?: string;
  /**
   * What the message is called, in the language the list was asked for.
   *
   * The half of the answer a reader without the id in their head can use: an
   * unnumbered message has no short id at all, and naming it by its uid names
   * nothing. Absent where the message carries no description, and on a server
   * older than the field; filled for the sampled rows in the same query that
   * fills {@link messageId} rather than one request per row.
   */
  readonly title?: string | null;
  readonly code: CriteriaMissCode | (string & {});
  readonly detail?: Readonly<Record<string, unknown>>;
}

/**
 * What the criteria left out of an open issue: the whole count and a capped
 * sample of the rows, taken from the same resolve as the members beside it.
 */
export interface IssueOmissions {
  /** How many candidates were dropped in all; `misses` is a sample of them. */
  readonly missCount: number;
  readonly misses: readonly PreviewMiss[];
}

/**
 * A message published into a released issue's period after the release.
 *
 * The omission panel's row, less the reason: there is only one reason, and the
 * banner that lists these says it once. `messageUid` addresses the row; the
 * short id and the title are what a reader recognises it by, and either may be
 * absent where the message has none.
 */
export interface MessageSinceRelease {
  readonly messageUid: string;
  readonly messageId?: string;
  readonly title?: string | null;
}

/**
 * The issue screen in one response, from one resolution.
 *
 * Field names match the five single-purpose endpoints it composes, so the
 * facade's state is assembled without translation.
 */
export interface IssueWorkbench {
  readonly issue: PublicationIssue;
  readonly members: readonly IssueMember[];
  readonly audit: readonly IssueAuditEntry[];
  readonly overrides: readonly IssueOverride[];
  /** Absent on a published or retired issue. */
  readonly checklist?: PublishChecklist;
  /** Absent where nothing was resolved (a frozen issue, or a series without membership). */
  readonly omissions?: IssueOmissions;
  /**
   * Every source-series period this issue's period covers, in cut-off order.
   *
   * Present — possibly empty — on an OPEN compilation, and absent everywhere
   * else: on every other regime because the question does not apply, and on a
   * released compilation because the survey comes with the resolve and a frozen
   * issue takes none (its record is the header's source-series and source-issue
   * ids). Presence is what tells the screen which of the two panels to draw: a
   * compilation has no candidates to have left out, so {@link omissions} is
   * absent for it and this is its counterpart — what it compiled, and what it is
   * still waiting for.
   */
  readonly sources?: readonly SourceIssue[];
  /**
   * The renders the server is still holding for this issue — newest generation
   * per language.
   *
   * Rides along rather than being asked for separately, so a reader who reloads
   * the screen is offered the documents that actually exist instead of having to
   * produce them again to find out. Empty where nothing is stored, and always
   * empty for a frozen issue, which takes no preview at all. Absent on a server
   * older than the field.
   */
  readonly previews?: readonly IssuePreview[];
  /**
   * The instant this answer was resolved at, epoch ms.
   *
   * The server's own reading of it rather than the client's: an open issue may
   * be read at a past instant, and a screen that assumed its own clock would
   * label a member list with a moment it was not taken at.
   */
  readonly viewedAt: number;
  /**
   * The start of the period this answer was resolved over, epoch ms.
   *
   * The issue's own stored start, unless the caller asked about a different one
   * — a period an admin is typing but has not saved. Echoed for the same reason
   * `viewedAt` is: the screen must be able to say which period the numbers on it
   * describe, and its own idea of what it asked for is not evidence of what the
   * server answered.
   *
   * `null` where the resolution has no lower bound at all — an in-force
   * publication selects what stands at the cut-off rather than what was
   * published inside a window, so its answer has no start. That is a fact about
   * the question, not a missing field: the key is always present.
   *
   * A server older than the field sends nothing, and the reader falls back to
   * the issue's stored start, which is what such a server resolved over.
   */
  readonly viewedFrom: number | null;
  /**
   * The members whose message was published after the cut-off being asked about.
   *
   * Always taken from the AS-OF-NOW resolution, whatever instant the rest of the
   * answer was resolved at, because it is the same question in both views: read
   * now these rows are members that a release right now would carry, and read at
   * the planned cut-off they are the ones it would leave behind. A screen that
   * had to re-ask for them per view could show two different sets under one
   * question.
   *
   * WHICH cut-off they are late for is the instant the request named where it
   * named one — the planned view, and the planned cut-off as typed while a
   * period is being edited — and the issue's stored planned cut-off otherwise.
   * {@link lateAfter} says which was used, so the screen never has to assume.
   *
   * Empty where there is no such instant, where it has not passed, and on a
   * frozen issue — none of the three has anything to be late for. Absent on a
   * server older than the field, which a reader takes as empty.
   */
  readonly afterPlannedCutoff?: readonly IssueMember[];
  /**
   * The instant {@link afterPlannedCutoff} was measured against, epoch ms.
   *
   * `null` where there is none — an issue with no planned cut-off, asked about
   * at no particular instant, and every frozen issue — and the list is then
   * empty. It is what lets the screen NAME the moment it reports lateness
   * against instead of assuming the stored one: while a period is being edited
   * that moment is the cut-off as typed, and a heading quoting the saved value
   * beside rows counted from the typed one is two answers under one sentence.
   *
   * Absent on a server older than the field, where the stored planned cut-off is
   * what such a server measured against.
   */
  readonly lateAfter?: number | null;
  /**
   * The messages published into this released issue's period since its release.
   *
   * The issue's own selection asked again over its own period and cut-off, less
   * its frozen members: what an amend would take in. Only a published issue of
   * a series that selects by query and tiles its periods answers with a list
   * (possibly empty); an open issue answers `null`, and an imported issue or a
   * server older than the field answers nothing. Both read as an empty list.
   */
  readonly publishedSinceRelease?: readonly MessageSinceRelease[] | null;
  /**
   * The release or amend the server is running on this issue right now.
   *
   * The server's own record of the work, kept on the issue rather than in the
   * browser that started it. A publish and an amend each render the document
   * again per language and take tens of seconds, and the request outlives the
   * dialog it was pressed from — so a page reloaded in the middle of one, a
   * second tab and another admin entirely all have to see the same fact, and
   * none of them may start a second one on top of it.
   *
   * Absent while the issue is idle, and absent again the moment the request
   * ends. The server also treats a record older than its own ceiling as stale
   * and stops reporting it, so work that died with its request cannot lock an
   * issue for good.
   */
  readonly inFlight?: IssueInFlight;
}

/**
 * A release or an amend the server is still working on.
 *
 * Named rather than a bare flag, for the reason the facade's own action signal
 * is: the two are shown in different places — a publish is the release control,
 * an amend is the document list it is about to replace — so a screen has to know
 * WHICH is running, not merely that something is.
 */
export interface IssueInFlight {
  readonly action: 'PUBLISH' | 'AMEND';
  /** When the work started, epoch ms, as the server stamped it. */
  readonly since: number;
}

/**
 * What a criteria document WOULD select, without saving it.
 *
 * `sample` is capped server-side and is a sample rather than a page — `total`
 * is the real answer and `truncated` says whether the sample is one. Its rows
 * are named messages rather than bare uids: the panel exists to let an admin
 * recognise what the document they are typing selects, and a column of uuids
 * answers that question with something nobody can read.
 */
export interface ResolvePreview {
  readonly source: 'PROBE';
  readonly total: number;
  readonly candidates: number;
  readonly sample: readonly NamedMessage[];
  readonly truncated: boolean;
  readonly warnings?: readonly PreviewWarning[];
  /**
   * How many candidates the predicate dropped, ALL of them.
   *
   * Read this rather than `misses.length`. The rows are capped server-side — a
   * wide document over a live corpus drops thousands, and serializing them onto
   * a path that fires while somebody is still typing answers a question nobody
   * asked — so the array is a sample and this is the size of what it samples.
   */
  readonly missCount?: number;
  readonly misses?: readonly PreviewMiss[];
}

/** What a row in the issue list is, when it is not a real issue. */
export const ISSUE_ROW_KINDS = ['MISSING', 'UPCOMING'] as const;
export type IssueRowKind = (typeof ISSUE_ROW_KINDS)[number];

/** A real issue reports its stored status; a synthesized row reports its kind. */
export type ComputedIssueStatus = IssueStatus | IssueRowKind;

/**
 * Why gap detection did or did not run. `CADENCED_SERIES` means it did.
 *
 * The time relation never closes the gate: a series with a cadence is expected
 * to keep releasing whatever its issues carry, so an in-force weekly gets its
 * upcoming period and its missing ones like a tiling weekly does. What the
 * relation decides is how coverage between two releases is read, and that is
 * the server's business.
 */
export const GAP_DETECTION_REASONS = [
  'CADENCED_SERIES',
  'NO_CADENCE',
  'SERIES_NOT_ACTIVE',
  'SERIES_DORMANT',
  'CADENCE_PERIOD_UNKNOWN',
] as const;
export type GapDetectionReason = (typeof GAP_DETECTION_REASONS)[number];

export interface PublicationSeriesDesc {
  readonly lang: string;
  readonly name: string;
  readonly fileNamePattern?: string;
  readonly messageReferenceFormat?: string;
  readonly nameSuggestionPattern?: string;
  readonly linkPattern?: string;
}

export interface PublicationSeries {
  readonly seriesId: string;
  readonly status: SeriesStatus;
  /**
   * The revision this copy was read at, echoed back by a save.
   *
   * Filled by every read and COMPARED by the write — never assigned from a body.
   * A save that carries it is told when somebody else has changed the series in
   * the meantime, instead of quietly reverting their change: a save sends the
   * whole object, so a form loaded before their edit carries the old value of
   * every field it is not about. Absent keeps the older last-write-wins, which
   * is why it travels wherever a screen has one to send.
   */
  readonly version?: number;
  readonly categoryId?: string;
  readonly descs: readonly PublicationSeriesDesc[];
  readonly cadence: SeriesCadence;
  /** What kind of publication this is. Never inferred from the cadence. */
  readonly kind: SeriesKind;
  readonly contentMode: ContentMode;
  readonly timeRelation?: TimeRelation;
  /**
   * The series this one compiles, by its immutable id.
   *
   * Carried exactly when the time relation is `COMPILED_FROM_SOURCE`, and never
   * beside criteria or a liveness flag: a compilation asks the source series'
   * published issues what they contained rather than asking the messages
   * anything. Admin shape only — the citizen-facing series shape does not carry
   * it.
   */
  readonly sourceSeriesId?: string;
  readonly aliveAtCutoff?: boolean;
  readonly languages: readonly string[];
  readonly firstIssueStartsAt?: number;
  readonly nominalCutoffDay?: CutoffDay;
  /** 1-31. Carried by MONTHLY and YEARLY only (S-6). */
  readonly nominalCutoffDayOfMonth?: number;
  /** 1-12. Carried by YEARLY only (S-6). */
  readonly nominalCutoffMonth?: number;
  /**
   * 'HH:mm' in the DOMAIN's timezone. Every cadence but NONE carries one (S-7).
   *
   * There is no per-series timezone. The zone is a domain setting and the domains
   * differ, so a second one here could disagree with the one that counts.
   */
  readonly nominalCutoffTime?: string;
  /**
   * Set on every series the importer created, and on nothing else. Provenance:
   * it is what tells a series carried in with the archive apart from one
   * somebody authored here.
   */
  readonly importSource?: string;
  readonly legacyTemplateId?: string;
  /** No issue in long enough that the series has stopped producing them. */
  readonly dormant?: boolean;
  /** Admin tier only. Absent until a query-backed series has been given one. */
  readonly criteria?: IssueCriteria;
  readonly reportId?: string;
  /**
   * Where the cut-off falls when an issue of this series is released.
   *
   * Absent behaves as `RELEASE_MOMENT`: the publish dialog offers "now" and the
   * admin may still choose a past instant.
   */
  readonly cutoffDefault?: CutoffDefault;
  readonly releaseMode?: ReleaseMode;
  readonly nextIssueCreation?: NextIssueCreation;
  readonly messagePublication?: MessagePublication;
  /**
   * The one domain that OWNS this publication. Required, and never blank.
   *
   * It decides three things at once: which desk lists and administers the
   * publication, which desk's admins may write it, and which timezone its
   * cut-offs are read in. Absence is not a value — a publication nobody owns is
   * one no desk is accountable for, and an ordinary save is refused rather than
   * being allowed to claim it.
   */
  readonly domainId: string;
  /**
   * Who else may see and cite it, read-only.
   *
   * Absent on a payload written before the field existed; a reader falls back to
   * the mode the content implies — see the editors' own default.
   */
  readonly availability?: Availability;
  /**
   * The domains named by `SELECTED_DOMAINS`, never including the owner.
   *
   * Meaningless under the other two values, and sent empty rather than omitted
   * when the mode changes away from SELECTED_DOMAINS: an omitted collection
   * reads as "unchanged" and would leave a stale share standing.
   */
  readonly availableDomainIds?: readonly string[];
  readonly sortOrder?: number;
  readonly numberingScheme?: NumberingScheme;
  /** Report layout. Only meaningful when contentMode is GENERATED_FROM_QUERY. */
  readonly pageSize?: PageSize;
  readonly pageOrientation?: PageOrientation;
  readonly mapThumbnails?: boolean;
  readonly messageSortBy?: string;
  readonly messageSortOrder?: SortDirection;
  /** Whether issues carry a separate file per language. */
  readonly languageSpecific?: boolean;
  /**
   * Free-form report parameters passed to the report template.
   *
   * The interval's week and year are NOT in here — they travel with the issue,
   * so a parameter repeating them would be a second source that can disagree.
   */
  readonly reportParams?: Readonly<Record<string, unknown>>;
}

/** Where a language's file came from. Absent when the language has no file. */
export const ISSUE_FILE_SOURCES = ['GENERATED', 'UPLOADED'] as const;
export type IssueFileSource = (typeof ISSUE_FILE_SOURCES)[number];

/**
 * How much an issue's stored member list is trusted to be what went out.
 *
 * `IMPORTED` is the one every screen branches on: an issue carried in with the
 * archive was never resolved in this application, so re-deciding its content is
 * not an action that exists. The other five grade a snapshot this system did
 * take, from an exact replay down to a series with no membership at all.
 *
 * `COMPILED` is not a grade of a criteria replay but a statement that there was
 * no query to replay: the members were taken from the source series' own frozen
 * rows, and the header names the issues they came from.
 */
export const MEMBERSHIP_PROVENANCES = [
  'EXACT',
  'EXPLAINED_DIFF',
  'UNION_SNAPSHOT',
  'COMPILED',
  'NO_MEMBERSHIP',
  'IMPORTED',
] as const;
export type MembershipProvenance = (typeof MEMBERSHIP_PROVENANCES)[number];

export interface PublicationIssueDesc {
  readonly lang: string;
  readonly name: string;
  readonly fileName?: string;
  readonly link?: string;
  /**
   * Where the document is actually fetchable.
   *
   * Not the stored file path: that is a storage location and 404s if fetched.
   * The server resolves it — external link where there is one, repository URL
   * otherwise — so the two halves of "where is this document" cannot disagree.
   *
   * Editor shape only; the public issue endpoint does not carry it.
   */
  readonly href?: string;
  /** Editor shape only. Whether the file was generated or uploaded by hand. */
  readonly fileSource?: IssueFileSource;
  /**
   * Whether this language's file name was set by hand for THIS issue.
   *
   * Absent reads as `false` — the name follows the series' pattern — which is
   * also what a server built before per-issue file names sends, so a screen
   * asking the question gets the right answer from an older backend rather than
   * marking every issue as tailored.
   */
  readonly fileNameOverridden?: boolean;
  /**
   * What the series' pattern would name this language's file.
   *
   * Resolved server-side by the same naming the release uses, and the only
   * honest answer to "what will this be called": the pattern may be written in
   * tokens a screen cannot resolve — the cut-off's month or day — and
   * {@link fileName} is empty until the release writes the document, which is
   * the state of every issue that can still be tailored.
   *
   * Editor shape only, and absent from a server that does not compute it.
   */
  readonly suggestedFileName?: string;
  /**
   * Whether this language's name was typed rather than rendered from the series.
   *
   * A typed name stops tracking the interval: move the period and a suggested
   * name follows it while a typed one stays put. Absent reads as `false` — the
   * series' pattern names it — which is also what a server built before per-issue
   * names sends.
   */
  readonly nameOverridden?: boolean;
  /**
   * Editor shape only. Whether a publish will leave this file alone.
   *
   * Set by an upload, and the difference between a hand-made correction that
   * survives and one that vanishes the next time somebody presses publish.
   */
  readonly fileSourceSticky?: boolean;
}

export interface PublicationIssue {
  readonly publicId: string;
  readonly seriesId?: string;
  readonly status: IssueStatus;
  /**
   * The revision this copy was read at, echoed back by every write against it.
   *
   * It also moves when something changes ABOUT the issue that is not stored on
   * it: a curation writes child rows, and the revision is forced on so two
   * curators working the same open issue collide rather than one of them
   * silently replacing the other's decision in a member list neither re-reads.
   */
  readonly version?: number;
  readonly descs: readonly PublicationIssueDesc[];
  readonly week?: number;
  readonly weekTo?: number;
  readonly year?: number;
  readonly edition?: string;
  /**
   * What the numbering PRINTS, where somebody wrote it differently.
   *
   * The numbers above stay derived from the cut-off and stay numeric —
   * ordering, the timeline strip, gap detection and the archive all compute from
   * them, and nothing anybody types may reach that arithmetic. These are the
   * labels, free text: a week may be written `36+37` or `36 & 37`, and every
   * surface that PRINTS a number reads the label first and the derived value
   * only when there is none. The second week has no label of its own — a span
   * written any other way is written into the week, so it says its span once.
   *
   * Absent — from an older server as much as from an untouched issue — reads as
   * "no label", so the derived number prints.
   */
  readonly weekLabel?: string;
  readonly yearLabel?: string;
  /**
   * The report THIS issue renders with, when that is not the series' report.
   *
   * Absent means it follows the series, which is the ordinary case. Resolved
   * `issue.reportId ?? series.reportId` wherever a render or a preview asks
   * which report is in play.
   */
  readonly reportId?: string;
  /**
   * The render parameters THIS issue carries.
   *
   * Merged OVER the series' map key by key at render time — a key absent here
   * keeps the series' value — and the numbering above is injected last, which is
   * why `week`, `weekTo`, `year` and `edition` are refused as keys.
   */
  readonly reportParams?: Readonly<Record<string, unknown>>;
  /** Epoch ms. The whole backend serializes dates as numbers. */
  readonly intervalFrom?: number;
  readonly intervalTo?: number;
  readonly cutoffStampedAt?: number;
  /**
   * How that cut-off was arrived at, in the server's own words.
   *
   * One of {@link CUTOFF_SOURCES}, and left open to any other string on purpose:
   * a build older than the server's vocabulary must still render the issue, and
   * the screen names an unrecognised value as an unknown source rather than
   * failing to compile against a response it can show. Absent on an issue this
   * application has neither published nor imported — there is nothing to say yet.
   */
  readonly cutoffSource?: CutoffSource | (string & {});
  /**
   * Where each end of the content period came from.
   *
   * Only `STAMPED` was recorded at a release. A `NOMINAL` bound was computed from
   * the cadence, a `RECOVERED` one was read back out of the archive, and a
   * `MANUAL` one was typed — so the three are derivations of a date nobody
   * observed, and a reader deciding whether to trust the period needs to see
   * which of the four they are looking at.
   */
  readonly intervalFromSource?: IntervalBoundSource;
  readonly intervalToSource?: IntervalBoundSource;
  readonly publicFrom?: number;
  readonly publicTo?: number;
  /**
   * What the public window WOULD be, for a window nobody has stamped yet.
   *
   * Display-only, computed per request and never stored: an issue that has not
   * been released has no window at all, and a published one keeps its open end
   * until the successor's release closes it. Two rows carry them. An OPEN issue
   * of a cadenced series gets its planned cut-off and one cadence period on from
   * there — a cadence-less series carries neither, because nothing says when the
   * next one is due. And the PUBLISHED issue whose successor is OPEN gets that
   * successor's planned cut-off as its end: that is the instant the release will
   * stamp onto it, and it is what the site will do rather than what the record
   * currently says.
   *
   * `null` is the server saying it has no expectation, which is not the same as
   * the field being absent from an older build's response — read both as "no
   * projection" and fall back to the stored window.
   */
  readonly expectedPublicFrom?: number | null;
  readonly expectedPublicTo?: number | null;
  /**
   * When the release action actually ran.
   *
   * NOT the cut-off: the cut-off is the instant membership was frozen, and the
   * release can happen minutes later. Absent on an imported issue, where nobody
   * ran a release in this application at all.
   */
  readonly publishedAt?: number;
  readonly publishedBy?: string;
  readonly memberCount?: number;
  readonly membershipProvenance?: MembershipProvenance;
  /**
   * The series a compiled issue took its members from, at the moment it froze.
   *
   * Part of the snapshot header rather than read back off the series: the series
   * can be re-pointed afterwards, and the record of what this issue compiled must
   * not move with it. Absent on every other regime.
   */
  readonly snapshotSourceSeriesId?: string;
  /**
   * The source issues whose frozen rows this one holds, in the order they were
   * compiled.
   *
   * The answer cannot be re-derived once more issues of the source series fall
   * into the period, which is why it is recorded rather than computed. Absent on
   * every other regime, and on a server older than the field.
   */
  readonly snapshotSourceIssueIds?: readonly string[];
  /** True where the cut-off was reconstructed at import rather than stamped at release. */
  readonly cutoffReconstructed?: boolean;
  readonly legacyPublicationId?: string;
  /**
   * What THIS issue selects, when that is not what its series selects.
   *
   * Absent means it inherits. The escape hatch that removes the reason to clone a
   * whole series for one edition that has to differ — a two-week issue over the
   * week 52/1 turnover, a supplement carrying one extra message series.
   */
  readonly criteriaOverride?: IssueCriteria;
  /**
   * Whether the issue is selecting something other than its series says.
   *
   * Derived server-side, and NOT the same as `criteriaOverride` being present: a
   * published issue answers from the snapshot it went out with (the override may
   * since have been edited away), and an override equal to the series' criteria is
   * no deviation at all. Both are comparisons the client should not have to make.
   */
  readonly criteriaOverridden?: boolean;
  /**
   * What the SERIES selects — projected onto the issue so the screen can compare.
   *
   * Read-only. Fetching the series separately would give this screen a second
   * source that can disagree with the  flag the server
   * already computed from both documents.
   */
  readonly seriesCriteria?: IssueCriteria;
}

/**
 * A row in a series' issue list: a real issue, or a period that never got one.
 *
 * One type because the backend sends one list. Splitting it here would mean
 * re-interleaving two arrays by sort key on the client, which is the merge the
 * server already did.
 *
 * `pseudo` is the discriminator and is ABSENT on a real row — not null, absent.
 * Narrow on it rather than on a missing `publicId`.
 */
export interface PublicationIssueRow extends Omit<PublicationIssue, 'publicId' | 'status'> {
  /** Absent on a synthesized row: a period nobody published has no entity. */
  readonly publicId?: string;
  /** Absent on a synthesized row: only a real issue has a stored status. */
  readonly status?: IssueStatus;
  /** Present only on a synthesized row. Its presence is what tells the two apart. */
  readonly pseudo?: IssueRowKind;
  readonly computedStatus?: ComputedIssueStatus;
  /** One key space for both kinds, so the merged list is one sequence. */
  readonly sortKey?: number;
  /** Epoch ms. `cutoffStampedAt ?? intervalTo`, resolved server-side. */
  readonly effectiveCutoff?: number;
  /** What each configured language would call this period. Synthesized rows only. */
  readonly suggestedDescs?: readonly PublicationIssueDesc[];
  /** The issues either side of a gap; absent at the head or tail of the run. */
  readonly precedingPublicId?: string;
  readonly followingPublicId?: string;
}

/**
 * Whether anybody looked for gaps, and why not.
 *
 * Read this BEFORE `gapCount`. A closed gate omits the count entirely, because
 * "nobody looked" and "looked, found none" must not render the same way — and
 * every imported series is DRAFT, which closes the gate, so that is the common
 * case rather than an unusual one.
 */
export interface GapDetection {
  readonly enabled: boolean;
  /** Switch on this. `reason` is prose for a log and will be reworded. */
  readonly reasonCode: GapDetectionReason;
  readonly reason: string;
}

/**
 * Why a message is in an issue.
 *
 * Derived server-side from the member source and the snapshot relation, never
 * stored — a stored derivable value is a second source of truth that can
 * disagree with the first.
 */
export const MEMBER_REASONS = [
  'IN_INTERVAL',
  'IN_FORCE_AT_CUTOFF',
  'FROM_SOURCE_ISSUE',
  'MANUAL_INCLUDE',
  'IMPORTED',
] as const;
export type MemberReason = (typeof MEMBER_REASONS)[number];

/**
 * How a message came to be in an issue.
 *
 * Four values, not five: an EXCLUDE is not a member source, because an excluded
 * message is not a member — it is a decision recorded against the issue, and it
 * appears in the history rather than in the list.
 *
 * `COMPILED` is the row a compilation took from one of its source issues, and it
 * is kept apart from `CRITERIA` because no query selected it: what put it here is
 * that another issue printed it, and the row names which one.
 */
export const MEMBER_SOURCES = ['CRITERIA', 'COMPILED', 'OVERRIDE_INCLUDE', 'IMPORTED'] as const;
export type MemberSource = (typeof MEMBER_SOURCES)[number];

/**
 * The source issue a compiled member came from, as the row names it.
 *
 * The publicId is the record and the rest is read live from that issue, so a
 * source issue that has since been deleted arrives as its id alone rather than
 * taking the member row down with it. `name` is in the language the list was
 * asked for.
 */
export interface SourceIssueRef {
  readonly publicId: string;
  readonly seriesId?: string;
  readonly week?: number;
  readonly weekTo?: number;
  readonly year?: number;
  /** Epoch ms. The source issue's effective cut-off — what ordered the union. */
  readonly cutoff?: number;
  /** Epoch ms. Where the source issue's content period starts; absent on an in-force source. */
  readonly intervalFrom?: number;
  readonly name?: string;
}

/**
 * What a source-series period looks like from the compilation's side.
 *
 * `PUBLISHED` contributed its frozen rows, `OPEN` has not gone out yet and
 * contributes nothing, and `MISSING` is a synthesized stretch of the period no
 * issue covers at all — which is why it is a status here and not on the issue
 * vocabulary: two of the three name an issue, and the third names its absence.
 */
export const SOURCE_ISSUE_STATUSES = ['PUBLISHED', 'OPEN', 'MISSING'] as const;
export type SourceIssueStatus = (typeof SOURCE_ISSUE_STATUSES)[number];

/**
 * One source-series period, as the survey behind the compilation saw it.
 *
 * The same facts a member row's source carries, plus what the period is — and
 * the id is optional here alone: a `MISSING` row is a stretch nobody published,
 * so there is no issue for it to name.
 */
export interface SourceIssue extends Omit<SourceIssueRef, 'publicId'> {
  readonly publicId?: string;
  readonly status: SourceIssueStatus;
  /** How many rows it contributed. Absent on a period with no issue to count. */
  readonly memberCount?: number;
  /** Epoch ms. The start of the period, and of an uncovered stretch. */
  readonly intervalFrom?: number;
}

/**
 * The two curation decisions, as the server names them.
 *
 * Each has its own endpoint here rather than travelling as a field, so this is
 * the vocabulary the audit trail and the refusals speak rather than something
 * this client ever sends.
 */
export const OVERRIDE_KINDS = ['INCLUDE', 'EXCLUDE'] as const;
export type OverrideKind = (typeof OVERRIDE_KINDS)[number];

/**
 * Who decided a message belongs in an issue, when, and why.
 *
 * Carried only on a row that stands on a decision rather than on the query, and
 * only for a caller allowed to read it — so its PRESENCE is the signal, not the
 * value of any one field. An older backend omits the object entirely, which is
 * why every consumer falls back to the stored note.
 */
export interface MemberCuration {
  /** Which way the decision went. Absent on a payload written before it was carried. */
  readonly kind?: OverrideKind;
  readonly author?: string;
  /** Epoch ms. */
  readonly at?: number;
  readonly reason?: string;
}

/**
 * One curation decision that STANDS on an issue, and the message it is about.
 *
 * The exclusions are why this shape exists. An excluded message is not a member,
 * so it has no row in the member list for a "withdraw this decision" affordance
 * to hang off — and the only other record of it is the history, which says what
 * HAPPENED rather than what stands: an exclude followed by a withdrawal leaves
 * two entries and no decision, and reading them as a state brings a withdrawn
 * exclusion back to life on the screen.
 *
 * The same curation facts a member row carries, plus the uid, because the
 * why-line an include renders and the reason an exclude was taken are the same
 * facts and must not be able to disagree.
 */
export interface IssueOverride extends MemberCuration {
  readonly messageUid: string;
  /**
   * The short id the message is known by — `NM-815-26` — where it has one.
   *
   * The uid addresses the decision and is never what a reader is shown: an
   * excluded message is named on the screen the same way a member is, or the
   * row says nothing about which message was taken out. Absent on a message
   * that carries no short id, and on a server older than the field.
   */
  readonly messageId?: string | null;
  /**
   * What the message is called, in the language the list was asked for.
   *
   * What names a row whose message has no number yet, and the second line under
   * one that has. Absent where the message carries no description, and on a
   * server older than the field.
   */
  readonly title?: string | null;
}

/**
 * What has changed under a frozen member since its issue went out.
 *
 * The status and the type are compared, and a message that is gone entirely
 * says so under its own name rather than reporting two empty values. The end of
 * the publication window is frozen as part of the record but never compared: it
 * acquires a value as a side effect of every cancel and expiry, so it never says
 * anything the status does not already say.
 */
export const MEMBER_DRIFT_FIELDS = ['exists', 'type', 'status'] as const;
export type MemberDriftField = (typeof MEMBER_DRIFT_FIELDS)[number];

/**
 * What a frozen member's message looks like TODAY.
 *
 * Sent on every frozen row: the status shown beside a member of a published
 * issue is the one the message has now, and the frozen one is "published" for
 * every member by construction. It never rewrites the frozen row beside it: the
 * snapshot is the record of what was published, and healing it would erase the
 * divergence somebody has to see before deciding whether to amend.
 */
export interface LiveMessageState {
  readonly exists: boolean;
  /**
   * Whether the message is still one the public may read.
   *
   * Derived server-side from the live status, because "withdrawn since the issue
   * went out" is the case an admin most wants flagged and it is not visible from
   * a status name unless the reader knows which statuses are public.
   */
  readonly publiclyVisible: boolean;
  readonly type?: string;
  readonly status?: string;
}

/**
 * One message in an issue.
 *
 * The frozen* fields are what the message looked like when the issue was
 * published, not what it looks like now. Rendering the live message here would
 * quietly rewrite history: a published PDF lists what it listed.
 */
export interface IssueMember {
  readonly messageUid: string;
  readonly sortIndex?: number;
  readonly frozenShortId?: string;
  /**
   * The LIVE title of the message, in the language the list was asked for.
   *
   * The one field here that is deliberately not frozen, and the reason is what a
   * reader does with it: an id names a row only to somebody who already knows
   * which message it is, so this is how anybody else tells the rows apart. A
   * title kept as it read at release would sit under a row whose message has
   * since been renamed and say the wrong thing about the message that is there
   * now — the frozen* fields beside it already carry the record, and the drift
   * marks say where the two disagree. Absent when the message is gone.
   */
  readonly title?: string;
  readonly frozenType?: string;
  readonly frozenStatus?: string;
  /** Epoch ms. */
  readonly frozenPublishDateFrom?: number;
  readonly frozenPublishDateTo?: number;
  readonly source?: MemberSource;
  readonly reasonCode?: MemberReason;
  readonly reasonNote?: string;
  /**
   * Which source issue put this row in a compiled issue.
   *
   * Present on compiled rows alone: absent on a manual include, which no source
   * issue printed, and on every row of every other regime. A message two source
   * issues both carried names the EARLIEST of them, because that is the one the
   * union took the row from.
   */
  readonly sourceIssue?: SourceIssueRef;
  /**
   * The decision behind an override row.
   *
   * Absent on a row the query selected, and absent below the admin tier. Read
   * defensively: `reasonNote` is the older, stored half of the same answer and
   * is what an imported row carries.
   */
  readonly curation?: MemberCuration;
  /**
   * What has moved under a FROZEN row since its issue went out.
   *
   * A row that moved is still a member: it was published when the issue was
   * decided at its cut-off, and what changed happened afterwards. Absent on a
   * live list — an open issue resolves its members now, so there is nothing for
   * them to have changed from — and absent on a frozen row that still agrees
   * with its message, which is the ordinary case.
   */
  readonly drift?: readonly MemberDriftField[];
  /** The live message, on every FROZEN row; absent on a live list. */
  readonly current?: LiveMessageState;
}

/**
 * Who an audit entry was recorded for.
 *
 * `IMPORT` is its own kind rather than a nameless `USER`: an entry carried in
 * with an issue was not an action anybody took in this application, and the two
 * must not read alike on the history panel.
 */
export type AuditActorKind = 'USER' | 'SYSTEM' | 'IMPORT';

/**
 * Every action the trail can carry, as the server declares its vocabulary.
 *
 * Specific by design — there is no generic `UPDATED`, because a history line
 * saying "something changed" answers nothing on the screen somebody opened to
 * ask what happened. The server refuses to write an action outside this list, so
 * it is closed on that side; it is NOT closed on this one, which is why the
 * entry's own field stays a plain string below.
 */
export const ISSUE_AUDIT_ACTIONS = [
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
] as const;
export type IssueAuditAction = (typeof ISSUE_AUDIT_ACTIONS)[number];

/**
 * One superseded document an audit entry preserved, named by its language.
 *
 * No address. The archive sits outside the served repository root and is reached
 * only through the stream endpoint, which resolves the location from the entry
 * itself — so the pair {entry, language} IS the address, and there is nothing
 * else for a client to hold.
 */
export interface IssueArchiveFile {
  readonly lang: string;
  /** The name the document had when it was public, not the name it is stored under. */
  readonly fileName?: string;
}

/**
 * What one history line preserved before it overwrote something.
 *
 * Present on an amend, on a release that wrote over existing bytes, and on a
 * hand replacement of a released document; absent everywhere else — which is
 * exactly what tells the panel which lines have a superseded document behind
 * them.
 */
export interface IssueArchive {
  readonly auditEntryId?: number;
  readonly files?: readonly IssueArchiveFile[];
}

/** One entry in an issue’s history. */
export interface IssueAuditEntry {
  readonly id?: number;
  /**
   * One of {@link ISSUE_AUDIT_ACTIONS}, and typed loosely on purpose.
   *
   * A build older than the server's vocabulary must render the bare code rather
   * than fail to compile against a response it can still show: the code is
   * imperfect English but it is the name of what happened, where a narrowed type
   * would push callers into a cast that says nothing.
   */
  readonly action: string;
  readonly actorKind?: AuditActorKind;
  readonly actorLabel?: string;
  /** Epoch ms. */
  readonly created?: number;
  readonly reason?: string;
  readonly archive?: IssueArchive;
  /**
   * Whatever makes THIS action understandable, in the shape that action uses.
   *
   * A string on most lines and an object on the ones that carry named facts —
   * a transfer carries the two domains it moved a publication between — so it is
   * typed as the open bag it is rather than as a union nothing on the wire
   * guarantees. Readers pull out the keys their own action names; see
   * `ownerTransfer` in `publication-status`.
   */
  readonly detail?: string | Readonly<Record<string, unknown>>;
}

/**
 * How much a failing rail row matters, in the server's own words.
 *
 * `OK | WARN | BLOCK`, because that is what the wire carries. The union used to
 * read `BLOCKER | WARNING | INFO`, which no response ever contained, so every
 * comparison against it was dead and the screen fell back to deriving the tier
 * from `acknowledgeable` — under which the single acknowledgeable row was the
 * only warning and every other failing row painted as a hard blocker.
 */
export const CHECK_SEVERITIES = ['OK', 'WARN', 'BLOCK'] as const;
export type CheckSeverity = (typeof CHECK_SEVERITIES)[number];

/** One row of the release rail. */
export interface PublishCheck {
  readonly code: string;
  /**
   * The code the release gate compares, where it differs from the row's own.
   *
   * The rail names its rows after the QUESTION it asked — "are cancelled members
   * still counted as in force" — while the gate compares the resolver's warning
   * vocabulary, in which that answer has a different name. The server states the
   * correspondence here rather than leaving each client to hand-maintain a table
   * of it, which is how a ticked box came to be sent back under a word the gate
   * did not recognise.
   *
   * Absent on a row whose two names coincide, and on a server older than the
   * field; a reader falls back to `code`, which is correct for both.
   */
  readonly acknowledgeCode?: string;
  readonly severity: CheckSeverity;
  readonly passed: boolean;
  /** A failing WARN row may be acknowledged; a BLOCK row never can. */
  readonly acknowledgeable: boolean;
  /**
   * Whether the question this row asks applies to this issue at all.
   *
   * A series that publishes an uploaded PDF is never asked whether its report is
   * configured, and a rule that does not apply passes for a reason that says
   * nothing — so listing it beside the ones that were actually checked inflates
   * a rail nobody then reads. `false` means "not asked"; absent means the server
   * did not say, and a row nobody has ruled out is treated as asked.
   */
  readonly applicable?: boolean;
  /**
   * What this row was measured to be, in English.
   *
   * Written for an API reader to quote — it names the languages, the ids and the
   * zone of the instants it mentions. A screen prefers {@link detailCode}, which
   * says the same thing in a form that can be worded in the reader's language.
   */
  readonly detail?: string;
  /**
   * Which sentence {@link detail} is, as a stable name.
   *
   * A row's code names the QUESTION and does not change with the answer, so a
   * screen that had only the code could say what was checked but never what came
   * of it. This names the answer's shape — `MEMBER_LIMIT.of`,
   * `CUTOFF_NOT_FUTURE.at`, `NOT_APPLICABLE.IN_FORCE_SERIES` — and the values go
   * in {@link detailParams}, so the sentence around them can be translated.
   *
   * Absent on a server older than the field, where `detail` stands alone.
   */
  readonly detailCode?: string;
  /**
   * The values that sentence interpolates — counts, limits, codes, instants.
   *
   * Instants are epoch milliseconds and never rendered text: a moment the server
   * formatted would show a different clock and layout from every other instant on
   * the same screen. Always an object on a server that sends `detailCode`, empty
   * where the sentence takes no values.
   */
  readonly detailParams?: Record<string, unknown>;
}

/**
 * Whether this issue can be released, and what stands in the way.
 *
 * `canPublish` is the server’s answer and the only one that counts — the UI
 * must not re-derive it from the rows, or the button and the backend can
 * disagree about the same issue.
 */
export interface PublishChecklist {
  readonly rows: readonly PublishCheck[];
  readonly canPublish: boolean;
  readonly blockingCodes: readonly string[];
  /**
   * How many messages the resolution behind these rows selected.
   *
   * The count for THIS instant, which is the whole reason it travels with the
   * rail: a release closed at a chosen moment freezes a different member list
   * from the one the screen resolved, and a dialog quoting the screen's count
   * beside that cut-off states a number the release will not produce.
   *
   * Absent on a server older than the field, where a caller falls back to the
   * count it already had rather than showing none.
   */
  readonly memberCount?: number;
}

/**
 * What a release is being asked to do.
 *
 * `cutoff` is the chosen END OF THE CONTENT PERIOD and may lie in the past only;
 * absent means "now". The document is always rendered by the release itself.
 */
export interface PublishRequest {
  /** Epoch ms. Past only — the server refuses a future instant with CUTOFF_IN_FUTURE. */
  readonly cutoff?: number;
  readonly acknowledgedWarnings: readonly string[];
  /**
   * The issue's revision, as the screen last read it.
   *
   * Worth sending here above everywhere else: a release cannot be taken back,
   * and pressing it against a rail drawn before somebody else curated the member
   * set freezes a different publication from the one that was reviewed.
   */
  readonly version?: number;
}

/**
 * One language's rendered preview.
 *
 * `href` is where the PDF is actually fetchable, with the caller's own
 * credentials — the same arrangement as a published document, and the reason
 * there are no tickets anywhere in this section.
 */
export interface IssuePreview {
  readonly lang: string;
  /** Epoch ms. */
  readonly renderedAt: number;
  /**
   * Not on the wire today: neither the render response nor the workbench row
   * carries an address, because the file answers only to a request carrying the
   * bearer and is fetched by language. Kept optional for a server that may add
   * one; nothing reads it.
   */
  readonly href?: string;
}

/** What a publish actually did. */
export interface PublishResult {
  readonly publicId: string;
  /** Epoch ms — the instant membership was frozen. */
  readonly stampedAt: number;
  readonly memberCount: number;
  readonly unacknowledgedWarnings?: readonly string[];
  /** The successor the publish opened, where the series creates one. */
  readonly successorId?: string;
}

/**
 * What an amend is being asked to do.
 *
 * There is no cut-off here and there cannot be. An amend replaces the document a
 * citation already points at, so re-taking the instant the content was decided
 * would quietly turn a correction into a different publication. The reason is
 * mandatory (3–512 trimmed) because an amend rewrites something people have read.
 */
export interface AmendRequest {
  readonly reason: string;
  readonly acknowledgedWarnings: readonly string[];
  /** The issue's revision, as the screen last read it. */
  readonly version?: number;
}

/** What an amend actually did. `archivePaths` are the outgoing files it preserved. */
export interface AmendResult {
  readonly publicId: string;
  /** Epoch ms — the issue's OWN cut-off, unchanged by the amend. */
  readonly stampedAt?: number;
  readonly memberCount: number;
  readonly unacknowledgedWarnings?: readonly string[];
  readonly archivePaths?: readonly string[];
}

/**
 * Just enough of a message to name it and address it.
 *
 * What the include dialog needs: the admin types a short id, the override needs
 * the uid, and `id` IS the uid on the wire.
 */
export interface ResolvedMessage {
  readonly id: string;
  readonly shortId?: string;
}

/**
 * The most issues one series' archive is ever asked for in a single request.
 *
 * Four times the largest archive in the estate (the two weekly series carry
 * ~500 each), so it truncates nothing that exists while still refusing to
 * accept an answer of unbounded size from a series that has run away.
 */
export const ISSUE_LIST_MAX_SIZE = 2000;

/**
 * One cell of a series' recent-periods strip.
 *
 * A period, not an issue: the cells that matter most are the ones with no issue
 * behind them. `publicId` is absent on those — the same rule the issue list's
 * synthesized rows follow, because both come from one synthesizer.
 */
export interface IssueTimelineRow {
  /** What the issue is called, or what the series would call that period. */
  readonly label?: string;
  readonly week?: number;
  /**
   * The week a multi-week issue runs TO — the "52+1" holiday issue.
   *
   * Absent on an ordinary single-week period, and absent on every synthesized
   * cell: a period that never got an issue covers one period by construction.
   */
  readonly weekTo?: number;
  readonly year?: number;
  /**
   * The edition within the year, where the series numbers its issues that way.
   *
   * Absent everywhere else, and absent on a synthesized cell — an edition is
   * minted when an issue is created, and nothing minted one for a period that
   * never got one.
   */
  readonly edition?: string;
  readonly computedStatus?: ComputedIssueStatus;
  /** Absent where the publication has no membership, and on a synthesized cell. */
  readonly memberCount?: number;
  /** Absent on a synthesized cell. Its absence is what identifies one. */
  readonly publicId?: string;
  /** Epoch ms. */
  readonly intervalFrom?: number;
  readonly intervalTo?: number;
}

/** One series' strip of recent periods, newest first. */
export interface IssueTimeline {
  readonly publicationSeriesId: string;
  readonly rows: readonly IssueTimelineRow[];
  /**
   * Whether periods were examined for gaps at all.
   *
   * Read this before believing a strip with no MISSING cell in it: a series
   * nobody examined and a series with nothing missing produce the same cells.
   */
  readonly gapDetection?: GapDetection;
}

/**
 * The most series one timeline request may name.
 *
 * The endpoint never scans the estate — it answers for exactly the series it was
 * given — and refuses more than this many in one call.
 */
export const TIMELINE_MAX_SERIES = 50;

/** How many periods back a strip reads by default. The server's own default. */
export const TIMELINE_DEFAULT_PERIODS = 8;

/**
 * How far back a cadence-less series' card reads.
 *
 * A series with no cadence has no periods to count, so the endpoint answers with
 * its most recent ISSUES and truncates them to whatever was asked for — and the
 * card says how many issues there are. Asked for at the strip's eight, a series
 * of eleven editions would report eight, which is not a shorter answer but a
 * wrong one. Generous enough that no publication in the estate comes near it,
 * and still a bound rather than an open-ended read.
 */
export const UNSCHEDULED_ARCHIVE_LIMIT = 100;

/** One series' issue list: the rows, and what to make of them. */
export interface IssueListResult {
  readonly publicationSeriesId: string;
  readonly data: readonly PublicationIssueRow[];
  /** REAL issues only — never the synthesized rows. */
  readonly total: number;
  readonly gapDetection: GapDetection;
  /** Absent when `gapDetection.enabled` is false. Never read it without checking. */
  readonly gapCount?: number;
}

/** Something an admin should know before creating a draft. Never a refusal. */
export interface IssueDraftWarning {
  readonly code: string;
  /** The server's own English sentence. Diagnostic only — never rendered. */
  readonly message?: string;
}

/**
 * An issue that does not exist yet, in the shape of one that does.
 *
 * The same body the create endpoint accepts for the same period, plus the three
 * things only an unsaved issue has: what the criteria would select over the
 * interval, which issue it chains off, and what to know before creating it.
 *
 * `publicId` and `status` are optional here and required on a real issue: a
 * draft is not an entity, and typing it as one invites a caller to open it.
 */
export interface IssueDraft extends Omit<PublicationIssue, 'publicId' | 'status'> {
  readonly publicId?: string;
  readonly status?: IssueStatus;
  /**
   * What the criteria would select over this interval, right now.
   *
   * `null` rather than `0` when no count could be taken — a series with no
   * membership semantics, or criteria that do not resolve. Zero is a finding
   * about the period; null is the absence of one.
   */
  readonly wouldMatchCount?: number | null;
  readonly chainedFromPublicId?: string;
  /**
   * What that issue is CALLED, in the language the draft was asked for. Absent
   * where it carries no name in any language, and absent on a draft that chains
   * off nothing — an id names nothing to a person, so the name is the half a
   * screen may show.
   */
  readonly chainedFromName?: string;
  readonly warnings?: readonly IssueDraftWarning[];
}

/**
 * What creating an issue needs — the whole period, in one request.
 *
 * The bounds arrive REVIEWED rather than derived: the draft endpoint proposed
 * them, an admin looked at them, and these are what came back approved. Both
 * travel together because a period is one decision; creating the issue and then
 * correcting its end is two writes, with an issue standing between them whose
 * period nobody agreed to.
 */
export interface CreateIssueRequest {
  readonly seriesId: string;
  /** Epoch ms. Absent chains from the series' own anchor. */
  readonly intervalFrom?: number;
  /** Epoch ms. Absent lets the cadence derive the nominal close. */
  readonly intervalTo?: number;
  /**
   * What to call the issue, where the admin did not keep the suggestion.
   *
   * Applied through the same name path an edit uses, so a name typed here is
   * recorded as chosen rather than derived and a later interval change does not
   * put the suggestion back over it. A language absent from the map — and an
   * absent map — leaves the server's own suggestion standing, which is why only
   * the languages the admin actually retyped belong in it.
   */
  readonly names?: Readonly<Record<string, string>>;
  /**
   * Whether the period is being reconstructed after the fact.
   *
   * It decides the bound's provenance — RECOVERED rather than STAMPED — and so
   * only says anything at all when a start bound is supplied with it.
   */
  readonly recovered?: boolean;
}

/**
 * What moving a publication to another desk needs.
 *
 * The reason is mandatory, as it is on every other change somebody will have to
 * account for afterwards: a transfer moves who is accountable for a publication,
 * and the entry it writes is the only record of why.
 */
export interface TransferOwnerRequest {
  /** The domain the publication moves TO. */
  readonly domainId: string;
  /** Mandatory, 3–512, kept on the audit entry. */
  readonly reason: string;
  /** The series' revision, as the screen last read it. */
  readonly version?: number;
}

/**
 * The query parameters of a guarded write, with the revision beside them.
 *
 * A module function rather than a method: the guard spec walks this service's
 * prototype against the list of methods it pins, and a private helper is on the
 * prototype like any other.
 *
 * An ABSENT revision is not zero and not an empty string — it is the caller
 * declining the check, which the server reads as the older last-write-wins. So
 * the parameter is omitted entirely rather than sent blank, which the server
 * would refuse as a value that is not a whole number.
 */
function withVersion(
  params: Readonly<Record<string, string>>,
  version?: number,
): Record<string, string> {
  return version === undefined ? { ...params } : { ...params, version: String(version) };
}

/**
 * What narrows the estate list, as parameters.
 *
 * A module function for the same reason `withVersion` is one: the two readers of
 * this endpoint — the imperative one and the reactive descriptor — narrow it
 * identically, and a private method would be a method the guard spec has to pin.
 */
function searchSeriesParams(status?: SeriesStatus, domain?: string): Record<string, string> {
  const params: Record<string, string> = {};
  if (status) {
    params['status'] = status;
  }
  if (domain) {
    params['domain'] = domain;
  }
  return params;
}

/** The two curation writes take the same body, revision included. */
function curationBody(
  messageUids: readonly string[],
  reason: string,
  version?: number,
): Record<string, unknown> {
  const body: Record<string, unknown> = { messageUids: [...messageUids], reason };
  if (version !== undefined) {
    body['version'] = version;
  }
  return body;
}

/**
 * The publications admin API — series, and the issues under them.
 *
 * Endpoint contract, where it is not obvious:
 *
 * - `/publication-series/search-details` is the ADMIN list and carries `status`
 *   and `importSource`. `/series/{id}` is the citizen-facing shape and carries
 *   neither, so a page that needs to know whether a series is DRAFT must not
 *   read it from there.
 * - the issue list is `/publication-issues/series/{seriesId}`, newest first. It
 *   returns an ENVELOPE, not a bare array: the rows include synthesized MISSING
 *   and UPCOMING periods, and `gapDetection` says whether they were looked for
 *   at all.
 * - every date on the MODELS below is epoch milliseconds, and the endpoints
 *   serialise theirs as **ISO 8601 with an offset**. Every response is run
 *   through {@link withEpochDates} here, so nothing past this file ever sees the
 *   string form — and a comparison against `Date.now()` compares two numbers
 *   rather than silently answering false. Requests need no conversion: the
 *   backend reads an epoch number into a `Date`. The three request DESCRIPTORS
 *   are the exception, because the resource that runs them is the caller's — see
 *   {@link PublicationSeriesApiService.searchSeriesRequest}.
 * - a status change is `PUT .../status` with the bare status as a JSON string
 *   body (`"RETIRED"`), not an object.
 * - every write against a series or an issue that already exists may name the
 *   revision it was composed against, and is refused with `STALE_VERSION` when
 *   somebody else has moved the row since. It travels in the BODY where there is
 *   one to put it in and as `?version=` where there is not — the status change,
 *   the withdrawals and the file calls all have their body spoken for. Creates
 *   and dry runs name no single row and carry none.
 */
@Injectable({ providedIn: 'root' })
export class PublicationSeriesApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Written once: two callers ask for the estate, one of them reactively. */
  private readonly searchDetailsUrl = `${this.apiBaseUrl}/publication-series/search-details`;

  /**
   * Every series, in the admin shape.
   *
   * `domain` is an OWNER filter, not a visibility one: the admin area lists a
   * publication in the one domain accountable for it, and a series shared with
   * this domain is a citation target rather than something to administer here.
   * Omitting it returns the estate, which is what a script asks for — not what
   * a desk's list asks for.
   *
   * There is deliberately no `lang`: the admin shape carries every configured
   * language because it is what the editor saves back, so a narrowed payload
   * would be uneditable — this list used to send one, which the server ignored
   * while the client re-fetched on every language change for an answer that
   * never differed.
   */
  searchSeries(status?: SeriesStatus, domain?: string): Observable<PublicationSeries[]> {
    return this.http
      .get<PublicationSeries[]>(this.searchDetailsUrl, {
        params: searchSeriesParams(status, domain),
      })
      .pipe(map(withEpochDates));
  }

  /**
   * The same estate read, as a request descriptor for a reactive consumer.
   *
   * The endpoint stays defined once. A picker that only exists under one setting
   * — the source of a compilation — asks for the estate when that setting is
   * chosen and not on every open of the drawer it sits in, which is what a
   * request keyed on a signal does and an eager subscription cannot.
   *
   * A descriptor carries no response handling, so the resource built from it
   * has to normalise the wire itself: `parse: withEpochDates`, the same boundary
   * every observable on this service applies for itself.
   */
  searchSeriesRequest(status?: SeriesStatus, domain?: string): HttpResourceRequest {
    return { url: this.searchDetailsUrl, params: searchSeriesParams(status, domain) };
  }

  getSeries(seriesId: string): Observable<PublicationSeries> {
    return this.http
      .get<PublicationSeries>(
        `${this.apiBaseUrl}/publication-series/editable-series/${encodeURIComponent(seriesId)}`,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * One series in the CITIZEN shape, as a request descriptor.
   *
   * The lean read, because the one thing asked of it is what the series is
   * called: the editor shape carries the whole configuration and is an admin
   * read, and a screen that names another desk's series has no business asking
   * for its criteria. What comes back carries the id and the localized names and
   * nothing operational — `status` and `importSource` are not on it — so nothing
   * may branch on this answer.
   */
  leanSeriesRequest(seriesId: string): HttpResourceRequest {
    return {
      url: `${this.apiBaseUrl}/publication-series/series/${encodeURIComponent(seriesId)}`,
    };
  }

  /**
   * The issues of one series, newest first, with the periods that have none.
   *
   * The envelope is the answer, not the array. A caller that renders `data` and
   * ignores `gapDetection` reports an archive nothing examined as an archive
   * with nothing wrong with it.
   *
   * `maxSize` is a CEILING, not a page. The archive is paged on the client and
   * the gap envelope is computed over the whole set, so asking for a screenful
   * would report gaps against twenty rows out of five hundred. What the bound
   * stops is an unbounded response: the endpoint returns every issue a series
   * has ever had, and the busiest one has ~500 with two localized rows each.
   * The wire name is `maxSize`, matching every other paged endpoint in this API.
   */
  issuesOfSeries(
    seriesId: string,
    maxSize: number = ISSUE_LIST_MAX_SIZE,
  ): Observable<IssueListResult> {
    return this.http
      .get<IssueListResult>(
        `${this.apiBaseUrl}/publication-issues/series/${encodeURIComponent(seriesId)}`,
        { params: { maxSize: String(maxSize) } },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * The landing screen's per-series strips, in ONE request.
   *
   * One call for the whole page rather than one per series: built from the issue
   * list this is roughly sixty requests on the production estate, and it still
   * could not draw a missing period — gap synthesis needs exactly one named
   * series, and the issue list only offers it when one is named.
   *
   * The series are named explicitly and never inferred server-side, so the
   * caller is responsible for keeping the list inside `TIMELINE_MAX_SERIES`.
   */
  recentTimelines(
    seriesIds: readonly string[],
    lang: string,
    periods: number = TIMELINE_DEFAULT_PERIODS,
  ): Observable<IssueTimeline[]> {
    return this.http
      .get<IssueTimeline[]>(`${this.apiBaseUrl}/publication-issues/recent`, {
        params: {
          publicationSeriesId: seriesIds.join(','),
          periods: String(periods),
          lang,
        },
      })
      .pipe(map(withEpochDates));
  }

  /**
   * The desk's strips, without waiting to be told which series it owns.
   *
   * The series are chosen server-side by the same rule the dashboard groups by
   * (a schedule or not, active or a native draft, the same cap), so this request
   * can go out beside the series list rather than after it.
   *
   * BOTH kinds come back from the one call, each answered the way it can be:
   * a series with a calendar gets `periods` cells, and one without gets its
   * newest `unscheduledIssues` rows — because a cadence-less series has no
   * periods to count, so asking it for the strip's eight would report eight
   * editions for a series that has eleven. Splitting the two questions into two
   * requests made the dashboard pay a second round trip for an answer the
   * server assembles from the same scan.
   */
  recentTimelinesForDomain(
    domain: string,
    lang: string,
    options: { periods?: number; unscheduledIssues?: number } = {},
  ): Observable<IssueTimeline[]> {
    return this.http
      .get<IssueTimeline[]>(`${this.apiBaseUrl}/publication-issues/recent-by-domain`, {
        params: {
          domain,
          periods: String(options.periods ?? TIMELINE_DEFAULT_PERIODS),
          unscheduledIssues: String(options.unscheduledIssues ?? UNSCHEDULED_ARCHIVE_LIMIT),
          lang,
        },
      })
      .pipe(map(withEpochDates));
  }

  /**
   * One issue in the lean shape, as a request descriptor.
   *
   * The one thing asked of it is what the issue is called, so it reads the lean
   * endpoint rather than the editor one: the editor shape carries the whole
   * configuration and a screen that only names an issue has no business asking
   * for its criteria. What comes back carries the id and the localized names;
   * nothing operational is on it, so nothing may branch on this answer.
   */
  leanIssueRequest(publicId: string): HttpResourceRequest {
    return {
      url: `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}`,
    };
  }

  getIssue(publicId: string): Observable<PublicationIssue> {
    return this.http
      .get<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/editable-issue/${encodeURIComponent(publicId)}`,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Everything the issue screen shows, from ONE resolution.
   *
   * The issue, its member rows, its history, its curation decisions, the
   * release checklist (open issues, admins only) and the left-out count with a
   * sample of the rows — computed server-side from a single resolve, so the
   * rail, the list and the omissions panel can never answer for different
   * instants, and the screen costs one round trip instead of six.
   *
   * `at` asks the same question about a PAST instant, which only an open issue
   * has more than one answer to — a frozen one is the record of what went out,
   * and the parameter is ignored there. Everything resolved travels with it:
   * members, the rail, the omissions. Omitted means now, and the server refuses
   * an instant outside (period start, now].
   *
   * `from` asks it about a different period START — the one somebody is typing
   * into the edit panel and has not saved. It is a what-if and saves nothing: the
   * resolution runs over (from, at] instead of over the stored period, so the
   * members, the count, the omissions and the rail's membership rows all answer
   * for the period being proposed. The rail's rows about the STORED interval
   * (whether it is present, whether it joins the previous issue's, whether the
   * cut-off falls after the predecessor and before the successor, whether it is
   * in the future) keep answering for the stored one — they are facts about the
   * issue as saved, and a proposal cannot change them. Honoured on an OPEN issue
   * only, ignored on a frozen one, and refused with the same coded 400 as an
   * out-of-bounds `at` when it does not fall at or before the instant asked for.
   * The answer echoes back the start it actually used.
   */
  issueWorkbench(
    publicId: string,
    lang?: string,
    at?: number,
    from?: number,
  ): Observable<IssueWorkbench> {
    const params: Record<string, string> = {};
    if (lang) {
      params['lang'] = lang;
    }
    if (at !== undefined) {
      params['at'] = String(at);
    }
    if (from !== undefined) {
      params['from'] = String(from);
    }
    return this.http
      .get<IssueWorkbench>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/workbench`,
        { params },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Move a series between DRAFT, ACTIVE and RETIRED.
   *
   * `reason` is required on the way out of ACTIVE and travels as a query
   * parameter, as it does on the issue-level retire — the body is already spoken
   * for by the status itself. `version` travels beside it for the same reason.
   */
  setSeriesStatus(
    seriesId: string,
    status: SeriesStatus,
    reason?: string,
    version?: number,
  ): Observable<PublicationSeries> {
    // A bare JSON string, not { status }. The backend reads the body as a String
    // and strips the quotes itself.
    return this.http
      .put<PublicationSeries>(
        `${this.apiBaseUrl}/publication-series/series/${encodeURIComponent(seriesId)}/status`,
        JSON.stringify(status),
        {
          headers: { 'Content-Type': 'application/json' },
          params: withVersion(reason ? { reason } : {}, version),
        },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Create a series.
   *
   * The trailing slash is the backend path, not a typo.
   */
  createSeries(series: PublicationSeries): Observable<PublicationSeries> {
    return this.http
      .post<PublicationSeries>(`${this.apiBaseUrl}/publication-series/series/`, series)
      .pipe(map(withEpochDates));
  }

  /**
   * Which rules a series would fail, without saving it.
   *
   * The BODY is what gets judged, so this answers "is what I am looking at
   * valid" rather than "was the last save valid". An empty array means it would
   * be accepted.
   */
  validateSeries(series: PublicationSeries): Observable<SeriesFieldError[]> {
    return this.http
      .post<SeriesFieldError[]>(`${this.apiBaseUrl}/publication-series/validate`, series)
      .pipe(
        map(withEpochDates),
        map((errors) => [...errors]),
      );
  }

  /** Every publication category, for the settings picker. */
  publicationCategories(lang?: string): Observable<PublicationCategory[]> {
    const query = lang ? `?lang=${encodeURIComponent(lang)}` : '';
    return this.http
      .get<PublicationCategory[]>(`${this.apiBaseUrl}/publication-categories/all${query}`)
      .pipe(
        map(withEpochDates),
        map((categories) => [...categories]),
      );
  }

  /**
   * Create a category.
   *
   * The trailing slash is the backend path, as it is for series.
   */
  createCategory(category: PublicationCategory): Observable<PublicationCategory> {
    return this.http
      .post<PublicationCategory>(
        `${this.apiBaseUrl}/publication-categories/publication-category/`,
        category,
      )
      .pipe(map(withEpochDates));
  }

  /** Save a category. `categoryId` is the path key and is not changed by this. */
  updateCategory(
    categoryId: string,
    category: PublicationCategory,
  ): Observable<PublicationCategory> {
    return this.http
      .put<PublicationCategory>(
        `${this.apiBaseUrl}/publication-categories/publication-category/${encodeURIComponent(categoryId)}`,
        category,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Delete a category.
   *
   * Refused with CATEGORY_IN_USE while any series still belongs to it, which is
   * the answer the screen shows rather than a generic failure.
   */
  deleteCategory(categoryId: string): Observable<void> {
    return this.http
      .delete<void>(
        `${this.apiBaseUrl}/publication-categories/publication-category/${encodeURIComponent(categoryId)}`,
      )
      .pipe(map(withEpochDates));
  }

  /** The token vocabulary the name and file patterns accept. */
  nameTokens(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiBaseUrl}/publication-series/name-tokens`).pipe(
      map(withEpochDates),
      map((tokens) => [...tokens]),
    );
  }

  /**
   * Create an issue on a series.
   *
   * `intervalFrom` chains the new issue from a known period start — which is
   * what a gap row supplies, and what makes creating one retroactively
   * different from creating the next one. `intervalTo` closes it where the admin
   * reviewed a different end than the one the cadence derives; omitted, the
   * server derives its own. `recovered` marks the bounds as RECOVERED rather
   * than STAMPED, because nobody stamped them: the period is being reconstructed
   * after the fact and the marker is how a later reader can tell.
   *
   * Both bounds travel in the ONE request. Applying a reviewed end afterwards
   * meant the issue existed for a moment with a period nobody approved, and a
   * second call that failed left it there.
   *
   * `names` travels with them for the same reason: a name reviewed at create is
   * part of the same decision, and applying it afterwards leaves the issue
   * standing under a name nobody chose for as long as the second call takes —
   * or for good, when it fails.
   */
  createIssue(request: CreateIssueRequest): Observable<PublicationIssue> {
    const body: Record<string, unknown> = {
      seriesId: request.seriesId,
      intervalFrom: request.intervalFrom ?? null,
      recovered: request.recovered ?? false,
    };
    // Omitted rather than null: an absent end asks the server to derive one,
    // where a null would be an end the caller chose and left empty.
    if (request.intervalTo !== undefined) {
      body['intervalTo'] = request.intervalTo;
    }
    // Same rule for the names: absent leaves the server's own suggestion, and a
    // caller who did not rename anything says so by not carrying the field.
    if (request.names !== undefined) {
      body['names'] = { ...request.names };
    }
    return this.http
      .post<PublicationIssue>(`${this.apiBaseUrl}/publication-issues/issue`, body)
      .pipe(map(withEpochDates));
  }

  /**
   * The whole catalogue as the JSON the importer reads back.
   *
   * Fetched with the caller's own credentials rather than opened in a new tab
   * behind a one-time ticket, which is how the older admin exports work. The
   * difference matters: a ticket does not produce a security identity, so a
   * ticketed endpoint has to be `@PermitAll` with the role checked in code — and
   * this response carries the criteria documents, so a `@PermitAll` endpoint
   * returning it is precisely the shape the API's tier split forbids. Asking for
   * it over HttpClient keeps `@RolesAllowed` on the server, where it can be seen.
   */
  exportSeries(): Observable<readonly PublicationSeries[]> {
    return this.http
      .get<PublicationSeries[]>(`${this.apiBaseUrl}/publication-series/export`)
      .pipe(map(withEpochDates));
  }

  /** Where the JSON-import dialog posts. Runs as a background batch job. */
  uploadUrl(): string {
    return `${this.apiBaseUrl}/publication-series/upload-series`;
  }

  /**
   * Edit an open issue: everything this edition says differently from its series.
   *
   * Its names, its printed numbering, its file names, its interval, and the
   * report it renders with together with that report's parameters.
   *
   * Every field is optional and an absent one means "leave it alone", so a caller
   * renaming an issue does not send the interval back. A form that round-trips a
   * field in order to change a different one will eventually round-trip a stale
   * value. Where a field can also be handed BACK to the series, the EMPTY STRING
   * is how that is said — absent already means something else.
   *
   * Deliberately not `null`: a field the request omits and a field it sends as
   * null arrive at the server as the same value, so a null clear would make
   * every partial save — the criteria panel sending one flag, the edit panel
   * sending one date — silently wipe every field it did not mention.
   *
   * The document's CONTENT is not here on purpose: a file and a link have their
   * own calls, which archive, guard file-name collisions and audit. What is here
   * is the file's NAME, which is a decision about the edition rather than about
   * the bytes.
   */
  updateIssue(
    publicId: string,
    edit: {
      names?: Readonly<Record<string, string>>;
      /**
       * The edition, as free text of 1 to 64 characters.
       *
       * Sent only when it CHANGED: absent means "leave it alone" as everywhere
       * else, and an empty string is refused rather than read as a clear — an
       * issue always has an edition, and the first one is `1`.
       */
      edition?: string;
      /**
       * What the numbering prints, as free text — never a number.
       *
       * `''` clears the label, so the derived value prints again; absent leaves
       * it alone, like every other field here. No format check beyond a length
       * and a no-control-characters rule, which is the whole point: a double
       * issue is written `36+37`, and a box that refuses that is a box that
       * refuses the case it exists for.
       */
      weekLabel?: string;
      yearLabel?: string;
      intervalFrom?: number;
      intervalTo?: number;
      /**
       * One file name per language, hand-set for THIS issue.
       *
       * `''` clears the language's name so the series' pattern names the file
       * again — the same shape the labels above use, and for the same reason:
       * absent means "leave it alone", so without the empty string there would
       * be no way to hand a file name back to the series. Legal only while the
       * issue is OPEN; a published document's address is what citations resolve
       * to.
       */
      fileNames?: Readonly<Record<string, string>>;
      /** The report for this issue alone; `''` hands it back to the series. */
      reportId?: string;
      reportParams?: Readonly<Record<string, unknown>>;
      /** Tailor what this issue selects. */
      criteriaOverride?: IssueCriteria;
      /**
       * Hand it back to the series.
       *
       * A separate flag because null is a meaningful value here and absent is a
       * different one: absent means "leave it alone", as for every other field,
       * and this is the only way to say "go back to inheriting".
       */
      clearCriteriaOverride?: boolean;
      /** The issue's revision, as the screen last read it. */
      version?: number;
    },
  ): Observable<PublicationIssue> {
    return this.http
      .put<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}`,
        edit,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Upload one language's document.
   *
   * Multipart, one file, and the server names it: the filename travels in the
   * part header and is sanitised there. Legal on a published issue as well as an
   * open one — that is the post-publish correction path, and the server archives
   * what it replaces before writing.
   */
  uploadIssueFile(
    publicId: string,
    lang: string,
    file: File,
    version?: number,
  ): Observable<PublicationIssue> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http
      .post<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/file/${encodeURIComponent(lang)}`,
        body,
        // The body is the multipart envelope, so the revision travels beside it.
        { params: withVersion({}, version) },
      )
      .pipe(map(withEpochDates));
  }

  /** Clear one language's uploaded file, returning it to generated content. */
  clearIssueFile(publicId: string, lang: string, version?: number): Observable<PublicationIssue> {
    return this.http
      .delete<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/file/${encodeURIComponent(lang)}`,
        { params: withVersion({}, version) },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Set or clear one language's external link.
   *
   * A blank link clears it. An external publication's link IS its document, so
   * this is the same operation as an upload for a hosted one.
   */
  setIssueLink(
    publicId: string,
    lang: string,
    link: string,
    version?: number,
  ): Observable<PublicationIssue> {
    return this.http
      .put<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/link/${encodeURIComponent(lang)}`,
        version === undefined ? { link } : { link, version },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Save a series.
   *
   * The status is NOT part of a save: the backend owns the transition and
   * validates it, so activation goes through setSeriesStatus. A save that
   * carried a status would route around the rules that decide whether a series
   * may be active at all.
   *
   * The revision travels IN the body, as the series' own `version` — the field
   * the read filled in. There is no parameter for it here because the whole
   * object is being sent anyway, and a second place to carry it is a second
   * place for it to be forgotten.
   */
  updateSeries(seriesId: string, series: PublicationSeries): Observable<PublicationSeries> {
    return this.http
      .put<PublicationSeries>(
        `${this.apiBaseUrl}/publication-series/series/${encodeURIComponent(seriesId)}`,
        series,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * An unsaved series with the server's own defaults.
   *
   * Fetched rather than constructed here, so the form cannot drift from what the
   * server would accept — a client-side default that disagrees produces a series
   * that looks valid and is refused on save.
   */
  newSeriesTemplate(): Observable<PublicationSeries> {
    return this.http
      .get<PublicationSeries>(`${this.apiBaseUrl}/publication-series/new-series-template`)
      .pipe(map(withEpochDates));
  }

  /**
   * What a criteria document would select, without saving it.
   *
   * The only way to judge a document short of publishing an issue from it.
   * Persists nothing, so it is safe to call while somebody is still editing.
   *
   * `lang` picks the language the sampled rows and the omissions are titled in;
   * it never changes what the document selects. Omitting it leaves the server to
   * title each row from whatever description it finds first, which is a list
   * that reads in a language nobody chose.
   */
  resolvePreview(
    request: {
      criteria: IssueCriteria;
      cutoff: number;
      intervalFrom?: number;
      timeRelation?: TimeRelation;
      aliveAtCutoff?: boolean;
    },
    lang?: string,
  ): Observable<ResolvePreview> {
    return this.http
      .post<ResolvePreview>(`${this.apiBaseUrl}/publication-series/resolve-preview`, request, {
        params: lang ? { lang } : {},
      })
      .pipe(map(withEpochDates));
  }

  // ------------------------------------------------------- the issue workbench

  /**
   * The member list.
   *
   * LIVE while the issue is OPEN, FROZEN once published — and the caller does
   * not get to choose. There is deliberately no parameter for it: asking a
   * published issue what it *would* contain today returns an authoritative-looking
   * answer about a document that does not exist.
   */
  /**
   * The member list. `lang` only picks the language each row's live title is
   * read in; the server falls back to the first titled description when a
   * message is not written in it, and the membership itself never depends on it.
   */
  issueMembers(publicId: string, lang?: string): Observable<IssueMember[]> {
    return this.http
      .get<
        IssueMember[]
      >(`${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/members`, { params: lang ? { lang } : {} })
      .pipe(map(withEpochDates));
  }

  /** The history panel. */
  issueAudit(publicId: string): Observable<IssueAuditEntry[]> {
    return this.http
      .get<
        IssueAuditEntry[]
      >(`${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/audit`)
      .pipe(map(withEpochDates));
  }

  /**
   * What has happened to the SERIES itself, newest first.
   *
   * The same entry shape the issue trail carries, and deliberately a separate
   * read: these are the entries recorded against the series rather than against
   * any one issue — an activation, a retirement, a transfer, and the line
   * left behind when an issue was deleted. An issue's own trail goes with the
   * issue, so without this the only record of a deletion would be gone too.
   */
  seriesHistory(seriesId: string, lang?: string): Observable<IssueAuditEntry[]> {
    return this.http
      .get<
        IssueAuditEntry[]
      >(`${this.apiBaseUrl}/publication-series/${encodeURIComponent(seriesId)}/history`, { params: lang ? { lang } : {} })
      .pipe(map(withEpochDates));
  }

  /**
   * The curation decisions that STAND on this issue, include and exclude alike.
   *
   * The state, not the trail. The history records every decision ever taken and
   * every withdrawal of one, so a screen reading it as the current picture shows
   * a withdrawn exclusion as though it were still in force — which is a message
   * an admin then tries to reinstate and the server refuses.
   *
   * ADMIN TIER, matching the writes it describes: it carries the author and
   * the reason, which is the admin-only half of a why-line.
   *
   * `lang` picks the language each decision's message is titled in, exactly as
   * it does on the member list; the decisions themselves never depend on it.
   */
  issueOverrides(publicId: string, lang?: string): Observable<IssueOverride[]> {
    return this.http
      .get<
        IssueOverride[]
      >(`${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/overrides`, { params: lang ? { lang } : {} })
      .pipe(map(withEpochDates));
  }

  /**
   * The release rail.
   *
   * `allowFuture` lets an admin see what publishing ahead of the cut-off would
   * say, without doing it.
   *
   * `cutoff` asks the same question about a DIFFERENT instant. The rail resolves
   * membership as of the cut-off, so a release closed at a chosen past moment is
   * judged against a member list the default rail never looked at — and an admin
   * choosing that moment was, until this parameter existed, shown warnings about
   * the one they had just declined. Omitted means now, as it does on the release
   * itself.
   */
  publishChecklist(
    publicId: string,
    allowFuture = false,
    cutoff?: number,
  ): Observable<PublishChecklist> {
    const params: Record<string, string | boolean> = { allowFuture };
    if (cutoff !== undefined) {
      params['cutoff'] = String(cutoff);
    }
    return this.http
      .get<PublishChecklist>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/publish-checklist`,
        { params },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Release the issue.
   *
   * `acknowledgedWarnings` carries the codes the admin has explicitly accepted.
   * A BLOCKER can never be acknowledged, so sending one changes nothing — the
   * server refuses either way.
   *
   * `cutoff` is omitted from the body when the caller did not choose one, rather
   * than sent as null: absent means "now", and a null would be a chosen instant
   * with no value.
   */
  publishIssue(publicId: string, request: PublishRequest): Observable<PublishResult> {
    const body: Record<string, unknown> = {
      acknowledgedWarnings: [...request.acknowledgedWarnings],
    };
    if (request.cutoff !== undefined) {
      body['cutoff'] = request.cutoff;
    }
    // Omitted rather than null where the screen has none: an absent revision is
    // the caller declining the check, and a null is a value the server refuses.
    if (request.version !== undefined) {
      body['version'] = request.version;
    }
    return this.http
      .put<PublishResult>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/publish`,
        body,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Render the OPEN issue's live member list, without releasing anything.
   *
   * Returns one row per language, each with the address the document is
   * fetchable at. Nothing is frozen and no audit entry is written — but the
   * issue's own stamp moves, so a caller holding the issue has to re-read it
   * rather than keep the copy it rendered from.
   *
   * `at` is the cut-off the document is rendered at, epoch ms: the instant the
   * screen shows the issue at, which is also the cut-off the publish dialog
   * offers first. Without it the body is `null`, and the server picks the
   * series' default instant, as it always has for older clients and scripts.
   */
  previewIssue(publicId: string, at?: number): Observable<IssuePreview[]> {
    const url = `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/preview`;
    const body = at === undefined ? null : { at };
    return this.http.post<IssuePreview[]>(url, body).pipe(
      map(withEpochDates),
      map((rows) => [...(rows ?? [])]),
    );
  }

  /**
   * One language's rendered preview, as bytes.
   *
   * Fetched over `HttpClient` rather than followed as a link, for two reasons
   * that both bite. The endpoint is `@RolesAllowed("admin")` with no ticket path,
   * so it answers only to a request carrying the bearer — which a plain anchor
   * does not. And the address the render response reports is root-relative
   * (`/rest/...`) while the API lives on its own origin, so following it verbatim
   * asks the web host for a path it does not serve.
   *
   * Built from the base URL and the issue rather than from the reported `href`,
   * so the one address this client fetches is the one it composed.
   */
  previewFile(publicId: string, lang: string): Observable<Blob> {
    return this.http.get(
      `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/preview/${encodeURIComponent(lang)}`,
      { responseType: 'blob' },
    );
  }

  /**
   * Where one superseded document is streamed from.
   *
   * A URL rather than a request, unlike the preview beside it, and the
   * difference is in what the endpoint answers to. This one checks the admin
   * role in code instead of by annotation precisely so that a one-time ticket
   * satisfies it — a ticket produces no security identity, so a declarative gate
   * would refuse the navigation before ever looking at it. The caller appends a
   * ticket and opens the address; fetching it over `HttpClient` would work too
   * and would throw away the only reason the endpoint is shaped this way.
   *
   * ADDRESSED BY THE AUDIT ENTRY, not by a path or a date. Every generation of a
   * language's file is kept, so "the archived Danish document of this issue"
   * names as many files as the issue has been amended; the entry is the only
   * thing that tells them apart, and the server resolves the location from it.
   */
  archiveFileUrl(publicId: string, auditEntryId: number, lang: string): string {
    return (
      `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}` +
      `/archive/${encodeURIComponent(auditEntryId)}/${encodeURIComponent(lang)}`
    );
  }

  /**
   * Withdraw a published issue. Retiring is not deleting: the file stays at its link.
   *
   * The reason travels as a query parameter and is mandatory — a withdrawal that
   * records no reason leaves the next reader of the history with the fact and
   * none of the cause.
   */
  retireIssue(publicId: string, reason: string, version?: number): Observable<PublicationIssue> {
    return this.http
      .put<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/retire`,
        null,
        { params: withVersion(reason ? { reason } : {}, version) },
      )
      .pipe(map(withEpochDates));
  }

  reactivateIssue(
    publicId: string,
    reason?: string,
    version?: number,
  ): Observable<PublicationIssue> {
    return this.http
      .put<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/reactivate`,
        null,
        { params: withVersion(reason ? { reason } : {}, version) },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Remove an issue outright, with the trail it carried.
   *
   * NOT the withdrawal above, and the difference is what survives. Retiring
   * takes an issue off the public list and leaves the document where citations
   * already point at it; this removes the issue, its members, its standing
   * curation decisions and its own history — so it is refused on anything that
   * has been published, and refused on a withdrawn issue while any message still
   * cites it. The series keeps one line saying what was removed and by whom,
   * which is the only record left afterwards.
   *
   * The revision and the optional reason travel as query parameters, the same
   * shape the withdrawal uses: a DELETE has no body to put them in.
   *
   * `lang` is for the REFUSAL, not for the removal: a removal blocked by
   * citations names the messages that cite the issue, and those names are the
   * whole of what the reader has to act on. Without it they come back titled in
   * whatever language the server found first.
   */
  deleteIssue(
    publicId: string,
    version?: number,
    reason?: string,
    lang?: string,
  ): Observable<void> {
    return this.http
      .delete<void>(`${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}`, {
        params: withVersion({ ...(reason ? { reason } : {}), ...(lang ? { lang } : {}) }, version),
      })
      .pipe(map(withEpochDates));
  }

  /**
   * Force one or more messages into an issue, as ONE decision.
   *
   * `messageUids` rather than a uid per request, because a curator adding eleven
   * messages is taking one decision about a list: the server applies all of them
   * or none, and names every unresolvable uid in a single refusal rather than
   * making the curator discover them one round-trip at a time.
   *
   * Curation is an administrator's, in the domain that owns the series, not an
   * editor's; a desk that does not own the series is refused with NOT_IN_DOMAIN.
   *
   * `version` is THE ISSUE's, not the override's: a curation is a decision about
   * the member set, the member set belongs to the issue, and a curation forces
   * the issue's revision on — so two curators who both loaded the same open
   * issue collide here rather than overwriting each other in a member list
   * neither of them will re-read.
   */
  includeMembers(
    publicId: string,
    messageUids: readonly string[],
    reason: string,
    version?: number,
  ): Observable<void> {
    return this.http
      .put<void>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/overrides/include`,
        curationBody(messageUids, reason, version),
      )
      .pipe(map(withEpochDates));
  }

  excludeMembers(
    publicId: string,
    messageUids: readonly string[],
    reason: string,
    version?: number,
  ): Observable<void> {
    return this.http
      .put<void>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/overrides/exclude`,
        curationBody(messageUids, reason, version),
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Withdraw a curation decision, by the message it was about.
   *
   * Addressed by message rather than by override id because that is what the
   * caller is looking at: a row with a why-line saying somebody put it there.
   * The reason travels as a query parameter, the same shape as retire — this is
   * a decision about one named thing, and a body carrying only a reason reads as
   * a form when it is an action.
   */
  clearOverride(
    publicId: string,
    messageUid: string,
    reason: string,
    version?: number,
  ): Observable<void> {
    return this.http
      .delete<void>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/overrides/${encodeURIComponent(messageUid)}`,
        { params: withVersion({ reason }, version) },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Re-decide a published issue's content and replace its document in place.
   *
   * Everything a citation depends on survives: the public id, the link, the
   * public window and the cut-off are untouched, and the outgoing files are
   * archived rather than overwritten. Refused on an imported issue, whose members
   * and document are a historical record.
   */
  amendIssue(publicId: string, request: AmendRequest): Observable<AmendResult> {
    return this.http
      .put<AmendResult>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/amend`,
        {
          reason: request.reason,
          acknowledgedWarnings: [...request.acknowledgedWarnings],
          ...(request.version === undefined ? {} : { version: request.version }),
        },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Open a successor edition of a published issue, linked to it.
   *
   * The link is made here, where it cannot be forgotten. The predecessor's public
   * window is NOT closed yet — it stays current until the new edition publishes,
   * so the site never shows two current editions nor none.
   *
   * `version` is the PREDECESSOR's: it is the row this action reads and caps, and
   * the successor does not exist yet to have one.
   */
  newEdition(
    publicId: string,
    intervalFrom?: number,
    version?: number,
  ): Observable<PublicationIssue> {
    return this.http
      .post<PublicationIssue>(
        `${this.apiBaseUrl}/publication-issues/issue/${encodeURIComponent(publicId)}/new-edition`,
        {
          ...(intervalFrom === undefined ? {} : { intervalFrom }),
          ...(version === undefined ? {} : { version }),
        },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * The uid behind whatever the admin typed.
   *
   * An override is addressed by uid, but nobody knows a uid — what is written on
   * a message, and quoted in a mail asking for it to be added, is its short id.
   * The messages endpoint resolves either form, and answers 200 with an empty
   * body for an id that names nothing, which is why the result is nullable rather
   * than an error.
   */
  resolveMessage(idOrShortId: string): Observable<ResolvedMessage | null> {
    return this.http
      .get<ResolvedMessage | null>(
        `${this.apiBaseUrl}/messages/message/${encodeURIComponent(idOrShortId)}`,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * The issue that does not exist yet, for the period the caller names.
   *
   * ONE endpoint behind three questions, and deliberately so: "the next one after
   * this" (`afterPublicId`), "what would land in these bounds" (the pair), and
   * "the next one" (neither). Built as three, the interval one screen prefills
   * and the interval another displays are two derivations of one bound, and the
   * day they disagree an admin creates a period that overlaps the one before it.
   *
   * Writes nothing, which is what makes it safe to ask while a date is being
   * dragged around.
   */
  issueDraft(
    seriesId: string,
    request: {
      afterPublicId?: string;
      intervalFrom?: number;
      intervalTo?: number;
      lang?: string;
    } = {},
  ): Observable<IssueDraft> {
    const params: Record<string, string> = {};
    if (request.lang) {
      params['lang'] = request.lang;
    }
    if (request.afterPublicId) {
      params['afterPublicId'] = request.afterPublicId;
    }
    if (request.intervalFrom !== undefined) {
      params['intervalFrom'] = String(request.intervalFrom);
    }
    if (request.intervalTo !== undefined) {
      params['intervalTo'] = String(request.intervalTo);
    }
    return this.http
      .get<IssueDraft>(
        `${this.apiBaseUrl}/publication-series/series/${encodeURIComponent(seriesId)}/issue-draft`,
        { params },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * A series in the shape of a new one, copied from an existing one.
   *
   * Comes back unsaved and blank-id, in DRAFT, with the provenance and issue
   * counters cleared — patterns, criteria, report config and descs carried. The
   * server derives it so a copy cannot drift from
   * what a create would accept; deciding here which fields to drop would put a
   * second answer beside the one the save is validated against.
   */
  copySeriesTemplate(seriesId: string): Observable<PublicationSeries> {
    return this.http
      .get<PublicationSeries>(
        `${this.apiBaseUrl}/publication-series/copy-series-template/${encodeURIComponent(seriesId)}`,
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Move a publication to another desk.
   *
   * Allowed to somebody who is admin in BOTH the current owner's domain — which
   * is the one the request header names, so the caller has to be sitting there —
   * and the target. Refused with `NOT_IN_DOMAIN` otherwise, and the refusal names
   * which of the two is missing, which is why the screen shows the server's own
   * sentence beneath the mapped one.
   *
   * One-offs go through here too: a one-off is a series.
   *
   * The revision travels in the body beside the reason. A transfer changes who
   * may write the publication at all, so being refused because somebody else
   * moved it in the meantime is worth a refusal rather than a change made against
   * a series nobody has looked at since.
   */
  transferOwner(seriesId: string, request: TransferOwnerRequest): Observable<PublicationSeries> {
    return this.http
      .put<PublicationSeries>(
        `${this.apiBaseUrl}/publication-series/series/${encodeURIComponent(seriesId)}/owner`,
        {
          domainId: request.domainId,
          reason: request.reason,
          ...(request.version === undefined ? {} : { version: request.version }),
        },
      )
      .pipe(map(withEpochDates));
  }

  /**
   * Every publication category, as the bytes the importer reads back.
   *
   * Taken as a Blob and saved verbatim rather than re-serialized from a parsed
   * object: the export is an import file, and a round trip through JS loses any
   * field this client does not model — which is exactly the field somebody will
   * one day need moved between installations.
   */
  exportCategories(): Observable<Blob> {
    return this.http.get(`${this.apiBaseUrl}/publication-categories/all`, {
      responseType: 'blob',
    });
  }

  /**
   * Seed the categories from a file produced by another installation.
   *
   * Multipart, and it runs as a background batch job like every other admin
   * import — so the response is the job's submission text, not a result. It is
   * not additive: every category the file names is rewritten field for field.
   */
  importCategories(file: File): Observable<string> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post(
      `${this.apiBaseUrl}/publication-categories/upload-publication-categories`,
      body,
      { responseType: 'text' },
    );
  }
}
