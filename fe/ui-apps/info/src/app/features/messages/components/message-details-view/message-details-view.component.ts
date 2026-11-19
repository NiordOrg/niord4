import {
  afterNextRender,
  Component,
  DestroyRef,
  EnvironmentInjector,
  inject,
  Injector,
} from '@angular/core';
import { DOCUMENT, Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import type { DialogRef } from '@angular/cdk/dialog';
import { MessageDetailCardComponent } from '../message-detail-card/message-detail-card.component';
import { MessageAreaNameComponent } from '../message-detail-card/message-area-name.component';
import { openInfoDialog } from '../../../../shared/components/dialog/open-dialog';
import { Icon } from '../../../../shared/icons/icon';
import { MessagesStore } from '../../messages.store';
import type { MessageDetailsDialogData } from '../message-details-dialog/message-details-dialog.component';

@Component({
  selector: 'app-message-details-view',
  imports: [MessageDetailCardComponent, MessageAreaNameComponent, Icon, TranslocoPipe],
  template: `
    <!-- Busy while the list is being read: a first visit on a slow link would otherwise show an
         empty page, which reads as "no warnings in this area". The spinner is decoration; the
         busy state is what a screen reader is told. -->
    <div class="message-details-list" [attr.aria-busy]="store.loading()">
      <!-- A live region from the start, so a read that fails after a filter change is announced
           and not only drawn. -->
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

      @for (msg of store.messages(); track msg.id) {
        @if (store.areaHeadings().get(msg.id); as heading) {
          <h4 class="message-area-heading">
            <app-message-area-name [area]="heading" [lineage]="false" />
          </h4>
        }
        <div class="message-details-item">
          <app-message-detail-card [msg]="msg" format="list" (showDetails)="openDetails($event)" />
        </div>
      }
    </div>
  `,
})
export class MessageDetailsViewComponent {
  protected readonly store = inject(MessagesStore);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly environment = inject(EnvironmentInjector);
  private readonly deepLinkedMessageId = inject(ActivatedRoute).snapshot.paramMap.get('messageId');

  constructor() {
    const messageId = this.deepLinkedMessageId;
    if (messageId) {
      // A link into one message opens its dialog. Opening an overlay while the view is still
      // being composed would write to the application during composition, so it waits for the
      // first render.
      afterNextRender(() => void this.openDeepLinked(messageId));
    }
  }

  openDetails(
    messageId: string,
    messageShown?: (messageId: string) => void,
  ): Promise<DialogRef<unknown, unknown> | undefined> {
    return openInfoDialog(
      this.injector,
      () =>
        import('../message-details-dialog/message-details-dialog.component').then(
          (m) => m.MessageDetailsDialogComponent,
        ),
      // The list itself, not what it holds yet: a link into one message opens before the list
      // behind it has been read.
      {
        messageId,
        messageIds: this.store.messageIds,
        messageShown,
      } satisfies MessageDetailsDialogData,
    );
  }

  /**
   * While the dialog a link opened is up, the address names the message it shows: each step,
   * followed reference or Back inside it replaces the link's entry with that message's link,
   * query kept, so a reload or a shared address shows the message on screen. The view reads
   * the route once, so the new address opens nothing again; the history entry is replaced and
   * its state kept, so Back still leaves for wherever the reader came from.
   *
   * Closing the dialog returns the address to the list's own, so a reload or a shared address
   * does not open the dialog again. It replaces the entry too. A dialog closed by Back or
   * Forward already has an address to go to, which the browser has put in place by then, and
   * it is left alone — the check is against the last address written here, and a move inside
   * the dialog after it writes nothing either.
   *
   * A dialog opened from the list gives focus back to the row it was opened from. One a link
   * opened had no control behind it, and focus would fall to the page's body, so the next Tab
   * started again at the top of the page. Once the address is the list's again, focus goes to
   * the row of the message last shown, or to the main content when the list does not hold it.
   */
  private async openDeepLinked(messageId: string): Promise<void> {
    let written = this.location.path();
    let shown = messageId;
    const queryStart = written.indexOf('?');
    const query = queryStart < 0 ? '' : written.slice(queryStart);
    const dialogRef = await this.openDetails(messageId, (shownId) => {
      shown = shownId;
      if (this.destroyRef.destroyed || this.location.path() !== written) return;
      // One path segment, as the route and the web server read it back: a short id may hold a
      // slash.
      this.location.replaceState(
        `/messages/details/message/${encodeURIComponent(shownId)}${query}`,
        '',
        this.location.getState(),
      );
      written = this.location.path();
    });
    dialogRef?.closed.subscribe(() => {
      if (this.destroyRef.destroyed || this.location.path() !== written) return;
      void this.router
        .navigate(['/messages/details'], { queryParamsHandling: 'preserve', replaceUrl: true })
        .then((moved) => {
          // The list's own address is another route, so another view draws the list: its rows
          // are there once it has rendered, and this view is gone by then.
          if (moved) {
            afterNextRender(() => this.focusAfterLinkedDialog(shown), {
              injector: this.environment,
            });
          }
        });
    });
  }

  /** The row of the message last shown, when the list holds it; otherwise the main content. */
  private focusAfterLinkedDialog(shownId: string): void {
    const index = this.store
      .messages()
      .findIndex((message) => message.id === shownId || message.shortId === shownId);
    const rows = this.document.querySelectorAll('.message-details-list .message-details-item');
    const row = index < 0 ? null : rows[index]?.querySelector<HTMLElement>('button.row-link');
    (row ?? this.document.getElementById('main-content'))?.focus();
  }
}
