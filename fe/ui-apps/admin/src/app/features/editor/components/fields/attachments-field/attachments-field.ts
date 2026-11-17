import { CdkDrag, CdkDragHandle, type CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { afterNextRender, Component, computed, ElementRef, inject, Injector } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { FileUpload } from '../../../../../shared/components/file-upload/file-upload';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { Icon } from '../../../../../shared/icons/icon';
import { formatByteSize } from '../../../../../shared/utils/format-byte-size';
import { MessageAttachment as MessageAttachmentViewer } from '../../../../messages/components/message-attachment/message-attachment';
import {
  ATTACHMENT_DISPLAYS,
  type AttachmentDisplay,
  type MessageAttachment,
  type MessageAttachmentDescription,
} from '../../../../messages/models/message.model';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { MessageAttachmentTypeService } from '../../../../messages/services/message-attachment-type.service';
import { MessageUrlBuilderService } from '../../../../messages/services/message-url-builder.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/** A configured model language (code + human label) for the per-language caption inputs. */
interface LanguageOption {
  readonly code: string;
  readonly label: string;
}

/** A display-mode `<option>`: its wire value and the translated label key. */
interface DisplayOption {
  readonly value: AttachmentDisplay;
  readonly labelKey: string;
}

const DISPLAY_LABEL_KEYS: Record<AttachmentDisplay, string> = {
  '': 'editor.attachments.display.none',
  ABOVE: 'editor.attachments.display.above',
  BELOW: 'editor.attachments.display.below',
  SEPARATE_PAGE: 'editor.attachments.display.separatePage',
};

const DISPLAY_OPTIONS: readonly DisplayOption[] = ATTACHMENT_DISPLAYS.map((value) => ({
  value,
  labelKey: DISPLAY_LABEL_KEYS[value],
}));

/** The uploaded body is a JSON array of attachment VOs; a lone object is accepted defensively. */
function narrowAttachments(response: unknown): MessageAttachment[] {
  if (Array.isArray(response)) {
    return response.filter(isAttachmentVo);
  }
  return isAttachmentVo(response) ? [response] : [];
}

/** An attachment VO carries at least a `path` or `fileName` string — the wire's minimal shape. */
function isAttachmentVo(value: unknown): value is MessageAttachment {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return typeof record['path'] === 'string' || typeof record['fileName'] === 'string';
}

/**
 * The message-level `attachments` field row. Facade-driven — never form controls: the shared uploader
 * posts to the message's CURRENT edit-repo path (re-read via {@link uploadUrl} so a post-save
 * revision bump re-points it automatically) and its parsed VO list is appended through
 * `facade.addAttachments`; the uploader sends files one at a time, so files picked together are
 * appended in pick order. Rows reorder by dragging their grip, or with the up and down arrow keys
 * on it, through `facade.moveAttachment`. Per attachment the table edits the per-language
 * captions, the inline Display mode (image/video only), and the CSS-unit width/height (only once a
 * Display mode is set), each flowing through `facade.updateAttachment`; the trash button drops it via
 * `facade.removeAttachment` (which owns the current-revision physical-file delete guard). The
 * collapsed viewer is a paperclip + count + comma-joined file names.
 */
@Component({
  selector: 'app-attachments-field',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    TranslocoPipe,
    EditorFieldRow,
    FileUpload,
    Icon,
    MessageAttachmentViewer,
    LocalizedInputs,
  ],
  templateUrl: './attachments-field.html',
  host: { class: 'contents' },
})
export class AttachmentsField {
  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly types = inject(MessageAttachmentTypeService);
  private readonly api = inject(MessagesApiService);
  private readonly urls = inject(MessageUrlBuilderService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly displayOptions = DISPLAY_OPTIONS;

  protected readonly attachments = computed<readonly MessageAttachment[]>(
    () => this.facade.message()?.attachments ?? [],
  );

  /** The comma-joined file names for the collapsed viewer summary. */
  protected readonly fileNames = computed<string>(() =>
    this.attachments()
      .map((attachment) => attachment.fileName)
      .filter((name): name is string => !!name)
      .join(', '),
  );

  /** One entry per configured model language (N-language — never a hardcoded pair). */
  protected readonly languages = computed<readonly LanguageOption[]>(() =>
    this.language.modelLanguages.map((option) => ({ code: option.value, label: option.label })),
  );

  /** The live upload target — re-read on each upload so a save-bump revision re-points it. */
  protected readonly uploadUrl = computed<string>(() => {
    const message = this.facade.message();
    return message ? this.api.attachmentUploadRepoPath(message) : '';
  });

  /** Whether an attachment can be shown inline (image/video) — gates the Display + size cells. */
  protected canDisplay(attachment: MessageAttachment): boolean {
    return this.types.isPreviewable(attachment.type);
  }

  /** The per-language caption record fed to the localized-inputs group (missing langs render empty). */
  protected captionRecord(attachment: MessageAttachment): Record<string, string> {
    const out: Record<string, string> = {};
    for (const { code } of this.languages()) {
      out[code] = (attachment.descs ?? []).find((desc) => desc.lang === code)?.caption ?? '';
    }
    return out;
  }

  /** Open the attachment's repo file in a new tab (the "show" affordance for any file type). */
  protected onShowAttachment(attachment: MessageAttachment): void {
    const path = attachment.path;
    if (path) {
      window.open(this.urls.getAttachmentFileUrl(path), '_blank', 'noopener');
    }
  }

  /** Human-readable byte size for the table's Size column (absent size renders nothing). */
  protected formatSize(bytes: number | undefined): string {
    return bytes === undefined ? '' : formatByteSize(bytes);
  }

  /** A row dropped at another place in the table. */
  protected onDrop(event: CdkDragDrop<unknown>): void {
    this.facade.moveAttachment(event.previousIndex, event.currentIndex);
  }

  /**
   * The keyboard's way to reorder: the up and down arrow keys on a row's grip move the row one
   * place, and the focus follows it to the grip of its new row.
   */
  protected onGripKeydown(event: KeyboardEvent, index: number): void {
    const step = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0;
    if (step === 0) {
      return;
    }
    event.preventDefault();
    const to = index + step;
    if (!this.facade.editableNow() || to < 0 || to >= this.attachments().length) {
      return;
    }
    this.facade.moveAttachment(index, to);
    afterNextRender(
      () => this.host.nativeElement.querySelectorAll<HTMLElement>('[data-reorder]')[to]?.focus(),
      { injector: this.injector },
    );
  }

  /** Freshly uploaded VOs (the uploader's parsed body) → append through the facade. */
  protected onUploaded(response: unknown): void {
    const attachments = narrowAttachments(response);
    if (attachments.length > 0) {
      this.facade.addAttachments(attachments);
    }
  }

  /** A caption edit for one language — rebuild the desc list immutably (seeding missing langs). */
  protected onCaptionInput(index: number, { lang, value }: LocalizedValueInput): void {
    const attachment = this.attachments()[index];
    if (!attachment) {
      return;
    }
    this.facade.updateAttachment(index, { descs: this.buildDescs(attachment, lang, value) });
  }

  /** A Display-mode change — selecting `''` clears display/width/height inside the facade. */
  protected onDisplayChange(index: number, value: string): void {
    this.facade.updateAttachment(index, { display: value as AttachmentDisplay });
  }

  protected onWidthChange(index: number, width: string): void {
    this.facade.updateAttachment(index, { width });
  }

  protected onHeightChange(index: number, height: string): void {
    this.facade.updateAttachment(index, { height });
  }

  protected onDelete(index: number): void {
    this.facade.removeAttachment(index);
  }

  /** Seed every configured language, preserving other captions and appending non-model descs. */
  private buildDescs(
    attachment: MessageAttachment,
    lang: string,
    caption: string,
  ): MessageAttachmentDescription[] {
    const langs = this.languages().map((option) => option.code);
    const existing = attachment.descs ?? [];
    const merged = langs.map<MessageAttachmentDescription>((code) => ({
      lang: code,
      caption: code === lang ? caption : existing.find((desc) => desc.lang === code)?.caption,
    }));
    const extra = existing.filter((desc) => !langs.includes(desc.lang ?? ''));
    return [...merged, ...extra];
  }
}
