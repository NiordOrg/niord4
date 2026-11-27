import type { PaperSize } from '../../../../../shared/models/print/print-settings.model';
import { formatWallClockTime, parseWallClockTime } from '../../../../../shared/utils/zoned-date';
import type {
  Availability,
  ContentMode,
  CutoffDay,
  CutoffDefault,
  IssueCriteria,
  MessagePublication,
  NextIssueCreation,
  NumberingScheme,
  PageOrientation,
  PageSize,
  PublicationSeries,
  PublicationSeriesDesc,
  ReleaseMode,
  SeriesCadence,
  SortDirection,
  TimeRelation,
} from '../../../services/api/publication-series-api.service';

/**
 * The settings form's shape, and the rules that decide which of it applies.
 *
 * Pure on purpose — no injector, no HTTP — so the shaping can be tested without a
 * TestBed. What lives here is the difference between what an admin TYPED and what
 * the server is allowed to be told.
 *
 * Those are not the same thing, and the gap is deliberate. Most of the S-rules are
 * "exactly when" rules: a weekday belongs to a weekly cadence and to nothing else,
 * criteria belong to a query-backed series and to nothing else. Switching a series
 * from weekly to yearly must therefore stop sending the weekday — but it must NOT
 * erase the weekday the admin typed, because switching back has to bring it home.
 * So the form model keeps everything and {@link buildSeriesPayload} strips on the
 * way out.
 *
 * These predicates shape the payload; they do NOT judge it. The authority on
 * whether a series is valid is the server's own `/validate`, which is the same code
 * that runs on save. Re-implementing the validator here would produce a second
 * source of truth whose drift shows up as a form that says yes and a save that says
 * no.
 */

/** The flat scalar fields, all present regardless of which currently apply. */
export interface SeriesFormModel {
  seriesId: string;
  categoryId: string;
  /**
   * The OWNER, and read-only on the screen.
   *
   * Seeded from the session domain on create and from the stored value on edit.
   * It is in the model rather than read live from the domain facade because the
   * payload is built from the model and the zone the cut-off fields are printed
   * in is the owner's — so a form editing another desk's series still prints its
   * schedule in that desk's zone.
   */
  domainId: string;
  /** Who else may see and cite it. The list below only applies to SELECTED_DOMAINS. */
  availability: Availability;
  sortOrder: number | undefined;
  messagePublication: MessagePublication;
  languageSpecific: boolean;

  contentMode: ContentMode;
  reportId: string;
  pageSize: PageSize;
  pageOrientation: PageOrientation;
  mapThumbnails: boolean;
  messageSortBy: string;
  messageSortOrder: SortDirection;

  cadence: SeriesCadence;
  nominalCutoffDay: CutoffDay;
  /**
   * `null`, never `undefined`, when the box is empty.
   *
   * A field-bound `<input type="number">` reads and writes `number | null`; an
   * `undefined` model value falls out of that contract and the control writes a
   * string back into a numeric field. The payload turns the empty case back into
   * `undefined` so the wire is unchanged.
   */
  nominalCutoffDayOfMonth: number | null;
  nominalCutoffMonth: number | null;
  nominalCutoffTime: string;
  cutoffDefault: CutoffDefault;
  numberingScheme: NumberingScheme;
  nextIssueCreation: NextIssueCreation;
  firstIssueStartsAt: number | undefined;

  timeRelation: TimeRelation;
  /** The compiled series' source, by id. `''` when there is none, as every id field here. */
  sourceSeriesId: string;
  aliveAtCutoff: boolean;

  releaseMode: ReleaseMode;
}

/** The per-language text, keyed by language. */
export type DescModels = Record<string, SeriesDescModel>;

export interface SeriesDescModel {
  name: string;
  nameSuggestionPattern: string;
  fileNamePattern: string;
  messageReferenceFormat: string;
  linkPattern: string;
}

/** One report parameter row. Ordered, because the report reads them in order. */
export interface ReportParamRow {
  key: string;
  value: string;
}

/**
 * Parameter names the issue supplies, which therefore cannot be typed.
 *
 * Seven names, and they carry three different things. `week` (and the week a
 * double issue runs to), `year` and `edition` are what the issue PRINTS: free
 * text, rendered exactly as somebody wrote it, so a week may read "36+37" and a
 * year "Two thousand". `yearNumber` and `weekNumber` are what the numbering
 * DERIVED from the cut-off — the numbers for the one thing a template computes
 * from a year, the volume line of an accumulated report, which a printed label
 * cannot be asked to do arithmetic on. They are absent where the issue has
 * neither. `weeks` is the issue's weeks as the series' name pattern writes them,
 * which the weekly reports print in their heading.
 *
 * All seven are injected into the report at render time. A row repeating one of
 * them would be a second source for the same value that can disagree with the
 * first — so the server refuses them, and the form refuses them first, next to
 * the input rather than as a failed save.
 */
export const RESERVED_REPORT_PARAM_KEYS: readonly string[] = [
  'week',
  'weekto',
  'weeks',
  'year',
  'edition',
  'yearnumber',
  'weeknumber',
];

/** Case- and whitespace-insensitive: `Week ` is the same parameter as `week`. */
export function isReservedReportParamKey(key: string): boolean {
  return RESERVED_REPORT_PARAM_KEYS.includes(key.trim().toLowerCase());
}

/** The reserved keys a set of rows carries, in the order they appear. */
export function reservedReportParamKeys(rows: readonly ReportParamRow[]): readonly string[] {
  return rows.map((row) => row.key.trim()).filter((key) => isReservedReportParamKey(key));
}

/** Defaults for a field the server has not filled in. Never sent as-is — see buildSeriesPayload. */
const FALLBACK = {
  pageSize: 'A4',
  pageOrientation: 'PORTRAIT',
  messageSortOrder: 'ASC',
  cutoffDay: 'WEDNESDAY',
  cutoffTime: '12:00',
} as const;

export const CADENCES: readonly SeriesCadence[] = ['NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];

export const CUTOFF_DAYS: readonly CutoffDay[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

/**
 * Where a series' cut-off falls by default, offered in the order they read.
 *
 * `RELEASE_MOMENT` first because it is the default and the only answer most
 * series can give: closing the content period when the release runs is what a
 * weekly notice does, and a calendar boundary only means something to a
 * publication whose content period IS a calendar year.
 */
export const CUTOFF_DEFAULTS: readonly CutoffDefault[] = [
  'RELEASE_MOMENT',
  'PERIOD_START',
  'PERIOD_END',
];

export const NUMBERING_SCHEMES: readonly NumberingScheme[] = [
  'NONE',
  'ISO_WEEK_YEAR',
  'YEAR_EDITION',
  'MONTH_YEAR',
  'EDITION_SEQUENCE',
];

export const CONTENT_MODES: readonly ContentMode[] = [
  'GENERATED_FROM_QUERY',
  'UPLOADED_FILE',
  'EXTERNAL_LINK',
  // Offered, because eight imported series are NONE and a picker that cannot show
  // a series its own value is a picker that changes it on the next save.
  'NONE',
];

export const PAGE_SIZES: readonly PageSize[] = [
  'A3',
  'A4',
  'A5',
  'B4',
  'B5',
  'LETTER',
  'LEGAL',
  'LEDGER',
];

/**
 * The paper each page size is, in the message print dialog's words: the same eight sizes, with
 * the three US papers spelled in lower case there.
 */
const PAPER_OF_PAGE_SIZE: Readonly<Record<PageSize, PaperSize>> = {
  A3: 'A3',
  A4: 'A4',
  A5: 'A5',
  B4: 'B4',
  B5: 'B5',
  LETTER: 'letter',
  LEGAL: 'legal',
  LEDGER: 'ledger',
};

/** The text key of a page size's name ("Letter" for `LETTER`). */
export function pageSizeTextKey(size: PageSize): string {
  return `messages.print.settings.paperSize.${PAPER_OF_PAGE_SIZE[size]}`;
}

export const PAGE_ORIENTATIONS: readonly PageOrientation[] = ['PORTRAIT', 'LANDSCAPE'];
export const MESSAGE_PUBLICATIONS: readonly MessagePublication[] = ['NONE', 'INTERNAL', 'EXTERNAL'];
export const RELEASE_MODES: readonly ReleaseMode[] = ['MANUAL_GATE', 'AUTO_RELEASE'];
export const NEXT_ISSUE_CREATIONS: readonly NextIssueCreation[] = ['AUTO_ON_PUBLISH', 'MANUAL'];
/**
 * The three membership regimes, in the order they are offered.
 *
 * The two that ask the messages themselves first, then the one that asks another
 * publication: a compilation carries the union of what the source series'
 * published issues contained, so it has a source series where the other two have
 * criteria.
 */
export const TIME_RELATIONS: readonly TimeRelation[] = [
  'PUBLISHED_IN_INTERVAL',
  'IN_FORCE_AT_CUTOFF',
  'COMPILED_FROM_SOURCE',
];

// --------------------------------------------------------------- applicability

/** Criteria, a time relation and a liveness flag exist only here (S-1, S-2). */
export function isQueryBacked(model: Pick<SeriesFormModel, 'contentMode'>): boolean {
  return model.contentMode === 'GENERATED_FROM_QUERY';
}

/**
 * Whether this series compiles another series' issues (S-24).
 *
 * The third regime, and the one that changes what the rest of the form means: a
 * compilation names a source series and carries neither criteria nor a liveness
 * flag, because its members are the frozen rows of issues that already went out
 * one by one.
 */
export function isCompilation(
  model: Pick<SeriesFormModel, 'contentMode' | 'timeRelation'>,
): boolean {
  return isQueryBacked(model) && model.timeRelation === 'COMPILED_FROM_SOURCE';
}

/**
 * Whether the issues of this series TILE their periods (S-4).
 *
 * Two of the three regimes do: an interval issue covers the stretch since the
 * previous one closed, and a compilation covers the stretch of the source series
 * it has not compiled yet. An in-force issue has no lower bound at all — it
 * selects what stands at the cut-off — so it has no first interval to start and
 * nothing to chain off.
 */
export function tilesPeriods(model: Pick<SeriesFormModel, 'timeRelation'>): boolean {
  return (
    model.timeRelation === 'PUBLISHED_IN_INTERVAL' || model.timeRelation === 'COMPILED_FROM_SOURCE'
  );
}

/**
 * Whether membership is decided by a criteria document (S-1, S-2).
 *
 * The criteria and the liveness flag are one shape's settings, not the
 * query-backed shape's: a compilation is query-backed — it generates a document
 * the same way, and every gate that asks "does this produce a PDF" must keep
 * saying yes — but it selects by asking another publication, so neither field
 * applies to it and both are refused on it.
 */
export function selectsByCriteria(
  model: Pick<SeriesFormModel, 'contentMode' | 'timeRelation'>,
): boolean {
  return isQueryBacked(model) && !isCompilation(model);
}

/** A weekly cadence carries a weekday, and nothing else may (S-5). */
export function usesWeekday(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence === 'WEEKLY';
}

/** Monthly and yearly carry a day of the month (S-6). */
export function usesDayOfMonth(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence === 'MONTHLY' || model.cadence === 'YEARLY';
}

/** Only yearly carries a month (S-6). */
export function usesMonth(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence === 'YEARLY';
}

/** Every cadence but NONE has a nominal time of day (S-7). */
export function usesCutoffTime(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence !== 'NONE';
}

/**
 * Only a yearly series may be cut off at a period boundary (S-21).
 *
 * The cut-off default describes a PERIOD, so a series with no period can only be
 * cut off at the release; and a calendar-driven default on a weekly series would
 * stamp every issue at a nominal noon nobody pressed — which is exactly the
 * nominal-versus-stamped confusion the cut-off model exists to keep apart.
 */
export function usesPeriodCutoff(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence === 'YEARLY';
}

/**
 * Whether the form currently holds a cut-off default the rule refuses.
 *
 * Reported rather than silently corrected: switching a yearly series to weekly
 * leaves a period boundary selected, and an admin who is told why can decide
 * whether the cadence or the cut-off was the mistake. What gets SENT is stripped
 * either way — see {@link buildSeriesPayload}.
 */
export function cutoffDefaultRefused(
  model: Pick<SeriesFormModel, 'cadence' | 'cutoffDefault'>,
): boolean {
  return model.cutoffDefault !== 'RELEASE_MOMENT' && !usesPeriodCutoff(model);
}

/**
 * A cadence-less series has no next issue to create and no sequence to number
 * within (S-8).
 *
 * NOT the same question as "is this a one-off". Eleven NCAGS editions and eight
 * ice-service notices have no cadence and are unmistakably series; what they
 * share with a one-off is only that nothing schedules their next issue. What
 * kind of publication it is lives in `kind`.
 *
 * Rendered as a locked pair rather than hidden, because "there is no next issue"
 * is a fact worth reading on the screen where somebody just chose NONE.
 */
export function isCadenceless(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return model.cadence === 'NONE';
}

/**
 * Whether the next issue follows from the cadence instead of from a choice.
 *
 * The mirror of {@link isCadenceless}, and the other half of the same rule. A
 * cadence IS the statement that the next issue opens by itself when the current
 * one publishes — so on a series that has one there is nothing to choose, and the
 * screen states the consequence instead of offering a control.
 *
 * Left as a setting it is a switch that turns the schedule off without saying so:
 * a weekly series whose successor nobody opens looks exactly like one that has
 * fallen behind, and the only place the difference is written down is a field
 * three tabs into the settings drawer.
 */
export function nextIssueIsDerived(model: Pick<SeriesFormModel, 'cadence'>): boolean {
  return !isCadenceless(model);
}

/** Page setup travels with a report or not at all (S-9). */
export function hasReport(model: Pick<SeriesFormModel, 'contentMode' | 'reportId'>): boolean {
  return isQueryBacked(model) && model.reportId.trim().length > 0;
}

/** Only a series whose issues tile their periods has a first interval to start (S-4). */
export function usesFirstIssueStart(
  model: Pick<SeriesFormModel, 'contentMode' | 'timeRelation'>,
): boolean {
  return isQueryBacked(model) && tilesPeriods(model);
}

/**
 * In-force membership IS the liveness test, so the flag is forced true (S-3).
 *
 * Reported rather than silently corrected: the checkbox locks on and says why.
 */
export function livenessIsForced(
  model: Pick<SeriesFormModel, 'contentMode' | 'timeRelation'>,
): boolean {
  return isQueryBacked(model) && model.timeRelation === 'IN_FORCE_AT_CUTOFF';
}

/** A citable series needs a reference format in every language (S-13). */
/**
 * Who a publication of this kind should be available to, before anybody chooses.
 *
 * A generated series is a desk's own product — the weekly notices belong to the
 * desk that publishes them — so it starts closed. Everything else is a document
 * that exists to be cited: a one-off, a link, an uploaded file, and the old
 * system made those visible everywhere by construction, so starting them closed
 * would silently remove them from every other desk's picker.
 *
 * A DEFAULT, never a rule. It seeds a new publication and is re-applied while
 * the content mode is still being chosen; an existing one keeps what it was
 * given.
 */
export function defaultAvailability(contentMode: ContentMode): Availability {
  return contentMode === 'GENERATED_FROM_QUERY' ? 'OWNER_ONLY' : 'ALL_DOMAINS';
}

/**
 * Whether "selected domains" has been chosen and nothing selected.
 *
 * A hard rule rather than a shrug, on both sides. The payload sends an empty
 * list, which the server reads as a publication shared with nobody — the same
 * thing OWNER_ONLY says, under a name that claims otherwise — so a save in this
 * state either fails or quietly stores a state no screen can express. Refused
 * here, next to the control, rather than as a refusal about a field whose empty
 * list is invisible from the message.
 *
 * The count is over the list AS HELD, which includes a share with a domain the
 * catalogue no longer carries: an inactive desk is still somebody this
 * publication is shared with, so a list holding only that one is not empty.
 */
export function availabilityNeedsDomain(
  model: Pick<SeriesFormModel, 'availability'>,
  availableDomainIds: readonly string[],
): boolean {
  return model.availability === 'SELECTED_DOMAINS' && availableDomainIds.length === 0;
}

export function isCitable(model: Pick<SeriesFormModel, 'messagePublication'>): boolean {
  return model.messagePublication !== 'NONE';
}

// ------------------------------------------------------------- who may write

/**
 * Whose publication this is, seen from the desk it is being looked at from.
 *
 * THE ONE OWNER ANSWER. Every screen that shows a series, a one-off or an issue
 * asks this and nothing else, so a publication cannot be refused on one surface
 * and offered on the next.
 *
 * - `unknown` — nothing has been read yet. Neither an answer nor a refusal: a
 *   screen that concluded anything here would be deciding from an empty hand,
 *   and a page that appears and is then taken away reads worse than one that
 *   waits.
 * - `owned` — this desk's to show and to write. A publication that names NO
 *   owner is owned by every desk: there is no desk to switch to in order to
 *   reach it, so refusing would make it unreachable from everywhere while the
 *   one save that would give it an owner lives behind the very screen being
 *   refused.
 * - `foreign` — another desk's. A caller sitting at no desk at all is foreign to
 *   an owned publication too; the alternative makes the rule optional for
 *   anybody who names no domain.
 */
export type OwnerVerdict = 'unknown' | 'owned' | 'foreign';

/**
 * The owner answer, from the publication's own owner and the desk in use.
 *
 * An issue has no owner of its own — it inherits its series', which is where the
 * accountability is recorded — so an issue is judged by passing its series here.
 */
export function ownerVerdict(
  // Typed with an OPTIONAL owner although the value object requires one: this
  // has to be able to answer about a row written before the owner was mandatory
  // as well as about one that is still on its way.
  series: { readonly domainId?: string } | undefined,
  currentDomainId: string | undefined,
): OwnerVerdict {
  if (!series) {
    return 'unknown';
  }
  const domainId = series.domainId?.trim();
  if (!domainId) {
    return 'owned';
  }
  return domainId === currentDomainId ? 'owned' : 'foreign';
}

/**
 * Whether an admin sitting in `currentDomainId` may WRITE this series.
 *
 * The same verdict, read for writes: everything but another desk's publication
 * may be offered. It is the server's rule — restated here so a screen can
 * decline to offer an action instead of offering a button that always fails.
 *
 * A series that NAMES a domain is that domain's to write. The domain is not
 * decoration: it carries the timezone every cut-off is read in and it says which
 * desk is accountable for what the series publishes. The strongest role does not
 * lift this, because the question is which desk the caller is sitting at rather
 * than how much they are trusted.
 *
 * A series that is not in hand yet answers `true`, matching the server's own
 * handling of one it was given nothing for. The alternative is every action on
 * the screen disappearing and coming back while the series loads, which reads as
 * a broken panel — and the refusal is named either way if the request goes out.
 * WHETHER THE PUBLICATION IS SHOWN AT ALL is the stricter half of the same
 * verdict and is decided by {@link ownerVerdict} itself.
 */
export function seriesWritable(
  series: { readonly domainId?: string } | undefined,
  currentDomainId: string | undefined,
): boolean {
  return ownerVerdict(series, currentDomainId) !== 'foreign';
}

/** Roughly one cadence period, in milliseconds. Preview windows only — never a stored bound. */
const PERIOD_MS: Readonly<Record<SeriesCadence, number | undefined>> = {
  NONE: undefined,
  DAILY: 24 * 60 * 60 * 1000,
  WEEKLY: 7 * 24 * 60 * 60 * 1000,
  MONTHLY: 31 * 24 * 60 * 60 * 1000,
  YEARLY: 366 * 24 * 60 * 60 * 1000,
};

/**
 * The window a cadence-less publication is previewed over.
 *
 * There is no period to step back by, and the alternative — no lower bound at
 * all — asks the resolver to hydrate every message ever published on a keystroke
 * debounce. A year is wide enough to be a useful answer and narrow enough to be
 * an answerable one.
 */
const UNBOUNDED_PREVIEW_WINDOW_MS = 366 * 24 * 60 * 60 * 1000;

/**
 * The lower bound to preview a criteria document over.
 *
 * An in-force series has none, exactly as its issues have none — passing one
 * would preview a narrower set than the series actually produces.
 *
 * Every OTHER shape gets one, and that is the point of the function. For a
 * cadenced series the window is ONE CADENCE PERIOD back; for a cadence-less one
 * it is the declared first-interval start where there is one and a year
 * otherwise. All three are approximations and deliberately so: nothing here is
 * stored, the real bound comes from the previous issue at publish time, and an
 * interval probe with no floor is a request for the whole corpus.
 */
export function previewIntervalFrom(
  model: Pick<SeriesFormModel, 'cadence' | 'contentMode' | 'timeRelation' | 'firstIssueStartsAt'>,
  cutoff: number,
): number | undefined {
  if (livenessIsForced(model)) {
    return undefined;
  }
  const period = PERIOD_MS[model.cadence];
  if (period !== undefined) {
    return cutoff - period;
  }
  return model.firstIssueStartsAt ?? cutoff - UNBOUNDED_PREVIEW_WINDOW_MS;
}

// ------------------------------------------------------- the nominal cut-off time

/**
 * The nominal cut-off time, as the shared time field reads it.
 *
 * Stored as a wall clock (`HH:mm` in the series' own zone) and edited through a
 * control whose value type is `Date | null`, so the two halves below carry it
 * across. The date part is a fixed sentinel and never leaves this file: only the
 * hours and minutes mean anything, and the field itself only ever reads those.
 *
 * A value the pattern does not recognise yields `null` rather than a guess — a
 * half-typed time is not a time, and inventing one would silently move a
 * series' cut-off.
 */
export function cutoffTimeToDate(value: string | undefined): Date | null {
  return parseWallClockTime(value);
}

/** The inverse of {@link cutoffTimeToDate}: the picked clock as `HH:mm`, or `''` when unset. */
export function dateToCutoffTime(value: Date | null): string {
  return formatWallClockTime(value) ?? '';
}

// -------------------------------------------------------- the proposed id

/**
 * Letters no ASCII identifier can carry, and what they are written as instead.
 *
 * Spelled out rather than stripped by the accent pass below: `ø` and `å` carry
 * no combining mark to remove, so decomposition alone turns "Søndag" into
 * "sndag". The pairs are the ones the languages this installation runs actually
 * use; anything else falls through to the accent pass and then to a separator.
 */
const ID_TRANSLITERATIONS: Readonly<Record<string, string>> = {
  æ: 'ae',
  ø: 'oe',
  å: 'aa',
  ä: 'ae',
  ö: 'oe',
  ü: 'ue',
  ß: 'ss',
};

/**
 * The identifier a name suggests, for a series that does not have one yet.
 *
 * The id is the import/export key and the handle every stored citation points
 * at, and S-16 freezes it at create — so it is worth getting right once, and it
 * is not worth typing twice. A proposal from the name is a starting point the
 * admin can overwrite; the moment they do, this stops being consulted.
 *
 * Deliberately narrow: lower-case ASCII letters, digits and single hyphens.
 * The value travels in URLs, file names and export files, so a character that
 * needs escaping in any of those is one that will eventually be escaped
 * inconsistently in one of them.
 */
export function seriesIdFromName(name: string): string {
  let mapped = '';
  for (const character of name.toLowerCase()) {
    mapped += ID_TRANSLITERATIONS[character] ?? character;
  }
  return mapped
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

// ------------------------------------------------------------------- seeding

export function toFormModel(series: PublicationSeries): SeriesFormModel {
  return {
    seriesId: series.seriesId ?? '',
    categoryId: series.categoryId ?? '',
    domainId: series.domainId ?? '',
    // Absent on a payload written before the field existed. Falling back to what
    // the content mode implies keeps an imported citation publication visible
    // from every desk, which is where it was before anybody had the choice.
    availability: series.availability ?? defaultAvailability(series.contentMode ?? 'NONE'),
    sortOrder: series.sortOrder,
    messagePublication: series.messagePublication ?? 'NONE',
    // Absent means language-specific: it is the entity default, and the importer
    // only ever sets it false for templates that said so.
    languageSpecific: series.languageSpecific ?? true,

    contentMode: series.contentMode ?? 'GENERATED_FROM_QUERY',
    reportId: series.reportId ?? '',
    pageSize: series.pageSize ?? FALLBACK.pageSize,
    pageOrientation: series.pageOrientation ?? FALLBACK.pageOrientation,
    mapThumbnails: series.mapThumbnails ?? false,
    messageSortBy: series.messageSortBy ?? '',
    messageSortOrder: series.messageSortOrder ?? FALLBACK.messageSortOrder,

    cadence: series.cadence ?? 'NONE',
    nominalCutoffDay: series.nominalCutoffDay ?? FALLBACK.cutoffDay,
    nominalCutoffDayOfMonth: series.nominalCutoffDayOfMonth ?? null,
    nominalCutoffMonth: series.nominalCutoffMonth ?? null,
    nominalCutoffTime: series.nominalCutoffTime ?? FALLBACK.cutoffTime,
    // Absent behaves as the release moment, which is both the entity default and
    // the only answer a series without a calendar period can give (S-21).
    cutoffDefault: series.cutoffDefault ?? 'RELEASE_MOMENT',
    numberingScheme: series.numberingScheme ?? 'NONE',
    nextIssueCreation: series.nextIssueCreation ?? 'AUTO_ON_PUBLISH',
    firstIssueStartsAt: series.firstIssueStartsAt,

    timeRelation: series.timeRelation ?? 'PUBLISHED_IN_INTERVAL',
    sourceSeriesId: series.sourceSeriesId ?? '',
    aliveAtCutoff: series.aliveAtCutoff ?? false,

    releaseMode: series.releaseMode ?? 'MANUAL_GATE',
  };
}

/**
 * One desc model per language the FORM offers, seeded from whatever the series has.
 *
 * Keyed by every offered language rather than only the ones with a row, so a
 * language the series does not yet cover still has somewhere to type. Which rows
 * actually get sent is decided on save, from the declared language list.
 */
export function toDescModels(series: PublicationSeries, languages: readonly string[]): DescModels {
  const models: DescModels = {};
  for (const lang of languages) {
    const desc = series.descs?.find((d) => d.lang === lang);
    models[lang] = {
      name: desc?.name ?? '',
      nameSuggestionPattern: desc?.nameSuggestionPattern ?? '',
      fileNamePattern: desc?.fileNamePattern ?? '',
      messageReferenceFormat: desc?.messageReferenceFormat ?? '',
      linkPattern: desc?.linkPattern ?? '',
    };
  }
  return models;
}

/**
 * The domains a shared publication names, as the editor holds them.
 *
 * The owner is stripped on the way in as well as on the way out: it is already
 * the owner, and a stored list that names it would render as a tickbox saying
 * the desk has shared the publication with itself.
 */
export function toAvailableDomainIds(series: PublicationSeries): readonly string[] {
  const owner = series.domainId?.trim();
  return (series.availableDomainIds ?? []).filter((id) => !!id && id !== owner);
}

/** Report parameters as ordered rows. The wire carries an object; the form carries a list. */
export function toReportParamRows(series: PublicationSeries): ReportParamRow[] {
  const params = series.reportParams ?? {};
  return Object.keys(params).map((key) => ({ key, value: String(params[key] ?? '') }));
}

// ------------------------------------------------------------------- payload

/**
 * The series to send, with every inapplicable field stripped.
 *
 * `base` carries the fields the form does not own — created/updated stamps, the
 * import markers, the status — so a save cannot quietly drop them. The status
 * specifically is NOT the form's to set: the backend owns the transition and
 * validates it, and a save carrying a status would route around that.
 */
export function buildSeriesPayload(
  base: PublicationSeries,
  model: SeriesFormModel,
  descs: DescModels,
  languages: readonly string[],
  criteria: IssueCriteria | undefined,
  reportParams: readonly ReportParamRow[],
  availableDomainIds: readonly string[] = [],
): PublicationSeries {
  const queryBacked = isQueryBacked(model);
  const byCriteria = selectsByCriteria(model);
  const compiled = isCompilation(model);
  const report = hasReport(model);
  const sortBy = model.messageSortBy.trim();
  const owner = model.domainId.trim();

  return {
    ...base,
    seriesId: model.seriesId.trim(),
    categoryId: blankToUndefined(model.categoryId),
    // The owner is not blanked to `undefined` the way an optional field is: a
    // publication nobody owns is refused, so an empty value has to travel as the
    // empty answer it is rather than as a field the server fills in for itself.
    domainId: owner,
    availability: model.availability,
    // Sent EMPTY under the other two values rather than omitted. An omitted
    // collection reads as "unchanged", so narrowing a publication back to its own
    // desk would leave every share it had standing.
    availableDomainIds:
      model.availability === 'SELECTED_DOMAINS'
        ? [...new Set(availableDomainIds.filter((id) => !!id && id !== owner))]
        : [],
    sortOrder: model.sortOrder,
    messagePublication: model.messagePublication,
    languageSpecific: model.languageSpecific,
    languages: [...languages],
    descs: buildDescs(descs, languages),

    contentMode: model.contentMode,
    reportId: queryBacked ? blankToUndefined(model.reportId) : undefined,

    // S-9: the three page settings exist exactly when a report does. Sending one
    // without a report fails a rule the admin never saw a field for.
    pageSize: report ? model.pageSize : undefined,
    pageOrientation: report ? model.pageOrientation : undefined,
    mapThumbnails: report ? model.mapThumbnails : undefined,

    // S-10: a sort field and its direction are half a setting on their own.
    messageSortBy: sortBy === '' ? undefined : sortBy,
    messageSortOrder: sortBy === '' ? undefined : model.messageSortOrder,

    cadence: model.cadence,
    nominalCutoffDay: usesWeekday(model) ? model.nominalCutoffDay : undefined,
    // `?? undefined` so an empty box is omitted from the body, as it always was.
    nominalCutoffDayOfMonth: usesDayOfMonth(model)
      ? (model.nominalCutoffDayOfMonth ?? undefined)
      : undefined,
    nominalCutoffMonth: usesMonth(model) ? (model.nominalCutoffMonth ?? undefined) : undefined,
    nominalCutoffTime: usesCutoffTime(model)
      ? blankToUndefined(model.nominalCutoffTime)
      : undefined,

    // S-21: always sent — the server refuses a series that does not say where its
    // cut-off falls — but a period boundary is stripped back to the release
    // moment on anything but a yearly series, so switching cadence cannot carry a
    // now-illegal value into a save the admin thought was about something else.
    cutoffDefault: usesPeriodCutoff(model) ? model.cutoffDefault : 'RELEASE_MOMENT',

    // S-8: a one-off has no next issue and no sequence, whatever the controls read.
    numberingScheme: isCadenceless(model) ? 'NONE' : model.numberingScheme,
    // And the mirror of it: a cadenced series always opens its next issue when
    // the current one publishes, so the field follows the cadence rather than
    // whatever a form once held. A stored MANUAL on a cadenced series heals on
    // the next save instead of travelling back out.
    nextIssueCreation: nextIssueIsDerived(model) ? 'AUTO_ON_PUBLISH' : 'MANUAL',

    firstIssueStartsAt: usesFirstIssueStart(model) ? model.firstIssueStartsAt : undefined,

    // S-1 and S-2: the regime belongs to the query-backed shape and to nothing
    // else, and the two settings it decides between belong to a narrower shape
    // still -- a compilation is query-backed and has neither.
    timeRelation: queryBacked ? model.timeRelation : undefined,
    criteria: byCriteria ? criteria : undefined,
    // S-3: in force IS the liveness test, so the flag cannot be anything but true.
    aliveAtCutoff: byCriteria ? livenessIsForced(model) || model.aliveAtCutoff : undefined,
    // S-24: the source is set exactly when the series compiles one. Sent absent
    // rather than blank on every other regime, which is how the server is told to
    // clear a source a series has stopped having.
    sourceSeriesId: compiled ? blankToUndefined(model.sourceSeriesId) : undefined,

    releaseMode: model.releaseMode,
    reportParams: report ? buildReportParams(reportParams) : undefined,
  };
}

/**
 * A desc row per DECLARED language, in the declared order.
 *
 * Rows for languages the series no longer declares are dropped rather than sent:
 * S-12 rejects a desc outside the language list, so keeping them would make the
 * series unsaveable for a reason the admin cannot see on the screen.
 */
function buildDescs(descs: DescModels, languages: readonly string[]): PublicationSeriesDesc[] {
  return languages.map((lang) => {
    const desc = descs[lang];
    return {
      lang,
      name: desc?.name.trim() ?? '',
      nameSuggestionPattern: blankToUndefined(desc?.nameSuggestionPattern),
      fileNamePattern: blankToUndefined(desc?.fileNamePattern),
      messageReferenceFormat: blankToUndefined(desc?.messageReferenceFormat),
      linkPattern: blankToUndefined(desc?.linkPattern),
    };
  });
}

/** Rows to an object, skipping blank keys and keeping the last of a duplicate. */
function buildReportParams(rows: readonly ReportParamRow[]): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key !== '') {
      params[key] = row.value;
    }
  }
  return params;
}

/**
 * Blank means absent.
 *
 * The VOs omit nulls platform-wide, so a field the server never set arrives as
 * `undefined` and a field the admin cleared has to arrive the same way — otherwise
 * an empty string reads as a value and trips the "set exactly when" rules.
 */
function blankToUndefined(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? undefined : trimmed;
}
