import {
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { FileUpload } from '../../../../../shared/components/file-upload/file-upload';
import { Icon } from '../../../../../shared/icons/icon';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { MessageUrlBuilderService } from '../../../../messages/services/message-url-builder.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';
import { ThumbnailMapDialog } from '../../thumbnail-map-dialog/thumbnail-map-dialog';

/**
 * The `layout` field row: the message thumbnail (upload / clear) plus the `separatePage`
 * flag ("start this message on a new page in PDF reports").
 *
 * Thumbnail: the current `thumbnailPath` renders through the repo-file URL with a
 * cache-breaker query param — the map-image endpoint writes the SAME repo path on every
 * upload to a revision, so the URL must change even when the path does not. Uploads post
 * the image straight to the message-map-image endpoint (multipart, plain-text response =
 * the new repo path) and the facade re-points the message at it; Clear delegates to the
 * facade, which also owns the current-revision-only physical delete. "Select from map" opens
 * the capture dialog and PUTs the returned PNG data URL to the same endpoint.
 *
 * Binds the ONE form model's `separatePage` boolean slice via {@link field} (`form.separatePage`,
 * `FieldTree<boolean>`). Writes the field signal so the change flows through the host's single sync
 * point. All edit affordances gate on `editableNow()`.
 */
@Component({
  selector: 'app-layout-field',
  imports: [TranslocoPipe, EditorFieldRow, FileUpload, Icon, ThumbnailMapDialog],
  templateUrl: './layout-field.html',
  host: { class: 'contents' },
})
export class LayoutField {
  /** The `separatePage` boolean slice of the host's form model. */
  readonly field = input.required<FieldTree<boolean>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly urls = inject(MessageUrlBuilderService);
  private readonly api = inject(MessagesApiService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Controls the "Select thumbnail from map" capture dialog. */
  protected readonly mapDialogVisible = signal(false);

  protected readonly control = computed(() => this.field()());

  /**
   * Cache-breaker for the thumbnail `<img>`: recomputes (bumps) whenever `thumbnailPath`
   * changes, and is bumped manually after every completed upload — a re-upload to the same
   * unsaved revision keeps the same repo path but new bytes.
   */
  private readonly cacheBreaker = linkedSignal<string | undefined, number>({
    source: () => this.facade.message()?.thumbnailPath,
    computation: (_path, previous) => (previous ? previous.value + 1 : 0),
  });

  /** Repo-file URL for the current thumbnail (with the cache-breaker), or `''` when unset. */
  protected readonly thumbnailUrl = computed(() => {
    const path = this.facade.message()?.thumbnailPath;
    return path ? `${this.urls.getAttachmentFileUrl(path)}?cb=${this.cacheBreaker()}` : '';
  });

  /**
   * Upload target for the map-image endpoint. Built from the facade message's CURRENT
   * `editRepoPath`/`revision` (a computed, so a post-save revision bump re-points the
   * uploader); the pair is spliced in un-encoded — the backend route keeps embedded slashes.
   */
  protected readonly uploadUrl = computed(() => {
    const message = this.facade.message();
    return message?.editRepoPath !== undefined && message.revision !== undefined
      ? `${this.apiBaseUrl}/message-map-image/${message.editRepoPath}/${message.revision}`
      : '';
  });

  protected onToggle(event: Event): void {
    this.control().value.set((event.target as HTMLInputElement).checked);
  }

  /** The map-image endpoint answers `text/plain` with the new repo thumbnail path. */
  protected onUploaded(body: unknown): void {
    if (typeof body !== 'string') {
      return;
    }
    const path = body.trim();
    if (path) {
      this.facade.setThumbnail(path);
      this.cacheBreaker.update((n) => n + 1);
    }
  }

  /**
   * A map snapshot was captured in the dialog: PUT the PNG data URL to the map-image endpoint, then
   * re-point the message at the returned thumbnail path. Bumping the cache-breaker manually is
   * required because a re-capture to the same unsaved revision keeps the same repo path but new bytes.
   */
  protected onThumbnailCaptured(dataUrl: string): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    this.api
      .changeMessageMapImage(message, dataUrl)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (body) => {
          const path = body.trim();
          if (path) {
            this.facade.setThumbnail(path);
            this.cacheBreaker.update((n) => n + 1);
            this.notifications.success('editor.layout.thumbnailUpdated');
          }
        },
        error: () => this.notifications.error('editor.layout.thumbnailCaptureError'),
      });
  }
}
