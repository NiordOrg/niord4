import {
  afterNextRender,
  Component,
  DestroyRef,
  inject,
  TemplateRef,
  ViewContainerRef,
  viewChild,
} from '@angular/core';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { TemplatePortal } from '@angular/cdk/portal';
import { translateSignal } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { NotificationSeverity } from '../../../core/notifications/notification-options.model';

/**
 * Global toast surface on the CDK overlay. Renders the
 * `NotificationService` store as a top-right stack; each toast shows a severity
 * icon, summary (+ optional detail), and a close button, and auto-dismisses after
 * its `life` (the store owns the timer). Mount ONE `<app-toast>` at the app shell.
 *
 * Each toast is a `role="alert"` live region so screen readers announce it as it
 * appears; the close button reuses the shared close label.
 */
@Component({
  selector: 'app-toast',
  imports: [Icon],
  template: `
    <ng-template #panel>
      <div class="toast-stack" aria-live="polite">
        @for (message of messages(); track message.id) {
          <div class="toast toast-{{ message.severity }}" role="alert">
            <app-icon [name]="iconFor(message.severity)" class="toast-icon" />
            <div class="toast-body">
              <div class="toast-summary">{{ message.summary }}</div>
              @if (message.detail) {
                <div class="toast-detail">{{ message.detail }}</div>
              }
            </div>
            <button
              type="button"
              class="toast-close"
              (click)="dismiss(message.id)"
              [attr.aria-label]="closeLabel()"
            >
              <app-icon name="lucideX" />
            </button>
          </div>
        }
      </div>
    </ng-template>
  `,
  host: { class: 'contents' },
})
export class Toast {
  private readonly notifications = inject(NotificationService);
  private readonly overlay = inject(Overlay);
  private readonly vcr = inject(ViewContainerRef);

  protected readonly messages = this.notifications.messages;
  protected readonly closeLabel = translateSignal('term.close');

  private readonly panel = viewChild.required<TemplateRef<unknown>>('panel');
  private overlayRef: OverlayRef | null = null;

  constructor() {
    // Attach a persistent top-right container once the view exists; the `@for`
    // inside it tracks the store reactively, so the container stays put and only
    // the toast rows come and go.
    afterNextRender(() => {
      this.overlayRef = this.overlay.create({
        positionStrategy: this.overlay.position().global().top('1rem').right('1rem'),
        scrollStrategy: this.overlay.scrollStrategies.noop(),
        hasBackdrop: false,
        panelClass: 'toast-pane',
      });
      this.overlayRef.attach(new TemplatePortal(this.panel(), this.vcr));
    });
    inject(DestroyRef).onDestroy(() => this.overlayRef?.dispose());
  }

  protected dismiss(id: number): void {
    this.notifications.dismiss(id);
  }

  protected iconFor(severity: NotificationSeverity): string {
    switch (severity) {
      case 'success':
        return 'lucideCheck';
      case 'info':
        return 'lucideInfo';
      case 'warn':
        return 'lucideTriangleAlert';
      case 'error':
        return 'lucideCircleX';
    }
  }
}
