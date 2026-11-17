import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';

import { ApiUrlResolver } from '../../../../../core/config/absolute-api-url';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Card } from '../../../../../shared/components/card/card';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { nowTick } from '../../../../../shared/utils/now-tick';
import { currentDesc } from '../../../utils/localized-descs';
import {
  PublicationSeriesApiService,
  type ContentMode,
  type IssueAuditEntry,
  type PublicationCategory,
} from '../../../services/api/publication-series-api.service';
import { OneOffDetailFacade } from './one-off-detail.facade';
import { OneOffEditor, type OneOffEditorModel } from './one-off-editor';
import { PublicationForeignDomain } from './publication-foreign-domain';
import { AVAILABILITIES, auditActionKey, auditActorKey, ownerTransfer } from './publication-status';
import {
  oneOffStatus,
  oneOffStatusClass,
  oneOffStatusKey,
  publicationVisibility,
} from './publication-visibility';
import { PublicWindowDialog, type PublicWindowDecision } from './public-window-dialog';
import { PublicationReasonDialog } from './reason-dialog';
import { ownerVerdict, seriesWritable } from './series-rules';
import { PublicationTransferTargets } from './transfer-owner';
import { PublicationTransferDialog, type TransferOwnerDecision } from './transfer-owner-dialog';

/** The keys that say where a publication may be cited FROM, as one sentence. */
const AVAILABILITY_FROM_PREFIX = 'admin.publications.availability.from.';

/** The actor label an imported entry carries — see {@link OneOffDetail.importedEntry}. */
const IMPORT_ACTOR_KEY = 'admin.publications.actor.IMPORT';

/**
 * What identifies one history line to the panel's own per-line state.
 *
 * The same pair the list tracks by, and for the same reason: an entry written by
 * a server that does not send its id still has to be told apart from the one
 * above it, and the instant it was recorded is the only other thing it carries.
 */
function auditEntryKey(entry: IssueAuditEntry): string {
  return `${entry.id ?? ''}:${entry.created ?? ''}`;
}

/**
 * One one-off publication, on a page of its own.
 *
 * A one-off is a series holding a single issue, but nothing a reader wants from
 * it looks like a series: there is no cadence, no sequence, no timeline and no
 * next issue — there is a document, a place on the public site, and a record of
 * what has happened to it. So the screen is those three, and the settings that
 * produced them are one drawer away.
 *
 * IT IS A ROUTED SIBLING OF THE SERIES SCREEN, not a tab inside the dashboard.
 * The one-offs table on the dashboard is a list of publications; a publication
 * that has a file to replace, a public listing to explain and a history to read
 * needs somewhere to put them, and pushing all three into a drawer would make
 * the drawer the page.
 *
 * OWNER EQUALITY decides both halves. A publication another desk owns is refused
 * in place of the page, naming that desk and offering the switch to it — the
 * server answers the read from any desk, so the client is what declines to show
 * another desk's work. What renders is this desk's, and every action on it is
 * gated on the same answer, which is what the editors apply too.
 */
@Component({
  selector: 'app-one-off-detail',
  imports: [
    Card,
    Confirm,
    CopyLinkButton,
    ErrorBanner,
    Icon,
    Menu,
    MenuTrigger,
    OneOffEditor,
    ProgressBar,
    PublicationForeignDomain,
    PublicationReasonDialog,
    PublicationTransferDialog,
    PublicWindowDialog,
    TranslocoPipe,
  ],
  providers: [OneOffDetailFacade, ConfirmService],
  templateUrl: './one-off-detail.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class OneOffDetail {
  protected readonly facade = inject(OneOffDetailFacade);
  private readonly api = inject(PublicationSeriesApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly language = inject(LanguageService);
  private readonly locale = inject(LocaleFormatService);
  private readonly domainFacade = inject(DomainFacade);
  private readonly transferTargets = inject(PublicationTransferTargets);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly apiUrls = inject(ApiUrlResolver);

  /**
   * The publication this page is about, from the URL.
   *
   * Read from the snapshot rather than subscribed: the route param is what
   * chose this component instance, and navigating to another publication
   * recreates it.
   */
  private readonly seriesId = this.route.snapshot.paramMap.get('seriesId') ?? '';

  // Fetched with every language's name and labelled here: narrowing to one
  // language on the server would leave a category named only in another
  // language rendering as its raw id.
  private readonly categories = toSignal(
    this.api.publicationCategories().pipe(catchError(() => of([]))),
    { initialValue: [] as PublicationCategory[] },
  );

  /** The catalogue a transfer entry's domain ids are named from. */
  private readonly domains = computed(() => this.domainFacade.availableDomains());

  constructor() {
    // Once, not as an effect: the id comes from the route snapshot, and the
    // router builds a new component when it changes — so there is nothing here
    // for an effect to react to.
    this.facade.load(this.seriesId);
  }

  /**
   * What the banner says when the publication cannot be shown.
   *
   * A publication the server says is not there, a series that is not a one-off
   * included, is named as such, and the banner offers no Retry (the template
   * leaves its label out): the same read can only meet the same 404. Any other
   * failure keeps "could not be loaded" and Retry.
   */
  protected readonly loadErrorKey = computed(() =>
    this.facade.notFound()
      ? 'admin.publications.error.seriesNotFound'
      : 'admin.publications.oneOff.detail.loadError',
  );

  // ------------------------------------------------------------- the header

  /** The name in the reader's language, falling back to the first one that has one. */
  protected readonly name = computed(() => {
    const series = this.facade.series();
    if (!series) {
      return this.seriesId;
    }
    return (
      currentDesc(
        series.descs ?? [],
        this.language.modelLanguages.map((l) => l.value),
        this.language.selectedLanguage(),
        this.language.defaultLanguage,
      )?.name ||
      series.descs?.find((desc) => desc.name)?.name ||
      series.seriesId
    );
  });

  protected readonly typeKey = computed(() => {
    switch (this.facade.series()?.contentMode as ContentMode | undefined) {
      case 'EXTERNAL_LINK':
        return 'admin.publications.oneOff.typeLink';
      case 'UPLOADED_FILE':
        return 'admin.publications.oneOff.typeUploaded';
      case 'GENERATED_FROM_QUERY':
        return 'admin.publications.oneOff.typeGenerated';
      default:
        return 'admin.publications.oneOff.typeNone';
    }
  });

  /**
   * Where the publication may be cited from, as a sentence.
   *
   * Not the option label the editor shows: on the editor it is an answer being
   * chosen, and here it is a fact being read — "Selected domains" is a choice,
   * "can be cited from selected domains" is a description.
   */
  protected readonly availabilityKey = computed(() => {
    const availability = this.facade.series()?.availability;
    const known = AVAILABILITIES.find((option) => option === availability);
    return AVAILABILITY_FROM_PREFIX + (known ?? 'UNKNOWN');
  });

  protected readonly categoryName = computed(() => {
    const categoryId = this.facade.series()?.categoryId;
    const category = this.categories().find((row) => row.categoryId === categoryId);
    if (!category) {
      return categoryId ?? '';
    }
    return (
      currentDesc(
        category.descs ?? [],
        this.language.modelLanguages.map((l) => l.value),
        this.language.selectedLanguage(),
        this.language.defaultLanguage,
      )?.name || category.categoryId
    );
  });

  // ------------------------------------------------------------ what may be done

  /**
   * Whose publication this is, from the desk being worked at.
   *
   * One answer for the page: a publication another desk owns is refused in place
   * of the whole screen — the document, the public standing and the history all
   * describe work this desk is not accountable for — and the same answer read
   * one notch looser is what every action is gated on.
   */
  protected readonly ownerState = computed(() =>
    ownerVerdict(this.facade.series(), this.domainFacade.sessionDomain()?.domainId),
  );

  /** The owning desk to refuse in favour of, and nothing while there is none. */
  protected readonly foreignOwnerDomainId = computed(() =>
    this.ownerState() === 'foreign' ? this.facade.series()?.domainId : undefined,
  );

  /** Whether this publication is the current desk's to change. */
  protected readonly writable = computed(() =>
    seriesWritable(this.facade.series(), this.domainFacade.sessionDomain()?.domainId),
  );

  /**
   * Whether moving it is on the table.
   *
   * The publication has to be this desk's to change, and there has to be
   * somewhere to move it TO: an admin who administers one domain has no target,
   * and a menu entry opening a dialog with an empty picker explains nothing.
   */
  protected readonly canTransfer = computed(() => {
    const series = this.facade.series();
    const writable = this.writable();
    const targets = this.transferTargets.targetsFor(series?.domainId);
    return series !== undefined && writable && targets.length > 0;
  });

  // `translateSignal` keeps the menu labels reactive to a language change: the
  // menu is a computed, and a value it cannot track leaves the first answer
  // cached for the life of the page.
  private readonly transferLabel = translateSignal('admin.publications.transfer.action');

  /**
   * The overflow menu: moving the publication to another desk.
   *
   * Everything that changes the publication's PUBLIC STANDING is on the card
   * that explains it instead — a status transition and a period are answers to
   * what that card says, and an admin who has just read "withdrawn" should not
   * have to go looking in a menu for the way back. Built empty rather than
   * disabled when nothing applies: the trigger is hidden, because a menu that
   * opens onto nothing is worse than no menu.
   */
  protected readonly menuItems = computed<MenuItem[]>(() => {
    const items: MenuItem[] = [];
    if (this.canTransfer()) {
      items.push({ label: this.transferLabel(), command: () => this.openTransfer() });
    }
    return items;
  });

  // ------------------------------------------------------------ the document

  protected readonly uploaded = computed(
    () => this.facade.series()?.contentMode === 'UPLOADED_FILE',
  );
  protected readonly linkBacked = computed(
    () => this.facade.series()?.contentMode === 'EXTERNAL_LINK',
  );
  protected readonly referenceOnly = computed(() => this.facade.series()?.contentMode === 'NONE');
  protected readonly citable = computed(
    () => (this.facade.series()?.messagePublication ?? 'NONE') !== 'NONE',
  );

  /**
   * One row per language the issue carries, with whatever is behind it.
   *
   * Driven off the ISSUE rather than off the series: the file name and the
   * fetchable address are issue-level, and the series only says what shape they
   * take.
   *
   * The publication's own per-language address is the fallback for `href`. The
   * one-off list resolves it the way the public site does — an explicit link
   * where there is one, the repository address of the uploaded file otherwise —
   * so a natively uploaded document that carries no link column of its own
   * still has somewhere to point.
   *
   * Both are resolved against the backend's own origin before they leave here:
   * the server reports them root-relative, and the app is not always served from
   * the host that answers them. An address that already names a host is left as
   * it is — an external link belongs to whoever hosts it.
   */
  protected readonly documentRows = computed(() => {
    const descs = this.facade.issue()?.descs ?? [];
    const addresses = new Map(
      (this.facade.publication()?.links ?? []).map((link) => [link.lang, link.value ?? undefined]),
    );
    return descs.map((desc) => {
      const href = desc.href ?? addresses.get(desc.lang);
      return {
        lang: desc.lang,
        fileName: desc.fileName,
        href: href === undefined ? undefined : this.apiUrls.absoluteApiUrl(href),
        link: desc.link === undefined ? undefined : this.apiUrls.absoluteApiUrl(desc.link),
      };
    });
  });

  protected readonly hasFile = computed(() => this.documentRows().some((row) => !!row.href));

  /** Whether the publication keeps a file per language rather than one for all. */
  protected readonly perLanguageFiles = computed(
    () => this.facade.series()?.languageSpecific === true,
  );

  /**
   * The file rows as the card shows them: one per language, or just one.
   *
   * A publication that does not declare a file per language HAS one file, and
   * the issue still carries a desc per language it is written in — so the rows
   * repeat the same document under different flags. The one with an address is
   * preferred: that is the row the file is actually filed under.
   */
  protected readonly fileRows = computed(() => {
    const rows = this.documentRows();
    if (this.perLanguageFiles() || rows.length < 2) {
      return rows;
    }
    return [rows.find((row) => !!row.href) ?? rows[0]];
  });

  /** How many files there are, said the short way the card has room for. */
  protected readonly fileCountKey = computed(() =>
    this.perLanguageFiles()
      ? 'admin.publications.oneOff.detail.fileForEachLanguage'
      : 'admin.publications.oneOff.detail.oneFileForAllLanguages',
  );

  /** The addresses a link-backed publication actually points at. */
  protected readonly linkRows = computed(() => this.documentRows().filter((row) => !!row.link));

  /**
   * Which language a replacement file lands on.
   *
   * The first the issue declares. A publication with one file for every language
   * has exactly one answer; one with a file per language is replaced a language
   * at a time from the drawer, where each row has its own control.
   */
  protected readonly primaryLang = computed(() => this.documentRows()[0]?.lang);

  /**
   * When the current file was put there, where the history says so.
   *
   * Taken from the audit rather than from the file, because there is no file
   * metadata on the wire — an upload is recorded, and the most recent record is
   * the one that describes what is on the page now.
   */
  protected readonly uploadedAt = computed(() => {
    const entry = this.facade
      .audit()
      .filter((row) => row.action === 'FILE_UPLOADED' && row.created !== undefined)
      .sort((a, b) => (b.created ?? 0) - (a.created ?? 0))[0];
    return entry?.created;
  });

  /** The citation the publication prints on a message, in the reader's language. */
  protected readonly referenceFormat = computed(() => {
    const series = this.facade.series();
    if (!series) {
      return undefined;
    }
    return currentDesc(
      series.descs ?? [],
      this.language.modelLanguages.map((l) => l.value),
      this.language.selectedLanguage(),
      this.language.defaultLanguage,
    )?.messageReferenceFormat;
  });

  // ------------------------------------------------- the public listing card

  /**
   * When the publication goes, or went, onto the public site.
   *
   * The issue's answer first, the publication's as the fallback: they are the
   * same value from the same row, and the issue read is the one this page always
   * makes. A publication whose issue has not been created yet has neither.
   */
  protected readonly publicFrom = computed(
    () => this.facade.issue()?.publicFrom ?? this.facade.publication()?.publicFrom ?? undefined,
  );

  /**
   * When the period closes, where it has an end.
   *
   * The publication's own answer first, the issue's as the fallback: they are
   * the same value from the same row, and a server that does not report it on
   * the publication yet still has it on the issue this page already reads.
   */
  protected readonly publicTo = computed(
    () => this.facade.publication()?.publicTo ?? this.facade.issue()?.publicTo ?? undefined,
  );

  /** Read from a moving clock: this is a page an admin leaves open. */
  private readonly tick = nowTick();

  /**
   * The clock the public period is read against.
   *
   * The page's own clock, but never earlier than the server's instant of a
   * release made here. The release stamps the start of the period with the
   * server's clock, which the officer's PC may trail, and the page's clock moves
   * once a minute: read against that alone, a publication released a moment ago
   * would read "scheduled" until the next tick.
   */
  private readonly now = computed(() => Math.max(this.tick(), this.facade.releasedAt() ?? 0));

  /**
   * Whether the publication's category is one the public site lists at all.
   *
   * Tri-state on purpose. `undefined` is a server that does not report the flag,
   * NOT a category that is unpublished — and the difference decides which
   * sentences the card is allowed to say. Taken from the publication rather than
   * looked up in the category catalogue this page fetches for the NAME: public
   * visibility has one definition and it is the server's.
   */
  protected readonly categoryPublish = computed(() => this.facade.publication()?.categoryPublish);

  /**
   * Where the publication STANDS: draft, published, withdrawn.
   *
   * The issue's own status, which is the first of the three facts. Read from the
   * publication rather than from the issue so a payload that carries no issue
   * still answers.
   */
  protected readonly status = computed(() =>
    oneOffStatus(this.facade.publication()?.issueStatus ?? this.facade.issue()?.status),
  );

  protected readonly statusKey = computed(() => oneOffStatusKey(this.status()));
  protected readonly statusClass = computed(() => oneOffStatusClass(this.status()));

  /**
   * The three facts, as the one line that follows from them.
   *
   * The card's headline, and the same derivation the dashboard column uses — a
   * screen that re-stated these rules would be a second answer that can disagree
   * with the public site.
   */
  protected readonly visibility = computed(() =>
    publicationVisibility(
      {
        issueStatus: this.facade.publication()?.issueStatus ?? this.facade.issue()?.status,
        publicFrom: this.publicFrom(),
        publicTo: this.publicTo(),
        categoryPublish: this.categoryPublish(),
        categoryName: this.categoryName(),
        now: this.now(),
      },
      (value) => this.date(value),
    ),
  );

  /** The card's own title, which has to stop promising the public site. */
  protected readonly publicHeaderKey = computed(() =>
    this.categoryPublish() === false
      ? 'admin.publications.oneOff.detail.notOnPublicSite'
      : 'admin.publications.oneOff.detail.public',
  );

  /** The period as the card prints it: both ends, or the one it has. */
  protected readonly periodLine = computed(() => {
    const from = this.publicFrom();
    const to = this.publicTo();
    if (from === undefined && to === undefined) {
      return { key: 'admin.publications.oneOff.detail.periodUnset', params: {} };
    }
    if (from === undefined) {
      return {
        key: 'admin.publications.oneOff.detail.periodUntil',
        params: { to: this.date(to) },
      };
    }
    return to === undefined
      ? {
          key: 'admin.publications.oneOff.detail.periodNoEnd',
          params: { from: this.date(from) },
        }
      : {
          key: 'admin.publications.oneOff.detail.periodBoth',
          params: { from: this.date(from), to: this.date(to) },
        };
  });

  /**
   * Whether the issue the status transitions act on is named at all.
   *
   * Withdrawing and publishing again are the issue's own transitions, so a
   * publication whose issue has not been created yet has nothing for either of
   * them to address.
   */
  private readonly hasIssue = computed(() => !!this.facade.publication()?.issuePublicId);

  /** Whether taking the publication off the public site is the action it needs. */
  protected readonly canWithdraw = computed(
    () => this.writable() && this.hasIssue() && this.status() === 'PUBLISHED',
  );

  /** And the way back, offered only from the state it undoes. */
  protected readonly canRepublish = computed(
    () => this.writable() && this.hasIssue() && this.status() === 'WITHDRAWN',
  );

  /**
   * Whether releasing it is what a publication being prepared is waiting for.
   *
   * A draft that has everything it needs — an uploaded one with its bytes, a
   * link-backed one with its address — is one press from the public site, and
   * until this action existed there was no press: the screen said "Not published
   * yet" with nothing on it that could change that, and the toast after the
   * upload promised a release nothing was going to perform.
   *
   * Gated on `publishable`, which is the server's own answer to whether the
   * document is complete. A draft still missing its file is left saying what it
   * is waiting for rather than offered a button that would be refused.
   */
  protected readonly canPublish = computed(
    () =>
      this.writable() &&
      this.hasIssue() &&
      this.status() === 'DRAFT' &&
      this.facade.publication()?.publishable === true,
  );

  /**
   * Whether the period can be changed from here.
   *
   * Everything but a withdrawn publication: a period on one that is gone from
   * the public site changes nothing anybody can see, and offering it would
   * suggest the period is what is holding the document back.
   */
  protected readonly canEditPeriod = computed(
    () => this.writable() && this.status() !== 'WITHDRAWN',
  );

  /**
   * The refusal the PAGE shows, which is the one no dialog is already showing.
   *
   * Every write on this page is refused into one signal, and each dialog renders
   * it beside the control that was refused. A dialog standing open over the page
   * therefore printed the same sentence twice — once where it belongs and once
   * behind it — which reads as two separate things having gone wrong.
   */
  protected readonly pageErrorKey = computed(() =>
    this.withdrawDialogOpen() || this.periodDialogOpen() || this.transferDialogOpen()
      ? undefined
      : this.facade.errorKey(),
  );

  protected dateTime(value: number | undefined): string {
    return value === undefined ? '' : this.locale.formatDateTime(new Date(value));
  }

  protected date(value: number | undefined): string {
    return value === undefined ? '' : this.locale.formatDate(new Date(value));
  }

  // ---------------------------------------------------------------- history

  protected actionKey(entry: IssueAuditEntry): string | undefined {
    return auditActionKey(entry.action);
  }

  protected actorKey(entry: IssueAuditEntry): string | undefined {
    return auditActorKey(entry);
  }

  /** An entry the import wrote: the action and the actor are one fact there. */
  protected importedEntry(entry: IssueAuditEntry): boolean {
    return auditActorKey(entry) === IMPORT_ACTOR_KEY;
  }

  /**
   * What the line carries as hover text: its own id, and an import's identifier.
   *
   * An import records the identifier the publication had in the system it came
   * out of where every other line records a reason somebody typed. Nobody reads
   * a uuid off a history panel — the one person who needs it is matching it
   * against a record elsewhere — so it travels as the hover text of the date
   * line and behind the disclosure beneath it, rather than as running text.
   */
  protected entryTitle(entry: IssueAuditEntry): string | null {
    const parts = [
      this.importedEntry(entry) ? entry.reason : undefined,
      entry.id === undefined ? undefined : String(entry.id),
    ].filter((part) => !!part);
    return parts.length === 0 ? null : parts.join(' · ');
  }

  /**
   * Which history lines have their identifier open, held per line.
   *
   * Per line rather than one flag for the panel: an identifier belongs to the
   * line that carries it, and one shared flag would open every one of them.
   */
  private readonly openIdentifiers = signal<ReadonlySet<string>>(new Set<string>());

  protected identifierOpen(entry: IssueAuditEntry): boolean {
    return this.openIdentifiers().has(auditEntryKey(entry));
  }

  protected toggleIdentifier(entry: IssueAuditEntry): void {
    const key = auditEntryKey(entry);
    this.openIdentifiers.update((open) => {
      const next = new Set(open);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  }

  protected ownerMove(entry: IssueAuditEntry): { from: string; to: string } | undefined {
    const move = ownerTransfer(entry);
    return move ? { from: this.domainName(move.from), to: this.domainName(move.to) } : undefined;
  }

  private domainName(domainId: string): string {
    return this.domains().find((domain) => domain.domainId === domainId)?.name || domainId;
  }

  // ----------------------------------------------------------------- actions

  /**
   * Back to the list.
   *
   * `['../..']` because this page's route is `publications/one-off/:seriesId`:
   * one step pops the id, the second pops the literal segment, and what is left
   * is the section the publication is listed in.
   */
  protected back(): void {
    void this.router.navigate(['../..'], { relativeTo: this.route });
  }

  // -------------------------------------------------------------- the drawer

  protected readonly editorModel = signal<OneOffEditorModel | undefined>(undefined);

  /**
   * Whether the editor exists at all.
   *
   * It reads the lists its pickers offer as it is built, so it is built when the
   * drawer is opened and torn down once the drawer has slid out — this page is
   * read far more often than it is edited from.
   */
  protected readonly editorMounted = signal(false);

  protected edit(): void {
    const publication = this.facade.publication();
    if (!publication) {
      return;
    }
    this.editorMounted.set(true);
    this.editorModel.set({ mode: 'edit', publication });
  }

  protected onEditorSaved(): void {
    this.facade.refresh();
  }

  /** The close INTENT: the drawer starts sliding out, the editor stays alive. */
  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  /** And the drawer is gone, so the component that drew it can go too. */
  protected onEditorHidden(): void {
    this.editorMounted.set(false);
  }

  // ------------------------------------------------------------- the file

  protected onFileChosen(lang: string | undefined, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !lang) {
      return;
    }
    // Cleared straight away so choosing the same file twice fires again: the
    // control reports no change when the selection is identical.
    input.value = '';
    this.facade.uploadFile(lang, file);
  }

  // ------------------------------------------- on the public site, or not

  protected readonly withdrawDialogOpen = signal(false);

  protected openWithdraw(): void {
    if (!this.canWithdraw()) {
      return;
    }
    this.withdrawDialogOpen.set(true);
  }

  /**
   * Take it off the public site, with the reason the transition records.
   *
   * The file stays exactly where it is and the citations already written into
   * messages keep resolving: what changes is that the publication leaves the
   * public site, its history included.
   */
  protected submitWithdraw(reason: string): void {
    this.facade.setPublished(false, reason, () => this.withdrawDialogOpen.set(false));
  }

  /**
   * Release it, behind a confirm that says what goes public and when.
   *
   * The message names the day: a publication carrying a start still to come goes
   * onto the public site on that day rather than now, and the release is the
   * moment somebody would otherwise assume it had appeared.
   */
  protected publish(): void {
    if (!this.canPublish()) {
      return;
    }
    const from = this.publicFrom();
    const scheduled = from !== undefined && from > this.now();
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.oneOff.detail.publishTitle'),
      message: this.transloco.translate(
        scheduled
          ? 'admin.publications.oneOff.detail.publishScheduledMessage'
          : 'admin.publications.oneOff.detail.publishMessage',
        { from: this.date(from) },
      ),
      accept: () => this.facade.publish(),
    });
  }

  /**
   * Put it back on the public site, behind a plain confirm.
   *
   * No reason is asked for: publishing again restores a state the publication
   * already had, and nothing new is being recorded about it.
   */
  protected republish(): void {
    if (!this.canRepublish()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.publications.oneOff.detail.republishTitle'),
      message: this.transloco.translate('admin.publications.oneOff.detail.republishMessage'),
      accept: () => this.facade.setPublished(true),
    });
  }

  // ------------------------------------------------------- the public period

  protected readonly periodDialogOpen = signal(false);

  protected openPeriod(): void {
    if (!this.canEditPeriod()) {
      return;
    }
    this.periodDialogOpen.set(true);
  }

  /**
   * Whether the period must name a start.
   *
   * The server refuses a published publication without one, so the form refuses
   * it first. A publication still being prepared may have no start at all — that
   * is the ordinary shape of a draft, and the start is chosen before, or at, the
   * release.
   */
  protected readonly startRequired = computed(() => this.status() === 'PUBLISHED');

  protected submitPeriod(decision: PublicWindowDecision): void {
    this.facade.setPublicWindow(decision.publicFrom, decision.publicTo, () =>
      this.periodDialogOpen.set(false),
    );
  }

  // --------------------------------------------------- moving it to a desk

  protected readonly transferDialogOpen = signal(false);

  protected openTransfer(): void {
    if (!this.canTransfer()) {
      return;
    }
    this.transferDialogOpen.set(true);
  }

  protected submitTransfer(decision: TransferOwnerDecision): void {
    this.facade.transferOwner(decision.domainId, decision.reason, () =>
      this.transferDialogOpen.set(false),
    );
  }

  protected readonly ownerDomainId = computed(() => this.facade.series()?.domainId);
}
