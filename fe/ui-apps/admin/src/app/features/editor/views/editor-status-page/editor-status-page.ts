import { Component, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Card } from '../../../../shared/components/card/card';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import { EditorViewHeader } from '../../components/editor-view-header/editor-view-header';
import { MessageIssuesPanel } from '../../components/message-issues-panel/message-issues-panel';
import { StatusActionPanel } from '../../components/status-action-panel/status-action-panel';
import { EditorFacade } from '../../services/editor-facade';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

/**
 * Status view-mode page: a read-only message preview (left) beside the status action panel (right).
 * Deep-linkable — drives a standalone load by `:id`. A content-language pill switcher (one pill per
 * configured model language, defaulting to the current app language) drives the language-parametric
 * preview renderer independently of the app language — a local, non-persisted choice.
 *
 * The right column stacks the status actions over the publications panel: "where has this gone
 * out, and where is it about to" is the other half of the question this page answers, and it is
 * the one an editor needs before deciding whether a correction is still worth making.
 */
@Component({
  selector: 'app-editor-status-page',
  imports: [
    LanguageFlag,
    TranslocoPipe,
    Icon,
    Card,
    EditorViewHeader,
    MessageDetailsRenderer,
    StatusActionPanel,
    MessageIssuesPanel,
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
        <div class="flex flex-1 flex-col gap-4 overflow-auto p-4 md:flex-row md:items-start">
          <app-card
            [header]="'editor.status.renderedMessage' | transloco"
            sectionClass="min-w-0 flex-1"
          >
            <div
              cardActions
              class="flex gap-0.5"
              role="group"
              [attr.aria-label]="'editor.status.previewLanguage' | transloco"
            >
              @for (lang of languages; track lang.value) {
                <button
                  type="button"
                  class="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-semibold text-fg-subtle"
                  [class.bg-surface-0]="previewLang() === lang.value"
                  [class.text-fg]="previewLang() === lang.value"
                  [class.shadow-niord-sm]="previewLang() === lang.value"
                  [attr.aria-pressed]="previewLang() === lang.value"
                  (click)="previewLang.set(lang.value)"
                >
                  <app-language-flag
                    class="h-3 w-[1.125rem] rounded-xs"
                    [lang]="lang.value"
                    [flagPath]="lang.flagPath"
                    [codeFallback]="false"
                  />
                  {{ lang.value.toUpperCase() }}
                </button>
              }
            </div>
            <app-message-details-renderer
              [message]="message"
              [language]="previewLang()"
              [showPromulgation]="true"
            />
          </app-card>
          <div class="flex w-full shrink-0 flex-col gap-4 md:w-80">
            <app-card [padded]="false">
              <app-status-action-panel />
            </app-card>
            <app-card [header]="'editor.status.publications.title' | transloco" [padded]="false">
              <app-message-issues-panel [messageUid]="message.id" />
            </app-card>
          </div>
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
export class EditorStatusPage {
  private readonly route = inject(ActivatedRoute);
  private readonly language = inject(LanguageService);
  protected readonly facade = inject(EditorFacade);

  /** Configured model languages — one preview pill each (flag + code), scales to N languages. */
  protected readonly languages = this.language.modelLanguages;
  /** Locally-selected preview content language; snapshots the app language, then independent of it. */
  protected readonly previewLang = signal(this.language.selectedLanguage());

  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });

  constructor() {
    // Standalone load by :id (deep-linkable). Idempotent: a tab-switch from the edit view, which
    // already holds this saved message, does not refetch.
    effect(() => {
      const id = this.params().get('id') ?? undefined;
      untracked(() => this.facade.loadExisting(id));
    });
  }
}
