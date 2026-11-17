import { Component, input, output, inject, signal, computed } from '@angular/core';
import { UpperCasePipe } from '@angular/common';
import { TranslocoPipe } from '@jsverse/transloco';
import { SafeMessageHtmlPipe } from '../../../../shared/pipes/safe-message-html.pipe';
import { MessageIdBadgeComponent } from '../message-id-badge/message-id-badge.component';
import { MessageAttachmentComponent } from '../message-attachment/message-attachment.component';
import { MessageSourceComponent } from './message-source.component';
import { LanguageService } from '../../../../core/services/language.service';
import { AppConfigService } from '../../../../core/services/app-config.service';
import {
  ATTACHMENT_DISPLAYS,
  Message,
  MessagePart,
  referenceLabelKey,
} from '../../../../core/models/message.model';
import { Icon } from '../../../../shared/icons/icon';
import { ScrollRegion } from '../../../../shared/directives/scroll-region';
import { ContentLang } from '../../../../shared/directives/content-lang';

const [ABOVE, BELOW] = ATTACHMENT_DISPLAYS;

/** One part of the message, and whether it opens a run that needs its kind named above it. */
interface PartRow {
  readonly part: MessagePart;
  readonly showHeading: boolean;
}

@Component({
  selector: 'app-message-detail-card',
  imports: [
    TranslocoPipe,
    UpperCasePipe,
    SafeMessageHtmlPipe,
    MessageIdBadgeComponent,
    MessageAttachmentComponent,
    MessageSourceComponent,
    Icon,
    ScrollRegion,
    ContentLang,
  ],
  // The fields of a message are a list of named values, so they are a definition list: a name
  // and its value, with no columns to compare across and nothing a table's headers would name.
  template: `
    <div class="w-full">
      @for (att of attachmentsAbove(); track att.path) {
        <div>
          <app-message-attachment [attachment]="att" />
        </div>
      }

      @if (msg().originalInformation) {
        <div>
          <span aria-hidden="true" class="text-3xl leading-5">&#9733;</span>
          <span class="sr-only">{{ 'ARIA_ORIGINAL_INFO' | transloco }}</span>
        </div>
      }

      <div>
        <app-message-id-badge [msg]="msg()" [showStatus]="true" />
      </div>

      <!-- break-words breaks a word longer than the card: a Danish compound in a wide system
           font is wider than a phone, and a title that cannot break it takes the whole page
           sideways. Text in another language than the page's says which one: to the eye by the
           flag, to a screen reader by the lang it carries and by the flag's alternative text. A
           language the build ships no flag for is shown by its code, to the eye only: the code
           is hidden from a screen reader, which has the lang of the text beside it.
           The title is a heading, so a screen reader moves from message to message by it; in the
           list the button that opens the message sits inside the heading. The heading is a role
           on a div, not an h-element, because its level is the caller's and the look stays the
           card's own. -->
      @if (primaryDesc(); as desc) {
        <div role="heading" [attr.aria-level]="headingLevel()">
          @if (format() === 'list') {
            <button type="button" class="row-link block w-full" (click)="onDetailsClick(msg().id)">
              <span
                class="message-title break-words text-message-title font-bold"
                [appContentLang]="desc.lang"
                >{{ desc.title }}</span
              >
              <app-icon name="lucideExternalLink" class="ml-1 align-middle text-fg-subtle" />
              @if (desc.lang !== language()) {
                @if (appConfig.flagSrc(desc.lang); as flag) {
                  <img
                    class="ml-1 inline-block h-3 opacity-50"
                    [src]="flag"
                    [alt]="
                      'ARIA_LANGUAGE_FLAG'
                        | transloco: { language: appConfig.languageName(desc.lang) ?? desc.lang }
                    "
                    width="18"
                    height="12"
                  />
                } @else {
                  <span class="ml-1 text-xs text-fg-subtle" aria-hidden="true">{{
                    desc.lang | uppercase
                  }}</span>
                }
              }
            </button>
          } @else {
            <span
              class="message-title break-words text-message-title font-bold"
              [appContentLang]="desc.lang"
              >{{ desc.title }}</span
            >
            @if (desc.lang !== language()) {
              @if (appConfig.flagSrc(desc.lang); as flag) {
                <img
                  class="ml-1 inline-block h-3 opacity-50"
                  [src]="flag"
                  [alt]="
                    'ARIA_LANGUAGE_FLAG'
                      | transloco: { language: appConfig.languageName(desc.lang) ?? desc.lang }
                  "
                  width="18"
                  height="12"
                />
              } @else {
                <span class="ml-1 text-xs text-fg-subtle" aria-hidden="true">{{
                  desc.lang | uppercase
                }}</span>
              }
            }
          }
        </div>
      }

      <!-- The value column is minmax(0,1fr), not 1fr: a track whose minimum is auto grows to
           its widest content, and one message with a wide position table would then widen the
           card, the page and every other field with it.
           The name of a field is shown whole. Its column is 7.5rem, which keeps the values of
           the cards in a list in line with each other, and grows to the longest word of a
           name that needs more: Kalaallisut words are long, and a Danish one outgrows the
           column under a reader's own letter spacing. A name of several words wraps. On a
           phone the column would leave the value too little room, so there each name stands
           above its value. -->
      <dl
        class="mt-1 grid grid-cols-1 gap-x-2 sm:grid-cols-[minmax(min-content,7.5rem)_minmax(0,1fr)]"
      >
        @if (references().length > 0) {
          <dt class="break-words pt-2 text-left align-top text-fg-muted sm:pt-1">
            {{ 'FIELD_REFERENCES' | transloco }}
          </dt>
          <!-- A reference id stands in a line of text, so it is underlined as a link is: a
               colour alone would not tell it from the words beside it. -->
          <dd class="sm:pt-1">
            @for (ref of references(); track ref.messageId) {
              <div>
                <button type="button" class="link-button" (click)="onDetailsClick(ref.messageId)">
                  {{ ref.messageId }}
                </button>
                @if (referenceLabelKey(ref.type); as key) {
                  {{ key | transloco }}
                }
                @if (ref.descs?.[0]; as refDesc) {
                  @if (refDesc.description) {
                    -
                    <span [appContentLang]="refDesc.lang">{{ refDesc.description }}</span>
                  }
                }
              </div>
            }
          </dd>
        }

        @for (row of partRows(); track $index) {
          <dt class="break-words pt-2 text-left align-top text-fg-muted sm:pt-1">
            @if (row.showHeading) {
              {{ 'PART_TYPE_' + row.part.type | transloco }}
            }
          </dt>
          <!-- A position table states its coordinates on one line by contract, so on a phone
               it is wider than the card. It scrolls inside a box of its own rather than taking
               the page sideways, and while it does that box is a named tab stop, so a keyboard
               can scroll it too. The box sits inside the dd, which keeps its place in the
               definition list. The print stylesheet lets the content out again, where the page
               is 820 px wide and nothing has to scroll. -->
          <dd class="sm:pt-1">
            <div
              class="message-details-html overflow-x-auto"
              [appScrollRegion]="'PART_TYPE_' + row.part.type | transloco"
            >
              @if (row.part.descs?.[0]; as desc) {
                @if (desc.subject && !row.part.hideSubject) {
                  <div>
                    <strong [appContentLang]="desc.lang">{{ desc.subject }}</strong>
                    @if (desc.lang !== language()) {
                      @if (appConfig.flagSrc(desc.lang); as flag) {
                        <img
                          class="ml-1 inline-block h-3 opacity-50"
                          [src]="flag"
                          [alt]="
                            'ARIA_LANGUAGE_FLAG'
                              | transloco
                                : { language: appConfig.languageName(desc.lang) ?? desc.lang }
                          "
                          width="18"
                          height="12"
                        />
                      } @else {
                        <span class="ml-1 text-xs text-fg-subtle" aria-hidden="true">{{
                          desc.lang | uppercase
                        }}</span>
                      }
                    }
                  </div>
                }
                @if (desc.details) {
                  <div
                    [appContentLang]="desc.lang"
                    [innerHTML]="desc.details | safeMessageHtml"
                  ></div>
                }
              }
            </div>
          </dd>
        }

        @if (attachments().length > 0) {
          <dt class="break-words pt-2 text-left align-top text-fg-muted sm:pt-1">
            {{ 'FIELD_ATTACHMENTS' | transloco }}
          </dt>
          <dd class="sm:pt-1">
            <!-- One button that changes its words and says whether the list is open: a button
                 swapped for another would drop the focus of the reader who pressed it. -->
            <div>
              <app-icon name="lucidePaperclip" class="mr-1 align-middle text-fg-subtle" />
              <button
                type="button"
                class="link-button is-subtle"
                [attr.aria-expanded]="showAttachments()"
                (click)="showAttachments.set(!showAttachments())"
              >
                @if (showAttachments()) {
                  {{ 'HIDE_ATTACHMENTS' | transloco }}
                } @else {
                  {{ 'SHOW_ATTACHMENTS' | transloco }}
                }
              </button>
            </div>
            <!-- A file opens from its name, which is drawn as a link: the brand primary and an
                 underline, as a link in the message text is. -->
            @if (showAttachments()) {
              @for (att of attachments(); track att.path) {
                <div class="ml-5">
                  <app-icon name="lucideFile" class="mr-1 align-middle" />
                  <a class="link-button" [href]="att.path" target="_blank" rel="noopener">{{
                    att.fileName
                  }}</a>
                  @if (att.descs?.[0]; as attDesc) {
                    @if (attDesc.caption) {
                      <span [appContentLang]="attDesc.lang"> - {{ attDesc.caption }}</span>
                    }
                  }
                </div>
              }
            }
          </dd>
        }

        @if (chartsText(); as charts) {
          <dt class="break-words pt-2 text-left align-top text-fg-muted sm:pt-1">
            {{ 'FIELD_CHARTS' | transloco }}
          </dt>
          <dd class="sm:pt-1">{{ charts }}</dd>
        }

        <!-- A publication reference reads as the citation it is part of: the message-HTML
             stylesheet keeps the links under .message-publication in the colour of the text. -->
        @if (primaryDesc()?.publication; as publication) {
          <dt class="break-words pt-2 text-left align-top text-fg-muted sm:pt-1">
            {{ 'FIELD_PUBLICATION' | transloco }}
          </dt>
          <dd
            class="message-details-html message-publication sm:pt-1"
            [appContentLang]="primaryDesc()?.lang"
            [innerHTML]="publication | safeMessageHtml"
          ></dd>
        }
      </dl>

      @if (primaryDesc()?.source || msg().publishDateFrom) {
        <div class="pt-1 text-right">(<app-message-source [msg]="msg()" />)</div>
      }

      @for (att of attachmentsBelow(); track att.path) {
        <div>
          <app-message-attachment [attachment]="att" />
        </div>
      }
    </div>
  `,
})
export class MessageDetailCardComponent {
  protected readonly appConfig = inject(AppConfigService);
  readonly language = inject(LanguageService).language;

  msg = input.required<Message>();
  format = input<'list' | 'details'>('list');

  /**
   * The level of the heading the title is in: one below the heading the card stands under. In
   * the list and on the print page that is the area's h4; the dialog, titled by an h2, sets 3.
   */
  headingLevel = input(5);

  showDetails = output<string>();

  showAttachments = signal(false);

  protected readonly referenceLabelKey = referenceLabelKey;

  /** The description the card is written from — the active language's, after the sort. */
  protected readonly primaryDesc = computed(() => this.msg().descs?.[0]);

  protected readonly references = computed(() => this.msg().references ?? []);
  protected readonly attachments = computed(() => this.msg().attachments ?? []);

  /** A part opens a run whenever its kind differs from the part above it; the run is named once. */
  protected readonly partRows = computed<readonly PartRow[]>(() => {
    const parts = this.msg().parts ?? [];
    return parts.map((part, index) => ({
      part,
      showHeading: index === 0 || part.type !== parts[index - 1].type,
    }));
  });

  attachmentsAbove = computed(() => this.attachments().filter((a) => a.display === ABOVE));

  attachmentsBelow = computed(() => this.attachments().filter((a) => a.display === BELOW));

  chartsText = computed(() => {
    const charts = this.msg().charts;
    if (!charts || charts.length === 0) return '';
    return (
      charts
        .map(
          (c) => c.chartNumber + (c.internationalNumber ? ` (INT ${c.internationalNumber})` : ''),
        )
        .join(', ') + '.'
    );
  });

  onDetailsClick(messageId: string): void {
    this.showDetails.emit(messageId);
  }
}
