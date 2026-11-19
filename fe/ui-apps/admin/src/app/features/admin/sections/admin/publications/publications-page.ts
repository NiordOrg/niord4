import {
  DOCUMENT,
  Component,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { map, of } from 'rxjs';

import { ApiUrlResolver } from '../../../../../core/config/absolute-api-url';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import {
  ExpectedDate,
  type ExpectedDateFormat,
} from '../../../../../shared/components/expected-date/expected-date';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { listRead } from '../../../../../shared/utils/list-read';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import {
  RowLinkAnchorDirective,
  RowLinkDirective,
} from '../../../../../shared/directives/row-link.directive';
import { nowTick } from '../../../../../shared/utils/now-tick';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import {
  CONTENT_MODES,
  PublicationSeriesApiService,
  type ContentMode,
  type IssueTimelineRow,
  type PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import {
  OneOffApiService,
  type OneOffPublication,
} from '../../../services/api/one-off-api.service';
import {
  PublicationIssueDraftDialog,
  type IssueDraftDecision,
  type IssueDraftSeed,
} from './issue-draft-dialog';
import { IssueTimelineStrip, gapSpanText } from './issue-timeline';
import { OneOffEditor, type OneOffEditorModel } from './one-off-editor';
import { localizedNames, matchesFilter } from './publication-filter';
import { PublicationListFilter } from './publication-list-filter';
import { publicationErrorKey } from './publication-errors';
import { availabilityKey, seriesBadgeClass, seriesStatusKey } from './publication-status';
import {
  oneOffStatus,
  oneOffStatusClass,
  oneOffStatusKey,
  publicationVisibility,
  type PublicationVisibility,
} from './publication-visibility';
import { PublicationSeriesEditor, type SeriesEditorModel } from './series-editor';
import { PublicationsFacade } from './publications.facade';
import { PublicationReasonDialog } from './reason-dialog';
import { SeriesDetail } from './series-detail';
import { PublicationTransferTargets } from './transfer-owner';
import { PublicationTransferDialog, type TransferOwnerDecision } from './transfer-owner-dialog';

/**
 * An address, shortened to the two parts that identify the document.
 *
 * The scheme is dropped — every address in the table has one and none of them
 * tells the reader anything — and the middle of the path is replaced by an
 * ellipsis, keeping the host and the last segment. `https://a.test/x/y/doc.pdf`
 * reads as `a.test/…/doc.pdf`.
 *
 * Deliberately string work rather than `URL` parsing: the value comes off the
 * wire and a relative or malformed one has to shorten to SOMETHING rather than
 * throw, and splitting on `/` keeps a query string attached to the segment it
 * belongs to. A trailing slash leaves no last segment, so empty tail parts are
 * dropped before the host and the document are picked.
 */
function shortenAddress(address: string): string {
  const parts = address
    .replace(/^[a-z][a-z0-9+.-]*:\/\//i, '')
    .split('/')
    .filter((part) => part !== '');
  if (parts.length <= 2) {
    return parts.join('/');
  }
  return `${parts[0]}/…/${parts[parts.length - 1]}`;
}

/** What the one-off rows are read for. */
interface OneOffsKey {
  readonly tick: number;
  readonly domain: string | undefined;
  readonly shown: boolean;
}

/**
 * Publications: the series list, and one series' detail.
 *
 * Master and detail share a route rather than being two sections, which is the
 * convention the admin area already uses (`${path}/:${paramSegment}` resolves to
 * the same component). The selected series is held in a signal so the list is
 * not refetched when moving between them, and mirrored into the URL so a deep
 * link and a browser Back both land where the reader expects.
 *
 * ONE HEADER PER LEVEL. This screen renders the section title on the dashboard
 * and nothing else: a series carries its own header cluster, and an issue opened
 * inside one carries a third. Stacking them produced two back links and two
 * action clusters describing different things, so the description is dropped as
 * soon as a series is open and the back link, the settings button and the copy
 * action are the series screen's — raised back to here as outputs, because which
 * series is selected and which drawer is open are this page's state.
 *
 * The list groups are answers to different questions, not a filter over one
 * list: what this desk is running, what it ran once, and what it has finished.
 */
@Component({
  selector: 'app-publications-page',
  imports: [
    AdminSectionPage,
    Confirm,
    CopyLinkButton,
    ExpectedDate,
    Icon,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    SeriesDetail,
    IssueTimelineStrip,
    PublicationIssueDraftDialog,
    PublicationSeriesEditor,
    PublicationTransferDialog,
    OneOffEditor,
    PublicationListFilter,
    Menu,
    MenuTrigger,
    JsonImportDialog,
    PublicationReasonDialog,
    RowLinkDirective,
    RowLinkAnchorDirective,
    TranslocoPipe,
  ],
  providers: [PublicationsFacade, ConfirmService],
  templateUrl: './publications-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class PublicationsPage {
  protected readonly facade = inject(PublicationsFacade);
  protected readonly api = inject(PublicationSeriesApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);
  private readonly importDialog = viewChild.required(JsonImportDialog);
  private readonly oneOffApi = inject(OneOffApiService);
  private readonly apiUrls = inject(ApiUrlResolver);
  private readonly domainFacade = inject(DomainFacade);
  private readonly transferTargets = inject(PublicationTransferTargets);

  /**
   * The series the address names, read live rather than once on entry.
   *
   * The list and the series are sibling routes, so moving between them rebuilds
   * this component — but moving from one series to ANOTHER only changes the
   * param, and the router keeps the component. A link from a compiled issue to
   * one of the weeks it was compiled from is exactly that move, and a value read
   * from the snapshot would leave the old series on screen under the new address.
   */
  private readonly routedSeriesId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('seriesId') ?? undefined)),
    { requireSync: true },
  );

  /**
   * The selected series: the address, with this component's own moves applied
   * the moment they are made.
   *
   * Selecting a card sets it before the navigation settles, so the screen turns
   * over at once; the address then echoes the same id back. A navigation that
   * arrives from anywhere else resets it to what the address says.
   *
   * It is also the discriminator between the two screens this component holds,
   * and therefore what everything the DASHBOARD fetches is gated on.
   */
  protected readonly selectedSeriesId = linkedSignal(() => this.routedSeriesId());

  /**
   * The clicked row, handed to the series screen so its header is drawn at once.
   *
   * Carried by the navigation that opened this screen rather than looked up in
   * the list: the list is not fetched on the series branch at all, and the two
   * are separate route objects, so arriving here means this component was built
   * from scratch with nothing but the URL — and the row was in hand at the
   * moment of the click.
   *
   * It is only a seed: the series screen replaces it the moment its own fetch
   * answers. A deep link carries nothing, which is right — nobody clicked a row
   * — and the header then waits out the one request behind it.
   */
  protected readonly selectedSeries = (
    this.router.currentNavigation()?.extras.state as
      | { readonly series?: PublicationSeries }
      | undefined
  )?.series;

  constructor() {
    // The dashboard's data is fetched only while the dashboard is what is on
    // screen. Master and detail share this component, so nothing tears the
    // list, the strips or the one-off table down on the way to a series.
    //
    // Said HERE, straight away, and not only from the effect below. The facade's
    // reads are driven by effects registered while it was being injected, which
    // is before any line of this constructor runs, and they emit on the first
    // flush -- a gate that arrived on that same flush would arrive after them.
    // Every entry straight onto a series (a click from a card, a reload, a link
    // into an issue) would then have issued the list and both strip requests and
    // cancelled them a moment later, which spares the reader nothing: the server
    // has already started on all three.
    this.facade.setDashboardShown(this.selectedSeriesId() === undefined);

    // And kept in step afterwards, for the moves between the two views that
    // happen without this component being rebuilt.
    effect(() => {
      const onDashboard = this.selectedSeriesId() === undefined;
      untracked(() => this.facade.setDashboardShown(onDashboard));
    });
  }

  /** Open when a one-off is being created or edited. */
  protected readonly oneOffModel = signal<OneOffEditorModel | undefined>(undefined);

  /**
   * Whether the one-off editor exists at all.
   *
   * The editor reads the lists its pickers offer when it is CREATED, so it is
   * created when somebody opens it and torn down once it is off screen again —
   * nothing about a publication is held between two openings. The flag is a
   * second step because the two moments differ: the model closes the drawer, and
   * the drawer takes a moment to slide out, so the component that draws it has
   * to outlive the close by exactly that much.
   */
  protected readonly oneOffMounted = signal(false);

  /** Create the one-off editor if need be, and open it on `model`. */
  private showOneOffEditor(model: OneOffEditorModel): void {
    this.oneOffMounted.set(true);
    this.oneOffModel.set(model);
  }

  /**
   * Read from a moving clock: whether a period is still open, has not opened yet
   * or has run out is answered against now, and this is a dashboard an admin
   * leaves open all day.
   */
  private readonly now = nowTick();

  private readonly refreshOneOffs = signal(0);

  /**
   * The one-off rows, fetched separately from the series list.
   *
   * A one-off row is the series AND its single issue -- the type, the file or
   * link, and whether it is actually live -- and the series list carries none
   * of the issue half. Deriving "active" from the series status alone would
   * paint an ACTIVE series with an unpublished issue as live.
   *
   * Narrowed to the publications this desk OWNS, like the series list beside it:
   * a one-off another domain has shared with this one is something to cite from
   * the message editor, not a row to edit here.
   */
  protected readonly oneOffsRead = listRead<OneOffPublication, OneOffsKey>(
    toObservable(
      computed(() => ({
        tick: this.refreshOneOffs(),
        domain: this.domainFacade.sessionDomain()?.domainId,
        // The table lives on the dashboard alone. This page holds a second
        // screen inside itself, and a series or an issue opened on it read
        // nothing from here.
        shown: this.selectedSeriesId() === undefined,
      })),
    ),
    // A failed read is said in the table's place, with a retry, not shown as no one-offs.
    ({ domain, shown }) => (shown ? this.oneOffApi.list(domain) : of([])),
  );
  protected readonly oneOffs = this.oneOffsRead.value;

  /** The desk being worked at, named by the section blurb and the one-off empty state. */
  protected readonly sessionDomainName = computed(() => {
    const domain = this.domainFacade.sessionDomain();
    return domain?.name ?? domain?.domainId ?? '';
  });

  private readonly exportLabel = translateSignal('admin.publications.menu.export');
  private readonly importLabel = translateSignal('admin.publications.menu.import');
  private readonly newSeriesLabel = translateSignal('admin.publications.series.editor.newSeries');
  private readonly newOneOffLabel = translateSignal('admin.publications.series.editor.newOneOff');
  private readonly editLabel = translateSignal('admin.publications.oneOff.edit');
  private readonly moveLabel = translateSignal('admin.publications.transfer.action');
  private readonly withdrawLabel = translateSignal('admin.publications.oneOff.withdraw');

  /**
   * The one create menu, for the two things this section makes.
   *
   * One trigger rather than two buttons: a series and a one-off are the same
   * decision taken two ways, and a toolbar that spends two primary buttons on it
   * leaves nothing to distinguish the action a reader actually came for.
   */
  protected readonly newItems = computed<MenuItem[]>(() => [
    { label: this.newSeriesLabel(), command: () => this.createSeries() },
    { label: this.newOneOffLabel(), command: () => this.createOneOff() },
  ]);

  /**
   * The cog menu: export the series DEFINITIONS, or import them back.
   *
   * Definitions and nothing else. The payload is the series list -- no issues,
   * no categories -- so the file is a way to move a configuration between
   * installations, NOT a backup of what has been published. An admin who takes
   * it as one before a risky change gets back every series and not one issue.
   *
   * The import runs as a background batch job like every other admin import and
   * never activates anything: a file written against another installation is
   * exactly the case where the activation rules were checked against different
   * categories, domains and reports. It does not merge -- it rewrites each named
   * series field for field -- so it is confirmed before the file picker opens.
   */
  protected readonly cogItems = computed<MenuItem[]>(() => [
    {
      label: this.exportLabel(),
      command: () => this.exportSeries(),
    },
    { label: this.importLabel(), command: () => this.confirmImport() },
  ]);

  /**
   * Ask before opening the picker, naming what the file overwrites.
   *
   * The upload is not additive: every series the file names is rewritten field
   * for field, keeping only its status, and a series whose category is missing
   * here is dropped with nothing but a line in the batch log. None of that is
   * visible from a file picker, and by the time the job has run it is done.
   */
  protected confirmImport(): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.publications.import.confirm.header'),
      message: this.transloco.translate('admin.publications.import.confirm.message'),
      acceptLabel: this.transloco.translate('admin.publications.import.confirm.accept'),
      accept: () => this.importDialog().open(),
    });
  }

  /**
   * The one-line description, which belongs to the dashboard alone.
   *
   * A series and an issue each say what they are in their own header, so keeping
   * the section's blurb above them describes the screen the reader has left.
   */
  protected readonly pageDescriptionKey = computed(() =>
    this.selectedSeriesId() === undefined ? 'admin.publications.intro' : undefined,
  );

  // ------------------------------------------------------------- the filter

  /** What `?seriesFilter=` says, read live: Back and Forward move it without rebuilding the page. */
  private readonly routedSeriesFilter = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('seriesFilter') ?? '')),
    { requireSync: true },
  );

  /**
   * What the reader is looking for, held in the address bar.
   *
   * Linked to the query parameter, as the series screen's archive filter
   * is. The router keeps this page when only the query changes, so a filter
   * read once from the snapshot stayed on screen after Back had moved the
   * address, and a reload then showed another list. The writes below are this
   * component's own and the address echoes them back unchanged; a navigation
   * from anywhere else (Back, Forward, a link) resets it to what the address
   * says. Keeping it in the URL is what makes a filtered list survive a reload
   * and travel in a link — the estate is ~60 series plus a ~20-row archive, and
   * "the one I was looking at" is the whole question.
   */
  protected readonly seriesFilter = linkedSignal(() => this.routedSeriesFilter());

  /**
   * Take a settled filter, and mirror it into the address bar.
   *
   * Merged rather than replacing the query, so a parameter another screen owns
   * is not dropped by typing here; an empty filter removes the parameter
   * entirely rather than leaving `?seriesFilter=` behind.
   *
   * One filter is one history entry. The box settles at every pause in the
   * typing, so refining a filter the address already carries replaces its entry
   * rather than adding one, and Back does not step through each pause. A new
   * filter, and clearing one, add an entry, so Back returns to the list as it
   * was before.
   */
  protected setSeriesFilter(value: string): void {
    const refining = value !== '' && this.routedSeriesFilter() !== '';
    this.seriesFilter.set(value);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { seriesFilter: value === '' ? null : value },
      queryParamsHandling: 'merge',
      replaceUrl: refining,
    });
  }

  /** Whether anything is being filtered at all — the empty box is not a filter. */
  protected readonly filtering = computed(() => this.seriesFilter().trim() !== '');

  /**
   * The groups, each narrowed by the one box.
   *
   * One filter over every group rather than one per section: the reader is
   * looking for a publication and does not know in advance which group it is
   * filed under.
   */
  protected readonly cadenced = computed(() => this.matching(this.facade.cadenced()));
  protected readonly retired = computed(() => this.matching(this.facade.retired()));

  protected readonly filteredOneOffs = computed(() => {
    const query = this.seriesFilter();
    return this.oneOffs().filter((publication) =>
      matchesFilter(localizedNames(publication.series.descs), query),
    );
  });

  /**
   * How the one-off table is ordered: the server's order, or by period start.
   *
   * Three states rather than two, because the server's order is an answer of its
   * own — it is the order the estate is administered in, and a table that could
   * only be sorted by date would have no way back to it.
   */
  protected readonly oneOffOrder = signal<'none' | 'desc' | 'asc'>('none');

  /** `aria-sort` for the column header, in the vocabulary the attribute takes. */
  protected readonly oneOffOrderAria = computed(() => {
    const order = this.oneOffOrder();
    return order === 'none' ? 'none' : order === 'asc' ? 'ascending' : 'descending';
  });

  /**
   * Newest first, then oldest first, then back to the server's order.
   *
   * The most recent period is what an admin looking at this column is after —
   * "what went public lately" — so it is the first press rather than the second.
   */
  protected toggleOneOffOrder(): void {
    this.oneOffOrder.update((order) =>
      order === 'none' ? 'desc' : order === 'desc' ? 'asc' : 'none',
    );
  }

  /**
   * The public line for every visible row, built once per list.
   *
   * A map rather than a method called from the template, for the reason the row
   * menus are: a method would hand the translate pipe a fresh params object on
   * every change-detection pass, so the cell would be re-translated for a row
   * nothing had touched.
   */
  protected readonly oneOffLines = computed<ReadonlyMap<string, PublicationVisibility>>(() => {
    const now = this.now();
    return new Map(
      this.filteredOneOffs().map((publication) => [
        publication.series.seriesId,
        publicationVisibility(
          {
            issueStatus: publication.issueStatus,
            publicFrom: publication.publicFrom,
            publicTo: publication.publicTo,
            categoryPublish: publication.categoryPublish,
            now,
          },
          (value) => this.date(value),
        ),
      ]),
    );
  });

  /**
   * The rows as the table draws them, ordered by the start of the public period.
   *
   * A publication with no start sorts LAST in both directions rather than at one
   * end of the dates: it is not early or late, it has no period — and letting it
   * lead the ascending order would bury the rows the sort was asked for.
   */
  protected readonly sortedOneOffs = computed(() => {
    const rows = this.filteredOneOffs();
    const order = this.oneOffOrder();
    const lines = this.oneOffLines();
    if (order === 'none') {
      return rows;
    }
    const direction = order === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const left = lines.get(a.series.seriesId)?.from;
      const right = lines.get(b.series.seriesId)?.from;
      if (left === undefined || right === undefined) {
        return left === right ? 0 : left === undefined ? 1 : -1;
      }
      return (left - right) * direction;
    });
  });

  private matching(series: readonly PublicationSeries[]): readonly PublicationSeries[] {
    const query = this.seriesFilter();
    return series.filter((entry) => matchesFilter(localizedNames(entry.descs), query));
  }

  /**
   * Open a series.
   *
   * `[seriesId]`, not `['..', seriesId]`. The list and the detail are SIBLING
   * routes -- `publications` and `publications/:seriesId` -- so from the list `..`
   * pops to `/admin` and the result is `/admin/<seriesId>`, which matches nothing
   * and redirects to the app root. Selecting a series lost the page entirely, and
   * every test reached the detail by deep link, so nothing noticed.
   *
   * Only ever called from the list: the detail branch does not render the cards.
   *
   * The clicked ROW travels with the navigation. The series branch fetches no
   * list, so the row cannot be looked up once the page has been rebuilt — and
   * the name and the status are what the header is drawn from while the series'
   * own request is in flight.
   */
  protected select(series: PublicationSeries): void {
    this.selectedSeriesId.set(series.seriesId);
    void this.router.navigate([series.seriesId], { relativeTo: this.route, state: { series } });
  }

  /**
   * Open one period's issue straight from the dashboard.
   *
   * The series is a sibling route and the open issue is a query parameter on it,
   * which is how the detail screen already carries one — so a cell links to
   * exactly the URL an admin would reach by opening the series and clicking the
   * row, and Back from there lands on the dashboard rather than inside the
   * series.
   *
   * The clicked row travels along, exactly as it does from a card: closing the
   * issue lands on the series screen, whose header is drawn from the seed until
   * its own fetch answers.
   */
  protected openTimelineIssue(series: PublicationSeries, publicId: string): void {
    this.selectedSeriesId.set(series.seriesId);
    void this.router.navigate([series.seriesId], {
      relativeTo: this.route,
      queryParams: { issue: publicId },
      state: { series },
    });
  }

  protected clearSelection(): void {
    this.selectedSeriesId.set(undefined);
    void this.router.navigate(['..'], { relativeTo: this.route });
  }

  /**
   * The series' name in the active UI language, falling back to whatever it has.
   *
   * A series always has at least one desc, but not necessarily one in the
   * language being read — an imported series carries the languages the archive
   * had for it. Falling back beats rendering a blank row, because a blank row is
   * indistinguishable from a broken one.
   */
  protected name(series: PublicationSeries): string {
    const lang = this.language.selectedLanguage();
    const match = series.descs.find((d) => d.lang === lang) ?? series.descs[0];
    return match?.name ?? series.seriesId;
  }

  /**
   * The label key for a series' status.
   *
   * A stored status is the database's word for the state and reads the same in
   * every language; the key space is closed and covered by a spec, so a status
   * the vocabulary does not know renders as "unknown" rather than as a key.
   */
  protected statusKey(series: PublicationSeries): string {
    return seriesStatusKey(series.status);
  }

  // -------------------------------------------------------- the series card

  /**
   * The tint a series' badge wears, or `''` for the series that get none.
   *
   * ACTIVE is the ordinary case and carries no badge at all: badging every card
   * with the state they are nearly all in spends the reader's attention on the
   * one fact that never needs it.
   */
  protected badgeClass(series: PublicationSeries): string {
    return seriesBadgeClass(series.status);
  }

  /** The label beside that tint. Only DRAFT and RETIRED have one. */
  protected badgeKey(series: PublicationSeries): string {
    return series.status === 'DRAFT'
      ? 'admin.publications.badge.draft'
      : 'admin.publications.badge.retired';
  }

  /** A series with no calendar: its card summarises an archive instead of a strip. */
  protected unscheduled(series: PublicationSeries): boolean {
    return series.cadence === 'NONE';
  }

  /**
   * The issue being worked on, if there is one.
   *
   * Taken off the strip the page already has rather than asked for per card:
   * sixty cards would be sixty requests, and the strip already carries the one
   * row that matters here.
   */
  protected openIssueRow(series: PublicationSeries): IssueTimelineRow | undefined {
    return this.facade.timeline(series.seriesId)?.rows.find((row) => row.computedStatus === 'OPEN');
  }

  /** The newest issue that actually exists — never a synthesized period. */
  protected latestIssueRow(series: PublicationSeries): IssueTimelineRow | undefined {
    return this.facade.timeline(series.seriesId)?.rows.find((row) => !!row.publicId);
  }

  /**
   * Whether the recent-issues read actually answered for this series.
   *
   * The card says "no issues" only where it does: an unanswered series is one
   * whose archive the page does not know, which is a different fact from an
   * archive that is empty — and the read is unanswered for every card between
   * the first paint and the response, and for any series the read did not pick
   * up. Saying "no issues" there states something about a publication that may
   * hold hundreds of them.
   */
  protected archiveAnswered(series: PublicationSeries): boolean {
    return this.facade.timeline(series.seriesId) !== undefined;
  }

  /**
   * How many issues an unscheduled series has released.
   *
   * Counted over the archive read, which asks for far more issues than any
   * publication in the estate has — so this is the whole count and not the head
   * of one. A cadenced series is not counted this way at all: its strip is eight
   * PERIODS, and calling that a total would report a five-hundred-issue weekly
   * as having eight.
   */
  protected issueCount(series: PublicationSeries): number {
    return (this.facade.timeline(series.seriesId)?.rows ?? []).filter((row) => !!row.publicId)
      .length;
  }

  /**
   * The count sentence, in the form that fits the number it carries.
   *
   * "1 issue(s)" is the shape of a string nobody wrote for the number it ended
   * up with. Two keys and a choice, rather than one key that is wrong on every
   * publication that has released exactly once.
   */
  protected issueCountKey(count: number): string {
    return count === 1
      ? 'admin.publications.card.issueCountOne'
      : 'admin.publications.card.issueCount';
  }

  /** The member count on a card, in the singular when it is one. */
  protected membersKey(count: number | undefined): string {
    return count === 1 ? 'admin.publications.card.membersOne' : 'admin.publications.card.members';
  }

  /**
   * How much of the expected cut-off the card shows.
   *
   * A cut-off inside the coming week is planned around by weekday — "Thursday"
   * is what an editor works to. Further out that stops being true: a month or a
   * year away, the weekday is noise and the date is the fact, so anything but a
   * daily or weekly series names the day of the month instead.
   */
  protected cutoffFormat(series: PublicationSeries): ExpectedDateFormat {
    return series.cadence === 'WEEKLY' || series.cadence === 'DAILY' ? 'weekday' : 'date';
  }

  /**
   * Whether the open issue can say how many messages are in it.
   *
   * A publication that resolves nothing carries no count, which is not a count
   * of zero — that would claim the period resolved and came back empty. Zero IS
   * a finding, so the test is for absence rather than for truthiness.
   */
  protected hasMemberCount(row: IssueTimelineRow | undefined): boolean {
    // `??` rather than a comparison, because the wire sends null where the type
    // says undefined: both mean "no count was taken".
    return (row?.memberCount ?? null) !== null;
  }

  /**
   * The stretch of periods that produced nothing, or `undefined`.
   *
   * Said beside the latest issue rather than instead of it: "the last one was
   * 2018, and 2019 to 2025 produced nothing" is the whole state of a series that
   * has stopped, and neither half says it alone.
   */
  protected gapSpan(series: PublicationSeries): string | undefined {
    return gapSpanText(this.facade.timeline(series.seriesId), series);
  }

  /**
   * What a cadence-less series' issues actually ARE, in one line.
   *
   * A series with no calendar has no period to be waiting for, so "no open
   * issue" leaves the reader with nothing: what decides whether that is normal
   * is whether the next edition is a PDF somebody uploads, an address somebody
   * points at, or nothing at all. `undefined` for a content mode this build does
   * not know, rather than a sentence claiming the wrong one.
   */
  protected contentModeKey(series: PublicationSeries): string | undefined {
    const mode = series.contentMode;
    return mode !== undefined && CONTENT_MODES.includes(mode as ContentMode)
      ? 'admin.publications.card.contentMode.' + mode
      : undefined;
  }

  /** A stamped date on the card, in the reader's locale. */
  protected date(value: number | undefined): string {
    return value ? this.localeFormat.formatDate(value) : '';
  }

  /**
   * Whether opening the next issue by hand makes sense on this card.
   *
   * The status half is a rule the endpoint enforces — a draft series has no
   * issues to open. The open-issue half is an affordance: a cadenced series
   * opens its own successor when the current issue publishes, so a button
   * standing beside an open issue invites a duplicate of one that is coming.
   */
  protected canCreateIssue(series: PublicationSeries): boolean {
    return series.status === 'ACTIVE' && this.openIssueRow(series) === undefined;
  }

  // ------------------------------------------------------ the one-off table

  /**
   * The name in the active language, falling back to whatever the row has.
   *
   * A fallback rather than a blank: a publication named only in Danish is still
   * a publication, and an empty first column is unreadable as a list.
   */
  protected oneOffName(publication: OneOffPublication): string {
    const active = this.language.selectedLanguage();
    const descs = publication.series.descs ?? [];
    const match = descs.find((d) => d.lang === active && d.name);
    return match?.name ?? descs.find((d) => d.name)?.name ?? publication.series.seriesId;
  }

  /**
   * Where the publication stands: draft, published, withdrawn.
   *
   * The issue's own status under the names the section uses for it — the stored
   * words are the database's, and `Open` over a publication being prepared reads
   * as something somebody left half-done.
   */
  protected oneOffStatusKey(publication: OneOffPublication): string {
    return oneOffStatusKey(oneOffStatus(publication.issueStatus));
  }

  protected oneOffStatusClass(publication: OneOffPublication): string {
    return oneOffStatusClass(oneOffStatus(publication.issueStatus));
  }

  /** What kind of thing the publication is, in one word. */
  protected oneOffTypeKey(publication: OneOffPublication): string {
    switch (publication.series.contentMode) {
      case 'EXTERNAL_LINK':
        return 'admin.publications.oneOff.typeLink';
      case 'NONE':
        return 'admin.publications.oneOff.typeNone';
      default:
        return 'admin.publications.oneOff.typeUploaded';
    }
  }

  /**
   * Who may cite the publication, as the column reads it.
   *
   * An absent value is read as every domain, which is what a publication written
   * before the field existed actually behaved as — and what the citation-only
   * reference publications need. Reading it as "unknown" would put a shrug in a
   * column whose whole job is to answer the question.
   */
  protected oneOffAvailabilityKey(publication: OneOffPublication): string {
    const availability = publication.series.availability ?? 'ALL_DOMAINS';
    return availability === 'SELECTED_DOMAINS'
      ? 'admin.publications.oneOff.availabilityCount'
      : availabilityKey(availability);
  }

  /** The owner plus the desks it is shared with: everybody who may cite it. */
  protected oneOffAvailabilityParams(publication: OneOffPublication): { count: number } {
    return { count: (publication.series.availableDomainIds?.length ?? 0) + 1 };
  }

  /**
   * What the cell READS as: the address, or the uploaded file's name.
   *
   * Falls back to the address where a stored document has no file name of its
   * own, so a row that plainly HAS something behind it is never drawn as the
   * dash that means there is nothing.
   */
  protected oneOffTarget(publication: OneOffPublication): string {
    const from =
      publication.series.contentMode === 'EXTERNAL_LINK'
        ? publication.links
        : publication.fileNames;
    return this.inReadersLanguage(from) || this.oneOffAddress(publication);
  }

  /**
   * What the cell SHOWS, once an address is too long to read as a name.
   *
   * A file keeps its name — that is already the short thing a reader knows the
   * document by. An address is not: a repository URL runs past the column and
   * the part that identifies the document is the end of it, so the scheme and
   * the middle of the path are dropped and what is left is the host and the
   * document. The `href` is untouched, so the elision costs nothing but the
   * reading.
   */
  protected oneOffTargetLabel(publication: OneOffPublication): string {
    const target = this.oneOffTarget(publication);
    return this.oneOffIsLink(publication) ? shortenAddress(target) : target;
  }

  /**
   * Where the cell POINTS: the address the publication is actually fetched from.
   *
   * Read off the row's addresses whatever the content mode, because a stored
   * document is served over an address too — the row carries the repository URL
   * beside the file name, and it is the same one the publication's own screen
   * offers. `''` where the row carries none, which is the one case the file is
   * shown by name alone: a link that resolves to nothing is worse than a name.
   *
   * Resolved against the backend's own origin, because a repository address is
   * reported root-relative and the app is not always served from the host that
   * answers it — an external link, which already names its host, is untouched.
   */
  protected oneOffAddress(publication: OneOffPublication): string {
    return this.apiUrls.absoluteApiUrl(this.inReadersLanguage(publication.links));
  }

  /** Whether the thing behind the row is an address of its own, for the glyph. */
  protected oneOffIsLink(publication: OneOffPublication): boolean {
    return (
      publication.series.contentMode === 'EXTERNAL_LINK' && this.oneOffTarget(publication) !== ''
    );
  }

  /**
   * One language's value, falling back to whatever the row has.
   *
   * A publication carrying a file only in Danish is still a publication, and an
   * empty cell is indistinguishable from a row that failed to load.
   */
  private inReadersLanguage(values: readonly { lang: string; value: string | null }[]): string {
    const active = this.language.selectedLanguage();
    return (
      values.find((v) => v.lang === active && v.value)?.value ??
      values.find((v) => v.value)?.value ??
      ''
    );
  }

  /** Edit one from its row. */
  protected editOneOff(publication: OneOffPublication): void {
    this.showOneOffEditor({ mode: 'edit', publication });
  }

  /**
   * Open the publication's own screen.
   *
   * A SIBLING of the series screen, taking the same parameter shape: a one-off
   * is a series holding a single issue, and its detail is the same kind of thing
   * as a series' — a document, where it is public, and what has happened to it.
   */
  protected openOneOff(publication: OneOffPublication): void {
    void this.router.navigate(['one-off', publication.series.seriesId], {
      relativeTo: this.route,
    });
  }

  /**
   * The row menus, built once per list rather than once per change detection.
   *
   * A method called from the template would hand `[model]` a fresh array every
   * pass, which is a new input value on a menu nobody touched.
   */
  protected readonly oneOffMenus = computed<ReadonlyMap<string, MenuItem[]>>(() => {
    const edit = this.editLabel();
    const move = this.moveLabel();
    const withdraw = this.withdrawLabel();
    return new Map(
      this.filteredOneOffs().map((publication) => {
        const items: MenuItem[] = [{ label: edit, command: () => this.editOneOff(publication) }];
        // Offered only where there is somewhere to move it TO: the picker holds
        // the domains this admin administers minus the owner, and an empty one
        // turns a rule into a round trip that comes back refused.
        if (this.transferTargets.targetsFor(publication.series.domainId).length > 0) {
          items.push({ label: move, command: () => this.openTransfer(publication) });
        }
        // Offered from the one state it undoes. A publication still being
        // prepared has not been on the public site to be taken off it, and one
        // already withdrawn is put back from its own page, where the period and
        // the category that decide what happens next are on the screen.
        //
        // The issue is what is withdrawn, so a publication whose issue this
        // payload does not name has nothing for the action to address.
        if (oneOffStatus(publication.issueStatus) === 'PUBLISHED' && publication.issuePublicId) {
          items.push(
            { separator: true },
            {
              label: withdraw,
              styleClass: 'is-destructive',
              command: () => this.openWithdraw(publication),
            },
          );
        }
        return [publication.series.seriesId, items] as const;
      }),
    );
  });

  protected onOneOffSaved(): void {
    this.refreshOneOffs.update((tick) => tick + 1);
    // The series list carries the kind, so it has to be refetched too or the
    // new publication is missing from every count on the screen.
    this.facade.refresh();
  }

  // ----------------------------------------------- moving a one-off's owner

  /** The publication being moved. `undefined` = the dialog is closed. */
  private readonly transferTarget = signal<OneOffPublication | undefined>(undefined);
  protected readonly transferDialogOpen = signal(false);
  protected readonly transferWorking = signal(false);
  protected readonly transferErrorKey = signal<string | undefined>(undefined);
  protected readonly transferErrorDetail = signal<string | undefined>(undefined);

  protected readonly transferOwnerId = computed(
    () => this.transferTarget()?.series.domainId ?? undefined,
  );

  protected openTransfer(publication: OneOffPublication): void {
    this.transferTarget.set(publication);
    this.transferErrorKey.set(undefined);
    this.transferErrorDetail.set(undefined);
    this.transferDialogOpen.set(true);
  }

  /**
   * Move the publication, and stop listing it here.
   *
   * The owner decides which desk administers it, so a successful move takes the
   * row out of this table — refetching is what makes that happen, rather than
   * this screen deciding to drop a row the server may not have moved.
   */
  protected submitTransfer(decision: TransferOwnerDecision): void {
    const publication = this.transferTarget();
    if (!publication || this.transferWorking()) {
      return;
    }
    this.transferWorking.set(true);
    this.transferErrorKey.set(undefined);
    this.transferErrorDetail.set(undefined);

    this.api
      .transferOwner(publication.series.seriesId, {
        domainId: decision.domainId,
        reason: decision.reason,
        version: publication.series.version,
      })
      .subscribe({
        next: () => {
          this.transferWorking.set(false);
          this.transferDialogOpen.set(false);
          this.notifications.success('admin.publications.transfer.done');
          this.onOneOffSaved();
        },
        error: (response: unknown) => {
          this.transferWorking.set(false);
          const key = publicationErrorKey(response, 'admin.publications.transfer.failed');
          this.transferErrorKey.set(
            key === 'admin.publications.transfer.failed' && this.isForbidden(response)
              ? 'admin.publications.transfer.notInDomain'
              : key,
          );
          this.transferErrorDetail.set(this.serverMessage(response));
        },
      });
  }

  // -------------------------------- taking a one-off off the public site

  /** The publication being withdrawn. `undefined` = the dialog is closed. */
  private readonly withdrawTarget = signal<OneOffPublication | undefined>(undefined);
  protected readonly withdrawDialogOpen = signal(false);
  protected readonly withdrawWorking = signal(false);
  protected readonly withdrawErrorKey = signal<string | undefined>(undefined);

  /**
   * Ask why before taking it off the public site.
   *
   * The same question the editor asks for the same transition: it withdraws the
   * issue behind the publication, and the reason is the only part of that
   * decision which survives into the history for whoever reads it next.
   */
  protected openWithdraw(publication: OneOffPublication): void {
    this.withdrawTarget.set(publication);
    this.withdrawErrorKey.set(undefined);
    this.withdrawDialogOpen.set(true);
  }

  /**
   * Take it off the public site, through the issue's own withdrawal.
   *
   * NOT the one-off save. That endpoint reads the status as a transition worked
   * out from whether the publication is live at this instant, and "live" folds
   * in the public period — so a publication whose period starts next January, or
   * ended years ago, already reads as not live and asking the save to take it
   * off compares false with false, changes nothing and answers 200. The dialog
   * would close over a publication that is still published. The issue's retire
   * acts on the status alone and is version-checked against the issue.
   */
  protected submitWithdraw(reason: string): void {
    const publication = this.withdrawTarget();
    const publicId = publication?.issuePublicId;
    if (!publication || !publicId || this.withdrawWorking()) {
      return;
    }
    this.withdrawWorking.set(true);
    this.withdrawErrorKey.set(undefined);

    this.api.retireIssue(publicId, reason, publication.issueVersion).subscribe({
      next: () => {
        this.withdrawWorking.set(false);
        this.withdrawDialogOpen.set(false);
        this.notifications.success('admin.publications.oneOff.toast.saved');
        this.onOneOffSaved();
      },
      error: (response: unknown) => {
        this.withdrawWorking.set(false);
        this.withdrawErrorKey.set(
          publicationErrorKey(response, 'admin.publications.oneOff.toast.saveError'),
        );
      },
    });
  }

  /** Whether a refusal was about permission rather than about the payload. */
  private isForbidden(response: unknown): boolean {
    return (
      typeof response === 'object' &&
      response !== null &&
      (response as { status?: number }).status === 403
    );
  }

  /** The server's own sentence, for the refusals no key can carry the detail of. */
  private serverMessage(response: unknown): string | undefined {
    const error = (response as { error?: { message?: unknown } } | undefined)?.error;
    return typeof error?.message === 'string' ? error.message : undefined;
  }

  // -------------------------------------------------- opening the next issue

  /**
   * The series the draft belongs to, held whole rather than by id.
   *
   * The row is what the navigation after a successful create carries, so the
   * issue screen's Back lands on a series header that is already drawn.
   */
  protected readonly draftSeries = signal<PublicationSeries | undefined>(undefined);
  protected readonly draftSeed = signal<IssueDraftSeed | undefined>(undefined);
  protected readonly draftDialogOpen = signal(false);

  /**
   * Open the next issue by hand, reviewed before it is created.
   *
   * No bounds and no chain: the server derives the period from the newest issue
   * and the cadence, which is the same derivation the create would use. Not
   * recovered — nobody is reconstructing a period that was missed.
   */
  protected newIssue(series: PublicationSeries): void {
    this.draftSeries.set(series);
    this.draftSeed.set({ recovered: false });
    this.draftDialogOpen.set(true);
  }

  /**
   * Create it, and go straight to what was made.
   *
   * The next thing anybody does with a new issue is fill it, and that lives on
   * the issue screen — landing back on a dashboard whose card has quietly
   * changed leaves them to find it again.
   */
  protected submitDraft(decision: IssueDraftDecision): void {
    const series = this.draftSeries();
    if (!series) {
      return;
    }
    this.facade.createIssue(series.seriesId, decision, (issue) => {
      this.draftDialogOpen.set(false);
      this.openTimelineIssue(series, issue.publicId);
    });
  }

  /** `undefined` = the settings drawer is closed. */
  protected readonly editorModel = signal<SeriesEditorModel | undefined>(undefined);

  /**
   * Whether the series editor exists at all — the same two-step as the one-off
   * editor above, and for the same reason: it reads its pickers' lists when it
   * is created, so it is created on an opening and destroyed once it has slid
   * out of sight.
   */
  protected readonly editorMounted = signal(false);

  /** Create the series editor if need be, and open it on `model`. */
  private showSeriesEditor(model: SeriesEditorModel): void {
    this.editorMounted.set(true);
    this.editorModel.set(model);
  }

  /**
   * Open the settings for the series the screen is showing.
   *
   * The series arrives WITH the request rather than being looked up here: the
   * screen that asks has already fetched it from the admin tier, criteria
   * included, so there is nothing left to find and no second request to make.
   */
  protected openSettings(series: PublicationSeries): void {
    this.showSeriesEditor({ mode: 'edit', series });
  }

  /**
   * Start a new series from the SERVER's defaults.
   *
   * Fetched rather than constructed here, so the form cannot drift from what the
   * server would accept — a client-side default that disagrees produces a series
   * that looks valid in the browser and is refused on save.
   */
  protected createSeries(): void {
    this.openCreateEditor();
  }

  /**
   * Start a one-off publication.
   *
   * Its OWN small dialog, not the series editor with two fields pre-answered.
   * That earlier shortcut seeded `cadence: NONE` over a server template that
   * already returned exactly that, so the two buttons differed by one dropdown
   * value and the header still read "New publication series" — and it left an
   * admin publishing one PDF to work through five tabs of cadence, criteria,
   * numbering and automation that a one-off has no use for.
   */
  protected createOneOff(): void {
    this.showOneOffEditor({ mode: 'add' });
  }

  private openCreateEditor(seed?: (series: PublicationSeries) => PublicationSeries): void {
    this.api.newSeriesTemplate().subscribe({
      next: (series) =>
        this.showSeriesEditor({ mode: 'add', series: seed ? seed(series) : series }),
      error: () => this.notifications.error('admin.publications.toast.templateError'),
    });
  }

  /**
   * Bumped when the settings drawer writes the series the screen is showing.
   *
   * The drawer is mounted here rather than on the series screen, and that screen
   * fetches the series itself — so a save reaches it only by being told, and
   * without this the header states what it was before the save.
   */
  protected readonly seriesRefreshTick = signal(0);

  protected onSeriesSaved(series: PublicationSeries): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
    // A newly created series is worth landing on: the next thing an admin does
    // is give it its first issue, and that lives on the detail screen.
    if (this.selectedSeriesId() === undefined) {
      this.select(series);
      return;
    }
    this.seriesRefreshTick.update((tick) => tick + 1);
  }

  /** Retired series are collapsed: history, not the job. */
  protected readonly showRetired = signal(false);

  protected toggleRetired(): void {
    this.showRetired.update((open) => !open);
  }

  /**
   * Download the series definitions as the JSON the importer reads back.
   *
   * The definitions only: the payload is the series list, so no issue, no
   * member and no category travels with it. It moves a configuration between
   * installations; it is not a backup of what has been published.
   *
   * Fetched with the caller's credentials and saved from memory, rather than
   * opened in a new tab behind a one-time ticket like the older admin exports.
   * A ticket does not produce a security identity, so a ticketed endpoint has to
   * be `@PermitAll` with the role checked in code — and this response carries the
   * criteria documents, which is exactly the shape that must never sit behind
   * `@PermitAll`.
   */
  protected exportSeries(): void {
    this.api.exportSeries().subscribe({
      next: (series) => this.saveJson(series),
      error: () => this.notifications.error('admin.publications.toast.exportError'),
    });
  }

  private saveJson(series: readonly PublicationSeries[]): void {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(series, null, 2)], { type: 'application/json' }),
    );
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'publication-series.json';
    link.click();
    // Released once the click has been dispatched; the browser has taken its own
    // reference to the blob by then, and holding it would keep the whole export
    // in memory for the life of the page.
    URL.revokeObjectURL(url);
  }

  /**
   * Start a new series from an existing one.
   *
   * The template comes from the SERVER, like the blank one does, so a copy
   * cannot drift from what a create would accept — the id comes back blank, the
   * status DRAFT, the provenance and issue counters cleared, and the patterns,
   * criteria and report config carried. Deciding here which fields to drop would
   * put a second answer beside the one the save is validated against.
   */
  protected copySeries(seriesId: string): void {
    this.api.copySeriesTemplate(seriesId).subscribe({
      next: (series) => this.showSeriesEditor({ mode: 'add', series }),
      error: () => this.notifications.error('admin.publications.toast.copyError'),
    });
  }
}
