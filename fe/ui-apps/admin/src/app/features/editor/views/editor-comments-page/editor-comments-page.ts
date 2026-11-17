import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Card } from '../../../../shared/components/card/card';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';
import type { MessageComment } from '../../../messages/models/message-comment.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { CommentCard } from '../../components/comment-card/comment-card';
import { CommentEditor } from '../../components/comment-editor/comment-editor';
import { EditorViewHeader } from '../../components/editor-view-header/editor-view-header';
import { EditorCommentsStore } from '../../services/editor-comments.store';
import { EditorFacade } from '../../services/editor-facade';

/**
 * Comments view-mode page: a server-ordered thread of comment cards over a persistent "Add comment"
 * compose card. Each card owns its own inline reply / edit editor; the compose card and every reply
 * create a new comment, Edit updates an existing one — the page only routes the emitted comment to
 * create vs update by the presence of an id. The thread IS language-tagged, so it refetches on a
 * language change (the route-provided store owns that fetch, so the tab badge is correct before this
 * page is ever opened); a thread change resets the compose draft. The compose draft itself is kept
 * on that store too, so a tab switch does not lose a half-typed comment, and the leave guards and
 * the reload prompt ask about it.
 */
@Component({
  selector: 'app-editor-comments-page',
  imports: [TranslocoPipe, Icon, EditorViewHeader, Card, CommentCard, CommentEditor, ProgressBar],
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
        <div class="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-4 overflow-auto p-4">
          @for (comment of comments(); track comment.id) {
            <app-comment-card
              [comment]="comment"
              [canEdit]="canEdit(comment)"
              (acknowledge)="acknowledge(comment)"
              (save)="save($event)"
            />
          } @empty {
            <p class="text-center text-sm text-fg-muted">
              {{ 'editor.comments.none' | transloco }}
            </p>
          }

          <app-card [header]="'editor.comments.add' | transloco">
            <app-comment-editor
              [comment]="composeSeed()"
              [newComment]="true"
              [draft]="commentsStore.composeDraft()"
              (draftChange)="commentsStore.setComposeDraft($event)"
              (save)="saveCompose($event)"
            />
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
export class EditorCommentsPage {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(MessagesApiService);
  private readonly language = inject(LanguageService);
  private readonly notify = inject(NotificationService);
  protected readonly facade = inject(EditorFacade);
  protected readonly commentsStore = inject(EditorCommentsStore);

  private readonly params = toSignal(this.route.paramMap, {
    initialValue: this.route.snapshot.paramMap,
  });

  // Which thread the compose draft belongs to — a memoized PRIMITIVE so message writes that
  // replace the object for unrelated reasons never look like a thread change and wipe the draft.
  // Gated on `created`: a never-saved draft has no thread.
  private readonly commentMessageId = computed<string | undefined>(() => {
    const message = this.facade.message();
    return message?.created ? message.id : undefined;
  });

  // The thread lives on the route-provided store so the tab badge is correct on every tab, not
  // only once this page has been visited. This page is one of two readers.
  protected readonly comments = this.commentsStore.comments;

  // A fresh, id-less comment bound to the persistent "Add comment" editor. Reset to a NEW object
  // only when the compose draft is actually consumed (posted) or the THREAD changes (another
  // message id) — the editor repopulates on an input-reference change, from the store's kept draft
  // if there is one. Deliberately NOT reset on every refetch: acknowledging or replying to another
  // comment reloads the thread, and that must not wipe a half-typed compose draft. The typed text
  // itself lives on the store (`composeDraft`), which outlives this page.
  protected readonly composeSeed = signal<MessageComment>({ comment: '' });

  constructor() {
    effect(() => {
      const id = this.params().get('id') ?? undefined;
      untracked(() => this.facade.loadExisting(id));
    });

    // Clear the compose draft only when the thread itself changes (a different message id), so a
    // draft typed against one message can never be posted to another.
    effect(() => {
      this.commentMessageId();
      untracked(() => this.composeSeed.set({ comment: '' }));
    });
  }

  // Edit rights: the user's own comment or an admin; a fetched card always has an id, but the
  // id-less guard keeps the predicate correct if ever reused for a fresh comment.
  protected canEdit(comment: MessageComment): boolean {
    return !comment.id || !!comment.ownComment || this.facade.isAdmin();
  }

  /** The bottom "Add comment" card posted: clear ITS draft on success, then save/reload. */
  protected saveCompose(comment: MessageComment): void {
    this.save(comment, { resetCompose: true });
  }

  protected save(comment: MessageComment, options?: { resetCompose?: boolean }): void {
    const messageId = this.facade.message()?.id;
    if (!messageId) {
      return;
    }
    const language = this.language.selectedLanguage();
    const request$ = comment.id
      ? this.api.updateComment(messageId, comment, { language })
      : this.api.createComment(messageId, comment, { language });
    request$.subscribe({
      next: () => {
        // Only the compose card's own post clears the compose draft — a reply/edit saved from a
        // comment card must not wipe an unrelated draft in the bottom editor.
        if (options?.resetCompose) {
          this.commentsStore.clearComposeDraft();
          this.composeSeed.set({ comment: '' });
        }
        this.commentsStore.reload();
      },
      error: () => this.notify.error('editor.comments.saveError'),
    });
  }

  protected acknowledge(comment: MessageComment): void {
    const messageId = this.facade.message()?.id;
    if (!messageId || !comment.id) {
      return;
    }
    this.api.acknowledgeComment(messageId, comment.id).subscribe({
      next: () => this.commentsStore.reload(),
      error: () => this.notify.error('editor.comments.ackError'),
    });
  }
}
