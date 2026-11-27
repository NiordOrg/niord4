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
import { disabled, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { currentDesc } from '../../../utils/localized-descs';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { fixedListRead, listRead } from '../../../../../shared/utils/list-read';
import { LengthCounter, tooLong } from '../../../../../shared/forms/length-counter/length-counter';
import { LocalizedInputs } from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import { Icon } from '../../../../../shared/icons/icon';
import type { MessageReport } from '../../../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from '../../../../messages/services/api/message-reports-api.service';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';
import {
  OneOffApiService,
  type OneOffPublication,
  type OneOffRequest,
} from '../../../services/api/one-off-api.service';
import {
  PublicationSeriesApiService,
  type ContentMode,
  type IssueCriteria,
  type PublicationCategory,
  type PublicationSeries,
  type SeriesFieldError,
  type SortDirection,
} from '../../../services/api/publication-series-api.service';
import { PublicationAvailabilityField } from './availability-field';
import {
  STALE_NOT_SHOWN_KEY,
  STALE_VERSION,
  publicationErrorCode,
  publicationErrorKey,
  seriesFieldLabel,
  seriesRuleText,
} from './publication-errors';
import { SeriesCriteriaEditor } from './series-criteria-editor';
import {
  availabilityNeedsDomain,
  buildSeriesPayload,
  defaultAvailability,
  isCitable,
  isQueryBacked,
  isReservedReportParamKey,
  livenessIsForced,
  MESSAGE_PUBLICATIONS,
  PAGE_ORIENTATIONS,
  PAGE_SIZES,
  pageSizeTextKey,
  previewIntervalFrom,
  reservedReportParamKeys,
  seriesWritable,
  TIME_RELATIONS,
  toAvailableDomainIds,
  toDescModels,
  toFormModel,
  toReportParamRows,
  type DescModels,
  type ReportParamRow,
  type SeriesFormModel,
} from './series-rules';

/** The width of a publication's name column: varchar(255). */
const NAME_MAX = 255;

/** What the editor was opened for: a blank one, or an existing publication. */
export type OneOffEditorModel =
  | { readonly mode: 'add' }
  | { readonly mode: 'edit'; readonly publication: OneOffPublication };

/**
 * The value the save's transition flag must carry so that it asks for NOTHING.
 *
 * The endpoint reads that flag as a TRANSITION rather than as a field: it
 * compares what is asked for against where the publication actually stands, and
 * acts only on the difference. So a form that sends the whole publication back
 * on every save has to send the value that matches — anything else turns a
 * rename into a withdrawal, or into a refusal about a public period nobody
 * touched.
 *
 * Which means restating the server's own reading of "is this publication on the
 * public site right now": an ACTIVE series holding a PUBLISHED issue whose
 * period has begun and has not ended. Deliberately NOT the derived line the
 * screens show — that one answers "what should a reader be told", and it counts
 * a period that has run out as a published publication, which is true of the
 * document and false of the transition.
 *
 * The category's publish flag is not part of it: a category the public site does
 * not list keeps the publication off the site without any transition being
 * available to change it.
 */
function transitionThatChangesNothing(
  publication: OneOffPublication,
  series: PublicationSeries,
  now: number,
): boolean {
  if (series.status !== 'ACTIVE' || publication.issueStatus !== 'PUBLISHED') {
    return false;
  }
  const from = publication.publicFrom ?? undefined;
  const to = publication.publicTo ?? undefined;
  return (from === undefined || from <= now) && (to === undefined || to > now);
}

/**
 * Create or edit a one-off publication.
 *
 * EVERYTHING A SERIES HAS, MINUS THE PARTS ABOUT A SEQUENCE. A one-off is stored
 * as a series holding a single issue, and the options it can carry are the same
 * ones — including a query-backed content mode with its criteria and its report.
 * What it cannot have is a cadence, a cut-off schedule, a numbering scheme
 * across issues, a first interval or an automatic successor: those are questions
 * about the NEXT issue, and there is no next issue.
 *
 * An earlier version of this form offered only "uploaded PDF" and "external
 * link", which was narrower than the data it was editing — three of the five
 * one-offs in the estate carry contentMode NONE, and nothing stops a one-off
 * being generated from a query. All four modes are offered, and the fields each
 * one needs appear with it.
 *
 * The hard parts are not re-implemented: the criteria document is the same
 * editor the series form uses, and the payload is built by the same mapping. A
 * second copy of either would be a second place for them to drift.
 *
 * WHETHER IT IS LIVE IS NOT A SETTING IN HERE. Taking a publication off the
 * public list retires the issue behind it and has to record why, and putting it
 * back is the same kind of change in the other direction — things done TO a
 * publication rather than fields on one. They live where the publication itself
 * is: its row's menu and its own page. This drawer carries the stored answer
 * back untouched.
 *
 * THE OWNER IS THE DESK BEING WORKED AT, and it is not a picker. A one-off is
 * listed and administered in exactly one domain; who ELSE may cite it is the
 * separate availability question below, and it defaults to every domain — which
 * is what the citation-only publications need and what they had before anybody
 * had the choice. MOVING it to another desk is not in here either: that changes
 * who is accountable for the publication rather than what the publication says,
 * so it belongs where the publication itself is — its own page and its row in
 * the list.
 *
 * The order is the order somebody fills it in: what it is called, what it is,
 * and — directly under that answer — the file or the address that IS it. What
 * has a working default and is set once in a publication's life is folded into
 * "Advanced", reachable and out of the way.
 */
@Component({
  selector: 'app-one-off-editor',
  imports: [
    CopyLinkButton,
    Drawer,
    ErrorBanner,
    FormField,
    Icon,
    LengthCounter,
    LocalizedInputs,
    LocalizedTextInput,
    PublicationAvailabilityField,
    SeriesCriteriaEditor,
    TranslocoPipe,
  ],
  templateUrl: './one-off-editor.html',
  host: { class: 'contents' },
})
export class OneOffEditor {
  private readonly api = inject(OneOffApiService);
  private readonly seriesApi = inject(PublicationSeriesApiService);
  private readonly domainsApi = inject(DomainsAdminApiService);
  private readonly reportsApi = inject(MessageReportsApiService);
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly domainFacade = inject(DomainFacade);

  readonly model = input<OneOffEditorModel | undefined>(undefined);
  readonly closed = output<void>();
  readonly saved = output<void>();
  /**
   * The drawer has finished sliding out.
   *
   * Forwarded from the drawer so the host can unmount this component once it is
   * off screen — the editor's own reads happen when it is created, and that is
   * how they stay tied to a drawer somebody actually opened.
   */
  readonly hidden = output<void>();

  /**
   * The four content modes, in the order a one-off is usually chosen from.
   *
   * "Reference only" first because that is what a one-off most often is — a
   * publication that exists so messages can cite it — and a generated report
   * last because it is the rare shape here. The same four the series form
   * offers: a picker that cannot show a publication its own value is a picker
   * that changes it on the next save.
   */
  private readonly typeModes: readonly ContentMode[] = [
    'NONE',
    'UPLOADED_FILE',
    'EXTERNAL_LINK',
    'GENERATED_FROM_QUERY',
  ];

  protected readonly messagePublications = MESSAGE_PUBLICATIONS;
  /**
   * Two regimes, not three. A one-off has a single issue and no series to tile
   * against, so compiling another publication's issues is not a shape it can
   * take — and the source picker that regime needs is on the series editor,
   * which is where a compilation is set up.
   */
  protected readonly timeRelations = TIME_RELATIONS.filter(
    (relation) => relation !== 'COMPILED_FROM_SOURCE',
  );
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly pageSizeTextKey = pageSizeTextKey;
  protected readonly pageOrientations = PAGE_ORIENTATIONS;
  protected readonly sortDirections: readonly SortDirection[] = ['ASC', 'DESC'];

  protected readonly saving = signal(false);
  protected readonly validating = signal(false);
  protected readonly uploading = signal(false);
  /** The settings nobody changes twice, folded away until asked for. */
  protected readonly showAdvanced = signal(false);

  private readonly base = signal<PublicationSeries>({} as PublicationSeries);
  protected readonly value = signal<SeriesFormModel>(toFormModel({} as PublicationSeries));

  /**
   * The two fields that carry a rule, as a form.
   *
   * Signal Forms is the standard for an entity editor, and the point of putting
   * these two through it is that the rules reach the CONTROLS: `[formField]`
   * reflects `required` and `disabled` onto the element, so a blank category
   * marks itself and the immutable id locks itself. The rest of this drawer is
   * settings with no rule to carry, and they stay on the same `value` signal the
   * form wraps -- one model, one baseline, one payload.
   */
  protected readonly form = form(this.value, (path) => {
    // S-19: the category column is NOT NULL, so a one-off without one is refused.
    required(path.categoryId);
    // S-16: the import/export key and the citation handle, editable exactly once.
    disabled(path.seriesId, { when: () => this.isEdit() });
  });
  protected readonly descs = signal<DescModels>({});
  protected readonly criteria = signal<IssueCriteria | undefined>(undefined);
  protected readonly reportParams = signal<readonly ReportParamRow[]>([]);
  /** The desks this publication is shared with. Applies under SELECTED_DOMAINS alone. */
  protected readonly availableDomainIds = signal<readonly string[]>([]);
  /** Issue-level, per language: where a link-backed publication actually points. */
  protected readonly links = signal<Record<string, string>>({});
  /**
   * The file behind each language, as the server last named it.
   *
   * Issue-level and NOT part of the form: uploading is a write of its own that
   * lands the moment the file is chosen, so this is refreshed from the upload's
   * answer rather than folded into the baseline the Save gate compares against.
   */
  protected readonly fileNames = signal<Record<string, string>>({});
  /**
   * The request's transition flag — CARRIED, never edited from here.
   *
   * Seeded so that a save asks for no transition at all, and sent back unchanged:
   * a save made to fix a file-name pattern must not take a live publication off
   * the public site as a side effect. Withdrawing and publishing again are things
   * done TO a publication, on its own page, where the reason can be recorded.
   *
   * A NEW publication starts live: an admin writing a document down means it to
   * be citable, and this is the one place the flag is deliberately `true`.
   */
  private readonly carriedTransition = signal(true);

  /**
   * The instant the criteria preview runs against.
   *
   * Stamped once when the drawer opens rather than read live, so the preview
   * does not shift under the reader while they are editing the query.
   */
  protected readonly previewCutoff = signal(Date.now());

  /**
   * The languages this publication is written in.
   *
   * Seeded from the installation list on create and from the publication itself
   * on edit, so a publication that carries a language the installation no longer
   * configures keeps its text rather than silently losing that row.
   */
  protected readonly languages = signal<readonly string[]>([]);

  private installationLanguages(): readonly string[] {
    return this.language.modelLanguages.map((l) => l.value);
  }

  // Both lists carry server-localized labels, so both are re-fetched when the
  // language changes: the drawer's host outlives any one language, and a list
  // read once shows the labels of whichever language happened to be active then.
  //
  // The domains are read without the Keycloak state, which the server otherwise
  // computes by asking Keycloak once per domain: this list is rendered as ids
  // and names, so the wait buys the drawer nothing.
  private readonly domainsRead = listRead<AdminDomain, string>(
    toObservable(computed(() => this.language.selectedLanguage())),
    (lang) => this.domainsApi.getAll(lang, { keycloakState: false }),
  );
  protected readonly domains = this.domainsRead.value;

  // Fetched with every language's name, and labelled here: narrowing to one
  // language on the server would leave a category named only in another
  // language rendering as its raw id.
  private readonly categoriesRead = fixedListRead<PublicationCategory>(() =>
    this.seriesApi.publicationCategories(),
  );
  protected readonly categories = this.categoriesRead.value;

  /** The category's name in the current language, or the first configured one. */
  protected categoryName(category: PublicationCategory): string {
    return (
      currentDesc(
        category.descs ?? [],
        this.installationLanguages(),
        this.language.selectedLanguage(),
        this.language.defaultLanguage,
      )?.name || category.categoryId
    );
  }

  /**
   * The reports this desk may pick from.
   *
   * The full report inventory is a sysadmin-only read, so listing it here would
   * leave every plain admin with an empty picker — and no way past the gate that
   * makes a report a precondition for activating a query-backed publication.
   * This is the editor-tier list: the public reports plus the current domain's own.
   */
  private readonly reportsRead = fixedListRead<MessageReport>(() =>
    this.reportsApi.listReports({ forList: true }),
  );
  protected readonly reports = this.reportsRead.value;

  /**
   * The pattern vocabulary, in the shape the insert menu takes.
   *
   * Labelled with the token itself: what goes into the field IS the label, so a
   * writer picking one from the menu sees beforehand exactly what will appear
   * in the format.
   */
  private readonly nameTokensRead = fixedListRead<string>(() => this.seriesApi.nameTokens());
  private readonly nameTokens = this.nameTokensRead.value;

  /** The lists the pickers are filled from; one of them failing is said at the top. */
  private readonly optionReads = [
    this.domainsRead,
    this.categoriesRead,
    this.reportsRead,
    this.nameTokensRead,
  ];
  protected readonly optionsFailed = computed(() => this.optionReads.some((read) => read.failed()));

  /** Read again every option list that failed. */
  protected retryOptions(): void {
    for (const read of this.optionReads) {
      if (read.failed()) read.retry();
    }
  }

  protected readonly patternTokens = computed(() =>
    // Assembled here rather than in the template: the braces decode before
    // Angular parses interpolation, so an escaped `${` next to a binding reads
    // as the start of one.
    this.nameTokens().map((token) => {
      const label = '${' + token + '}';
      return { token: label, label };
    }),
  );

  protected readonly isEdit = computed(() => this.model()?.mode === 'edit');
  protected readonly headerKey = computed(() =>
    this.isEdit()
      ? 'admin.publications.oneOff.editor.editTitle'
      : 'admin.publications.oneOff.editor.addTitle',
  );

  /**
   * What the primary button promises.
   *
   * A new publication is created rather than saved, and — for an uploaded one —
   * created without the thing it exists to carry. Naming the outcome is where
   * that lands, beside the hint under the type that says where the file goes.
   */
  protected readonly saveLabelKey = computed(() =>
    this.isEdit()
      ? 'admin.publications.oneOff.editor.save'
      : 'admin.publications.series.editor.createDraft',
  );

  /** How a mode reads on a one-off, which is not how it reads on a series. */
  protected typeLabelKey(mode: ContentMode): string {
    switch (mode) {
      case 'NONE':
        return 'admin.publications.oneOff.typeNone';
      case 'UPLOADED_FILE':
        return 'admin.publications.oneOff.typeUploaded';
      case 'EXTERNAL_LINK':
        return 'admin.publications.oneOff.typeLink';
      default:
        return 'admin.publications.oneOff.typeGenerated';
    }
  }

  /**
   * The citation field, once the single issue has gone out.
   *
   * Which message field a citation is written into cannot change after
   * publication: the citations already stored live in whichever field was
   * configured at the time, and moving the setting orphans them. Here the answer
   * is exact rather than inferred — a one-off carries the status of its one
   * issue.
   */
  protected readonly messagePublicationLocked = computed(() => {
    const model = this.model();
    return model?.mode === 'edit' && model.publication.issueStatus === 'PUBLISHED';
  });

  /**
   * Whether this publication is the current desk's to change.
   *
   * Read from the STORED owner rather than from the working copy, which is what
   * keeps the answer about the publication rather than about what is on screen.
   * A new one is always writable — which desk it may be created IN is the
   * separate rule below, and it has its own hint.
   *
   * The drawer still opens and every setting still reads. Reads are not scoped,
   * and this is the only screen a one-off's configuration is legible from at
   * all; refusing to show it would hide a publication from admins who are
   * allowed to see it, to prevent writes the buttons already decline to offer.
   */
  protected readonly writable = computed(() => {
    const model = this.model();
    if (!model || model.mode === 'add') {
      return true;
    }
    return seriesWritable(model.publication.series, this.domainFacade.sessionDomain()?.domainId);
  });

  /** The desk that owns it, when it is not the one being worked in. */
  protected readonly otherDomainId = computed(() => {
    const model = this.model();
    return this.writable() || model?.mode !== 'edit'
      ? undefined
      : model.publication.series.domainId;
  });

  /** What the owner is called; the raw id where the catalogue has no row for it. */
  protected readonly ownerDomainName = computed(() => {
    const id = this.value().domainId;
    return this.domains().find((domain) => domain.domainId === id)?.name || id;
  });

  /**
   * Whether a new publication has a desk to be created in.
   *
   * The owner is the session domain and there is no picker, so an admin sitting
   * at no desk has nothing to create one into — and the save would be refused
   * for a field that is not on the screen.
   */
  protected readonly noSessionDomain = computed(
    () => this.model()?.mode === 'add' && this.value().domainId.trim() === '',
  );

  /**
   * The modes on offer, which is not every mode a one-off can be in.
   *
   * A publication generated from a query is a report with a schedule's worth of
   * machinery behind it — criteria, a report, page setup, parameters — and
   * choosing it here turns a form for writing down a document somebody already
   * produced into the series form. Nobody has ever built one this way; the ones
   * that exist arrived from the previous system. So the segment appears only
   * where the publication already IS query-backed, because a picker that cannot
   * show a publication its own value is a picker that changes it on the next
   * save — and the fields behind it stay reachable for the publications that
   * carry it. A new one of that shape is a series, and is made as one.
   *
   * Read from the STORED publication rather than from the working copy: gated on
   * what is on screen, pressing another segment would take the segment away with
   * it and leave no way back to the mode the publication is actually saved in.
   */
  protected readonly visibleTypeModes = computed(() => {
    const model = this.model();
    const storedIsGenerated =
      model?.mode === 'edit' && model.publication.series.contentMode === 'GENERATED_FROM_QUERY';
    return this.typeModes.filter((mode) => mode !== 'GENERATED_FROM_QUERY' || storedIsGenerated);
  });

  protected readonly queryBacked = computed(() => isQueryBacked(this.value()));
  protected readonly linkBacked = computed(() => this.value().contentMode === 'EXTERNAL_LINK');
  protected readonly uploaded = computed(() => this.value().contentMode === 'UPLOADED_FILE');
  protected readonly citable = computed(() => isCitable(this.value()));

  /** The rule, or the refusal it has already become. Never both. */
  protected readonly messagePublicationHintKey = computed(() =>
    this.messagePublicationLocked()
      ? 'admin.publications.series.hint.messagePublicationLocked'
      : 'admin.publications.series.hint.messagePublication',
  );

  /**
   * The issue behind an existing publication, which is where the bytes go.
   *
   * A publication being created has none: the issue is made by the first save,
   * so there is nowhere to put a file until then. Said in the drawer rather than
   * discovered when the upload fails.
   */
  protected readonly issuePublicId = computed(() => {
    const model = this.model();
    return model?.mode === 'edit' ? model.publication.issuePublicId : undefined;
  });

  protected readonly canUpload = computed(() => this.issuePublicId() !== undefined);

  /** Whether the publication keeps a file per language rather than one for all. */
  protected readonly perLanguageFiles = computed(() => this.value().languageSpecific);

  /**
   * The upload rows: one per language, or one for the whole publication.
   *
   * A publication that does not declare a file per language HAS one file, and a
   * row for every language it is written in offers to put a different document
   * behind each — which the server then stores as one, last write winning. The
   * first declared language is where those bytes go, which is the language the
   * one file is filed under.
   */
  protected readonly fileRows = computed(() => {
    const names = this.fileNames();
    const languages = this.perLanguageFiles() ? this.languages() : this.languages().slice(0, 1);
    return languages.map((lang) => ({ lang, fileName: names[lang] }));
  });

  /** S-3: "in force at the cut-off" IS the liveness test, so the flag locks on. */
  protected readonly livenessForced = computed(() => livenessIsForced(this.value()));

  /**
   * The liveness flag AS SENT, which is what the preview must probe with.
   *
   * `buildSeriesPayload` forces it on for an in-force publication, so a preview
   * run with the raw checkbox answers for a publication that will never be
   * saved — and answers it over every message ever published rather than over
   * what is still in force.
   */
  protected readonly effectiveAliveAtCutoff = computed(
    () => this.livenessForced() || this.value().aliveAtCutoff,
  );

  /**
   * The lower bound the preview runs from.
   *
   * A one-off has no cadence to step a period back by, so without this the probe
   * is unbounded: the resolver would hydrate the whole corpus on a keystroke
   * debounce and answer a question nobody asked.
   */
  protected readonly previewFrom = computed(() =>
    previewIntervalFrom(this.value(), this.previewCutoff()),
  );

  /**
   * A query-backed publication with no report renders nothing.
   *
   * The report IS the document: with none there is no file for the issue to
   * publish, so the publication can be saved as a draft but can never go live.
   * Reported next to the field rather than as a refused save.
   */
  protected readonly reportMissing = computed(
    () => this.queryBacked() && this.value().reportId.trim() === '',
  );

  /**
   * Net change against what the drawer was opened on.
   *
   * Compared against the seeded values rather than tracked as interaction, so
   * typing something back to what it was is not a change — a discard prompt over
   * nothing teaches people to dismiss the prompt without reading it.
   */
  private readonly baseline = signal('');
  protected readonly changed = computed(() => this.snapshot() !== this.baseline());

  private snapshot(): string {
    return JSON.stringify([
      this.value(),
      this.descs(),
      this.languages(),
      this.criteria(),
      this.reportParams(),
      this.links(),
      this.carriedTransition(),
      this.availableDomainIds(),
    ]);
  }

  /**
   * Report parameters that repeat a value the issue supplies.
   *
   * Refused here as well as by the server: week, year and edition are injected
   * at render, and a row carrying one is a second source for the same value.
   */
  protected readonly reservedParamKeys = computed(() =>
    reservedReportParamKeys(this.reportParams()),
  );

  protected isReservedParamKey(key: string): boolean {
    return isReservedReportParamKey(key);
  }

  /**
   * A name in at least one language, and a category. Everything else has a default.
   *
   * Gated on `changed()` as well: an untouched Save would PUT the whole body back
   * unchanged, which on a live publication re-runs the activation sequence for no
   * reason at all. Every read is unconditional so none is lost to a short circuit.
   */
  protected readonly canSave = computed(() => {
    const named = Object.values(this.descs()).some((d) => d.name.trim().length > 0);
    // ERR7. A name longer than its column holds is refused by the server; the
    // counter under the names says which and by how much.
    const nameTooLong = this.nameCounters().some((row) => tooLong(row.text, NAME_MAX));
    const changed = this.changed();
    const saving = this.saving();
    const invalid = this.form().invalid();
    const reserved = this.reservedParamKeys().length;
    // A publication cannot be made live without something to publish.
    const unrenderable = this.reportMissing() && this.carriedTransition();
    // The same owner-equality guard the series editor applies, and for the same
    // reason: the server refuses a write aimed at another desk's publication, so
    // the button declines rather than the request. Every read is unconditional
    // so none is lost to a short circuit.
    const writable = this.writable();
    const ownerless = this.noSessionDomain();
    // "Selected domains" with nothing selected says what OWNER_ONLY says under a
    // name that claims otherwise, so it is refused rather than sent as an empty
    // list. The control names it in place through the same predicate.
    const shareIncomplete = this.availabilityIncomplete();
    return (
      !saving &&
      changed &&
      named &&
      !nameTooLong &&
      !invalid &&
      reserved === 0 &&
      !unrenderable &&
      writable &&
      !ownerless &&
      !shareIncomplete
    );
  });

  /** See {@link canSave}. Exposed so the drawer's own gate reads the one rule. */
  protected readonly availabilityIncomplete = computed(() =>
    availabilityNeedsDomain(this.value(), this.availableDomainIds()),
  );

  /**
   * The links that actually point somewhere, so each can be handed over.
   *
   * Built beside the input group rather than inside it: the per-language input
   * component is a value primitive shared with the message editor, and a copy
   * action belongs to a link rather than to text in general. Rows with nothing
   * typed are left out — there is no address to copy yet.
   */
  protected readonly linkRows = computed(() => {
    const links = this.links();
    return this.languages()
      .map((lang) => ({ lang, url: (links[lang] ?? '').trim() }))
      .filter((row) => row.url.length > 0);
  });

  protected readonly names = computed(() => this.perLanguage((d) => d.name));

  protected readonly nameMax = NAME_MAX;

  /**
   * One counter per declared language, under the group of name inputs.
   *
   * The group renders its inputs itself, so a counter cannot sit under each;
   * each line names its language instead.
   */
  protected readonly nameCounters = computed(() => {
    const names = this.names();
    return this.languages().map((lang) => ({
      lang,
      label: this.language.modelLanguages.find((option) => option.value === lang)?.label ?? lang,
      text: names[lang] ?? '',
    }));
  });
  protected readonly referenceFormats = computed(() =>
    this.perLanguage((d) => d.messageReferenceFormat),
  );
  protected readonly fileNamePatterns = computed(() => this.perLanguage((d) => d.fileNamePattern));

  private perLanguage(pick: (desc: DescModels[string]) => string): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [lang, desc] of Object.entries(this.descs())) {
      out[lang] = pick(desc);
    }
    return out;
  }

  constructor() {
    // Seeded from the model rather than bound to it: the drawer edits a working
    // copy, so cancelling leaves the row on the dashboard untouched.
    effect(() => {
      const model = this.model();
      if (!model) {
        return;
      }
      untracked(() => this.seed(model));
    });
  }

  private seed(model: OneOffEditorModel): void {
    const languages = this.installationLanguages();
    this.languages.set(languages);

    if (model.mode === 'add') {
      // Not query-backed until somebody says so: the common case is a document
      // that already exists.
      const blank = {
        seriesId: '',
        contentMode: 'UPLOADED_FILE' as ContentMode,
        cadence: 'NONE',
        kind: 'ONE_OFF',
        // The desk being worked at owns whatever is created here. There is no
        // picker: a one-off is administered in exactly one domain, and it is
        // this one.
        domainId: this.domainFacade.sessionDomain()?.domainId ?? '',
        languages,
        descs: [],
      } as unknown as PublicationSeries;
      this.base.set(blank);
      this.value.set(toFormModel(blank));
      this.descs.set(toDescModels(blank, languages));
      this.criteria.set(undefined);
      this.reportParams.set([]);
      this.links.set({});
      this.fileNames.set({});
      this.carriedTransition.set(true);
      this.availableDomainIds.set([]);
      this.previewCutoff.set(Date.now());
      this.baseline.set(this.snapshot());
      this.showAdvanced.set(false);
      this.form().reset();
      return;
    }

    const series = model.publication.series;
    // The UNION, so a language the installation dropped does not silently erase
    // the text this publication already carries in it.
    const own = series.languages && series.languages.length > 0 ? [...series.languages] : [];
    const merged = [...new Set([...languages, ...own])];
    this.languages.set(own.length > 0 ? own : languages);

    this.base.set(series);
    this.value.set(toFormModel(series));
    this.descs.set(toDescModels(series, merged));
    this.criteria.set(series.criteria);
    this.reportParams.set(toReportParamRows(series));

    const links: Record<string, string> = {};
    for (const link of model.publication.links) {
      links[link.lang] = link.value ?? '';
    }
    this.links.set(links);
    const fileNames: Record<string, string> = {};
    for (const file of model.publication.fileNames) {
      if (file.value) {
        fileNames[file.lang] = file.value;
      }
    }
    this.fileNames.set(fileNames);
    this.carriedTransition.set(transitionThatChangesNothing(model.publication, series, Date.now()));
    this.availableDomainIds.set(toAvailableDomainIds(series));
    this.previewCutoff.set(Date.now());
    this.baseline.set(this.snapshot());
    this.showAdvanced.set(false);
    // Touched-state is about the sitting, not about the record: without this the
    // next publication opens wearing the marks of the last one.
    this.form().reset();
  }

  // --------------------------------------------------------------- the file

  /**
   * Put a file behind one language, now.
   *
   * NOT part of the form's Save. The bytes are issue-level and the endpoint that
   * takes them is its own write, so making it wait for Save would mean a drawer
   * where Cancel appears to undo an upload it cannot. The answer carries the
   * stored name back, which is the only thing on this screen that changes.
   */
  protected onFileChosen(lang: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const publicId = this.issuePublicId();
    if (!file || !publicId || this.uploading()) {
      return;
    }
    // Cleared straight away so choosing the same file twice fires again: the
    // control reports no change when the selection is identical.
    input.value = '';
    this.uploading.set(true);
    this.seriesApi.uploadIssueFile(publicId, lang, file).subscribe({
      next: (issue) => {
        this.uploading.set(false);
        const name = issue.descs.find((desc) => desc.lang === lang)?.fileName;
        this.fileNames.update((current) => ({ ...current, [lang]: name ?? file.name }));
        this.notifications.success('admin.publications.issue.fileUploaded');
        // The list behind the drawer shows the file, so it is now stale.
        this.saved.emit();
      },
      error: (response: unknown) => {
        this.uploading.set(false);
        this.notifications.error(
          publicationErrorKey(response, 'admin.publications.oneOff.detail.uploadFailed'),
        );
      },
    });
  }

  // ----------------------------------------------------------- the rules

  /**
   * The last answer from `/validate`. `undefined` = not asked since the last edit.
   *
   * Reset by the EDIT rather than by each setter, so a refusal cannot stay on
   * screen under the field that has just been corrected — including the two
   * controls that write straight into the form.
   */
  protected readonly validation = linkedSignal<
    readonly unknown[],
    readonly SeriesFieldError[] | undefined
  >({
    source: computed(() => [
      this.value(),
      this.descs(),
      this.languages(),
      this.criteria(),
      this.reportParams(),
      this.availableDomainIds(),
    ]),
    computation: () => undefined,
  });

  /**
   * Ask the server whether this would be accepted, without saving it.
   *
   * The same endpoint the series form uses, running the same code a save runs —
   * against the body being edited rather than against what is stored, which is
   * the whole point of asking before pressing Save.
   */
  protected validate(): void {
    this.validating.set(true);
    this.seriesApi.validateSeries(this.payload()).subscribe({
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

  /**
   * The name of the field a rule refused, as the screen calls it.
   *
   * The wire path (`descs.da.name`) is not a thing an admin has seen, so it is
   * resolved to the label above the control and the language it belongs to.
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
      this.language.modelLanguages.find((option) => option.value === label.lang)?.label ??
      label.lang;
    return `${name} (${language})`;
  }

  /**
   * Why a rule refused, with its id kept beside it.
   *
   * The id alone is the server's name for a rule and not a reason anybody can
   * act on. A rule with no sentence yet falls back to the generic line, which
   * already carries the id — so it is never lost and never printed twice.
   * Worded by the section's one {@link seriesRuleText}.
   */
  protected ruleText(rule: string): string {
    return seriesRuleText((key, params) => this.transloco.translate(key, params), rule);
  }

  protected setField<K extends keyof SeriesFormModel>(key: K, value: SeriesFormModel[K]): void {
    this.value.update((current) => {
      const next = { ...current, [key]: value };
      // The sharing default follows the content mode WHILE the publication is
      // being created, and only then: a document that exists to be cited starts
      // available everywhere, a generated one starts closed. On an existing
      // publication the stored answer stands — somebody chose it.
      return key === 'contentMode' && this.model()?.mode === 'add'
        ? { ...next, availability: defaultAvailability(next.contentMode) }
        : next;
    });
  }

  protected setName(event: { lang: string; value: string }): void {
    this.setDesc(event.lang, 'name', event.value);
  }

  protected setReferenceFormat(event: { lang: string; value: string }): void {
    this.setDesc(event.lang, 'messageReferenceFormat', event.value);
  }

  protected setFileNamePattern(event: { lang: string; value: string }): void {
    this.setDesc(event.lang, 'fileNamePattern', event.value);
  }

  private setDesc(
    lang: string,
    key: 'name' | 'messageReferenceFormat' | 'fileNamePattern',
    value: string,
  ): void {
    this.descs.update((current) => ({
      ...current,
      [lang]: {
        ...(current[lang] ?? {
          name: '',
          nameSuggestionPattern: '',
          fileNamePattern: '',
          messageReferenceFormat: '',
          linkPattern: '',
        }),
        [key]: value,
      },
    }));
  }

  /** Blank clears it: the sort order is optional and 0 is a real position. */
  protected setSortOrder(raw: string): void {
    const trimmed = raw.trim();
    this.setField('sortOrder', trimmed === '' ? undefined : Number(trimmed));
  }

  protected setLink(event: { lang: string; value: string }): void {
    this.links.update((current) => ({ ...current, [event.lang]: event.value }));
  }

  protected onCriteriaChange(criteria: IssueCriteria | undefined): void {
    this.criteria.set(criteria);
  }

  protected setReportParam(index: number, key: string, value: string): void {
    this.reportParams.update((rows) => rows.map((row, i) => (i === index ? { key, value } : row)));
  }

  protected addReportParam(): void {
    this.reportParams.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeReportParam(index: number): void {
    this.reportParams.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected save(): void {
    const model = this.model();
    if (!model || !this.canSave()) {
      return;
    }
    this.send(model);
  }

  /** The series as it would be sent, revision included. */
  private payload(): PublicationSeries {
    return buildSeriesPayload(
      this.base(),
      this.value(),
      this.descs(),
      this.languages(),
      this.criteria(),
      this.reportParams(),
      this.availableDomainIds(),
    );
  }

  private send(model: OneOffEditorModel): void {
    const series = this.payload();

    const request: OneOffRequest = {
      series,
      // Carried back as it stands, never changed from here: taking a publication
      // off the public list retires the issue behind it and has to record why,
      // and that is the row menu's and the publication page's action.
      active: this.carriedTransition(),
      // A ROW PER DECLARED LANGUAGE, blank ones included. A link the admin
      // cleared has to reach the server as a cleared link: omitting the row says
      // "unchanged", and the address stays public with nothing on this screen
      // showing it.
      links: this.linkBacked()
        ? this.languages().map((lang) => {
            const value = (this.links()[lang] ?? '').trim();
            return { lang, value: value === '' ? null : value };
          })
        : [],
    };

    this.saving.set(true);
    const call =
      model.mode === 'edit'
        ? this.api.update(model.publication.series.seriesId, request)
        : this.api.create(request);

    call.subscribe({
      next: (result) => {
        this.saving.set(false);
        // An uploaded publication is created but NOT released: it has no bytes
        // yet, and saying so is the difference between "it worked" and an admin
        // wondering why the publication is still a draft. Read off `publishable`
        // — the server's own answer to "is it still waiting for its file" —
        // rather than off the status, which is absent from a server that does
        // not report it and would then report every save as a draft.
        if (
          this.carriedTransition() &&
          !result.publishable &&
          result.series.contentMode === 'UPLOADED_FILE'
        ) {
          this.notifications.info('admin.publications.oneOff.toast.savedNeedsFile');
        } else {
          this.notifications.success('admin.publications.oneOff.toast.saved');
        }
        // Saved: there is nothing left to discard, so the close must not be
        // gated on the values that were just sent.
        this.baseline.set(this.snapshot());
        this.saved.emit();
        this.closed.emit();
      },
      error: (response: unknown) => {
        this.saving.set(false);
        const stale = publicationErrorCode(response) === STALE_VERSION;
        // Not the shared "it has been reloaded" sentence: this drawer does not
        // show the other change, and saving again overwrites it.
        const key = stale
          ? STALE_NOT_SHOWN_KEY
          : publicationErrorKey(response, 'admin.publications.oneOff.toast.saveError');
        // Composed against a revision somebody else has moved on. The save
        // changed nothing, so the base is re-read under the typed values (the
        // other admin's change is NOT shown): the next press then carries the
        // revision the server will accept, keeps the other admin's change in
        // every field this drawer is not about, and writes the typed values over
        // it in every field this drawer owns (last write wins).
        if (stale && model.mode === 'edit') {
          this.seriesApi.getSeries(model.publication.series.seriesId).subscribe({
            next: (fresh) => this.base.set(fresh),
            // Named once, by the toast below. A second message about a read
            // nobody asked for says nothing an admin can act on.
            error: () => undefined,
          });
        }
        this.notifications.error(key);
      },
    });
  }
}
