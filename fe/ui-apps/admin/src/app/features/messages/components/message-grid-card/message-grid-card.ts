import { Component, computed, inject, input, output } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import type { Message } from '../../models/message.model';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MessageRowMenu } from '../../message-details-menu/message-row-menu';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageEditShortcutService } from '../../services/message-edit-shortcut.service';
import { MessageSelectionService } from '../../services/message-selection.service';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import {
  RowLinkAnchorDirective,
  RowLinkDirective,
} from '../../../../shared/directives/row-link.directive';
import { MessageIdBadge } from '../message-id-badge/message-id-badge';
import { MessagePart } from '../message-part/message-part';
import { MessageSelectionToggle } from '../message-selection-toggle/message-selection-toggle';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

@Component({
  selector: 'app-message-grid-card',
  imports: [
    LanguageFlag,
    MessageRowMenu,
    MessageIdBadge,
    MessagePart,
    MessageSelectionToggle,
    RowLinkDirective,
    RowLinkAnchorDirective,
  ],
  templateUrl: './message-grid-card.html',
})
export class MessageGridCard {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly languageService = inject(LanguageService);
  private readonly urlBuilder = inject(MessageUrlBuilderService);
  private readonly selection = inject(MessageSelectionService);
  private readonly editShortcut = inject(MessageEditShortcutService);

  readonly message = input.required<Message>();

  /** Emits when the user clicks the card to open the details dialog. */
  readonly openRequested = output<string>();

  readonly title = computed(() => this.descriptionService.getTitle(this.message().descs));
  readonly thumbnailUrl = computed(() => this.urlBuilder.thumbnailUrl(this.message().id));
  readonly parts = computed(() => this.message().parts ?? []);
  readonly thumbnailAlt = translateSignal('messages.list.thumbnail.alt');
  readonly isSelected = computed(() => this.selection.isSelected(this.message().id));

  readonly languageMismatch = computed(() => {
    const desc = this.message().descs?.[0];
    return desc?.lang !== undefined && desc.lang !== this.languageService.selectedLanguage();
  });

  /** The first desc's language and the flag the build ships for it, or null when it has none. */
  readonly mismatchLanguage = computed(() => {
    const desc = this.message().descs?.[0];
    if (!desc?.lang) {
      return null;
    }
    const langOption = this.languageService.modelLanguages.find((l) => l.value === desc.lang);
    return { lang: desc.lang, flagPath: langOption?.flagPath };
  });

  /** Card click/activation: hold-E + click → edit directly (auth-gated), else open. */
  protected onActivate(): void {
    if (this.editShortcut.tryEdit(this.message())) {
      return;
    }
    this.openRequested.emit(this.message().id);
  }
}
