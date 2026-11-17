import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { Icon } from '../../../../shared/icons/icon';
import type { Message, MessageStatus } from '../../../messages/models/message.model';
import { EditorFacade } from '../../services/editor-facade';
import { MessageStatusService } from '../../services/message-status.service';
import { PublishConfirmDialog } from '../publish-confirm-dialog/publish-confirm-dialog';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';

/** A rendered node of the status stepper. */
interface FlowNode {
  readonly status: MessageStatus;
  /** 1-based position shown inside an upcoming/current circle (a done circle shows a check instead). */
  readonly step: number;
  readonly state: 'done' | 'current' | 'upcoming';
  /** The Draft node is clickable to un-verify while the message is VERIFIED. */
  readonly stepBack: boolean;
}

/** The forward progression spine every published message advances through, in order. */
const SPINE: readonly MessageStatus[] = ['DRAFT', 'VERIFIED', 'PUBLISHED'];

/**
 * The message lifecycle stepper shown at the top of the editor's live preview. It maps the working
 * message's status onto the `Draft → Verified → Published` spine (done / current / upcoming) and,
 * once the message reaches a terminal post-publish state, appends a 4th `Expired` / `Cancelled` node.
 * A DELETED message renders no flow at all.
 *
 * It carries only the FORWARD action: Verify (from Draft), Publish or a scheduled-publish caption
 * (from Verified), and the un-verify step-back on the clickable Draft node while Verified. Delete,
 * Cancel and Copy live on the Status page, not here. The stepper is informational for everyone who
 * can open the editor; the action is gated on `isEditor` and disabled while the editor holds
 * unsaved changes (`hasUnsavedChanges`: an edit not undone, or a message never saved).
 * All transitions/validation route through the shared {@link MessageStatusService}.
 */
@Component({
  selector: 'app-status-flow',
  imports: [LocaleDatePipe, NgTemplateOutlet, TranslocoPipe, Icon, Confirm, PublishConfirmDialog],
  providers: [ConfirmService],
  template: `
    @if (nodes().length) {
      <div
        class="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-surface-200 px-4 py-2.5 dark:border-surface-700"
      >
        <ol
          class="flex items-center gap-1"
          [attr.aria-label]="'editor.status.flowAria' | transloco"
        >
          @for (node of nodes(); track node.status; let first = $first) {
            @if (!first) {
              <li aria-hidden="true">
                <app-icon name="lucideChevronRight" class="text-xs text-fg-subtle" />
              </li>
            }
            <li>
              @if (node.stepBack) {
                <button
                  type="button"
                  class="flex items-center gap-1.5 rounded px-0.5 hover:opacity-100"
                  [class.opacity-55]="node.state === 'done'"
                  [class.opacity-40]="node.state === 'upcoming'"
                  [disabled]="facade.hasUnsavedChanges()"
                  [title]="'editor.status.action.revertToDraft' | transloco"
                  [attr.aria-label]="'editor.status.action.revertToDraft' | transloco"
                  (click)="confirmRevert()"
                >
                  <ng-container [ngTemplateOutlet]="badge" [ngTemplateOutletContext]="{ node }" />
                </button>
              } @else {
                <span
                  class="flex items-center gap-1.5"
                  [class.opacity-55]="node.state === 'done'"
                  [class.opacity-40]="node.state === 'upcoming'"
                >
                  <ng-container [ngTemplateOutlet]="badge" [ngTemplateOutletContext]="{ node }" />
                </span>
              }
            </li>
          }
        </ol>

        @if (facade.isEditor()) {
          @switch (cta()) {
            @case ('verify') {
              <button
                type="button"
                class="btn-primary ml-auto"
                [disabled]="facade.hasUnsavedChanges()"
                (click)="confirmVerify()"
              >
                <app-icon name="lucideCheck" />
                {{ 'editor.status.verify_short' | transloco }}
              </button>
            }
            @case ('publish') {
              <button
                type="button"
                class="btn-primary ml-auto"
                [disabled]="facade.hasUnsavedChanges()"
                (click)="openPublish()"
              >
                <app-icon name="lucideSend" />
                {{ 'editor.status.publish_short' | transloco }}
              </button>
            }
            @case ('scheduled') {
              <span class="ml-auto text-xs text-fg-muted">
                {{
                  'editor.status.publish'
                    | transloco: { date: publishDateFrom() | localeDate: 'dateTime' }
                }}
              </span>
            }
          }
        }
      </div>
    }

    <ng-template #badge let-node="node">
      <span
        class="flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold"
        [class.text-white]="node.state !== 'upcoming'"
        [class.text-fg-subtle]="node.state === 'upcoming'"
        [class.border]="node.state === 'upcoming'"
        [class.border-surface-300]="node.state === 'upcoming'"
        [style.background-color]="node.state === 'upcoming' ? null : colorVar(node.status)"
        [style.box-shadow]="
          node.state === 'current'
            ? '0 0 0 3px color-mix(in srgb, ' + colorVar(node.status) + ' 22%, transparent)'
            : null
        "
      >
        @if (node.state === 'done') {
          <app-icon name="lucideCheck" class="text-[11px]" />
        } @else {
          {{ node.step }}
        }
      </span>
      <span
        class="text-xs"
        [class.font-bold]="node.state === 'current'"
        [class.font-medium]="node.state !== 'current'"
        [class.text-fg]="node.state === 'current'"
        [class.text-fg-subtle]="node.state !== 'current'"
      >
        {{ 'msg.status.' + node.status | transloco }}
      </span>
    </ng-template>

    <app-confirm />
    <app-publish-confirm-dialog
      [(visible)]="publishVisible"
      [referencedMessages]="references()"
      (confirmed)="statusService.publish($event)"
    />
  `,
  host: { class: 'contents' },
})
export class StatusFlow {
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notify = inject(NotificationService);
  protected readonly facade = inject(EditorFacade);
  protected readonly statusService = inject(MessageStatusService);

  protected readonly status = computed<MessageStatus | undefined>(
    () => this.facade.message()?.status,
  );
  protected readonly scheduled = computed<boolean>(() => !!this.facade.message()?.publishDateFrom);
  protected readonly publishDateFrom = computed<string | undefined>(
    () => this.facade.message()?.publishDateFrom,
  );

  protected readonly references = signal<readonly Message[]>([]);
  protected readonly publishVisible = signal(false);

  /**
   * The stepper nodes for the current status. DRAFT/VERIFIED/PUBLISHED map onto the spine
   * (done / current / upcoming); EXPIRED/CANCELLED show the full spine done plus a current terminal
   * node; DELETED (or an unknown/absent status) renders nothing.
   */
  protected readonly nodes = computed<readonly FlowNode[]>(() => {
    const status = this.status();
    if (!status || status === 'DELETED') {
      return [];
    }
    const spineIndex = SPINE.indexOf(status);
    if (spineIndex >= 0) {
      return SPINE.map((nodeStatus, i) => ({
        status: nodeStatus,
        step: i + 1,
        state: i < spineIndex ? 'done' : i === spineIndex ? 'current' : 'upcoming',
        // The Draft node steps a Verified message back to Draft (un-verify).
        stepBack: status === 'VERIFIED' && nodeStatus === 'DRAFT',
      }));
    }
    // Terminal (EXPIRED / CANCELLED): the whole spine is done, the outcome is current.
    return [
      ...SPINE.map((nodeStatus, i) => ({
        status: nodeStatus,
        step: i + 1,
        state: 'done' as const,
        stepBack: false,
      })),
      { status, step: SPINE.length + 1, state: 'current' as const, stepBack: false },
    ];
  });

  /** The single forward action offered next to the stepper, or `null` (no forward action). */
  protected readonly cta = computed<'verify' | 'publish' | 'scheduled' | null>(() => {
    switch (this.status()) {
      case 'DRAFT':
        return 'verify';
      case 'VERIFIED':
        return this.scheduled() ? 'scheduled' : 'publish';
      default:
        return null;
    }
  });

  /** The strong per-status colour token used to fill a done/current node circle. */
  protected colorVar(status: MessageStatus): string {
    return `var(--niord-status-${status.toLowerCase()}-fg)`;
  }

  protected confirmVerify(): void {
    if (!this.statusService.validateForPublish()) {
      return;
    }
    this.confirm.confirmNeutral({
      message: this.transloco.translate('editor.status.confirm.verify'),
      acceptLabel: this.transloco.translate('editor.status.verify_msg'),
      rejectLabel: this.transloco.translate('term.cancel'),
      accept: () => this.statusService.verify(),
    });
  }

  protected confirmRevert(): void {
    this.confirm.confirmNeutral({
      message: this.transloco.translate('editor.status.confirm.revertToDraft'),
      acceptLabel: this.transloco.translate('editor.status.action.revertToDraft'),
      rejectLabel: this.transloco.translate('term.cancel'),
      accept: () => this.statusService.revertToDraft(),
    });
  }

  /** Validate, look up the messages publishing would cascade-cancel, then open the publish dialog. */
  protected openPublish(): void {
    if (!this.statusService.validateForPublish()) {
      return;
    }
    this.statusService
      .referencedMessages()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refs) => {
          this.references.set(refs);
          this.publishVisible.set(true);
        },
        error: () => {
          this.notify.error('editor.status.error.lookupFailed');
          this.references.set([]);
          this.publishVisible.set(true);
        },
      });
  }
}
