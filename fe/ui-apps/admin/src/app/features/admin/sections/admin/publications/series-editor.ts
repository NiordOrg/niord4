import { httpResource } from '@angular/common/http';
import {
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { disabled, form, FormField, max, min, required, validate } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { currentDesc } from '../../../utils/localized-descs';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { fixedListRead, listRead } from '../../../../../shared/utils/list-read';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { LengthCounter, tooLong } from '../../../../../shared/forms/length-counter/length-counter';
import { TimeField } from '../../../../../shared/forms/time-field/time-field';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import { Icon } from '../../../../../shared/icons/icon';
import { withEpochDates } from '../../../../../shared/utils/epoch-dates';
import type { MessageReport } from '../../../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from '../../../../messages/services/api/message-reports-api.service';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';
import {
  PublicationSeriesApiService,
  type IssueCriteria,
  type PublicationCategory,
  type PublicationSeries,
  type SeriesFieldError,
} from '../../../services/api/publication-series-api.service';
import { PublicationAvailabilityField } from './availability-field';
import { isServableId, UNSERVABLE_ID, UNSERVABLE_ID_KEY } from './publication-id';
import {
  STALE_NOT_SHOWN_KEY,
  STALE_VERSION,
  publicationErrorCode,
  publicationErrorKey,
  seriesFieldLabel,
  seriesRuleText,
} from './publication-errors';
import {
  availabilityNeedsDomain,
  buildSeriesPayload,
  CADENCES,
  CONTENT_MODES,
  CUTOFF_DAYS,
  CUTOFF_DEFAULTS,
  cutoffDefaultRefused,
  cutoffTimeToDate,
  dateToCutoffTime,
  defaultAvailability,
  hasReport,
  isCitable,
  isCadenceless,
  isCompilation,
  isQueryBacked,
  livenessIsForced,
  MESSAGE_PUBLICATIONS,
  nextIssueIsDerived,
  NUMBERING_SCHEMES,
  PAGE_ORIENTATIONS,
  PAGE_SIZES,
  pageSizeTextKey,
  messageSortByOptions,
  isReservedReportParamKey,
  previewIntervalFrom,
  reservedReportParamKeys,
  selectsByCriteria,
  seriesIdFromName,
  seriesWritable,
  TIME_RELATIONS,
  toAvailableDomainIds,
  toDescModels,
  toFormModel,
  toReportParamRows,
  usesCutoffTime,
  usesDayOfMonth,
  usesFirstIssueStart,
  usesMonth,
  usesPeriodCutoff,
  usesWeekday,
  type DescModels,
  type ReportParamRow,
  type SeriesFormModel,
} from './series-rules';
import { SeriesCriteriaEditor } from './series-criteria-editor';

/** The width of a publication's name column: varchar(255). */
const SERIES_NAME_MAX = 255;

/**
 * Make a server-supplied string safe to place inside a markup fragment.
 *
 * Only used where a value is interpolated into a sentence that is then rendered
 * as HTML. Angular sanitizes `[innerHTML]` and would strip anything dangerous
 * anyway; this stops a name that merely LOOKS like markup from being read as
 * markup and losing its own characters.
 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** What the page hands the editor. `undefined` closes it. */
export interface SeriesEditorModel {
  readonly mode: 'edit' | 'add';
  readonly series: PublicationSeries;
}

/**
 * Tab ids. Strings rather than an enum because the tabs component keys on them.
 *
 * FOUR, not five. Automation held one setting whose second value cannot be
 * chosen yet, which is a tab that costs a click to read a sentence — it is a
 * read-only line beside the frequency now, where the reader is already asking
 * how issues come out. The field stays in the model and on the wire.
 */
const TABS = ['basics', 'content', 'cadence', 'criteria'] as const;
type TabId = (typeof TABS)[number];

/**
 * What the source picker needs of a series: the id it saves, and the names it
 * shows one under.
 *
 * Narrower than the wire type on purpose. One option in the list is a series
 * that the estate read did not produce — the source a compilation already names,
 * held so the picker cannot silently clear it — and that one is known by its id
 * alone. Typed as a whole series it would have to be invented, and every field
 * invented for it would be a claim nothing checked.
 */
type SourceOption = Pick<PublicationSeries, 'seriesId' | 'descs'>;

/** Calendar bounds for the two nominal-cut-off numbers, enforced by the schema. */
const MONTH_MIN = 1;
const MONTH_MAX = 12;
const DAY_OF_MONTH_MIN = 1;
const DAY_OF_MONTH_MAX = 31;

/**
 * Everything a series owns, across four tabs.
 *
 * The organising idea is that a series is a CONTRACT its issues inherit — what
 * they contain, when they close, what they are called — and an issue may deviate
 * only where the model says it can. So this is the one screen where automation is
 * editable; everywhere else shows it read-only.
 *
 * THREE THINGS ARE DELIBERATE HERE.
 *
 * **The form keeps what it stops sending.** Most S-rules are "exactly when" rules:
 * a weekday belongs to a weekly cadence and nothing else. Switching a series to
 * yearly must stop SENDING the weekday but must not ERASE it, or switching back
 * loses work the admin did. `buildSeriesPayload` strips on the way out; the
 * controls keep their values and simply stop applying. See `series-rules.ts`.
 *
 * **Validity is the server's answer, not this component's.** `/validate` runs the
 * same code that runs on save, against the body being edited. Re-deriving the
 * rules here would produce a second source of truth, and its drift shows up as a
 * form that says yes and a save that says no. The applicability predicates shape
 * the payload; they never judge it.
 *
 * **The status is not a field, and its transitions are not in here.** Activation
 * is validated by the backend (S-17: ACTIVE is what puts a series in the picker,
 * so it must be complete), so the payload never carries a status. Activating,
 * withdrawing and reinstating are things done TO a series rather than settings
 * on it — they change what other people see, they are not part of the edit being
 * saved, and a drawer that performed them as well would make Cancel mean two
 * things. They belong to the series page's own actions menu.
 */
@Component({
  selector: 'app-publication-series-editor',
  imports: [
    AppTab,
    AppTabList,
    AppTabPanel,
    AppTabPanels,
    AppTabs,
    DateTimeField,
    Drawer,
    ErrorBanner,
    LengthCounter,
    LocalizedTextInput,
    PublicationAvailabilityField,
    TimeField,
    FormField,
    Icon,
    SeriesCriteriaEditor,
    TranslocoPipe,
  ],
  templateUrl: './series-editor.html',
  host: { class: 'contents' },
})
export class PublicationSeriesEditor {
  readonly model = input.required<SeriesEditorModel | undefined>();
  readonly saved = output<PublicationSeries>();
  readonly closed = output<void>();
  /**
   * The drawer has finished sliding out.
   *
   * Forwarded from the drawer so the host can unmount this component once it is
   * off screen — the editor's own reads happen when it is created, and that is
   * how they stay tied to a drawer somebody actually opened.
   */
  readonly hidden = output<void>();

  private readonly api = inject(PublicationSeriesApiService);
  private readonly reportsApi = inject(MessageReportsApiService);
  private readonly domainsApi = inject(DomainsAdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);
  private readonly domainFacade = inject(DomainFacade);

  protected readonly tabs = TABS;
  protected readonly cadences = CADENCES;
  protected readonly cutoffDays = CUTOFF_DAYS;
  protected readonly cutoffDefaults = CUTOFF_DEFAULTS;
  protected readonly numberingSchemes = NUMBERING_SCHEMES;
  protected readonly contentModes = CONTENT_MODES;
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly pageSizeTextKey = pageSizeTextKey;
  protected readonly pageOrientations = PAGE_ORIENTATIONS;
  protected readonly messagePublications = MESSAGE_PUBLICATIONS;
  protected readonly timeRelations = TIME_RELATIONS;

  protected readonly activeTab = signal<TabId>('basics');
  protected readonly saving = signal(false);
  protected readonly validating = signal(false);

  private readonly formModel = signal<SeriesFormModel>(toFormModel({} as PublicationSeries));
  protected readonly form = form(this.formModel, (path) => {
    required(path.seriesId);
    // S-19. The category column is NOT NULL, so a series without one is refused --
    // required here so the save button says so before the server has to.
    required(path.categoryId);
    // The owner, which every series has to have. Not a picker any more and not
    // conditional any more: it is the desk the publication is listed in,
    // administered from and whose timezone its cut-offs are read in, and a save
    // that carries none is refused. The only way to reach this rule is to create
    // a series while sitting at no desk, which the hint beside Save names.
    required(path.domainId);
    // The calendar bounds, in the schema rather than as native min/max attributes:
    // the attribute is a hint the browser draws and `form().invalid()` never sees,
    // so a month of 13 left Save live. `[formField]` reflects both back onto the
    // input, so the spinner still stops where it always did.
    min(path.nominalCutoffMonth, MONTH_MIN);
    max(path.nominalCutoffMonth, MONTH_MAX);
    min(path.nominalCutoffDayOfMonth, DAY_OF_MONTH_MIN);
    max(path.nominalCutoffDayOfMonth, DAY_OF_MONTH_MAX);
    // The import/export key and the citation handle (S-16). Immutable after create:
    // changing it renames the thing every stored reference points at.
    disabled(path.seriesId, { when: () => this.model()?.mode === 'edit' });
    // PUBCAT2. Only for a new id: the server's create refuses the same
    // characters, because no address could reach the series afterwards. The id
    // proposed from the name never holds one; a typed or pasted id can.
    validate(path.seriesId, ({ value }) =>
      this.model()?.mode !== 'add' || isServableId(value()) ? null : { kind: UNSERVABLE_ID },
    );
  });

  /** The new id holds what the server cannot serve; the field says so. */
  protected readonly seriesIdUnservable = computed(() =>
    this.form
      .seriesId()
      .errors()
      .some((error) => error.kind === UNSERVABLE_ID),
  );

  protected readonly unservableIdKey = UNSERVABLE_ID_KEY;

  /**
   * The working values, for template reads.
   *
   * Reads go through here and writes through setField, so the template never
   * reaches into the form's internals for a value it only wants to display.
   */
  protected readonly value = computed(() => this.formModel());

  /** The sort-field choices, with the series' own value among them. */
  protected readonly messageSortByOptions = computed(() =>
    messageSortByOptions(this.value().messageSortBy),
  );

  /**
   * The series as the server last handed it over, under the edited fields.
   *
   * The payload is built ON it, so every field this form does not own — and the
   * revision it was read at — travels from here rather than from the model the
   * drawer was opened with. That is the difference on a refused save: a
   * `STALE_VERSION` refetch replaces this, the typed values stay where they are,
   * and the next attempt is composed against the series as it now stands.
   */
  private readonly loadedSeries = signal<PublicationSeries | undefined>(undefined);

  /** Outside the form: collections, folded into the baseline compare below. */
  protected readonly descs = signal<DescModels>({});
  protected readonly languages = signal<readonly string[]>([]);
  protected readonly criteria = signal<IssueCriteria | undefined>(undefined);
  protected readonly reportParams = signal<readonly ReportParamRow[]>([]);
  /** The desks this series is shared with. Applies under SELECTED_DOMAINS alone. */
  protected readonly availableDomainIds = signal<readonly string[]>([]);

  /** Everything an edit can touch, as one source the stale-answer reset watches. */
  private readonly edited = computed(() => [
    this.formModel(),
    this.descs(),
    this.languages(),
    this.criteria(),
    this.reportParams(),
    this.availableDomainIds(),
  ]);

  /**
   * The last answer from `/validate`. `undefined` = not asked since the last edit.
   *
   * Reset by the edit rather than by each setter: the controls that carry schema
   * rules are `[formField]`-bound and write straight into the form, so a reset
   * hung off the hand-written setters would leave a stale refusal standing under
   * the field that had just been corrected.
   */
  protected readonly validation = linkedSignal<
    readonly unknown[],
    readonly SeriesFieldError[] | undefined
  >({
    source: this.edited,
    computation: () => undefined,
  });

  /** Every language the installation runs — the set a series may declare from (S-11). */
  protected readonly installationLanguages = this.language.modelLanguages;

  /**
   * Languages this series declares that the installation does not configure.
   *
   * Stamped when the drawer opens and held for as long as it stays open, rather
   * than derived from the live declaration: an imported series can carry a
   * language the build-time list has since dropped, and a checkbox that vanishes
   * the moment it is unticked cannot be reticked by whoever unticked it by
   * mistake. See {@link languageOptions}.
   */
  private readonly foreignLanguages = signal<readonly string[]>([]);

  /**
   * The checkbox list: the installation's languages, then anything else the
   * series declares.
   *
   * Both halves are offered because both are real. Ticking a language the
   * series does not yet cover has to find a row to type into, and a language the
   * installation dropped has to stay visible — S-11 flags it, and the admin
   * cannot answer the flag through a control that is not on the screen. Removing
   * it silently, which is what iterating the installation list alone did, throws
   * away the text with it.
   */
  protected readonly languageOptions = computed(() => {
    const configured = this.installationLanguages.map((option) => ({
      value: option.value,
      label: option.label,
      configured: true,
    }));
    const foreign = this.foreignLanguages().map((value) => ({
      value,
      // No label to look up: the installation does not know this language.
      label: value.toUpperCase(),
      configured: false,
    }));
    return [...configured, ...foreign];
  });

  protected readonly foreignLanguageCount = computed(() => this.foreignLanguages().length);

  /**
   * The reports this desk may pick from.
   *
   * The full report inventory is a sysadmin-only read, so listing it here would
   * leave every plain admin with an empty picker — and no way past the gate that
   * makes a report a precondition for activating a query-backed series. This is
   * the editor-tier list: the public reports plus the current domain's own.
   */
  private readonly reportsRead = fixedListRead<MessageReport>(() =>
    this.reportsApi.listReports({ forList: true }),
  );
  protected readonly reports = this.reportsRead.value;

  /**
   * The domains, read for the OWNER's name and its timezone.
   *
   * No longer a picker: the owner is the desk the admin is sitting at, and the
   * series is only listed and edited there. What the catalogue is still needed
   * for is the two facts about that one domain — what it is called, and which
   * zone this series' cut-offs are read in, because a series' timezone comes
   * from its domain and from nowhere else.
   *
   * Read without the Keycloak state, which the server otherwise computes by
   * asking Keycloak once per domain: nothing here renders it, and the wait is
   * paid by an editor that only needs a name and a timezone.
   */
  private readonly domainsRead = listRead<AdminDomain, string>(
    toObservable(computed(() => this.language.selectedLanguage())),
    (lang) => this.domainsApi.getAll(lang, { keycloakState: false }),
  );
  protected readonly domains = this.domainsRead.value;

  /** The owner as the catalogue knows it, or `undefined` while it is unknown. */
  private readonly ownerDomain = computed(() =>
    this.domains().find((domain) => domain.domainId === this.formModel().domainId),
  );

  /** What the owner is called; the raw id where the catalogue has no row for it. */
  protected readonly ownerDomainName = computed(() => {
    const id = this.formModel().domainId;
    return this.ownerDomain()?.name || id;
  });

  /**
   * Whether the owner has no timezone, which is S-20's other half.
   *
   * A series' cut-off schedule is declared in wall-clock terms and read in the
   * domain's zone. Own a cadenced series from a domain that has none and the
   * schedule fields on this screen refuse to show a clock at all rather than
   * guess at one — so it is said out loud, next to the owner: the fix belongs in
   * the domain, not here.
   */
  protected readonly ownerTimeZoneMissing = computed(() => {
    const owner = this.ownerDomain();
    const cadenced = this.formModel().cadence !== 'NONE';
    // Keyed on a row the catalogue actually returned: a domain that is simply
    // not in hand yet, or a read that failed, is not a domain without a zone.
    return !!owner && !owner.timeZone && cadenced;
  });

  /** The zone this series' instants are read in, or none while it has no domain. */
  protected readonly seriesTimeZone = computed(() => this.ownerDomain()?.timeZone);

  /**
   * Whether the catalogue has produced the owner at all.
   *
   * It arrives over HTTP, so between the drawer opening and the answer landing the owner is
   * simply not in hand — which is not the same fact as an owner that has no zone, and must not
   * be reported or acted on as one.
   */
  protected readonly ownerKnown = computed(() => this.ownerDomain() !== undefined);

  /**
   * The first-interval start as the date+time field reads it.
   *
   * An instant either way: the field is bound to {@link seriesTimeZone}, so it
   * shows and takes back the wall clock the OWNER domain sees while the value
   * crossing this boundary stays the moment itself. The seeded values are real
   * times of day (the importer carries cut-off instants such as Wednesday
   * 12:00), so the control carries a time as well as a date — a date-only field
   * would truncate one away on every save.
   *
   * Nothing is shown once the owner is known to have no zone. There is no wall
   * clock to print an instant as, and the field would otherwise print it in
   * whichever desk's zone happened to open the drawer — a reading of a schedule
   * that is not this series'. The stored value is untouched; the fix is a zone
   * on the domain. While the owner is merely not in hand yet the stored value
   * stays on screen (the field is closed to edits either way), so a saved start
   * does not blank and reappear on every drawer open.
   */
  protected readonly firstIssueStartsAtValue = computed(() => {
    const instant = this.formModel().firstIssueStartsAt;
    const ownerHasNoZone = this.ownerKnown() && !this.seriesTimeZone();
    return instant === undefined || ownerHasNoZone ? null : new Date(instant);
  });

  /**
   * The nominal cut-off time as the shared field reads it.
   *
   * A wall clock in the series' own zone, so no conversion happens here — only
   * the `HH:mm` ↔ `Date` shape change the control's value type asks for.
   */
  protected readonly nominalCutoffTimeValue = computed(() =>
    cutoffTimeToDate(this.formModel().nominalCutoffTime),
  );

  private readonly nameTokensRead = fixedListRead<string>(() => this.api.nameTokens());
  protected readonly nameTokens = this.nameTokensRead.value;

  /**
   * The same vocabulary, in the shape the pattern fields' insert menu takes.
   *
   * Labelled with the token itself rather than with a friendly name: what goes
   * into the field IS the label, so a writer picking `${uge}` from the menu sees
   * beforehand exactly what will appear in the pattern — and the labels then
   * read the same as the hint printed beneath the fields.
   */
  protected readonly patternTokens = computed(() =>
    this.nameTokens().map((token) => {
      const label = this.tokenLabel(token);
      return { token: label, label };
    }),
  );

  // Fetched with every language's name, and labelled here. Asking the server to
  // narrow to one language leaves a category that only has a name in another
  // language with no name at all — it would render as its raw id.
  private readonly categoriesRead = fixedListRead<PublicationCategory>(() =>
    this.api.publicationCategories(),
  );
  protected readonly categories = this.categoriesRead.value;

  /**
   * The lists the pickers are filled from; one of them failing is said above the tabs.
   * The source picker's list (`activeSeriesResource`, declared below) counts too; it is
   * read inside the computed and the retry, by when it exists.
   */
  private readonly optionReads = [
    this.reportsRead,
    this.domainsRead,
    this.nameTokensRead,
    this.categoriesRead,
  ];
  protected readonly optionsFailed = computed(
    () => this.optionReads.some((read) => read.failed()) || !!this.activeSeriesResource.error(),
  );

  /** Read again every option list that failed. */
  protected retryOptions(): void {
    for (const read of this.optionReads) {
      if (read.failed()) read.retry();
    }
    if (this.activeSeriesResource.error()) {
      this.activeSeriesResource.reload();
    }
  }

  /**
   * The estate's active series, for the source picker.
   *
   * The admin list rather than the citizen one, because the narrowing below asks
   * about the content mode and the regime, which the public shape does not carry.
   *
   * Asked for only while the compiled regime is the one on the form. It is the
   * whole estate in the editor shape — every configured language, every
   * criterion, every availability row — and exactly one series in it is a
   * compilation, so read on every open of this drawer it would be an estate-wide
   * list fetched for a control almost nobody is shown. The request is the
   * question: no regime, no request.
   */
  private readonly activeSeriesResource = httpResource<readonly PublicationSeries[]>(
    () => (this.compilation() ? this.api.searchSeriesRequest('ACTIVE') : undefined),
    {
      defaultValue: [],
      // The endpoint serialises its instants as ISO 8601 and every date on the
      // series model is epoch milliseconds. A resource built from a request
      // descriptor does its own normalising: the observables on the API service
      // pipe through the same function, and a response that skipped it would
      // reach the form as strings that compare false against every number.
      parse: (raw) => withEpochDates(raw as readonly PublicationSeries[]),
    },
  );

  // value() throws in the resource's error state, so the read is guarded by
  // hasValue(): a list nobody could fetch leaves the picker holding what the
  // series already names, and the failure is said above the tabs with the other
  // option lists (see optionReads), so it is not taken for an empty estate.
  private readonly activeSeries = computed<readonly PublicationSeries[]>(() =>
    this.activeSeriesResource.hasValue() ? this.activeSeriesResource.value() : [],
  );

  /**
   * What a compilation may name as its source, this desk's own series first.
   *
   * Narrowed to what S-25 allows — a series that generates from a query, is not
   * itself a compilation (no chains), and is not this series — so the picker does
   * not offer choices the save will refuse. The server stays the authority: the
   * list is a moment old, and a series can change regime while the drawer is
   * open.
   *
   * A stored source that the narrowing does not produce is appended rather than
   * dropped: a picker that cannot show a series its own value is a picker that
   * silently changes it on the next save, and a source series that has since been
   * retired is exactly the case an admin opened this screen to see.
   */
  protected readonly sourceSeriesOptions = computed<readonly SourceOption[]>(() => {
    const model = this.formModel();
    const own = model.seriesId.trim();
    const eligible = this.activeSeries().filter(
      (series) =>
        series.contentMode === 'GENERATED_FROM_QUERY' &&
        series.timeRelation !== 'COMPILED_FROM_SOURCE' &&
        series.seriesId !== own,
    );
    const ordered: SourceOption[] = [
      ...eligible.filter((series) => series.domainId === model.domainId),
      ...eligible.filter((series) => series.domainId !== model.domainId),
    ];
    const held = model.sourceSeriesId.trim();
    // The stand-in is the two fields an option is read by and nothing else: the
    // series it names was not in the list, so there is no third fact about it to
    // claim here.
    return held === '' || ordered.some((series) => series.seriesId === held)
      ? ordered
      : [...ordered, { seriesId: held, descs: [] }];
  });

  /** A series' name in the current language, or its id where it has none. */
  protected seriesName(series: SourceOption): string {
    return (
      currentDesc(
        series.descs ?? [],
        this.installationLanguages.map((l) => l.value),
        this.language.selectedLanguage(),
        this.language.defaultLanguage,
      )?.name || series.seriesId
    );
  }

  /** The category's name in the current language, or the first configured one. */
  protected categoryName(category: PublicationCategory): string {
    return (
      currentDesc(
        category.descs ?? [],
        this.installationLanguages.map((l) => l.value),
        this.language.selectedLanguage(),
        this.language.defaultLanguage,
      )?.name || category.categoryId
    );
  }

  /**
   * Net change against what was opened.
   *
   * Signal Forms' `dirty` tracks interaction and never clears on revert, so it
   * cannot gate Save on "differs from what was loaded". The out-of-form signals
   * are folded in so a language toggle or a criteria edit moves the gate too.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(() => this.snapshot() !== this.baselineJson());

  private snapshot(): string {
    return JSON.stringify([
      this.formModel(),
      this.descs(),
      this.languages(),
      this.criteria(),
      this.reportParams(),
      this.availableDomainIds(),
    ]);
  }

  // ------------------------------------------------------------- applicability

  protected readonly queryBacked = computed(() => isQueryBacked(this.formModel()));

  /**
   * Whether this series compiles another one, and so has no query of its own.
   *
   * The Criteria tab asks a different question under this regime: which
   * publication is being compiled, rather than which messages are selected. So
   * the criteria editor, its probe and the liveness flag come off the screen
   * entirely — none of the three has anything to say about a member list that
   * was decided issue by issue somewhere else.
   */
  protected readonly compilation = computed(() => isCompilation(this.formModel()));

  /** Whether the criteria document and the liveness flag apply at all (S-1, S-2). */
  protected readonly showCriteria = computed(() => selectsByCriteria(this.formModel()));
  protected readonly showReportSetup = computed(() => hasReport(this.formModel()));
  protected readonly showWeekday = computed(() => usesWeekday(this.formModel()));
  protected readonly showDayOfMonth = computed(() => usesDayOfMonth(this.formModel()));
  protected readonly showMonth = computed(() => usesMonth(this.formModel()));
  protected readonly showCutoffTime = computed(() => usesCutoffTime(this.formModel()));

  /**
   * Whether a period boundary is a legal cut-off default here (S-21).
   *
   * Only a yearly series has a period whose boundary means anything; everything
   * else is cut off at the release. The two period options are offered but
   * disabled rather than removed, so a reader can see what the choice would be
   * and why it is not available.
   */
  protected readonly periodCutoffAllowed = computed(() => usesPeriodCutoff(this.formModel()));

  /** A cut-off default the rule refuses — a leftover from a cadence change. */
  protected readonly cutoffDefaultBroken = computed(() => cutoffDefaultRefused(this.formModel()));

  protected isCutoffDefaultLocked(option: string): boolean {
    return option !== 'RELEASE_MOMENT' && !this.periodCutoffAllowed();
  }

  protected readonly oneOff = computed(() => isCadenceless(this.formModel()));

  /**
   * Whether the next-issue setting is derived from the cadence, and so not offered.
   *
   * The control is REPLACED rather than disabled on a cadenced series: a locked
   * select still reads as a setting somebody could unlock, and this one has no
   * second value at all. What takes its place says what the series does.
   */
  protected readonly nextIssueDerived = computed(() => nextIssueIsDerived(this.formModel()));
  protected readonly showFirstIssueStart = computed(() => usesFirstIssueStart(this.formModel()));
  protected readonly livenessForced = computed(() => livenessIsForced(this.formModel()));
  protected readonly citable = computed(() => isCitable(this.formModel()));

  /**
   * The liveness flag AS SENT, which is what the preview has to probe with.
   *
   * S-3 forces it on for an in-force series, and `buildSeriesPayload` does force
   * it — so a preview run with the raw checkbox value answers a question about a
   * series that will never be saved, over the whole corpus back to the first
   * imported message rather than over what is still in force.
   */
  protected readonly effectiveAliveAtCutoff = computed(
    () => this.livenessForced() || this.formModel().aliveAtCutoff,
  );

  /**
   * A query-backed series with no report renders nothing.
   *
   * The report IS the document: without one there is no issue to publish and no
   * preview to render, so an ACTIVE series in this state is a series in the
   * message editor's picker that can never produce anything. Named here rather
   * than left to the activation refusal.
   */
  protected readonly reportMissing = computed(
    () => this.queryBacked() && this.formModel().reportId.trim() === '',
  );

  /** The window the criteria preview runs over — see `previewIntervalFrom`. */
  protected readonly previewCutoff = signal(0);
  protected readonly previewFrom = computed(() =>
    previewIntervalFrom(this.formModel(), this.previewCutoff()),
  );

  protected readonly creating = computed(() => this.model()?.mode === 'add');

  /**
   * The drawer's title.
   *
   * "New series" on a create — the same words the menu entry that opened it
   * uses, so the drawer confirms what was clicked rather than renaming it.
   */
  protected readonly headerKey = computed(() =>
    this.creating()
      ? 'admin.publications.series.editor.newSeries'
      : 'admin.publications.series.editor.editTitle',
  );

  /**
   * The draft notice, with the desk it names set in bold.
   *
   * Assembled here rather than in the template because the domain sits inside
   * the sentence: the emphasis is markup, and `[innerHTML]` is what renders it.
   * The name is escaped on the way in — it comes from the domain catalogue, and
   * a value from the server has no business carrying tags into this sentence.
   */
  protected readonly createNoticeHtml = computed(() => {
    // Read so the sentence follows a language change even when the desk is
    // called the same thing in both: `translate` is not a signal, so without
    // this the computed has nothing to invalidate it.
    this.language.selectedLanguage();
    return this.transloco.translate('admin.publications.series.editor.createNotice', {
      domain: `<strong class="font-semibold">${escapeHtml(this.ownerDomainName())}</strong>`,
    });
  });

  /**
   * What the primary button promises.
   *
   * "Save" is wrong on a create: the series does not go live, it becomes a
   * draft in this domain and publishes nothing until somebody activates it.
   * Naming the outcome on the button is where that lands, next to the notice
   * at the top of the form that says the same thing at more length.
   */
  protected readonly saveLabelKey = computed(() =>
    this.creating()
      ? 'admin.publications.series.editor.createDraft'
      : 'admin.publications.series.editor.save',
  );

  /**
   * One line under the id: what it will be, or why it can no longer change.
   *
   * Both sentences are about the same rule from opposite ends of it, so
   * printing both would be saying it twice.
   */
  protected readonly seriesIdHintKey = computed(() =>
    this.creating()
      ? 'admin.publications.series.hint.seriesIdImmutable'
      : 'admin.publications.series.hint.seriesId',
  );

  /** The rule, or the refusal it has already become. Never both. */
  protected readonly messagePublicationHintKey = computed(() =>
    this.messagePublicationLocked()
      ? 'admin.publications.series.hint.messagePublicationLocked'
      : 'admin.publications.series.hint.messagePublication',
  );

  /**
   * The citation field, once anything cites it.
   *
   * Which message field a citation is written into cannot change after an issue
   * has published: the citations already stored live in whichever field was
   * configured at the time, and moving the setting orphans them. The server
   * refuses the change; the form stops offering it, so the refusal is not the
   * first anybody hears of the rule.
   *
   * Keyed on the STATUS rather than on a published issue, because no field on
   * the series says whether one exists. A DRAFT series has never released
   * anything, so it is the one state where the setting is certainly still free.
   */
  protected readonly messagePublicationLocked = computed(() => {
    const model = this.model();
    return model?.mode === 'edit' && model.series.status !== 'DRAFT';
  });

  /**
   * Report parameters that repeat a value the issue supplies.
   *
   * Reported per row so the message sits next to the input that caused it, and
   * folded into the save gate: the server refuses these, and a save that gets as
   * far as the server only to come back is a slower way of saying the same thing.
   */
  protected readonly reservedParamKeys = computed(() =>
    reservedReportParamKeys(this.reportParams()),
  );

  protected isReservedParamKey(key: string): boolean {
    return isReservedReportParamKey(key);
  }

  /**
   * The parameter names the chosen report declares that this series does not
   * answer yet.
   *
   * A HINT and nothing more. Nothing is added for anybody and nothing is
   * refused: a template may read a key the report never declared, and a report
   * may declare one its template ignores, so the report is not an authority on
   * what a series should carry — only the shortest way to find out what it is
   * likely to be asked for.
   *
   * Only the report's KEYS are read. Its values are the message print dialog's
   * defaults, written for a different caller, and offering one as a starting
   * value would put text into a series that nobody chose.
   *
   * Compared case-insensitively, as the reserved names are: a series answering
   * `Volume` already answers `volume`, and suggesting it again would add a second
   * row for one parameter.
   */
  protected readonly suggestedParamKeys = computed<readonly string[]>(() => {
    const reportId = this.formModel().reportId.trim();
    if (reportId === '') {
      return [];
    }
    const report = this.reports().find((candidate) => candidate.reportId === reportId);
    const carried = new Set(this.reportParams().map((row) => row.key.trim().toLowerCase()));
    return Object.keys(report?.params ?? {}).filter((key) => {
      const name = key.trim();
      return name !== '' && !isReservedReportParamKey(name) && !carried.has(name.toLowerCase());
    });
  });

  /** Append the suggested key with no value — what it should say is the admin's. */
  protected addSuggestedParam(key: string): void {
    this.reportParams.update((rows) => [...rows, { key, value: '' }]);
  }

  /**
   * Whether this series is the current domain's to change.
   *
   * Read from the STORED series rather than from the domain control, and that is
   * the whole trick: keying it on what is typed would lock the form the instant
   * an admin opened the domain picker, and unlock it again by picking their own —
   * so the answer has to be about the series as it stands.
   *
   * A new series is always writable here. Which domain it may be placed IN is a
   * different rule, enforced on the save and named by its own refusal.
   *
   * The drawer still OPENS, and everything in it still reads. Reads are not
   * scoped, and the full configuration of a series is only legible from this
   * screen — refusing to show it would hide a series from admins who are allowed
   * to see it, to prevent a write the buttons below already decline to offer.
   */
  protected readonly writable = computed(() => {
    const model = this.model();
    const currentDomainId = this.domainFacade.sessionDomain()?.domainId;
    return model?.mode === 'add' || seriesWritable(model?.series, currentDomainId);
  });

  /** The domain that owns the series, when it is not the one being worked in. */
  protected readonly otherDomainId = computed(() => {
    const model = this.model();
    return this.writable() ? undefined : model?.series.domainId;
  });

  /**
   * Whether a new series has a desk to be created in.
   *
   * The owner is the session domain and there is no picker, so an admin sitting
   * at no desk at all has nothing to create a series into — and the save would be
   * refused for a field that is not on the screen. Said beside Save instead.
   */
  protected readonly noSessionDomain = computed(
    () => this.model()?.mode === 'add' && this.formModel().domainId.trim() === '',
  );

  /**
   * "Selected domains" with nothing selected — a hard rule, not a warning.
   *
   * The payload would send an empty list, which says what OWNER_ONLY says under
   * a name that claims otherwise. The control names it in place; this is the
   * same predicate, so the message and the disabled button cannot disagree.
   */
  protected readonly availabilityIncomplete = computed(() =>
    availabilityNeedsDomain(this.formModel(), this.availableDomainIds()),
  );

  /** The width of a series name's column: varchar(255). */
  protected readonly nameMax = SERIES_NAME_MAX;

  /**
   * A declared language's name is longer than its column holds (ERR7).
   *
   * The names live outside the form, so the rule is a predicate here, read by
   * Save; the counter under each name says by how much.
   */
  protected readonly nameTooLong = computed(() => {
    const descs = this.descs();
    return this.languages().some((lang) => tooLong(descs[lang]?.name, SERIES_NAME_MAX));
  });

  protected readonly saveDisabled = computed(
    () =>
      !this.changed() ||
      this.form().invalid() ||
      this.saving() ||
      !this.writable() ||
      this.noSessionDomain() ||
      this.availabilityIncomplete() ||
      this.nameTooLong() ||
      this.reservedParamKeys().length > 0,
  );

  /**
   * Whether the admin has taken the id over from the proposal.
   *
   * A flag rather than a comparison against the derived value: an id that
   * happens to equal what the name would suggest is still an id somebody typed,
   * and re-deriving over it on the next keystroke in the name would overwrite a
   * deliberate answer with an identical-looking accident.
   */
  private readonly seriesIdTyped = signal(false);

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens, not per keystroke.
        untracked(() => this.rebuild(model));
      }
    });
  }

  private rebuild(model: SeriesEditorModel): void {
    const series = model.series;
    this.loadedSeries.set(series);
    // The owner of anything NEW is the desk being worked at, whether the drawer
    // was opened blank or as a copy of another series: a copy belongs to the
    // person taking it, not to the domain they took it from. An existing series
    // keeps the owner it has, and moving it is its own action with its own
    // reason -- see the transfer on the detail screen.
    const next =
      model.mode === 'add'
        ? { ...toFormModel(series), domainId: this.domainFacade.sessionDomain()?.domainId ?? '' }
        : toFormModel(series);
    const languages =
      series.languages && series.languages.length > 0
        ? [...series.languages]
        : this.installationLanguages.map((l) => l.value);
    // Seeded for the UNION of the installation languages and the ones this series
    // declares. The installation half means ticking a new language finds a row to
    // type into; the series half covers a series declaring a language the
    // installation has since dropped -- S-11 flags that, and the admin needs to
    // see the text before deciding what to do about it.
    const descs = toDescModels(series, [
      ...new Set([...this.installationLanguages.map((l) => l.value), ...languages]),
    ]);
    const reportParams = toReportParamRows(series);

    this.formModel.set(next);
    this.foreignLanguages.set(
      languages.filter((lang) => !this.installationLanguages.some((l) => l.value === lang)),
    );
    this.languages.set(languages);
    this.descs.set(descs);
    this.criteria.set(series.criteria);
    this.reportParams.set(reportParams);
    // A copy carries the source's sharing, and that is the point of it: a
    // series copied from one that every desk could cite is one every desk can
    // still cite, unless the admin says otherwise here.
    const availableDomainIds = toAvailableDomainIds({ ...series, domainId: next.domainId });
    this.availableDomainIds.set(availableDomainIds);
    this.baselineJson.set(
      JSON.stringify([next, descs, languages, series.criteria, reportParams, availableDomainIds]),
    );
    this.activeTab.set('basics');
    this.saving.set(false);
    // A copy arrives with every field filled in but no id, so the proposal is
    // live again -- and an existing series is never proposed at, because its id
    // is frozen.
    this.seriesIdTyped.set(model.mode !== 'add' || next.seriesId.trim() !== '');
    // Stamped once per open so the preview window does not drift under the
    // reader while they edit — a count that changes because time passed is a
    // count nobody can act on.
    this.previewCutoff.set(Date.now());
    this.form().reset();
  }

  // -------------------------------------------------------------------- edits

  protected setField<K extends keyof SeriesFormModel>(key: K, value: SeriesFormModel[K]): void {
    this.formModel.update((model) => {
      const next = { ...model, [key]: value };
      // The sharing default follows the content mode WHILE a series is being
      // created, and only then. A generated series is a desk's own product and
      // starts closed; a link or an uploaded document exists to be cited and
      // starts open. On an existing series the stored answer stands -- somebody
      // chose it, and changing the content mode is not a decision about who may
      // cite the publication.
      return key === 'contentMode' && this.model()?.mode === 'add'
        ? { ...next, availability: defaultAvailability(next.contentMode) }
        : next;
    });
  }

  protected setFromSelect<K extends keyof SeriesFormModel>(key: K, event: Event): void {
    this.setField(key, (event.target as HTMLSelectElement).value as SeriesFormModel[K]);
  }

  protected setChecked(key: keyof SeriesFormModel, event: Event): void {
    this.formModel.update((model) => ({
      ...model,
      [key]: (event.target as HTMLInputElement).checked,
    }));
  }

  /**
   * The first-interval start, back from the field as an instant.
   *
   * The field resolved the typed wall clock in the owner's zone already, so
   * there is nothing left to interpret here — only the moment to store. An
   * unparseable value stores nothing rather than a guess.
   */
  protected setFirstIssueStartsAt(value: Date | null): void {
    const instant = value === null || Number.isNaN(value.getTime()) ? undefined : value.getTime();
    this.setField('firstIssueStartsAt', instant);
  }

  /** The nominal cut-off time, back from the field as the `HH:mm` the model holds. */
  protected setNominalCutoffTime(value: Date | null): void {
    this.setField('nominalCutoffTime', dateToCutoffTime(value));
  }

  /**
   * A token as it is written in a pattern.
   *
   * Assembled here rather than in the template: the braces decode before Angular
   * parses interpolation, so an escaped `${` next to a binding reads as the start
   * of one.
   */
  protected tokenLabel(token: string): string {
    return '${' + token + '}';
  }

  protected isLanguageOn(lang: string): boolean {
    return this.languages().includes(lang);
  }

  /**
   * Declare or undeclare a language.
   *
   * The desc row is NOT deleted on untick — it stays in `descs` and is simply not
   * sent, so unticking and reticking does not lose the text. S-12 refuses a desc
   * outside the declared list, which is why the send is filtered rather than the
   * model.
   */
  protected toggleLanguage(lang: string): void {
    // Held in the OFFERED order rather than click order: the language list drives
    // the order desc rows render in, and a series whose languages read in the
    // sequence they happened to be ticked is a series that reorders itself for
    // the next reader. Read outside the update so the ordering does not depend on
    // the value being replaced.
    const order = this.languageOptions().map((option) => option.value);
    this.languages.update((languages) => {
      const next = languages.includes(lang)
        ? languages.filter((l) => l !== lang)
        : [...languages, lang];
      return order.filter((l) => next.includes(l));
    });
  }

  protected setDescField(lang: string, field: keyof DescModels[string], value: string): void {
    this.descs.update((descs) => ({
      ...descs,
      [lang]: { ...descs[lang], [field]: value },
    }));
    if (field === 'name') {
      this.proposeSeriesId();
    }
  }

  /**
   * The admin has taken the id over. Nothing proposes at it again.
   *
   * Hung off the control's own `input` event rather than off the model, because
   * the proposal writes into the same field: a model watcher could not tell the
   * two writers apart, and the first proposed character would look like typing.
   */
  protected markSeriesIdTyped(): void {
    this.seriesIdTyped.set(true);
  }

  /**
   * Re-derive the id from the name, while it is still a proposal.
   *
   * From the FIRST configured language that has a name, not from a hard-coded
   * one: the language list is deployment configuration, and the first entry is
   * the installation's primary language — which is the name an id should read
   * like. Falling through to the later languages covers a series being named in
   * the second language first.
   */
  private proposeSeriesId(): void {
    if (this.seriesIdTyped() || !this.creating()) {
      return;
    }
    const descs = this.descs();
    const order = this.languageOptions().map((option) => option.value);
    const name = order.map((lang) => descs[lang]?.name?.trim() ?? '').find((value) => value !== '');
    this.form.seriesId().value.set(name ? seriesIdFromName(name) : '');
  }

  protected onDescInput(lang: string, field: keyof DescModels[string], event: Event): void {
    this.setDescField(lang, field, (event.target as HTMLInputElement).value);
  }

  protected onCriteriaChange(criteria: IssueCriteria | undefined): void {
    this.criteria.set(criteria);
  }

  protected addReportParam(): void {
    this.reportParams.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeReportParam(index: number): void {
    this.reportParams.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected setReportParam(index: number, field: keyof ReportParamRow, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.reportParams.update((rows) =>
      rows.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );
  }

  // ------------------------------------------------------------ validate/save

  /** The series as it would be sent, revision included. */
  private payload(): PublicationSeries {
    const model = this.model();
    return buildSeriesPayload(
      this.loadedSeries() ?? model?.series ?? ({} as PublicationSeries),
      this.formModel(),
      this.descs(),
      this.languages(),
      this.criteria(),
      this.reportParams(),
      this.availableDomainIds(),
    );
  }

  /** Errors for one field path, so the template can render them in place. */
  protected errorsFor(field: string): readonly SeriesFieldError[] {
    return (this.validation() ?? []).filter((e) => e.field === field);
  }

  protected readonly validationErrors = computed(() => this.validation() ?? []);

  /**
   * The name of the field a rule refused, as the screen calls it.
   *
   * The wire path (`descs.da.name`) is not a thing an admin has seen, so it is
   * resolved to the label above the control and the language it belongs to. A
   * path in neither shape falls back to itself — ugly and honest, and the same
   * choice the section's code mapping makes for a code it does not know.
   */
  protected fieldLabel(error: SeriesFieldError): string {
    const label = seriesFieldLabel(error.field);
    if (!label) {
      return error.field;
    }
    const name = this.transloco.translate(label.key);
    // The key somebody typed into the parameter table, which is the only thing
    // that says WHICH row of it the rule refused over.
    if (label.param) {
      return `${name} (${label.param})`;
    }
    if (!label.lang) {
      return name;
    }
    const language =
      this.installationLanguages.find((option) => option.value === label.lang)?.label ?? label.lang;
    return `${name} (${language})`;
  }

  /**
   * Why a rule refused, with its id kept beside it.
   *
   * Worded by the section's one {@link seriesRuleText}, so a rule reads the
   * same here, in the one-off editor and on the series page.
   */
  protected ruleText(rule: string): string {
    return seriesRuleText((key, params) => this.transloco.translate(key, params), rule);
  }

  protected validate(): void {
    this.validating.set(true);
    this.api.validateSeries(this.payload()).subscribe({
      next: (errors) => {
        this.validation.set(errors);
        this.validating.set(false);
      },
      error: () => {
        this.validating.set(false);
        this.notifications.error('admin.publications.series.toast.validateError');
      },
    });
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const series = this.payload();
    const request =
      model.mode === 'add'
        ? this.api.createSeries(series)
        : this.api.updateSeries(series.seriesId, series);

    this.saving.set(true);
    request.subscribe({
      next: (saved) => {
        this.notifications.success('admin.publications.series.toast.saved');
        this.saved.emit(saved);
      },
      error: (response: unknown) => {
        this.saving.set(false);
        // A refusal over the revision is not about the values, so there are no
        // rules to show for it -- what it needs is the series as it now stands.
        // The toast says what this form does with it: the other change is not
        // shown, and saving again overwrites it.
        if (publicationErrorCode(response) === STALE_VERSION) {
          this.notifications.error(STALE_NOT_SHOWN_KEY);
          this.reread(series.seriesId);
          return;
        }
        // The server refused. Say what it said where the refusal names something
        // actionable, then ask it WHY and show the rules -- rather than leaving a
        // toast that says only that something went wrong.
        this.notifications.error(
          publicationErrorKey(response, 'admin.publications.series.toast.saveError'),
        );
        this.validate();
      },
    });
  }

  /**
   * Re-read the series after a save was refused for being composed against an
   * older revision of it.
   *
   * Only the base is replaced, never the controls: the refused save changed
   * nothing, and throwing away what an admin has typed in order to show them a
   * field somebody else edited is a worse answer than telling them and keeping
   * their work. So the other admin's change is NOT shown, and the toast says so.
   * What the re-read buys is a payload built on the current row: the next press
   * carries the revision the server will accept, keeps the other admin's change
   * in every field this form is not about, and writes the typed values over it
   * in every field this form owns (last write wins).
   */
  private reread(seriesId: string): void {
    this.api.getSeries(seriesId).subscribe({
      next: (series) => this.loadedSeries.set(series),
      // Left alone rather than reported twice: the refusal has already been
      // named, and a second toast about a background read says nothing an admin
      // can act on. The next save is then refused as stale again.
      error: () => undefined,
    });
  }

  protected close(): void {
    this.closed.emit();
  }
}
