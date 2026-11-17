import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../shared/services/discard-confirm.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { LastMessagesViewService } from '../../../messages/services/last-messages-view.service';
import { MessageDescService } from '../../services/message-desc.service';
import { EditorFacade } from '../../services/editor-facade';

/**
 * Shared editor header (back-to-list + title + save-status chip + clear + save), reused across
 * the edit / status / history / comments views so the chrome stays identical between them. Save
 * lives HERE (not on the edit form) because the save-gate spans the whole editor: unsaved edits
 * survive tab switches, so the user must be able to save the working copy from any tab.
 * View-owned trailing actions (the edit form's narrow-Preview button) project into the
 * `[headerActions]` slot after the Save button.
 *
 * Re-provides its own {@link ConfirmService} and mounts its own `<app-confirm>` so the Clear
 * discard-guard and the VERIFIED-save confirm render on every host view — not only the edit form
 * (the status / history / comments pages don't mount one of their own).
 */
@Component({
  selector: 'app-editor-view-header',
  imports: [TranslocoPipe, Icon, Confirm, HelpAnchor],
  providers: [ConfirmService],
  template: `
    <header
      class="flex items-center gap-3 border-b border-surface-200 px-4 py-2 dark:border-surface-700"
    >
      <button type="button" class="btn-tertiary" (click)="back()">
        <app-icon name="lucideArrowLeft" />
        <span>{{ 'editor.back_to_list' | transloco }}</span>
      </button>

      @if (created()) {
        <button
          type="button"
          class="icon-button"
          [attr.aria-label]="'editor.reload_msg' | transloco"
          [title]="'editor.reload_msg' | transloco"
          (click)="reloadMessage()"
        >
          <app-icon name="lucideRefreshCw" />
        </button>
      }

      <div class="flex min-w-0 flex-1 items-center gap-2">
        <span class="truncate font-semibold">
          {{ title() || ('editor.header.untitled' | transloco) }}
        </span>
        <!-- Route-based: resolves the active editor tab's help section (edit / status /
             comments / history) from route data — see editor.routes.ts. -->
        <app-help-anchor />
      </div>

      <!-- Chip + Save key on hasUnsavedChanges — the user has touched the working copy AND its
           content actually differs from the baseline, or the message is not stored (a copy, a new
           message, a template's result), which can be saved at once. Reverting an edit on a
           stored message back to its loaded value clears the unsaved state. -->
      @if (facade.editable()) {
        <span
          class="flex items-center gap-1.5 text-sm"
          [class.text-warning]="facade.hasUnsavedChanges()"
          [class.text-fg-subtle]="!facade.hasUnsavedChanges()"
        >
          <span class="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true"></span>
          {{
            (facade.hasUnsavedChanges() ? 'editor.header.unsaved' : 'editor.header.saved')
              | transloco
          }}
        </span>
        <button type="button" class="btn-tertiary" (click)="clear()">
          <app-icon name="lucideEraser" />
          <span>{{ 'editor.clear' | transloco }}</span>
        </button>
      }
      @if (facade.editableNow()) {
        <button
          type="button"
          class="btn-primary"
          [disabled]="!facade.hasUnsavedChanges() || facade.saving()"
          (click)="save()"
        >
          @if (facade.saving()) {
            <app-icon name="lucideLoaderCircle" [spin]="true" />
          } @else {
            <app-icon name="lucideSave" />
          }
          <span>{{ 'editor.save_msg' | transloco }}</span>
        </button>
      }
      <ng-content select="[headerActions]" />
    </header>
    <app-confirm />
  `,
  host: { class: 'contents' },
})
export class EditorViewHeader {
  private readonly router = inject(Router);
  private readonly language = inject(LanguageService);
  private readonly desc = inject(MessageDescService);
  private readonly lastMessagesView = inject(LastMessagesViewService);
  private readonly confirm = inject(ConfirmService);
  private readonly discardConfirm = inject(DiscardConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly notify = inject(NotificationService);
  protected readonly facade = inject(EditorFacade);

  /** Reload targets the server-side message — hidden for never-saved copy/template drafts. */
  protected readonly created = computed<boolean>(() => !!this.facade.message()?.created);

  protected readonly title = computed<string>(() => {
    const message = this.facade.message();
    if (!message) {
      return '';
    }
    return (
      this.desc.descForLangOrDefault(message.descs, this.language.selectedLanguage())?.title ?? ''
    );
  });

  /** Back to the messages view the user came from (table/grid/details/map, filters intact). */
  protected back(): void {
    void this.router.navigateByUrl(this.lastMessagesView.lastUrl());
  }

  /**
   * Discard the working copy and re-resolve the message from the backend. Genuine unsaved changes
   * ({@link EditorFacade.hasUnsavedChanges}) gate the discard behind the shared discard confirm
   * (with the reload-specific wording).
   */
  protected async reloadMessage(): Promise<void> {
    if (
      this.facade.hasUnsavedChanges() &&
      !(await this.discardConfirm.confirmDiscard('editor.header.reloadConfirm'))
    ) {
      return;
    }
    this.facade.reload();
  }

  /** Validate, confirm a VERIFIED reset, then save + reload (landing on the edit route). */
  protected save(): void {
    if (this.facade.saving()) {
      return;
    }
    const validation = this.facade.validateForSave();
    if (!validation.ok) {
      this.notify.error(validation.errorKey ?? 'editor.save.error');
      return;
    }
    if (this.facade.message()?.status === 'VERIFIED') {
      this.confirm.confirmNeutral({
        message: this.transloco.translate('editor.save_verified'),
        acceptLabel: this.transloco.translate('editor.save.verifiedAccept'),
        rejectLabel: this.transloco.translate('editor.save.verifiedReject'),
        accept: () => this.doSave(),
      });
      return;
    }
    this.doSave();
  }

  private doSave(): void {
    this.facade.save().subscribe({
      next: (saved) => {
        this.notify.success('editor.save.success');
        // A save from an edit-family route lands on the canonical edit URL (a first save of a
        // copy/template mints the real id). Saving from the status / comments / history tabs
        // stays put — their :id is already the saved id, and switching tabs would be jarring.
        if (/^\/editor\/(edit|copy)(\/|$)/.test(this.router.url)) {
          void this.router.navigate(['/editor/edit', saved.id]);
        }
      },
      error: () => this.notify.error('editor.save.error'),
    });
  }

  /**
   * Discard everything and return to the editor Start screen. When there are genuine unsaved changes
   * ({@link EditorFacade.hasUnsavedChanges}, which includes a copy or new message never saved) the
   * shared discard confirm gates the reset; a stored message with nothing changed (or an edit
   * reverted) clears straight through.
   */
  protected async clear(): Promise<void> {
    if (this.facade.hasUnsavedChanges() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.doClear();
  }

  /**
   * Drop the whole working state (message, baseline, dirty/touched flags — the field expand map
   * follows the cleared baseline) and land on the Start screen, so the editor is back at a true
   * blank slate. The reset happens BEFORE navigating, so the route guards see a pristine editor.
   */
  private doClear(): void {
    this.facade.resetWorkingState();
    void this.router.navigate(['/editor']);
  }
}
