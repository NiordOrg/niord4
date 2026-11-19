import { Component, computed, inject, Injector } from '@angular/core';
import { UpperCasePipe } from '@angular/common';
import { TranslocoPipe } from '@jsverse/transloco';
import type { DialogRef } from '@angular/cdk/dialog';
import { MessageIdBadgeComponent } from '../message-id-badge/message-id-badge.component';
import { MessageAreaNameComponent } from '../message-detail-card/message-area-name.component';
import { openInfoDialog } from '../../../../shared/components/dialog/open-dialog';
import { LanguageService } from '../../../../core/services/language.service';
import { AppConfigService } from '../../../../core/services/app-config.service';
import { Area, Message } from '../../../../core/models/message.model';
import { Icon } from '../../../../shared/icons/icon';
import { ContentLang } from '../../../../shared/directives/content-lang';
import { MessagesStore } from '../../messages.store';

/** One run of the list: the area heading it starts with, if any, and the messages under it. */
interface AreaRun {
  readonly heading?: Area;
  readonly messages: readonly Message[];
}

@Component({
  selector: 'app-message-table-view',
  imports: [
    MessageIdBadgeComponent,
    MessageAreaNameComponent,
    Icon,
    TranslocoPipe,
    UpperCasePipe,
    ContentLang,
  ],
  // A list, not a table: the two cells are an id badge and a title, there are no columns to
  // name, and a table with no header would need either invented headings or a role that says
  // it is not really a table. Each area's heading stands above a list of that area's messages
  // rather than inside one, so a screen reader counts messages, not messages and headings. The
  // lists name their role: WebKit drops the list role of a list drawn without bullets, and
  // VoiceOver then announces neither the list nor its length.
  template: `
    <!-- Busy while the list is being read, with a spinner for the eye; a live region from the
         start, so a read that fails after a filter change is announced and not only drawn. -->
    <div class="message-details-list" [attr.aria-busy]="store.loading()">
      <div role="alert">
        @if (store.failed()) {
          <p class="message message-error">
            <app-icon name="lucideTriangleAlert" class="message-icon" />
            {{ 'MSGS_LOAD_FAILED' | transloco }}
          </p>
        }
      </div>

      @if (store.loading()) {
        <p class="flex justify-center py-5 text-fg-muted" aria-hidden="true">
          <app-icon name="lucideLoaderCircle" size="1.5em" class="motion-safe:animate-spin" />
        </p>
      }

      <!-- Said in words, in a live region there from the start: a blank page under the filter
           bar reads as a page that did not work. -->
      <div role="status">
        @if (store.empty()) {
          <p class="py-5 text-center text-fg-muted">{{ 'MSGS_NONE' | transloco }}</p>
        }
      </div>

      @for (run of runs(); track run.messages[0].id) {
        @if (run.heading; as heading) {
          <h4 class="message-area-heading">
            <app-message-area-name [area]="heading" [lineage]="false" />
          </h4>
        }
        <ul role="list" class="m-0 list-none p-0">
          @for (msg of run.messages; track msg.id) {
            <li>
              @if (msg.descs) {
                <button
                  type="button"
                  class="row-interactive flex w-full items-center gap-2 border-b border-surface-100 bg-surface-0 p-2.5 text-left"
                  (click)="openDetails(msg.id)"
                >
                  <span class="shrink-0 whitespace-nowrap">
                    <app-message-id-badge [msg]="msg" [showStatus]="true" />
                  </span>
                  <!-- min-w-0 lets the title shrink below its longest word, and break-words
                       breaks that word: a Danish compound is wider than a phone's row, and a
                       flex item that cannot break it takes the whole page sideways. -->
                  <span
                    class="message-title min-w-0 grow break-words text-message-title font-bold"
                    [appContentLang]="msg.descs[0].lang"
                  >
                    {{ msg.descs[0].title }}
                  </span>
                  @if (msg.descs[0].lang !== language()) {
                    @if (appConfig.flagSrc(msg.descs[0].lang); as flag) {
                      <img
                        class="inline-block h-3 shrink-0 opacity-50"
                        [src]="flag"
                        [alt]="
                          'ARIA_LANGUAGE_FLAG'
                            | transloco
                              : {
                                  language:
                                    appConfig.languageName(msg.descs[0].lang) ?? msg.descs[0].lang,
                                }
                        "
                        width="18"
                        height="12"
                      />
                    } @else {
                      <span class="shrink-0 text-xs text-fg-subtle" aria-hidden="true">{{
                        msg.descs[0].lang | uppercase
                      }}</span>
                    }
                  }
                </button>
              } @else {
                <div class="flex items-center gap-2 border-b border-surface-100 bg-surface-0 p-2.5">
                  <app-message-id-badge [msg]="msg" [showStatus]="true" />
                </div>
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
})
export class MessageTableViewComponent {
  protected readonly store = inject(MessagesStore);
  private readonly injector = inject(Injector);
  protected readonly appConfig = inject(AppConfigService);
  readonly language = inject(LanguageService).language;

  /** The list cut where each area heading falls. Messages above the first heading have none. */
  protected readonly runs = computed<readonly AreaRun[]>(() => {
    const headings = this.store.areaHeadings();
    const runs: { heading?: Area; messages: Message[] }[] = [];
    for (const msg of this.store.messages()) {
      const heading = headings.get(msg.id);
      if (heading || runs.length === 0) runs.push({ heading, messages: [] });
      runs[runs.length - 1].messages.push(msg);
    }
    return runs;
  });

  openDetails(messageId: string): Promise<DialogRef<unknown, unknown> | undefined> {
    return openInfoDialog(
      this.injector,
      () =>
        import('../message-details-dialog/message-details-dialog.component').then(
          (m) => m.MessageDetailsDialogComponent,
        ),
      { messageId, messageIds: this.store.messageIds },
    );
  }
}
