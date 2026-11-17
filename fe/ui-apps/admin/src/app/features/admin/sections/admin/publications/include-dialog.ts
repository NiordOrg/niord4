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
import { form, FormField, maxLength, minLength, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
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

import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { Autocomplete } from '../../../../../shared/forms/autocomplete/autocomplete';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import type { Message } from '../../../../messages/models/message.model';

/** The bounds the server enforces on a curation reason, restated so the form refuses first. */
const REASON_MIN = 3;
const REASON_MAX = 512;

/** How long the box must be left alone before the corpus is searched. */
const DEFAULT_DEBOUNCE_MS = 250;

/** How many candidates one search offers. A pick list, not a result page. */
const MAX_HITS = 8;

/** The two notice types whose short id carries a letter in the message lists. */
const TYPE_SUFFIXES: Readonly<Record<string, string>> = {
  TEMPORARY_NOTICE: '(T)',
  PRELIMINARY_NOTICE: '(P)',
};

/** What the admin decided: which message, and why it belongs here. */
export interface IncludeRequest {
  /** A short id (`NM-123-24`) or a uid — whichever the admin had to hand. */
  readonly messageId: string;
  readonly reason: string;
}

/** One candidate, in the shape the pick list renders. */
export interface MessageHit {
  readonly id: string;
  readonly label: string;
  readonly typeSuffix: string;
  readonly title: string;
  readonly published: string;
  /** Where it sits relative to the issue's period, where that is worth saying. */
  readonly relationKey?: string;
}

/** Empty is also what a failed search reports: neither offers anything to pick. */
const NO_HITS: readonly MessageHit[] = [];

function epochOf(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return value;
  }
  if (typeof value === 'string' && value !== '') {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? undefined : parsed;
  }
  return undefined;
}

/**
 * Put a message into an issue the query did not select.
 *
 * Two fields, and both are load-bearing.
 *
 * **Which message.** Searched rather than typed from memory. The id is what is
 * printed on a message and quoted in the mail asking for it to be added, but a
 * box that takes only an id refuses a typo without saying which message was
 * meant — and an admin working from a title has nothing to type at all. The list
 * offers published messages of the issue's own desk, each with the date it went
 * out and whether that falls inside the issue's period, because the commonest
 * reason to add one by hand is precisely that it does not.
 *
 * The box still accepts free text: an id pasted from elsewhere resolves
 * server-side, and a message the search cannot reach must not become one nobody
 * can add.
 *
 * **Why.** Mandatory, 3–512, and it is the whole point: an include is a decision
 * somebody took against the query, and the reason is the only part of it that a
 * reader of the member list months later can use.
 */
@Component({
  selector: 'app-publication-include-dialog',
  imports: [Dialog, Autocomplete, FormField, TranslocoPipe],
  templateUrl: './include-dialog.html',
  host: { class: 'contents' },
})
export class PublicationIncludeDialog {
  readonly visible = model(false);

  readonly working = input(false);

  /** What the issue is called, so the header says which one this adds to. */
  readonly issueName = input<string | undefined>(undefined);

  /** The desk that owns the publication — candidates come from it, not from everywhere. */
  readonly domainId = input<string | undefined>(undefined);

  /**
   * What that desk is CALLED, for the sentence under the box.
   *
   * Resolved by the caller, which holds the domain catalogue: the id is what the
   * search is filtered by, and `niord-nm` is not what anybody calls the desk
   * they work at.
   */
  readonly domainName = input<string | undefined>(undefined);

  /** The issue's content period, for the "lies outside it" note on a row. */
  readonly intervalFrom = input<number | undefined>(undefined);
  readonly intervalTo = input<number | undefined>(undefined);

  /**
   * Zero searches immediately, which is what a test wants and no screen does.
   *
   * A debounce is a fact about typing, so it belongs to the component rather
   * than to the pipeline underneath it.
   */
  readonly debounceMs = input(DEFAULT_DEBOUNCE_MS);

  /** A refusal, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  /** The server's own sentence, which is where the offending ids are named. */
  readonly errorDetail = input<string | undefined>(undefined);

  readonly submitted = output<IncludeRequest>();

  private readonly api = inject(MessagesApiService);
  private readonly localeFormat = inject(LocaleFormatService);

  private readonly model = signal<{ messageId: string; reason: string }>({
    messageId: '',
    reason: '',
  });

  protected readonly includeForm = form(this.model, (path) => {
    required(path.messageId);
    required(path.reason);
    minLength(path.reason, REASON_MIN);
    maxLength(path.reason, REASON_MAX);
  });

  /** What the box holds, which is both the search term and the value submitted. */
  protected readonly messageId = computed(() => this.model().messageId);

  constructor() {
    // Cleared when the dialog OPENS. A message id left over from the last
    // decision would be submitted against a different issue by whoever pressed
    // the button without reading the box.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.model.set({ messageId: '', reason: '' });
          this.includeForm().reset();
        });
      }
    });
  }

  /**
   * The candidates, re-asked as the box is typed into and never otherwise.
   *
   * Published only, and inside the owning desk: an include puts a message into a
   * document that is about to go out, so a draft nobody has released and a
   * message belonging to another desk are not answers to this question. A failed
   * search offers nothing rather than an error — the box still takes an id, and
   * a search that could not run must not stop somebody who knows exactly which
   * message they want.
   */
  private readonly hits = toSignal(
    toObservable(
      computed(() => ({
        query: this.model().messageId.trim(),
        domainId: this.domainId(),
        debounce: this.debounceMs(),
      })),
    ).pipe(
      distinctUntilChanged((a, b) => a.query === b.query && a.domainId === b.domainId),
      switchMap((request) => {
        if (request.query.length < 2) {
          return of(NO_HITS);
        }
        const params = new URLSearchParams();
        params.set('query', request.query);
        params.set('status', 'PUBLISHED');
        if (request.domainId) {
          params.set('domain', request.domainId);
        }
        const fetch: Observable<readonly MessageHit[]> = this.api
          .searchMessages(params.toString(), 0, MAX_HITS)
          .pipe(
            map((result) => (result.data ?? []).map((message) => this.toHit(message))),
            catchError(() => of(NO_HITS)),
          );
        const delayed: Observable<readonly MessageHit[]> =
          request.debounce > 0 ? timer(request.debounce).pipe(switchMap(() => fetch)) : fetch;
        return delayed.pipe(startWith(NO_HITS));
      }),
    ),
    { initialValue: NO_HITS },
  );

  protected readonly suggestions = computed(() => this.hits());

  private toHit(message: Message): MessageHit {
    const published = epochOf(message.publishDateFrom);
    return {
      id: message.id,
      label: message.shortId ?? message.id,
      typeSuffix: (message.type && TYPE_SUFFIXES[message.type]) ?? '',
      title: message.descs?.[0]?.title ?? '',
      published: published === undefined ? '' : this.localeFormat.formatDate(published),
      relationKey: this.relationKey(published),
    };
  }

  /**
   * Whether a candidate falls outside the issue's period.
   *
   * `undefined` when it falls inside, which is the ordinary case and needs no
   * note. When it does not, that IS the reason somebody is adding it by hand,
   * and saying so beside the row stops the wrong message being picked because
   * two editions carry near-identical titles.
   */
  private relationKey(published: number | undefined): string | undefined {
    if (published === undefined) {
      return undefined;
    }
    const from = this.intervalFrom();
    const to = this.intervalTo();
    if (from !== undefined && published < from) {
      return 'admin.publications.curation.beforePeriod';
    }
    if (to !== undefined && published > to) {
      return 'admin.publications.curation.afterPeriod';
    }
    return undefined;
  }

  /**
   * The desk to name in the hint — its name, or the id where nothing named it.
   *
   * Empty for a publication that belongs to no desk, which is a real
   * arrangement: the search then spans every desk, and {@link hintKey} says that
   * instead of naming one.
   */
  protected readonly domainLabel = computed(() => this.domainName() ?? this.domainId() ?? '');

  protected readonly hintKey = computed(() =>
    this.domainLabel()
      ? 'admin.publications.curation.messageIdHint'
      : 'admin.publications.curation.messageIdHintAnyDomain',
  );

  protected readonly headerKey = computed(() =>
    this.issueName()
      ? 'admin.publications.curation.includeTitleNamed'
      : 'admin.publications.curation.includeTitle',
  );

  protected onQuery(value: string): void {
    this.model.update((current) => ({ ...current, messageId: value }));
  }

  /** A picked row replaces what was typed with the id the override is addressed by. */
  protected onPick(items: readonly MessageHit[]): void {
    const picked = items[0];
    if (picked) {
      this.model.update((current) => ({ ...current, messageId: picked.label }));
    }
  }

  protected onClear(): void {
    this.model.update((current) => ({ ...current, messageId: '' }));
  }

  protected hitLabel = (hit: MessageHit): string => hit.label;

  // Measured on the TRIMMED text, which is what is submitted and what the server
  // counts. Every read is unconditional: a short-circuited `||` would leave the
  // later signals untracked on the pass that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const invalid = this.includeForm().invalid();
    const value = this.model();
    const idLength = value.messageId.trim().length;
    const reasonLength = value.reason.trim().length;
    const working = this.working();
    return (
      working || invalid || idLength === 0 || reasonLength < REASON_MIN || reasonLength > REASON_MAX
    );
  });

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    const value = this.model();
    this.submitted.emit({
      messageId: value.messageId.trim(),
      reason: value.reason.trim(),
    });
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
