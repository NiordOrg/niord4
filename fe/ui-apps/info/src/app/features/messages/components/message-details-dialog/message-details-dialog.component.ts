import {
  Component,
  computed,
  effect,
  inject,
  Signal,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { TranslocoPipe } from '@jsverse/transloco';
import { DialogFrame } from '../../../../shared/components/dialog/dialog-frame';
import { MessageDetailCardComponent } from '../message-detail-card/message-detail-card.component';
import { MessageMapComponent } from '../message-map/message-map.component';
import { MessageService } from '../../../../core/services/message.service';
import { LanguageService } from '../../../../core/services/language.service';
import { Message } from '../../../../core/models/message.model';
import { featuresForMessage, sortDescs } from '../../../../core/models/message.util';
import { settledValue } from '../../../../shared/utils/settled-value';
import { Icon } from '../../../../shared/icons/icon';
import { messagePdfUrl } from '../../message-pdf-url';

/** How a step with nowhere to go looks: the shared disabled button, its hover included. */
const UNAVAILABLE_STEP = {
  opacity: '0.45',
  cursor: 'not-allowed',
  background: 'var(--niord-action-primary-bg)',
} as const;

/**
 * What the opener hands the dialog: the message to show, and the list it can step through. The
 * list is the opener's live one rather than a copy: a dialog opened from a link into one message
 * is open before the list has been read, and its steps appear once the list is in.
 */
export interface MessageDetailsDialogData {
  readonly messageId: string;
  readonly messageIds: Signal<readonly string[]>;
  /**
   * Told the id of each message the reader moves to — a step, a followed reference, Back — and
   * not of the one the dialog opened on. Only a dialog a link opened is handed it: its opener
   * keeps the address on the message shown, so a reload or a shared address shows that one.
   */
  readonly messageShown?: (messageId: string) => void;
}

@Component({
  selector: 'app-message-details-dialog',
  imports: [TranslocoPipe, DialogFrame, MessageDetailCardComponent, MessageMapComponent, Icon],
  // The steps are marked unavailable rather than disabled: a disabled button gives up focus,
  // and a reader who stepped to the end of the list would find themselves nowhere. The look is
  // the disabled button's, bound here rather than written as utilities, because a utility lands
  // in the stylesheet every first visit transfers and this dialog is fetched only when opened.
  template: `
    <app-dialog-frame [title]="titleKey() | transloco">
      <!-- A live region from the start, so the notice is announced when it arrives: a dialog
           that has nothing to show says why to a reader who cannot see it. -->
      <div role="alert">
        @if (notFound()) {
          <p class="message message-error">
            <app-icon name="lucideTriangleAlert" class="message-icon" />
            <span>{{ 'MSG_NOT_FOUND' | transloco: { messageId: currentMessageId() } }}</span>
          </p>
        }
      </div>

      <!-- The first read of a dialog leaves nothing to show until it lands; the body the message
           is shown in says it is on its way rather than standing empty. The spinner is
           decoration: the busy state is what a screen reader is told. -->
      <div [attr.aria-busy]="loading()">
        @if (loading()) {
          <p class="flex justify-center py-5 text-fg-muted" aria-hidden="true">
            <app-icon name="lucideLoaderCircle" size="1.5em" class="motion-safe:animate-spin" />
          </p>
        }

        @if (msg(); as m) {
          @if (hasGeometry() && showMap()) {
            <div class="relative mb-5 h-50 w-full border border-surface-300 lg:h-75">
              <app-message-map
                class="absolute inset-0 bg-map-sea"
                [message]="m"
                [fitExtent]="true"
                [showNoPosMessages]="false"
                [maxZoom]="12"
              />
            </div>
          }

          <!-- The message's title is the heading below the dialog's own, an h2. -->
          <app-message-detail-card
            [msg]="m"
            format="details"
            [headingLevel]="3"
            (showDetails)="selectMessage($event)"
          />
        }
      </div>

      <div class="no-print mt-6 flex items-center justify-between">
        <div class="inline-flex items-center gap-1.5 text-left">
          @if (pushedMessageIds().length === 1 && showNavigation()) {
            <span class="inline-flex">
              <button
                type="button"
                class="btn-primary"
                [style]="isFirst() ? unavailableStep : null"
                (click)="selectPrev()"
                [attr.aria-disabled]="isFirst() ? 'true' : null"
                [attr.aria-label]="'ARIA_PREV_MESSAGE' | transloco"
              >
                <app-icon name="lucideChevronLeft" />
              </button>
              <button
                type="button"
                class="btn-primary"
                [style]="isLast() ? unavailableStep : null"
                (click)="selectNext()"
                [attr.aria-disabled]="isLast() ? 'true' : null"
                [attr.aria-label]="'ARIA_NEXT_MESSAGE' | transloco"
              >
                <app-icon name="lucideChevronRight" />
              </button>
            </span>
          }

          @if (pushedMessageIds().length > 1) {
            <button type="button" class="btn-primary" (click)="back()">
              <app-icon name="lucideArrowLeft" /> {{ 'BTN_BACK' | transloco }}
            </button>
          }

          @if (msg()) {
            <button
              type="button"
              class="btn-primary"
              (click)="printMessage()"
              [attr.aria-label]="'ARIA_PRINT_MESSAGE' | transloco"
            >
              <app-icon name="lucidePrinter" />
            </button>
          }
        </div>

        <div class="text-right">
          <button type="button" class="btn-primary" (click)="dialogRef.close()">
            {{ 'BTN_CLOSE' | transloco }}
          </button>
        </div>
      </div>
    </app-dialog-frame>
  `,
})
export class MessageDetailsDialogComponent {
  readonly dialogRef = inject<DialogRef<void>>(DialogRef);
  private readonly data = inject<MessageDetailsDialogData>(DIALOG_DATA);
  private readonly api = inject(MessageService);
  private readonly language = inject(LanguageService).language;

  readonly messageId = this.data.messageId;
  readonly messageIds = this.data.messageIds;

  /**
   * The references the reader has followed, the message shown being the one on top. It starts at
   * the message the opener named, so the dialog reads as soon as it is created.
   */
  readonly pushedMessageIds = signal<string[]>([this.messageId]);

  protected readonly currentMessageId = computed(() => {
    const stack = this.pushedMessageIds();
    return stack[stack.length - 1];
  });

  /**
   * Where the shown message sits in the list the opener handed over, or -1 when it is not in it —
   * a reference followed from another message, or one opened from the map. The step buttons are
   * offered only while that list is what the dialog is showing.
   */
  readonly currentIndex = computed(() => {
    const stack = this.pushedMessageIds();
    return stack.length === 1 ? this.messageIds().indexOf(stack[0]) : -1;
  });

  /** Keyed on the message on top of the stack and the language, and on nothing else. */
  private readonly resource = httpResource<Message | undefined>(
    () => ({
      url: `/rest/public/v1/message/${encodeURIComponent(this.currentMessageId())}`,
      params: { lang: this.language(), externalize: 'false' },
    }),
    { defaultValue: undefined, parse: (raw) => this.api.parseOne(raw) },
  );

  /**
   * The message stays on screen while the next one is read — the reader asked for another
   * message, not for an empty dialog — and a read that failed leaves none, which is what the
   * notice above the card says in words.
   */
  private readonly settled = settledValue<Message | undefined>(this.resource, undefined);

  readonly msg = computed(() => {
    const message = this.settled();
    return message ? sortDescs(message, this.language()) : undefined;
  });

  /**
   * Whether the not-found notice is up. The backend answers an id it does not know with a 404,
   * which the resource reports as an error — that branch is the not-found case, not a rare
   * failure — and a read that failed or returned no message says the same thing to a reader: the
   * message is not available. The words are the template's, so they follow the bundle when it
   * lands after the answer and when the reader switches language.
   */
  readonly notFound = computed(() => {
    const status = this.resource.status();
    return status === 'error' || (status === 'resolved' && !this.resource.value());
  });

  /** The first read is on its way, and there is no message on screen yet to keep. */
  readonly loading = computed(() => this.resource.isLoading() && !this.msg());

  /** An attachment shown above the text takes the map's place, as it has always done. */
  readonly showMap = computed(
    () => !this.msg()?.attachments?.some((attachment) => attachment.display === 'ABOVE'),
  );

  readonly showNavigation = computed(() => this.currentIndex() >= 0);
  protected readonly unavailableStep = UNAVAILABLE_STEP;
  readonly isFirst = computed(() => this.currentIndex() < 1);
  readonly isLast = computed(() => this.currentIndex() >= this.messageIds().length - 1);
  readonly hasGeometry = computed(() => featuresForMessage(this.msg()).length > 0);

  /**
   * The dialog is titled by the kind of message it shows, as the main menu names them. The
   * title is the dialog's name, and focus enters the dialog before the message has been read —
   * or for good, when there is none — so until then it is named after the site.
   */
  readonly titleKey = computed(() => {
    const mainType = this.msg()?.mainType;
    return mainType ? `MENU_${mainType}` : 'SITE_TITLE';
  });

  private readonly frame = viewChild.required(DialogFrame);

  constructor() {
    // A reader who steps, follows a reference or goes back keeps their place — on the step
    // button, or on the title — and the new message arrives where they are not, so it is said.
    const announcer = inject(LiveAnnouncer);
    let shownId: string | undefined;
    effect(() => {
      const message = this.msg();
      if (!message) return;
      if (shownId !== undefined && message.id !== shownId) {
        void announcer.announce(
          [message.shortId, message.descs?.[0]?.title].filter(Boolean).join(' '),
        );
      }
      shownId = message.id;
    });

    // The opener of a dialog a link opened is told each move at once, with the id the dialog
    // reads — the step's, or a reference as it was written — so the address names what the
    // dialog shows, the notice for a message that is gone included.
    const messageShown = this.data.messageShown;
    if (messageShown) {
      let toldId = this.messageId;
      effect(() => {
        const id = this.currentMessageId();
        if (id === toldId) return;
        toldId = id;
        untracked(() => messageShown(id));
      });
    }
  }

  selectPrev(): void {
    const index = this.currentIndex();
    if (index > 0) this.pushedMessageIds.set([this.messageIds()[index - 1]]);
  }

  selectNext(): void {
    const index = this.currentIndex();
    const messageIds = this.messageIds();
    if (index >= 0 && index < messageIds.length - 1) {
      this.pushedMessageIds.set([messageIds[index + 1]]);
    }
  }

  /** The reference that was pressed goes with the message it was in, so focus moves first. */
  selectMessage(messageId: string): void {
    this.pushedMessageIds.update((ids) => [...ids, messageId]);
    this.frame().focusTitle();
  }

  /** Going back to the list's own message takes the Back button away, so focus moves first. */
  back(): void {
    if (this.pushedMessageIds().length > 1) {
      this.pushedMessageIds.update((ids) => ids.slice(0, -1));
      this.frame().focusTitle();
    }
  }

  /**
   * The backend renders the sheet. `noopener` is what an `<a rel="noopener">` would buy: the
   * tab the PDF lands in gets no handle on this one.
   */
  printMessage(): void {
    window.open(messagePdfUrl(this.currentMessageId(), this.language()), '_blank', 'noopener');
  }
}
