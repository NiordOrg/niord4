import { Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Icon } from '../../../../shared/icons/icon';
import type { Message } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { EmptySearchResult } from '../empty-search-result/empty-search-result';
import { LoadMoreMessages } from '../load-more-messages/load-more-messages';
import { MessageIdBadge } from '../message-id-badge/message-id-badge';
import { MessageListStatus } from '../message-list-status/message-list-status';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

@Component({
  selector: 'app-map-result-list',
  templateUrl: './map-result-list.html',
  imports: [
    LanguageFlag,
    EmptySearchResult,
    Icon,
    LoadMoreMessages,
    MessageIdBadge,
    MessageListStatus,
    TranslocoPipe,
  ],
  host: {
    // Visual styling only — positioning, width and z-index are owned by the
    // shared left-column overlay in MessagesMapView's template. Flex shape
    // lets the inner section's `h-full` resolve against this host when the
    // parent's flex layout has shrunk the host below its natural content
    // size (body then scrolls internally via its own overflow-y-auto).
    class:
      'flex flex-col overflow-hidden rounded border border-surface-200 bg-surface-0/95 shadow dark:border-surface-700 dark:bg-surface-900/95',
  },
})
export class MapResultList {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly languageService = inject(LanguageService);

  readonly messages = input.required<readonly Message[]>();
  readonly total = input.required<number>();
  readonly loading = input<boolean>(false);

  readonly messageClicked = output<string>();
  /**
   * Fires the message id when the pointer enters a row, and `undefined` when
   * it leaves. Consumers use this to highlight the corresponding feature on
   * the map (see OlMessageListLayer's `hovered` input).
   */
  readonly messageHovered = output<string | undefined>();
  /** Fires when the user clicks the zoom button on a row. */
  readonly zoomRequested = output<string>();

  private readonly _collapsed = signal(true);
  readonly collapsed = this._collapsed.asReadonly();

  readonly isPartial = computed(() => this.messages().length < this.total());
  readonly headingKey = computed(() =>
    this.isPartial() ? 'messages.list.mapPanel.partialResult' : 'messages.list.mapPanel.fullResult',
  );
  readonly toggleKey = computed(() =>
    this.collapsed() ? 'messages.list.mapPanel.expand' : 'messages.list.mapPanel.collapse',
  );

  toggle(): void {
    this._collapsed.update((v) => !v);
  }

  collapse(): void {
    this._collapsed.set(true);
  }

  /**
   * True when the message has at least one feature in any of its parts. The
   * map zoom button is hidden for messages without geometry — there's nothing
   * to zoom to.
   */
  hasGeometry(message: Message): boolean {
    return message.parts?.some((p) => (p.geometry?.features?.length ?? 0) > 0) ?? false;
  }

  titleFor(message: Message): string {
    return this.descriptionService.getTitle(message.descs) ?? '';
  }

  isLanguageMismatch(message: Message): boolean {
    const desc = message.descs?.[0];
    return desc?.lang !== undefined && desc.lang !== this.languageService.selectedLanguage();
  }

  /** The first desc's language and the flag the build ships for it, or null when it has none. */
  foreignLanguageFor(message: Message): { lang: string; flagPath?: string } | null {
    const desc = message.descs?.[0];
    if (!desc?.lang) return null;
    const langOption = this.languageService.modelLanguages.find((l) => l.value === desc.lang);
    return { lang: desc.lang, flagPath: langOption?.flagPath };
  }
}
