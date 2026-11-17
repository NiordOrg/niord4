import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  Observable,
  catchError,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import {
  PublicationSeriesApiService,
  type IssueDraft,
} from '../../../services/api/publication-series-api.service';
import { BoundSourceMarker } from './bound-source-marker';
import { laterEdition } from './issue-number';
import { issueDraftWarningKey, publicationErrorKey } from './publication-errors';

/** What the screen knows about the period before the draft is asked for. */
export interface IssueDraftSeed {
  /** Chain from this issue's close. The form with no dates at all. */
  readonly afterPublicId?: string;
  /** The period a gap row already knows the bounds of. */
  readonly intervalFrom?: number;
  readonly intervalTo?: number;
  /**
   * Whether the period is being reconstructed after the fact.
   *
   * It decides the bound's provenance on the created issue — RECOVERED rather
   * than STAMPED — because nobody stamped it at a release.
   */
  readonly recovered: boolean;
}

/** What the admin decided, once they had seen it. */
export interface IssueDraftDecision {
  readonly intervalFrom?: number;
  readonly intervalTo?: number;
  readonly recovered: boolean;
  /**
   * What to call the issue, where the suggestion was not kept.
   *
   * Only the languages actually retyped, and absent when every box still holds
   * what the draft proposed. A name that travels is applied through the path a
   * rename uses, which records it as chosen by hand — so sending a suggestion
   * back would create an issue whose name has stopped tracking its own period
   * before anybody decided that it should.
   */
  readonly names?: Readonly<Record<string, string>>;
}

/** What the dialog holds while a draft is being asked for, answered or refused. */
interface DraftState {
  readonly draft?: IssueDraft;
  readonly loading: boolean;
  readonly errorKey?: string;
}

const EMPTY: DraftState = { loading: false };

/** How long a bound must be left alone before the draft is asked for again. */
const DEFAULT_DEBOUNCE_MS = 300;

/**
 * The issue that does not exist yet, shown before it is created.
 *
 * Creating a period retroactively used to be one click on a gap row: it posted
 * the row's own `intervalFrom` and the admin found out what they had made by
 * looking at the list afterwards. The bound a gap row carries is the PRECEDING
 * issue's close where there is one and a nominal step where there is not, so the
 * click could commit a reconstructed date as though somebody had chosen it.
 *
 * So the bounds are reviewed first. The draft comes from the server — the same
 * derivation the create will use, never a second one computed here — and it is
 * re-asked, debounced, whenever a bound is moved, so the name, the count and the
 * warnings on screen always describe the period that would actually be created.
 *
 * The dialog decides nothing and writes nothing. It emits what the admin
 * reviewed; the caller creates.
 */
@Component({
  selector: 'app-publication-issue-draft-dialog',
  imports: [BoundSourceMarker, Dialog, DateTimeField, FormField, TranslocoPipe],
  templateUrl: './issue-draft-dialog.html',
  host: { class: 'contents' },
})
export class PublicationIssueDraftDialog {
  readonly visible = model(false);

  readonly seriesId = input.required<string>();

  /** `undefined` while the dialog is closed; set as it opens. */
  readonly seed = input<IssueDraftSeed | undefined>(undefined);

  readonly working = input(false);

  /** A refused create, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  /**
   * Zero asks immediately, which is what a test wants and no screen does.
   *
   * A debounce is a fact about typing, so it belongs to the component rather
   * than to the pipeline underneath it — and a spec that has to wait 300 ms per
   * keystroke ends up asserting the timer instead of the behaviour.
   */
  readonly debounceMs = input(DEFAULT_DEBOUNCE_MS);

  readonly submitted = output<IssueDraftDecision>();

  private readonly api = inject(PublicationSeriesApiService);
  private readonly transloco = inject(TranslocoService);

  /** The bounds being reviewed, as a Signal Form so the shared pickers drive them. */
  private readonly bounds = signal<{ from: Date | null; to: Date | null }>({
    from: null,
    to: null,
  });
  protected readonly boundsForm = form(this.bounds);

  /** What the draft last put in the fields, so an edit can be told from a seed. */
  private readonly seeded = signal<{ from: number | null; to: number | null }>({
    from: null,
    to: null,
  });

  protected readonly chosenFrom = computed(() => this.bounds().from?.getTime() ?? null);
  protected readonly chosenTo = computed(() => this.bounds().to?.getTime() ?? null);

  /**
   * Whether the admin has moved a bound away from what the draft suggested.
   *
   * Derived by comparison rather than caught from a change event: the fields are
   * shared form controls with no edit callback of their own, and an event hook
   * on each would be a second thing to keep in step with the value.
   */
  private readonly touched = computed(() => {
    const from = this.chosenFrom();
    const to = this.chosenTo();
    const seeded = this.seeded();
    return from !== seeded.from || to !== seeded.to;
  });

  /**
   * What to ask the server for.
   *
   * Until a bound is moved this is the seed — chain off an issue, or the period a
   * gap row already knows — and afterwards it is what is in the fields. Every
   * signal is read before the branch, so none of them goes untracked on the pass
   * that takes the first one.
   */
  private readonly request = computed(() => {
    const open = this.visible();
    const seriesId = this.seriesId();
    const seed = this.seed();
    const touched = this.touched();
    const from = this.chosenFrom();
    const to = this.chosenTo();
    if (!open || !seed) {
      return undefined;
    }
    if (!touched) {
      return {
        seriesId,
        afterPublicId: seed.afterPublicId,
        intervalFrom: seed.intervalFrom,
        intervalTo: seed.intervalTo,
      };
    }
    return {
      seriesId,
      afterPublicId: undefined,
      intervalFrom: from ?? undefined,
      intervalTo: to ?? undefined,
    };
  });

  /**
   * The draft, re-asked whenever the period changes and never otherwise.
   *
   * Compared by VALUE rather than by identity. The request reads the bounds
   * whether or not it uses them, so seeding the fields from a draft produces a
   * fresh object describing the same question — and asking it again would seed
   * the fields again, which is a loop rather than a refresh.
   */
  private readonly draftState = toSignal(
    toObservable(this.request).pipe(
      distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
      switchMap((request) => {
        if (!request) {
          return of(EMPTY);
        }
        const fetch = this.api
          .issueDraft(request.seriesId, { ...request, lang: this.transloco.getActiveLang() })
          .pipe(
            map((draft): DraftState => ({ draft, loading: false })),
            catchError((response: unknown) =>
              of<DraftState>({
                loading: false,
                errorKey: publicationErrorKey(response, 'admin.publications.issueDraft.loadError'),
              }),
            ),
          );
        const ms = this.debounceMs();
        const delayed: Observable<DraftState> =
          ms > 0 ? timer(ms).pipe(switchMap(() => fetch)) : fetch;
        return delayed.pipe(startWith<DraftState>({ loading: true }));
      }),
    ),
    { initialValue: EMPTY },
  );

  protected readonly draft = computed(() => this.draftState().draft);
  protected readonly loading = computed(() => this.draftState().loading);
  protected readonly draftErrorKey = computed(() => this.draftState().errorKey);

  constructor() {
    // Cleared when the dialog OPENS. A period left over from the last issue
    // would be reviewed as though it described this one.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.bounds.set({ from: null, to: null });
          this.seeded.set({ from: null, to: null });
          this.nameDrafts.set({});
          this.boundsForm().reset();
        });
      }
    });

    // The recovered bounds, put in the fields for review — once. A later draft
    // is the answer to bounds the admin typed, and writing it back would undo
    // the edit that asked for it.
    effect(() => {
      const draft = this.draft();
      if (!draft) {
        return;
      }
      untracked(() => {
        if (this.touched()) {
          return;
        }
        const from = draft.intervalFrom ?? null;
        const to = draft.intervalTo ?? null;
        this.bounds.set({
          from: from === null ? null : new Date(from),
          to: to === null ? null : new Date(to),
        });
        this.seeded.set({ from, to });
      });
    });
  }

  /** The per-language name the series would give this period. */
  protected readonly names = computed(() => this.draft()?.descs ?? []);

  /**
   * The proposed edition, but only where it is not the first one.
   *
   * A new period's first document is the ordinary case, and its edition is 1 on
   * every series — a row saying so would appear on every create dialog and say
   * nothing. The one rule lives in {@link laterEdition} so that the dialog, the
   * issue header and the archive strip cannot disagree about when an edition is
   * worth printing.
   */
  protected readonly proposedEdition = computed(() => laterEdition(this.draft()?.edition));

  // ------------------------------------------------------------- the names

  /**
   * The names as typed, keyed by language. A language absent means "untouched".
   *
   * Per language rather than one flag for the set, and that is what makes the
   * fields survive a bound being moved: the name derives from the ISO week of
   * the cut-off, so the draft renames every period that is not being typed in —
   * and a language somebody HAS typed in must not be renamed underneath them.
   */
  private readonly nameDrafts = signal<Readonly<Record<string, string>>>({});

  /** What the box shows: what was typed, else what the draft proposes. */
  protected nameDraft(lang: string): string {
    const drafts = this.nameDrafts();
    return lang in drafts
      ? drafts[lang]
      : (this.names().find((desc) => desc.lang === lang)?.name ?? '');
  }

  protected onNameInput(lang: string, value: string): void {
    this.nameDrafts.update((drafts) => ({ ...drafts, [lang]: value }));
  }

  /** A language whose box has been emptied. An issue is never nameless. */
  protected nameMissing(lang: string): boolean {
    return this.nameDraft(lang).trim() === '';
  }

  /** Whether any box holds something other than the name the draft proposed. */
  private readonly namesChanged = computed(() =>
    this.names().some((desc) => this.nameDraft(desc.lang).trim() !== desc.name),
  );

  /** True while any language's box is empty — the create is refused until it is not. */
  protected readonly namesIncomplete = computed(() =>
    this.names().some((desc) => this.nameDraft(desc.lang).trim() === ''),
  );

  protected readonly warnings = computed(() =>
    (this.draft()?.warnings ?? []).map((warning) => ({
      code: warning.code,
      labelKey: issueDraftWarningKey(warning.code),
    })),
  );

  /**
   * The end lies before the start.
   *
   * Refused here as well as by the server, because a form that accepts an
   * impossible period and then reports it back is a slower way of saying no —
   * and the draft behind it would be refused too, leaving the dialog with
   * nothing to show while the fields still hold the values that emptied it.
   */
  protected readonly inverted = computed(() => {
    const from = this.chosenFrom();
    const to = this.chosenTo();
    return from !== null && to !== null && to <= from;
  });

  // Read unconditionally: a short-circuited `||` leaves the later signals
  // untracked on the pass that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const working = this.working();
    const loading = this.loading();
    const failed = this.draftErrorKey() !== undefined;
    const inverted = this.inverted();
    const missing = this.draft() === undefined;
    const nameless = this.namesIncomplete();
    return working || loading || failed || inverted || missing || nameless;
  });

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    const from = this.chosenFrom();
    const to = this.chosenTo();
    this.submitted.emit({
      intervalFrom: from ?? undefined,
      intervalTo: to ?? undefined,
      recovered: this.seed()?.recovered ?? false,
      // Only the languages that were actually retyped. A name that travels is
      // recorded as chosen by hand, so sending a suggestion back would create an
      // issue whose name has stopped tracking its own period before anybody
      // decided that it should.
      ...(this.namesChanged() ? { names: this.renamed() } : {}),
    });
  }

  /** The languages whose name was retyped, trimmed. A language absent is untouched. */
  private renamed(): Record<string, string> {
    const names: Record<string, string> = {};
    for (const desc of this.names()) {
      const typed = this.nameDraft(desc.lang).trim();
      if (typed !== desc.name) {
        names[desc.lang] = typed;
      }
    }
    return names;
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
