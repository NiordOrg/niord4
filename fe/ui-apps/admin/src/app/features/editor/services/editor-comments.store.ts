import { httpResource } from '@angular/common/http';
import { Injectable, computed, inject, linkedSignal } from '@angular/core';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import type { MessageComment } from '../../messages/models/message-comment.model';
import type { CommentDraft } from '../components/comment-editor/comment-editor';
import { EditorFacade } from './editor-facade';

/**
 * The message's comment thread, owned at the EDITOR ROUTE rather than by the comments page.
 *
 * The tab badge has to show the total on every tab, and the backend only ever sends
 * `unackComments` on the message itself — there is no total in the VO — so the total can only come
 * from the thread. Fetching it here (the editor shell's action menu injects this store, so it is
 * created on entry) makes the badge correct before the user has ever opened Comments, which is
 * what the old page-local resource could not do.
 *
 * Deliberately NOT put on `EditorFacade`: that would pull `HttpClient` + `APP_CONFIG` into a
 * service a dozen specs construct bare, and fire a request in every one of them.
 */
@Injectable()
export class EditorCommentsStore {
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly facade = inject(EditorFacade);
  private readonly language = inject(LanguageService);

  /**
   * A memoized PRIMITIVE, so message writes that replace the object for unrelated reasons don't
   * re-key the resource. Gated on `created`: a never-saved draft has no thread to fetch.
   */
  private readonly messageId = computed<string | undefined>(() => {
    const message = this.facade.message();
    return message?.created ? message.id : undefined;
  });

  /**
   * Keyed on (id, language, loadSeq). The thread is language-tagged, so a language switch
   * refetches; `loadSeq` bumps on every load/reload, so "Reload message" — which resolves the SAME
   * id — refetches rather than leaving stale comments on screen. It does NOT bump on ordinary
   * message mutations, so this cannot loop.
   */
  private readonly resource = httpResource<readonly MessageComment[]>(
    () => {
      this.facade.loadSeq();
      const id = this.messageId();
      if (!id) {
        return undefined;
      }
      return {
        url: `${this.apiBaseUrl}/messages/message/${encodeURIComponent(id)}/comments`,
        params: { lang: this.language.selectedLanguage() },
      };
    },
    { defaultValue: [] },
  );

  /**
   * A failed fetch leaves the resource erroring, where `value()` throws — the list reads empty,
   * and {@link loadFailed} tells the page to say so rather than show "no comments".
   */
  readonly comments = computed<readonly MessageComment[]>(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );

  /** The thread could not be read. */
  readonly loadFailed = computed<boolean>(() => !!this.resource.error());

  /** Total comments on the thread — what the tab badge shows. */
  readonly count = computed<number>(() => this.comments().length);

  /**
   * The half-typed "Add comment" text and recipients. Kept HERE, not in the comments page or its
   * editor, because the store outlives the page: a switch to the Edit, Status or History tab
   * destroys the page, and the draft must be there when Comments is opened again. Linked to the
   * thread's message id, so a draft typed against one message is dropped when another message (or
   * none) takes its place and can never be posted to it; a reload of the same message keeps it.
   * `undefined` when nothing is typed.
   */
  private readonly composeDraftState = linkedSignal<string | undefined, CommentDraft | undefined>({
    source: this.messageId,
    computation: () => undefined,
  });

  readonly composeDraft = this.composeDraftState.asReadonly();

  /** Whether a compose draft is held: what the leave guards and the reload prompt ask about. */
  readonly hasComposeDraft = computed<boolean>(() => this.composeDraftState() !== undefined);

  /** Keep what the compose editor holds now; an empty text with no recipients holds nothing. */
  setComposeDraft(draft: CommentDraft): void {
    const empty = draft.comment.trim() === '' && draft.emailAddresses.length === 0;
    this.composeDraftState.set(empty ? undefined : draft);
  }

  /** Drop the compose draft: its own post succeeded, or the editor was left. */
  clearComposeDraft(): void {
    this.composeDraftState.set(undefined);
  }

  /** Refetch after the thread is mutated (post / acknowledge / edit) without reloading the message. */
  reload(): void {
    this.resource.reload();
  }
}
