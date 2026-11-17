import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { Icon } from '../../../../../shared/icons/icon';
import {
  RichTextEditor,
  type RichTextLinkState,
} from '../../../../../shared/forms/rich-text-editor/rich-text-editor';
import { SafeMessageHtmlPipe } from '../../../../messages/pipes/safe-message-html.pipe';
import { MessageDescService } from '../../../services/message-desc.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';
import { LinkDialog } from '../../link-dialog/link-dialog';
import { ImagePickerDialog } from '../../image-picker-dialog/image-picker-dialog';
import { InsertLocationsDialog } from '../../insert-locations-dialog/insert-locations-dialog';
import { InsertTimeDialog } from '../../insert-time-dialog/insert-time-dialog';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/** A configured model language with its badge (flag + code) for the per-language editor headers. */
interface LanguageBadge {
  readonly code: string;
  readonly badge: string;
  readonly label: string;
  readonly flagPath?: string;
}

/**
 * The part-indexed `description` row: the collapsed viewer shows the body in the current UI
 * language, rendered through the message-HTML sanitiser like every other view of it; the expanded
 * editor is one `full`-preset {@link RichTextEditor} per configured model language (N-language —
 * never a hardcoded pair), each headed by its language badge.
 *
 * Body edits flow through the facade single-write-path (`setPartDetails` — an immutable part-descs
 * upsert that marks dirty). They do NOT drive an adjust round-trip (details never
 * feed the server-derived title/source), so — unlike the subject field — this field queues no
 * `adjustDebounced`. The TipTap editors mount ONLY while the row is expanded (an inner `@if`), so a
 * collapsed row never boots TipTap.
 */
@Component({
  selector: 'app-description-field',
  imports: [
    LanguageFlag,
    TranslocoPipe,
    SafeMessageHtmlPipe,
    RichTextEditor,
    EditorFieldRow,
    Icon,
    InsertTimeDialog,
    InsertLocationsDialog,
    ImagePickerDialog,
    LinkDialog,
  ],
  templateUrl: './description-field.html',
  host: { class: 'contents' },
})
export class DescriptionField {
  /** The message-part index this row edits. */
  readonly partIndex = input.required<number>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly desc = inject(MessageDescService);

  /** Which insert dialog is open, and the language it targets (the invoking RTE's). */
  protected readonly timeDialogVisible = signal(false);
  protected readonly locationsDialogVisible = signal(false);
  protected readonly imagePickerVisible = signal(false);
  protected readonly dialogLang = signal('');
  // The RTE the dialog's fragment is inserted into — set when its toolbar button is clicked.
  private insertTarget?: RichTextEditor;

  /** Insert/Edit-link dialog state — the editor that raised it, plus its anchor-at-selection state. */
  protected readonly linkDialogVisible = signal(false);
  protected readonly linkState = signal<RichTextLinkState>({
    href: '',
    text: '',
    title: '',
    target: '',
  });
  private linkEditor?: RichTextEditor;

  private readonly part = computed(() => this.facade.message()?.parts?.[this.partIndex()]);

  /** Whether the row is expanded — the editors mount only then (no collapsed TipTap). */
  protected readonly expanded = computed<boolean>(() =>
    this.fields.isExpanded('description', this.partIndex()),
  );

  /** The body in the current UI language (fallback the primary desc) for the collapsed viewer. */
  protected readonly viewerDetails = computed<string>(
    () =>
      this.desc.descForLangOrDefault(this.part()?.descs, this.language.selectedLanguage())
        ?.details ?? '',
  );

  /** One badge per configured model language (N-language). */
  protected readonly languages = computed<readonly LanguageBadge[]>(() =>
    this.language.modelLanguages.map((option) => ({
      code: option.value,
      badge: option.value.toUpperCase(),
      label: option.label,
      flagPath: option.flagPath,
    })),
  );

  /** The details body for one language (empty when the desc is missing — never index-crash). */
  protected detailsFor(lang: string): string {
    return this.desc.descForLanguage(this.part()?.descs, lang)?.details ?? '';
  }

  /** A body edit for one language — single write path; marks dirty, never adjusts. */
  protected onDetailsChange(lang: string, html: string): void {
    this.facade.setPartDetails(this.partIndex(), lang, html);
  }

  /** Open the Insert-Time dialog, remembering the invoking language's editor as the target. */
  protected openTimeDialog(lang: string, editor: RichTextEditor): void {
    this.insertTarget = editor;
    this.dialogLang.set(lang);
    this.timeDialogVisible.set(true);
  }

  /** Open the Insert-Locations dialog, remembering the invoking language's editor as the target. */
  protected openLocationsDialog(lang: string, editor: RichTextEditor): void {
    this.insertTarget = editor;
    this.dialogLang.set(lang);
    this.locationsDialogVisible.set(true);
  }

  /** Open the inline-image picker, remembering the invoking language's editor as the target. */
  protected openImagePicker(editor: RichTextEditor): void {
    this.insertTarget = editor;
    this.imagePickerVisible.set(true);
  }

  /** Insert the picked attachment as an `<img>` at the cursor of the editor that opened the picker. */
  protected onImagePicked(url: string): void {
    // Attribute-encode the server-supplied path so it can never break out of the src attribute.
    const safe = url
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    this.insertTarget?.insertContentAtCursor(`<img src="${safe}">`);
  }

  /** Insert a dialog's returned fragment at the cursor of the editor that opened it. */
  protected onInsert(html: string): void {
    this.insertTarget?.insertContentAtCursor(html);
  }

  /** Open the Insert/Edit-link dialog for the editor that raised it, prefilled with its anchor state. */
  protected openLinkDialog(state: RichTextLinkState, editor: RichTextEditor): void {
    this.linkEditor = editor;
    this.linkState.set(state);
    this.linkDialogVisible.set(true);
  }

  /** Apply the dialog result back to the editor that raised it (blank href ⇒ the link is removed). */
  protected onLinkConfirmed(result: RichTextLinkState): void {
    this.linkEditor?.applyLink(result);
  }
}
