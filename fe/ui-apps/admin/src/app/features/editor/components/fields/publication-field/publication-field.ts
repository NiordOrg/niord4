import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  RichTextEditor,
  type RichTextLinkState,
} from '../../../../../shared/forms/rich-text-editor/rich-text-editor';
import { Icon } from '../../../../../shared/icons/icon';
import { MessagePublication } from '../../../../messages/components/message-publication/message-publication';
import { LinkDialog } from '../../link-dialog/link-dialog';
import {
  InsertPublicationsDialog,
  type PublicationKind,
} from '../../insert-publications-dialog/insert-publications-dialog';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/** A configured model language with its badge (flag + code) for the per-language editor headers. */
interface LanguageBadge {
  readonly code: string;
  readonly badge: string;
  readonly label: string;
  readonly flagPath?: string;
}

/**
 * The message-level `publication` row (gated `showField('publication')`): the collapsed viewer
 * reuses the `message-publication` renderer (sanitized External + Internal HTML); the expanded
 * editor is, per configured model language (N-language), an External (`desc.publication`) and an
 * Internal (`desc.internalPublication`) `inline`-preset {@link RichTextEditor}.
 *
 * The `inline` preset carries no root `<p>` and preserves arbitrary inline markup — the server's
 * `publication=<id>` span attribute round-trips untouched through load → unrelated edit → save.
 * Edits flow through the facade single-write-path (`setMessageDesc` — an immutable descs upsert that
 * marks dirty but never adjusts). The TipTap editors mount ONLY while the row is expanded.
 */
@Component({
  selector: 'app-publication-field',
  imports: [
    LanguageFlag,
    TranslocoPipe,
    RichTextEditor,
    EditorFieldRow,
    MessagePublication,
    Icon,
    InsertPublicationsDialog,
    LinkDialog,
  ],
  templateUrl: './publication-field.html',
  host: { class: 'contents' },
})
export class PublicationField {
  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);

  protected readonly message = this.facade.message;

  /** Insert-Publications dialog state, set per invoking (kind × language) toolbar button. */
  protected readonly dialogVisible = signal(false);
  protected readonly dialogKind = signal<PublicationKind>('EXTERNAL');
  protected readonly dialogLang = signal('');
  protected readonly dialogPublicationId = signal<string | undefined>(undefined);

  /** Insert/Edit-link dialog state — the editor that raised it, plus its anchor-at-selection state. */
  protected readonly linkDialogVisible = signal(false);
  protected readonly linkState = signal<RichTextLinkState>({
    href: '',
    text: '',
    title: '',
    target: '',
  });
  private linkEditor: RichTextEditor | undefined;

  /** Whether the row is expanded — the editors mount only then (no collapsed TipTap). */
  protected readonly expanded = computed<boolean>(() => this.fields.isExpanded('publication'));

  /** Whether any language carries External or Internal content (drives the empty summary). */
  protected readonly hasPublication = computed<boolean>(() =>
    (this.message()?.descs ?? []).some((desc) => desc.publication || desc.internalPublication),
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

  /** The External body for one language (empty when the desc is missing — never index-crash). */
  protected externalFor(lang: string): string {
    return this.descFor(lang)?.publication ?? '';
  }

  /** The Internal body for one language. */
  protected internalFor(lang: string): string {
    return this.descFor(lang)?.internalPublication ?? '';
  }

  private descFor(lang: string) {
    return this.message()?.descs?.find((desc) => desc.lang === lang);
  }

  protected onExternalChange(lang: string, html: string): void {
    this.facade.setMessageDesc(lang, { publication: html });
  }

  protected onInternalChange(lang: string, html: string): void {
    this.facade.setMessageDesc(lang, { internalPublication: html });
  }

  /**
   * Open the Insert-Publications dialog for one editor instance. The cursor's publication id (if the
   * selection sits inside a publication span) puts the dialog in EDIT mode; otherwise INSERT.
   */
  protected openPublications(editor: RichTextEditor, kind: PublicationKind, lang: string): void {
    this.dialogKind.set(kind);
    this.dialogLang.set(lang);
    this.dialogPublicationId.set(editor.publicationIdAtSelection());
    this.dialogVisible.set(true);
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
