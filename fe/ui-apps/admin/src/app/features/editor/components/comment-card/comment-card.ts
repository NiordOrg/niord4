import { Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { MessageHtmlSanitizer } from '@niord/html/message-html-sanitizer';
import { Card } from '../../../../shared/components/card/card';
import { Icon } from '../../../../shared/icons/icon';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';
import { RelativeTimePipe } from '../../../../shared/pipes/relative-time.pipe';
import type { MessageComment } from '../../../messages/models/message-comment.model';
import { CommentEditor } from '../comment-editor/comment-editor';

/** The card's inline-editor state: collapsed, a fresh reply, or editing this comment in place. */
type Expansion = 'none' | 'reply' | 'edit';

/**
 * One comment rendered as a card: an initials avatar, author + relative time, an
 * acknowledged / unacknowledged status chip, the sanitized body, and an actions row
 * (Acknowledge / Edit / Reply). Reply and Edit each expand a rich-text {@link CommentEditor}
 * inside the card — Reply composes a new comment (no id ⇒ create), Edit rebinds this comment
 * (id present ⇒ update). The card collapses itself the instant its editor saves, so a reused
 * instance (same id across a list reload) never keeps a stale draft open.
 */
@Component({
  selector: 'app-comment-card',
  imports: [TranslocoPipe, Icon, Card, LocaleDatePipe, RelativeTimePipe, CommentEditor],
  template: `
    <app-card>
      <article class="flex flex-col gap-3">
        <header class="flex items-start justify-between gap-3">
          <div class="flex min-w-0 items-center gap-2">
            <span
              class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-fg-on-primary"
              aria-hidden="true"
            >
              {{ authorInitials() }}
            </span>
            <span class="truncate font-semibold text-fg">{{ comment().user }}</span>
            @if (comment().created) {
              <span class="shrink-0 text-sm text-fg-muted">
                · {{ comment().created | relativeTime }}
              </span>
            }
          </div>
          <span class="status-chip shrink-0" [class]="acknowledged() ? 'is-published' : 'is-draft'">
            @if (acknowledged()) {
              <app-icon name="lucideCheck" />
            }
            {{
              (acknowledged()
                ? 'editor.comments.acknowledgedLabel'
                : 'editor.comments.unacknowledged'
              ) | transloco
            }}
          </span>
        </header>

        <div class="prose-sm max-w-none text-fg" [innerHTML]="sanitizedBody()"></div>

        <div class="border-t border-dashed border-surface-200 dark:border-surface-700"></div>

        @if (acknowledged()) {
          <p class="flex items-center gap-2 text-sm text-fg-muted">
            <span
              class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] font-semibold text-fg-on-primary"
              aria-hidden="true"
            >
              {{ ackInitials() }}
            </span>
            {{
              'editor.comments.acknowledgedByOn'
                | transloco
                  : {
                      user: comment().acknowledgedBy,
                      date: comment().acknowledgeDate | localeDate: 'dateTime',
                    }
            }}
          </p>
        }

        <div class="flex items-center gap-4 text-sm">
          @if (canAcknowledge()) {
            <button type="button" class="link-button font-semibold" (click)="acknowledge.emit()">
              <app-icon name="lucideCheck" />
              <span>{{ 'editor.comments.acknowledge' | transloco }}</span>
            </button>
          }
          @if (canEdit()) {
            <button type="button" class="link-button is-subtle" (click)="toggle('edit')">
              {{ 'editor.comments.edit' | transloco }}
            </button>
          }
          <button type="button" class="link-button is-subtle" (click)="toggle('reply')">
            {{ 'editor.comments.reply' | transloco }}
          </button>
        </div>

        @if (expanded() !== 'none') {
          <div
            class="rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-700 dark:bg-surface-800"
          >
            <app-comment-editor
              [comment]="editorComment()"
              [newComment]="expanded() === 'reply'"
              (save)="onSave($event)"
              (acknowledge)="acknowledge.emit()"
            />
          </div>
        }
      </article>
    </app-card>
  `,
  host: { class: 'contents' },
})
export class CommentCard {
  private readonly sanitizer = inject(MessageHtmlSanitizer);

  readonly comment = input.required<MessageComment>();
  /** Whether the current user may edit this comment (own / admin) — gates the Edit affordance. */
  readonly canEdit = input(false);

  readonly acknowledge = output<void>();
  readonly save = output<MessageComment>();

  protected readonly expanded = signal<Expansion>('none');
  /** Stable blank bound to the editor in reply mode (a fresh, id-less comment ⇒ create). */
  private readonly blankReply: MessageComment = { comment: '' };
  /**
   * The comment SNAPSHOT the edit editor was opened on. Latched at open (not derived from the
   * live `comment` input) so a thread reload — which replaces the input with a fresh object of
   * the same id — cannot repopulate the open editor and silently revert an in-progress edit.
   */
  private readonly editSeed = signal<MessageComment>({ comment: '' });

  protected readonly acknowledged = computed(() => this.comment().acknowledgeDate !== undefined);
  protected readonly canAcknowledge = computed(() => !!this.comment().id && !this.acknowledged());
  protected readonly sanitizedBody = computed(() =>
    this.sanitizer.sanitize(this.comment().comment),
  );

  protected readonly authorInitials = computed(() => initials(this.comment().user));
  protected readonly ackInitials = computed(() => initials(this.comment().acknowledgedBy));

  protected readonly editorComment = computed<MessageComment>(() =>
    this.expanded() === 'edit' ? this.editSeed() : this.blankReply,
  );

  protected toggle(mode: Exclude<Expansion, 'none'>): void {
    this.expanded.update((current) => (current === mode ? 'none' : mode));
    if (this.expanded() === 'edit') {
      this.editSeed.set(this.comment());
    }
  }

  protected onSave(comment: MessageComment): void {
    // Collapse before emitting so a list reload that reuses this instance (tracked by id) can't
    // leave the editor open on a just-saved draft.
    this.expanded.set('none');
    this.save.emit(comment);
  }
}

/** Up to two leading initials from a display name ("Karsten M." → "KM"). */
function initials(name: string | undefined): string {
  return (name ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('');
}
