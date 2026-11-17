import { Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { EmailAddressField } from '../../../../shared/forms/email-address-field/email-address-field';
import { RichTextEditor } from '../../../../shared/forms/rich-text-editor/rich-text-editor';
import { Icon } from '../../../../shared/icons/icon';
import type { MessageComment } from '../../../messages/models/message-comment.model';

interface CommentFormModel {
  comment: string;
  emailAddresses: readonly string[];
}

/** What a new comment's editor holds before it is posted: the typed body and the recipients. */
export type CommentDraft = Readonly<CommentFormModel>;

/**
 * The editable comment form (shown only for a new / own / admin comment). The body is a
 * `basic`-preset rich-text editor (standard formatting, no niord insert buttons, no message-content
 * CSS); the e-mail field reuses the existing `EmailAddressField` CVA. The Save label flips to
 * "Save and Send" when e-mail addresses are present; Save is disabled while pristine or invalid.
 * Acknowledge shows for a saved, un-acked comment.
 */
@Component({
  selector: 'app-comment-editor',
  imports: [FormField, TranslocoPipe, EmailAddressField, RichTextEditor, Icon],
  template: `
    <form (submit)="$event.preventDefault()" class="flex flex-col gap-2">
      <app-rich-text-editor
        preset="basic"
        [formField]="form.comment"
        [label]="'editor.comments.bodyLabel' | transloco"
      />

      <span class="text-sm text-fg-muted">{{ 'editor.comments.emailLabel' | transloco }}</span>
      <app-email-address-field
        [formField]="form.emailAddresses"
        [ariaLabel]="'editor.comments.emailLabel'"
        [placeholder]="'editor.comments.emailPlaceholder'"
      />

      <div class="flex gap-2">
        <button type="button" class="btn-primary" [disabled]="saveDisabled()" (click)="onSave()">
          <app-icon name="lucideSave" />
          <span>{{ saveLabel() | transloco }}</span>
        </button>
        @if (showAcknowledge()) {
          <button type="button" class="btn-tertiary" (click)="acknowledge.emit()">
            {{ 'editor.comments.acknowledge' | transloco }}
          </button>
        }
      </div>
    </form>
  `,
  host: { class: 'contents' },
})
export class CommentEditor {
  readonly comment = input.required<MessageComment>();
  /** A brand-new comment (compose / reply) — flips the submit label to "Post"/"Send comment". */
  readonly newComment = input(false);
  /**
   * A kept draft to fill a new comment's editor with in place of the bound comment's own values,
   * read only when the editor is (re)populated: the compose card passes what its store kept, so a
   * tab switch or a return to Comments finds the half-typed text again. The change baseline stays
   * the bound comment's, so a filled-in draft counts as a change and can be posted at once.
   */
  readonly draft = input<CommentDraft | undefined>(undefined);
  readonly save = output<MessageComment>();
  readonly acknowledge = output<void>();
  /** The current body and recipients while this is a new comment, on every change (fill included). */
  readonly draftChange = output<CommentDraft>();

  private readonly formModel = signal<CommentFormModel>({ comment: '', emailAddresses: [] });
  protected readonly form = form(this.formModel, (path) => {
    required(path.comment);
  });

  /**
   * Net change against the (re)populated values. Signal Forms' `dirty` tracks
   * interaction and never clears on revert, so a serialized baseline comparison
   * gates Save instead. The comparison reads the FIELD value (`formModel`), never the
   * rich-text control's normalized HTML: the control's single-write-path rule keeps a
   * programmatic populate silent (no `valueChange`), so the field retains the raw stored body
   * even when TipTap re-wraps it on first render. Baseline and field therefore stay equal on
   * populate — no phantom change — and diverge only on a genuine user edit. An emptied editor
   * yields `''` from the control, so `required` still blocks Save.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(() => !this.changed() || this.form().invalid());

  // The recipient list is read straight off the form field (a signal), so the
  // Save-label reflects both (re)population and live edits — no separate mirror.
  // A brand-new comment (compose / reply) reads "Post comment" / "Send comment"; an
  // edit of an existing comment keeps "Save" / "Save and Send".
  protected readonly saveLabel = computed(() => {
    const hasEmail = this.formModel().emailAddresses.length > 0;
    if (this.newComment()) {
      return hasEmail ? 'editor.comments.sendComment' : 'editor.comments.postComment';
    }
    return hasEmail ? 'editor.comments.saveAndSend' : 'editor.comments.save';
  });

  protected readonly showAcknowledge = computed(() => {
    const comment = this.comment();
    return !!comment.id && !comment.acknowledgeDate;
  });

  constructor() {
    // (Re)populate the form whenever the selected comment changes, snapshotting
    // the new values as the change baseline so Save reflects the bound comment. A new comment's
    // kept draft (read untracked: its echo of the editor's own changes must not repopulate) fills
    // the form over the baseline.
    effect(() => {
      const comment = this.comment();
      untracked(() => {
        const next: CommentFormModel = {
          comment: comment.comment ?? '',
          emailAddresses: comment.emailAddresses ?? [],
        };
        const draft = this.newComment() ? this.draft() : undefined;
        this.formModel.set(draft ? { ...draft } : next);
        this.baselineJson.set(JSON.stringify(next));
        this.form().reset();
      });
    });

    // Report a new comment's text and recipients as they change, so the host can keep the draft
    // beyond this component's life. An existing comment's edit is not reported.
    effect(() => {
      const model = this.formModel();
      if (!this.newComment()) {
        return;
      }
      untracked(() =>
        this.draftChange.emit({ comment: model.comment, emailAddresses: model.emailAddresses }),
      );
    });
  }

  protected onSave(): void {
    if (this.saveDisabled()) {
      return;
    }
    const raw = this.formModel();
    this.save.emit({ ...this.comment(), comment: raw.comment, emailAddresses: raw.emailAddresses });
  }
}
