import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { Card } from '../../../../shared/components/card/card';
import { JsonDiffPane } from '../../../../shared/components/json-diff-pane/json-diff-pane';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { MessageAttachment } from '../../../messages/components/message-attachment/message-attachment';
import type { MessageAttachment as MessageAttachmentModel } from '../../../messages/models/message.model';
import type { MessageHistory } from '../../../messages/models/message-history.model';
import { Icon } from '../../../../shared/icons/icon';
import { EditorViewHeader } from '../../components/editor-view-header/editor-view-header';
import { RevisionTable } from '../../components/revision-table/revision-table';
import { EditorFacade } from '../../services/editor-facade';

/**
 * History view-mode page: a revision table (≤2 selectable) beside a recursive JSON-diff pane, plus
 * the single-revision attachment list. Deep-linkable. History is not language-tagged, so it does
 * not refetch on language change.
 */
@Component({
  selector: 'app-editor-history-page',
  imports: [
    TranslocoPipe,
    Icon,
    Card,
    EditorViewHeader,
    RevisionTable,
    JsonDiffPane,
    MessageAttachment,
    ProgressBar,
  ],
  template: `
    <app-editor-view-header />

    @if (facade.loadStatus() === 'error') {
      <!-- Retry only when it can help; a 403 (another domain, or no right to open the message)
           offers the way back to Start instead. -->
      <div
        class="m-4 flex flex-col items-start gap-3 rounded border border-danger/40 bg-danger/5 p-4"
        data-testid="editor-load-error"
      >
        <p class="text-sm text-danger">{{ facade.loadErrorPanel() | transloco }}</p>
        @if (facade.canRetryLoad()) {
          <button
            type="button"
            class="btn-primary"
            data-testid="editor-load-retry"
            (click)="facade.retryLoad()"
          >
            <app-icon name="lucideRefreshCw" />
            <span>{{ 'editor.load.retry' | transloco }}</span>
          </button>
        } @else {
          <button
            type="button"
            class="btn-primary"
            data-testid="editor-load-to-start"
            (click)="facade.leaveToStart()"
          >
            <app-icon name="lucideArrowLeft" />
            <span>{{ 'editor.load.toStart' | transloco }}</span>
          </button>
        }
      </div>
    } @else if (facade.message(); as message) {
      @if (message.created) {
        <div class="flex flex-1 flex-col overflow-auto p-4">
          <!-- Unpadded so the revision table runs edge-to-edge like the other list
               tables; the compare/attachment sections below re-add their own padding. -->
          <app-card [padded]="false">
            <app-revision-table
              [revisions]="history()"
              [selected]="selected()"
              (toggleRevision)="toggle($event)"
            />

            <div class="flex flex-col gap-4 p-4">
              <section class="flex flex-col gap-2">
                <h3 class="text-sm font-semibold">
                  @switch (selected().length) {
                    @case (0) {
                      {{ 'editor.history.noRevision' | transloco }}
                    }
                    @case (1) {
                      {{
                        'editor.history.revision'
                          | transloco: { version: orderedSelection()[0].version }
                      }}
                    }
                    @default {
                      {{
                        'editor.history.comparing'
                          | transloco
                            : { a: orderedSelection()[0].version, b: orderedSelection()[1].version }
                      }}
                    }
                  }
                </h3>
                <div class="flex gap-4 text-xs">
                  <span class="text-[var(--niord-diff-added)]">
                    {{ 'editor.history.legend.added' | transloco }}
                  </span>
                  <span class="text-[var(--niord-diff-changed)]">
                    {{ 'editor.history.legend.changed' | transloco }}
                  </span>
                  <span class="text-[var(--niord-diff-removed)]">
                    {{ 'editor.history.legend.removed' | transloco }}
                  </span>
                </div>
                <app-json-diff-pane [snapshots]="selectedSnapshots()" />
              </section>

              @if (attachments(); as atts) {
                <section class="flex flex-wrap gap-2">
                  @for (att of atts; track $index) {
                    <app-message-attachment [attachment]="att" [size]="32" />
                  }
                </section>
              }
            </div>
          </app-card>
        </div>
      } @else {
        <p class="p-8 text-center text-fg-muted">{{ 'editor.emptyState' | transloco }}</p>
      }
    } @else {
      <app-progress-bar [visible]="true" />
    }
  `,
  host: { class: 'flex flex-1 flex-col' },
})
export class EditorHistoryPage {
  private readonly route = inject(ActivatedRoute);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  protected readonly facade = inject(EditorFacade);

  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });

  // The message id to fetch history for — a memoized PRIMITIVE so message-object writes that
  // don't change the id don't re-run the request factory (the comments page's identical guard).
  private readonly historyMessageId = computed<string | undefined>(() => {
    const message = this.facade.message();
    return message?.created ? message.id : undefined;
  });

  // Keyed on (id, loadSeq): reading `loadSeq` refetches whenever the message is (re)loaded from
  // the backend — a "Reload message" resolves the SAME id, so keying only on the id would keep a
  // stale revision list on screen (same convention as the comments page's resource). The request
  // is an OBJECT so each factory re-run forces the refetch (an identical URL string would not).
  private readonly historyResource = httpResource<readonly MessageHistory[]>(
    () => {
      this.facade.loadSeq();
      const id = this.historyMessageId();
      if (!id) return undefined;
      return { url: `${this.apiBaseUrl}/messages/message/${encodeURIComponent(id)}/history` };
    },
    { defaultValue: [] },
  );

  // A failed fetch leaves the resource in its error state (value() throws); read via hasValue()
  // so an outage degrades to an empty list — the template reads this unguarded, which would
  // otherwise throw during change detection.
  protected readonly history = computed<readonly MessageHistory[]>(() =>
    this.historyResource.hasValue() ? this.historyResource.value() : [],
  );
  protected readonly selected = signal<readonly MessageHistory[]>([]);

  // The selection ordered oldest→newest by version, so the diff is deterministic and independent of
  // click order: the older revision is always the left/old (removed) side and the newer the
  // right/new (added) side, and the "Comparing #a and #b" header reads the same way.
  protected readonly orderedSelection = computed<readonly MessageHistory[]>(() =>
    [...this.selected()].sort((a, b) => a.version - b.version),
  );

  protected readonly selectedSnapshots = computed<readonly string[]>(() =>
    this.orderedSelection().map((revision) => revision.snapshot),
  );

  /**
   * Snapshot attachments, only when exactly one revision is selected. The container shows whenever
   * the snapshot carries an `attachments` ARRAY — including an empty one (a truthy-but-empty `[]`
   * renders the container with nothing inside) — and stays hidden when the field is absent
   * or the snapshot fails to parse (the attachments list stays undefined in those cases).
   */
  protected readonly attachments = computed<readonly MessageAttachmentModel[] | undefined>(() => {
    const selected = this.selected();
    if (selected.length !== 1) {
      return undefined;
    }
    try {
      const parsed = JSON.parse(selected[0].snapshot) as { attachments?: MessageAttachmentModel[] };
      return Array.isArray(parsed.attachments) ? parsed.attachments : undefined;
    } catch {
      return undefined;
    }
  });

  constructor() {
    effect(() => {
      const id = this.params().get('id') ?? undefined;
      untracked(() => this.facade.loadExisting(id));
    });
    // Clear the revision selection whenever the resolved history changes (the message id changed),
    // so revisions picked for one message can't leak into another's diff/attachment panes — the
    // table matches rows by version number alone, which would otherwise mis-highlight.
    effect(() => {
      this.history();
      untracked(() => this.selected.set([]));
    });
  }

  /** Toggle a revision in/out of the ≤2 selection. */
  protected toggle(revision: MessageHistory): void {
    const selected = this.selected();
    if (selected.some((candidate) => candidate.version === revision.version)) {
      this.selected.set(selected.filter((candidate) => candidate.version !== revision.version));
    } else if (selected.length < 2) {
      this.selected.set([...selected, revision]);
    }
  }
}
