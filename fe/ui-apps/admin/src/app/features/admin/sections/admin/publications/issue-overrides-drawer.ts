import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { Icon } from '../../../../../shared/icons/icon';
import type { MessageReport } from '../../../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from '../../../../messages/services/api/message-reports-api.service';
import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import {
  FILE_NAME_MAX_LENGTH,
  NAME_MAX_LENGTH,
  NUMBERING_LABEL_MAX_LENGTH,
  derivedNumbering,
  expandFileNamePattern,
  expandIssuePattern,
  overrideMarks,
  paramText,
  printedNumbering,
  seriesDefaults,
  withPdf,
  type IssueNumbering,
} from './issue-overrides';
import { isReservedReportParamKey } from './series-rules';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/**
 * What a save sends. Every field is optional and absent means "leave it alone".
 *
 * An EMPTY STRING is the second answer most of them need: it hands the field
 * back to the series, which absent cannot say because absent already means
 * something else. Not `null` — a field the request does not mention and a field
 * it sends as null are the same value by the time the server has read the body,
 * so a null clear would make every partial save wipe the fields it left out.
 */
export interface IssueOverridesPatch {
  names?: Record<string, string>;
  fileNames?: Record<string, string>;
  weekLabel?: string;
  yearLabel?: string;
  edition?: string;
  reportId?: string;
  reportParams?: Record<string, unknown>;
}

/** One row somebody added by hand, still being named. */
interface AddedParam {
  readonly id: number;
  readonly key: string;
  readonly value: string;
}

/**
 * Everything this edition says differently from its series, in one drawer.
 *
 * Four groups and one rule: a field left alone follows the series and moves with
 * it; a field touched is marked and stops following. Nothing here is a setting —
 * a setting would belong to the series — so every control is offered beside what
 * the series says, and every one of them can be handed back.
 *
 * The numbering is the group the other three read. The issue derives its week,
 * its second week and its year from the cut-off; what is edited here is what the
 * week and the year PRINT, as free text, because a double issue is written
 * `36+37` and no number check can accept that. The derived values are untouched
 * and stay numeric, so ordering, the timeline and gap detection cannot be broken
 * by anything typed here.
 *
 * Mounted only while it is open — see the issue screen — so nothing in it is
 * held after the reader is done with it.
 */
@Component({
  selector: 'app-issue-overrides-drawer',
  imports: [LanguageFlag, Drawer, Icon, TranslocoPipe],
  templateUrl: './issue-overrides-drawer.html',
})
export class IssueOverridesDrawer {
  /** Parent-owned visibility, exactly as every other drawer in the section. */
  readonly open = input(false);

  /**
   * The issue being tailored.
   *
   * Read once, when the first one arrives: the boxes are seeded from it and a
   * later refetch — a preview render, somebody else's curation — must not reach
   * in and replace what is being typed.
   */
  readonly issue = input<PublicationIssue | undefined>(undefined);

  /**
   * The series, for everything the drawer compares against.
   *
   * Optional because the screen above may still be loading it, and the drawer
   * degrades to "nothing to compare against": no marks, no suggestions, no reset
   * links — rather than to a wrong comparison.
   */
  readonly series = input<PublicationSeries | undefined>(undefined);

  /** Whether a save is in flight, so the footer can stand down. */
  readonly saving = input(false);

  /** The refusal the last save met, as an already-mapped key. */
  readonly errorKey = input<string | undefined>(undefined);

  readonly closed = output<void>();
  readonly hidden = output<void>();
  readonly save = output<IssueOverridesPatch>();

  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly reportsApi = inject(MessageReportsApiService);

  /**
   * The reports this desk may pick from.
   *
   * The editor-tier list — the public reports plus this domain's own — for the
   * same reason the series editor uses it: the full inventory is a sysadmin
   * read, and a plain admin would be left with an empty picker.
   */
  protected readonly reports = toSignal(
    this.reportsApi.listReports({ forList: true }).pipe(catchError(() => of([]))),
    { initialValue: [] as readonly MessageReport[] },
  );

  protected readonly maxLabelLength = NUMBERING_LABEL_MAX_LENGTH;
  protected readonly maxFileNameLength = FILE_NAME_MAX_LENGTH;
  protected readonly maxNameLength = NAME_MAX_LENGTH;

  constructor() {
    effect(() => {
      const issue = this.issue();
      if (!issue || untracked(() => this.seeded())) {
        return;
      }
      untracked(() => {
        this.seed(issue);
        this.seeded.set(true);
      });
    });
  }

  // ------------------------------------------------------------------ drafts

  private readonly seeded = signal(false);

  /**
   * The name boxes that have stopped following the series, per language.
   *
   * Absent IS "follows": the box then renders whatever the series would call the
   * issue today and moves with the numbering as it is typed. Text equal to that
   * suggestion is not an edit of anything and is recorded as absent again, so
   * typing the suggestion back is the same act as never having typed.
   */
  private readonly nameEdits = signal<Readonly<Record<string, string | undefined>>>({});

  /**
   * The name on file, per language — what a box falls back to.
   *
   * A pattern written from the cut-off's month or day cannot be expanded here,
   * and a box showing nothing at all would be worse than a box showing the name
   * the issue actually carries. See {@link nameDraft}.
   */
  private readonly seededNames = signal<Readonly<Record<string, string>>>({});

  /**
   * The file-name boxes that have stopped following the series, per language.
   *
   * Absent IS "follows": the box then renders whatever the series would name the
   * file today and moves with the numbering as it is typed. Text equal to that
   * suggestion is not an edit of anything and is recorded as absent again, so
   * typing the suggestion back is the same act as never having typed.
   */
  private readonly fileNameEdits = signal<Readonly<Record<string, string | undefined>>>({});

  /**
   * The file name on file, per language — what a box falls back to.
   *
   * A series that names no file-name pattern, or one written from the cut-off's
   * month or day, leaves nothing to expand; the document is then named after the
   * name the issue already carries, so that is what the box holds rather than
   * nothing at all. See {@link fileNameDraft}.
   */
  private readonly seededFileNames = signal<Readonly<Record<string, string>>>({});

  private readonly weekDraft = signal('');
  private readonly yearDraft = signal('');
  private readonly editionDraft = signal('');
  private readonly reportIdDraft = signal('');
  private readonly paramValues = signal<Readonly<Record<string, string>>>({});
  private readonly addedParams = signal<readonly AddedParam[]>([]);
  private nextAddedId = 1;

  /** What was on file when the drawer opened, so a save can send only changes. */
  private readonly seededIssue = signal<PublicationIssue | undefined>(undefined);

  private seed(issue: PublicationIssue): void {
    this.seededIssue.set(issue);
    const names: Record<string, string | undefined> = {};
    const seededNames: Record<string, string> = {};
    const fileNames: Record<string, string | undefined> = {};
    const seededFileNames: Record<string, string> = {};
    for (const lang of this.languages()) {
      const desc = issue.descs?.find((d) => d.lang === lang);
      // The PIN says a field stopped following the series — the stored text does
      // not. A name is always stored, so reading one as an edit would freeze
      // every box in the estate; a file name is also written by an upload and by
      // a release, neither of which is a decision about what the file should be
      // called. Both would leave the drawer wrongly marked and wrongly frozen the
      // moment it opened.
      names[lang] = desc?.nameOverridden === true ? (desc.name?.trim() ?? '') : undefined;
      seededNames[lang] = desc?.name ?? '';
      fileNames[lang] =
        desc?.fileNameOverridden === true ? (desc.fileName?.trim() ?? '') : undefined;
      seededFileNames[lang] = desc?.fileName ?? '';
    }
    this.nameEdits.set(names);
    this.seededNames.set(seededNames);
    this.fileNameEdits.set(fileNames);
    this.seededFileNames.set(seededFileNames);

    const printed = printedNumbering(issue);
    this.weekDraft.set(printed.week);
    this.yearDraft.set(printed.year);
    this.editionDraft.set(printed.edition);
    this.reportIdDraft.set(issue.reportId?.trim() ?? '');
    this.paramValues.set({ ...paramText(issue.reportParams) });
    this.addedParams.set([]);
  }

  /**
   * The languages the drawer writes.
   *
   * The SERIES' languages where it declares them — a box for a language the
   * publication does not carry accepts text that is dropped on save — and the
   * configured list otherwise, which is what an issue read without its series
   * can still be shown in.
   */
  protected readonly languages = computed<readonly string[]>(() => {
    const declared = this.series()?.languages;
    const configured = this.language.modelLanguages.map((lang) => lang.value);
    return declared && declared.length > 0
      ? configured.filter((lang) => declared.includes(lang))
      : configured;
  });

  protected readonly languageRows = computed(() =>
    this.languages().map((lang) => ({
      lang,
      flagPath: this.language.modelLanguages.find((l) => l.value === lang)?.flagPath,
    })),
  );

  private readonly defaults = computed(() => seriesDefaults(this.series()));

  /** The numbering the cut-off derived, which the labels are judged against. */
  private readonly derived = computed<IssueNumbering>(() => {
    const issue = this.issue();
    return issue ? derivedNumbering(issue) : { week: '', weekTo: '', year: '', edition: '' };
  });

  /** The numbering as the boxes currently read it. */
  private readonly numbering = computed<IssueNumbering>(() => ({
    week: this.weekDraft().trim(),
    // No box of its own: an issue running into a second week keeps the one its
    // cut-off derived, and a span written any other way is written into the week.
    weekTo: this.derived().weekTo,
    year: this.yearDraft().trim(),
    edition: this.editionDraft().trim(),
  }));

  /**
   * What is tailored, computed from the BOXES rather than from the stored issue.
   *
   * A mark that waited for a save would appear one refetch after the edit that
   * earned it, and the line under the title would count something other than
   * what the reader can see.
   */
  protected readonly marks = computed(() =>
    overrideMarks(
      {
        names: this.nameValues(),
        fileNames: this.fileNameValues(),
        fileNameFlags: this.storedFileNameFlags(),
        nameFlags: this.storedNameFlags(),
        numbering: this.numbering(),
        derived: this.derived(),
        reportId: this.reportIdDraft().trim(),
        reportParams: this.effectiveParams(),
      },
      this.defaults(),
      this.languages(),
    ),
  );

  /**
   * Whether each language's file name would still be pinned once this drawer
   * saved — not merely whether it is pinned today.
   *
   * The pin is what makes the release skip the pattern, and it is invisible
   * whenever the pinned text and the pattern happen to render the same string.
   * Reading the stored flag alone would then show a field with no mark and no
   * way back to the series, still pinned, waiting to rename the next document
   * that moves. Reading the BOX alone would leave the mark standing after the
   * field had been handed back, until a refetch. The pin as it will stand is
   * both: still set, and not yet released here.
   */
  private readonly storedFileNameFlags = computed<Readonly<Record<string, boolean>>>(() => {
    const flags: Record<string, boolean> = {};
    const edits = this.fileNameEdits();
    for (const desc of this.issue()?.descs ?? []) {
      flags[desc.lang] = desc.fileNameOverridden === true && edits[desc.lang] !== undefined;
    }
    return flags;
  });

  /**
   * The same, for the name, and for the same reason: a pinned name is one the
   * server stops re-deriving, so an issue that renumbers keeps it — even where
   * the pinned text is exactly what the pattern renders today.
   */
  private readonly storedNameFlags = computed<Readonly<Record<string, boolean>>>(() => {
    const flags: Record<string, boolean> = {};
    const edits = this.nameEdits();
    for (const desc of this.issue()?.descs ?? []) {
      flags[desc.lang] = desc.nameOverridden === true && edits[desc.lang] !== undefined;
    }
    return flags;
  });

  protected readonly overriddenCount = computed(() => this.marks().count);

  protected nameOverridden(lang: string): boolean {
    return this.marks().nameLangs.includes(lang);
  }

  protected fileNameOverridden(lang: string): boolean {
    return this.marks().fileNameLangs.includes(lang);
  }

  // ------------------------------------------------------------------ 1. name

  /**
   * What is in the box: the language's own name where one was typed, and the
   * series' suggestion where none was.
   *
   * The fallback order is the whole behaviour. An untouched box renders the
   * pattern expanded THROUGH the numbering as it is being typed, so rewriting a
   * week label rewrites the name the same keystroke. Where the pattern cannot be
   * expanded here — one written from the cut-off's month or day — the box holds
   * the name on file rather than a name with a hole in it.
   */
  protected nameDraft(lang: string): string {
    return this.nameEdits()[lang] ?? this.nameSuggestion(lang) ?? this.seededNames()[lang] ?? '';
  }

  /** Every box's text, which is what the marks are judged on. */
  private readonly nameValues = computed<Readonly<Record<string, string>>>(() => {
    const values: Record<string, string> = {};
    for (const lang of this.languages()) {
      values[lang] = this.nameDraft(lang);
    }
    return values;
  });

  protected onNameInput(lang: string, input: HTMLInputElement): void {
    // Judged on the trimmed text, so a stray space at the end is not the
    // difference between following the series and being pinned to a name that
    // says exactly what the series would have said.
    //
    // An EMPTIED box hands the field back rather than clearing it: a name is
    // never absent — the column is never empty and a nameless issue cannot be
    // found in any list that shows it — so emptying can only mean "let the
    // series name it again".
    const typed = input.value.trim();
    const follows = typed === '' || typed === this.nameSuggestion(lang);
    this.nameEdits.update((edits) => ({ ...edits, [lang]: follows ? undefined : input.value }));
    if (typed === '') {
      // Written back by hand: the bound value is the suggestion both before and
      // after, so nothing tells the binding the box no longer shows it.
      input.value = this.nameDraft(lang);
    }
  }

  /** What the series would call this issue, per language — absent where it cannot say. */
  protected nameSuggestion(lang: string): string | undefined {
    return expandIssuePattern(this.defaults().namePatterns[lang], this.numbering(), this.derived());
  }

  /**
   * The one pattern the hint quotes, where the series writes several.
   *
   * The reader's own language first, then whichever language declares one: the
   * hint says what SHAPE the series names its issues in, and printing every
   * language's pattern would turn a one-line hint into a list nobody reads.
   */
  protected readonly primaryNamePattern = computed(() =>
    this.pickPattern(this.defaults().namePatterns),
  );

  protected readonly primaryFileNamePattern = computed(() =>
    this.pickPattern(this.defaults().fileNamePatterns),
  );

  private pickPattern(patterns: Readonly<Record<string, string>>): string | undefined {
    const active = this.language.selectedLanguage();
    if (patterns[active]) {
      return patterns[active];
    }
    return this.languages()
      .map((lang) => patterns[lang])
      .find((pattern) => !!pattern);
  }

  /**
   * Hand every language's name back to the series.
   *
   * The edit is dropped rather than replaced, so the box goes back to rendering
   * the suggestion AND the save releases the pin. Writing the expansion in as an
   * edit would store a hand-set name that merely happens to agree with the
   * pattern today — and would stop following it the next time the numbering
   * moves, which is the opposite of what the control offering it says.
   */
  protected followSeriesNames(): void {
    this.nameEdits.set({});
  }

  // -------------------------------------------------------------- 2. file name

  /**
   * What is in the box: the language's own file name where one was typed, and
   * the series' suggestion where none was.
   *
   * Real text rather than a prompt behind an empty box, because a suggestion
   * somebody wants to adjust has to be adjustable — a name that is nearly right
   * should take one keystroke, not a retyping of the whole thing.
   *
   * Where there is no suggestion at all — a series naming no pattern, or one
   * written from the cut-off's month or day — the box holds the name the issue
   * already carries, which is the name the document will be written under.
   */
  protected fileNameDraft(lang: string): string {
    return (
      this.fileNameEdits()[lang] ??
      this.fileNameSuggestion(lang) ??
      this.seededFileNames()[lang] ??
      ''
    );
  }

  /** Every box's text, which is what the marks and the clash check read. */
  private readonly fileNameValues = computed<Readonly<Record<string, string>>>(() => {
    const values: Record<string, string> = {};
    for (const lang of this.languages()) {
      values[lang] = this.fileNameDraft(lang);
    }
    return values;
  });

  protected onFileNameInput(lang: string, value: string): void {
    // Judged on the trimmed text, so a stray space at the end is not the
    // difference between following the series and being pinned to a name that
    // says exactly what the series would have said.
    //
    // Only the exact text hands the field back, because the box then falls back
    // to the very string it already shows. The series' name typed without
    // `.pdf` stays an edit: falling back would rewrite the box under the caret,
    // and the next keystroke would land after the extension. Where the value is
    // read — the marks, the clash check and the save — it is judged with `.pdf`
    // added, as the server adds it, so it still counts as following the series.
    const follows = value.trim() === this.fileNameSuggestion(lang);
    this.fileNameEdits.update((edits) => ({ ...edits, [lang]: follows ? undefined : value }));
  }

  /**
   * What the series would name the file — what an untouched box holds.
   *
   * Expanded here first, because the box has to MOVE while the numbering is
   * being typed and a value resolved when the issue was read cannot. The
   * server's own answer stands behind it for the patterns this screen cannot
   * expand — a file name written from the cut-off's month or day — so the box
   * still shows what will be written rather than nothing at all.
   *
   * With the `.pdf` the server adds to every file name, so a pattern written
   * without it suggests the name the server will store.
   */
  protected fileNameSuggestion(lang: string): string | undefined {
    const expanded = expandFileNamePattern(
      this.defaults().fileNamePatterns[lang],
      this.numbering(),
      this.derived(),
    );
    if (expanded !== undefined) {
      return expanded;
    }
    const suggested = this.issue()
      ?.descs?.find((desc) => desc.lang === lang)
      ?.suggestedFileName?.trim();
    return suggested === '' ? undefined : suggested;
  }

  /**
   * Hand every language's file name back to the series.
   *
   * The edit is dropped rather than replaced, so the box goes back to rendering
   * the suggestion AND the save sends a clear. Writing the expansion in as an
   * edit would store a hand-set name that merely happens to agree with the
   * pattern today — and would stop following it the next time the numbering
   * moves, which is the opposite of what the control offering it says.
   */
  protected followSeriesFileNames(): void {
    this.fileNameEdits.set({});
  }

  /**
   * A file name two languages would write, which is one file.
   *
   * Judged on what will be WRITTEN, boxes still following the series included:
   * the issue's languages write into one folder, so two equal names mean the
   * document written second is the one BOTH languages then serve. Only a
   * hand-set box is named, because that is the one somebody can fix here — two
   * languages the series itself names identically is a fact about the series.
   */
  protected readonly fileNameClash = computed<readonly string[]>(() => {
    // Compared as the server stores them, with `.pdf`: `a` and `a.pdf` are one file.
    const stored = (lang: string) => withPdf(this.fileNameDraft(lang).trim()).toLowerCase();
    const counts = new Map<string, number>();
    for (const lang of this.languages()) {
      const name = stored(lang);
      if (name !== '') {
        counts.set(name, (counts.get(name) ?? 0) + 1);
      }
    }
    // Hand-set: pinned, or holding text other than the series' name. A box
    // holding that name typed without `.pdf` still follows the series.
    const edits = this.fileNameEdits();
    const pins = this.storedFileNameFlags();
    const handSet = (lang: string) => {
      const edit = edits[lang];
      return (
        edit !== undefined &&
        (pins[lang] === true || withPdf(edit.trim()) !== this.fileNameSuggestion(lang))
      );
    };
    return this.languages().filter((lang) => {
      const name = stored(lang);
      return name !== '' && (counts.get(name) ?? 0) > 1 && handSet(lang);
    });
  });

  // ------------------------------------------------------------- 3. numbering

  protected weekInput(): string {
    return this.weekDraft();
  }
  protected yearInput(): string {
    return this.yearDraft();
  }
  protected editionInput(): string {
    return this.editionDraft();
  }

  protected onWeekInput(value: string): void {
    this.weekDraft.set(value);
  }
  protected onYearInput(value: string): void {
    this.yearDraft.set(value);
  }
  protected onEditionInput(value: string): void {
    this.editionDraft.set(value);
  }

  /**
   * Which label boxes this series' documents actually print.
   *
   * A box for a value the document never prints would collect text nobody ever
   * sees — a yearly compilation has no week — so the rule is read from the series
   * rather than assumed, in the same one place the marks and the save read it.
   * The edition has no test: every publication carries one.
   */
  protected readonly printedLabels = computed(() => this.defaults().numbering);

  /**
   * The instant the derived numbers came from, for the hint that names it.
   *
   * `undefined` where the issue records none, and the hint then says only that
   * the boxes are free text — a sentence naming a cut-off that is not there
   * would be describing nothing.
   */
  protected readonly cutoffText = computed(() => {
    const issue = this.issue();
    const cutoff = issue?.intervalTo ?? issue?.cutoffStampedAt;
    return cutoff ? this.localeFormat.formatDate(cutoff) : undefined;
  });

  /**
   * What the hint under the boxes says, and what it needs to say it.
   *
   * Two sentences, because the state changes what is worth telling somebody. An
   * untouched issue is described by where its numbers CAME from — the cut-off,
   * named — with the freedom to rewrite them offered second. Once they have been
   * rewritten the cut-off is no longer what prints, so the sentence leads with
   * the freedom that was taken and says what follows from it.
   */
  protected readonly numberingHint = computed(() => {
    const cutoff = this.cutoffText();
    return !this.marks().numbering && cutoff !== undefined
      ? { key: 'admin.publications.overrides.numberingHintDerived', params: { cutoff } }
      : { key: 'admin.publications.overrides.numberingHintFree', params: {} };
  });

  /** Put the offered labels back, so the cut-off's own numbers print again. */
  protected followCutoff(): void {
    const derived = this.derived();
    const printed = this.printedLabels();
    // A label the series does not print has no box and is never sent, so putting
    // it back here would be resetting something nobody can see — and the save
    // would leave it exactly where it stood anyway.
    if (printed.week) {
      this.weekDraft.set(derived.week);
    }
    if (printed.year) {
      this.yearDraft.set(derived.year);
    }
  }

  /**
   * An ERASED edition, which is not a way to clear the field.
   *
   * The server refuses a blank edition, so the drawer refuses it first and says
   * so in place rather than sending a save that comes back as a code. An issue
   * that never carried one is a different thing and is not an error: the box is
   * simply empty, nothing is sent for it, and the rest of the drawer stays
   * usable — a file name should not be un-editable because a row imported years
   * ago never recorded which edition it was.
   */
  protected readonly editionInvalid = computed(
    () => this.editionDraft().trim() === '' && this.derived().edition !== '',
  );

  // ---------------------------------------------------------------- 4. report

  protected reportIdInput(): string {
    return this.reportIdDraft();
  }

  protected onReportSelect(event: Event): void {
    this.reportIdDraft.set((event.target as HTMLSelectElement).value);
  }

  protected followSeriesReport(): void {
    this.reportIdDraft.set('');
  }

  protected readonly reportOverridden = computed(() => this.marks().report);

  /** What the series' report is called, for the option that follows it. */
  protected readonly seriesReportName = computed(() => {
    const reportId = this.defaults().reportId;
    if (reportId === '') {
      return undefined;
    }
    return this.reports().find((report) => report.reportId === reportId)?.name ?? reportId;
  });

  // ------------------------------------------------------ 5. report parameters

  /**
   * One row per parameter the report can be given, series-first.
   *
   * The series' keys in their own order, then any the issue carries that the
   * series does not — an issue may answer a parameter the series never named.
   * The reserved four are absent from both: they come from the numbering above
   * and are injected, and a row repeating one would be a second source for a
   * value that is already decided.
   */
  protected readonly paramRows = computed<
    readonly { key: string; seriesValue: string | undefined; value: string }[]
  >(() => {
    const series = this.defaults().reportParams;
    const issue = this.paramValues();
    const keys = [
      ...Object.keys(series).filter((key) => !isReservedReportParamKey(key)),
      ...Object.keys(issue).filter((key) => !isReservedReportParamKey(key) && !(key in series)),
    ];
    return keys.map((key) => ({
      key,
      seriesValue: series[key],
      value: issue[key] ?? '',
    }));
  });

  protected readonly added = computed(() => this.addedParams());

  /** What the series says for a row somebody is still naming. */
  protected addedSeriesValue(key: string): string | undefined {
    return this.defaults().reportParams[key.trim()];
  }

  protected onParamValueInput(key: string, value: string): void {
    this.paramValues.update((values) => ({ ...values, [key]: value }));
  }

  protected addParam(): void {
    this.addedParams.update((rows) => [...rows, { id: this.nextAddedId++, key: '', value: '' }]);
  }

  protected removeAddedParam(id: number): void {
    this.addedParams.update((rows) => rows.filter((row) => row.id !== id));
  }

  protected onAddedKeyInput(id: number, key: string): void {
    this.addedParams.update((rows) => rows.map((row) => (row.id === id ? { ...row, key } : row)));
  }

  protected onAddedValueInput(id: number, value: string): void {
    this.addedParams.update((rows) => rows.map((row) => (row.id === id ? { ...row, value } : row)));
  }

  protected isReservedKey(key: string): boolean {
    return key.trim() !== '' && isReservedReportParamKey(key);
  }

  /** A reserved key typed into an added row: refused here, and refused on save. */
  protected readonly reservedAdded = computed(() =>
    this.addedParams().some((row) => this.isReservedKey(row.key)),
  );

  /**
   * The parameters this issue would carry — blank values dropped.
   *
   * A blank box IS "follows the series": the key is simply absent from the
   * issue's map, and the series' value is what the render then reads.
   */
  private readonly effectiveParams = computed<Readonly<Record<string, string>>>(() => {
    const out: Record<string, string> = {};
    for (const [key, value] of Object.entries(this.paramValues())) {
      if (!isReservedReportParamKey(key) && value.trim() !== '') {
        out[key] = value;
      }
    }
    for (const row of this.addedParams()) {
      const key = row.key.trim();
      if (key !== '' && !isReservedReportParamKey(key) && row.value.trim() !== '') {
        out[key] = row.value;
      }
    }
    return out;
  });

  // ---------------------------------------------------------------- the save

  /**
   * Hand every group back to the series at once.
   *
   * Every group is dropped rather than rewritten: the boxes then render what the
   * series would say through the numbering the cut-off derived, and the save
   * sends a clear for each field a pin is left on.
   */
  protected resetAll(): void {
    this.followCutoff();
    this.followSeriesNames();
    this.followSeriesFileNames();
    this.followSeriesReport();
    this.paramValues.set({});
    this.addedParams.set([]);
  }

  /** Whether there is anything to reset — the footer's own gate. */
  protected readonly resettable = computed(() => this.marks().factCount > 0 || this.changed());

  /**
   * What a save would send — and nothing more.
   *
   * Built rather than tracked as interaction: typing a value back to what it
   * already was is not a change, and a request round-tripping a field in order
   * to change a different one eventually round-trips a stale value.
   */
  protected readonly patch = computed<IssueOverridesPatch>(() => {
    const issue = this.seededIssue();
    if (!issue) {
      return {};
    }
    const patch: IssueOverridesPatch = {};

    const names: Record<string, string> = {};
    const fileNames: Record<string, string> = {};
    for (const lang of this.languages()) {
      const desc = issue.descs?.find((d) => d.lang === lang);

      // A box still following the series sends NOTHING, and the server derives
      // the name from the labels this same save carries. Sending the expansion
      // would pin the name to today's rendering of a pattern it is supposed to
      // go on tracking — the opposite of what the control offering it says.
      //
      // "Following" is decided here rather than read off the keystroke that
      // wrote it, because the suggestion MOVES: a name typed by hand under one
      // week label is the series' own name once the label is rewritten to the
      // one it was typed for, and the mark beside the box goes out. A save that
      // still pinned it would be the one thing on this screen nobody can see.
      //
      // A field carrying a PIN is read the other way, and the mark says that
      // too: it goes on wearing one however its text reads, so a save that let
      // the suggestion catching up release the pin would take back a decision
      // while the screen said it still stood.
      //
      // The empty string goes where a pin is left to take back, and only there.
      // Absent would leave that pin standing for ever, and a blank name on a
      // field that was never pinned is a clear of nothing.
      const namePinned = desc?.nameOverridden === true;
      const nameEdit = this.nameEdits()[lang]?.trim();
      if (nameEdit === undefined || (!namePinned && nameEdit === this.nameSuggestion(lang))) {
        if (namePinned) {
          names[lang] = '';
        }
      } else {
        const stored = desc?.name?.trim() ?? '';
        // Sent where the text moved — and also where it agrees with text that
        // carries no pin yet, because the pin is what the save is FOR and a
        // request that said nothing would never set it.
        if (nameEdit !== '' && (nameEdit !== stored || !namePinned)) {
          names[lang] = nameEdit;
        }
      }

      // The same reading, for the same reason: the file-name boxes expand the
      // same numbering, so a hand-typed name is the series' own the moment the
      // labels are rewritten to the ones it was typed for.
      const filePinned = desc?.fileNameOverridden === true;
      const fileEdit = this.fileNameEdits()[lang]?.trim();
      if (
        fileEdit === undefined ||
        (!filePinned && withPdf(fileEdit) === this.fileNameSuggestion(lang))
      ) {
        if (filePinned) {
          fileNames[lang] = '';
        }
      } else {
        const stored = desc?.fileName?.trim() ?? '';
        // An emptied box says what the suggestion says, and is sent as the clear
        // that releases the pin. Nothing is sent where there is no pin and no
        // text to take back.
        if (fileEdit !== stored || (fileEdit !== '' && !filePinned)) {
          fileNames[lang] = fileEdit;
        }
      }
    }
    if (Object.keys(names).length > 0) {
      patch.names = names;
    }
    if (Object.keys(fileNames).length > 0) {
      patch.fileNames = fileNames;
    }

    const derived = this.derived();
    // A box holding the derived number is not a label — it is the absence of
    // one, sent as the empty string that clears it. `undefined` means the label
    // did not move at all, and the field is then left out of the request.
    const label = (draft: string, stored: string | undefined, derivedValue: string) => {
      const typed = draft.trim();
      const desired = typed === '' || typed === derivedValue ? '' : typed;
      const current = stored?.trim() ?? '';
      return desired === current ? undefined : desired;
    };
    // A label with no box is left alone entirely, clears included: the drawer
    // never offered it, so a save from here has no opinion about it and must not
    // wipe a value some other screen or an import wrote.
    const printed = this.printedLabels();
    const week = printed.week ? label(this.weekDraft(), issue.weekLabel, derived.week) : undefined;
    if (week !== undefined) {
      patch.weekLabel = week;
    }
    const year = printed.year ? label(this.yearDraft(), issue.yearLabel, derived.year) : undefined;
    if (year !== undefined) {
      patch.yearLabel = year;
    }

    const edition = this.editionDraft().trim();
    if (edition !== '' && edition !== derived.edition) {
      patch.edition = edition;
    }

    const reportId = this.reportIdDraft().trim();
    const storedReportId = issue.reportId?.trim() ?? '';
    if (reportId !== storedReportId) {
      // The empty option IS "follow the series", and the empty string is how the
      // server is told so — absent would leave the issue on its own report.
      patch.reportId = reportId;
    }

    const params = this.effectiveParams();
    const stored = paramText(issue.reportParams);
    if (!sameParams(params, stored)) {
      patch.reportParams = { ...params };
    }
    return patch;
  });

  protected readonly changed = computed(() => Object.keys(this.patch()).length > 0);

  protected readonly canSave = computed(
    () =>
      this.changed() &&
      !this.saving() &&
      !this.editionInvalid() &&
      !this.reservedAdded() &&
      this.fileNameClash().length === 0,
  );

  protected submit(): void {
    if (!this.canSave()) {
      return;
    }
    this.save.emit(this.patch());
  }
}

/** Two parameter maps that would render the same report. */
function sameParams(
  a: Readonly<Record<string, string>>,
  b: Readonly<Record<string, string>>,
): boolean {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) {
    return false;
  }
  return keys.every((key) => a[key] === b[key]);
}
