import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { SandboxedHtml } from '../../../../../shared/components/sandboxed-html/sandboxed-html';
import { Icon } from '../../../../../shared/icons/icon';
import { MessageIdField } from '../../../../messages/components/message-id-field/message-id-field';
import {
  MailingListsApiService,
  type TestExecutionMail,
} from '../../../services/api/mailing-lists-api.service';
import type { MailingList } from './mailing-list.model';

export interface TriggerTestModel {
  /** The whole current (possibly unsaved) working list — sent verbatim to test-execute. */
  readonly list: MailingList;
  /** The POSITIONAL trigger index (read at click time). */
  readonly triggerIndex: number;
}

const TEST_MESSAGE_KEY = 'niord.mailingList.testMessage';

/**
 * Test-execute one trigger. Sends the whole working list + the positional trigger index (so a user
 * can test unsaved edits). A STATUS_CHANGE trigger needs a test message (a message-id picker,
 * seeded from + persisted to localStorage across sessions); a SCHEDULED trigger executes against
 * its own query. Produced mails list in a table; selecting one renders its HTML body in the
 * sandboxed iframe (never `[innerHTML]`).
 */
@Component({
  selector: 'app-trigger-test-dialog',
  imports: [Dialog, SandboxedHtml, Icon, MessageIdField, TranslocoPipe],
  templateUrl: './trigger-test-dialog.html',
  host: { class: 'contents' },
})
export class TriggerTestDialog {
  private readonly api = inject(MailingListsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the editor owns visibility. */
  readonly model = input.required<TriggerTestModel | undefined>();
  readonly closed = output<void>();

  protected readonly messageId = signal<string | null>(null);
  protected readonly mails = signal<TestExecutionMail[] | undefined>(undefined);
  protected readonly selectedMail = signal<TestExecutionMail | undefined>(undefined);
  protected readonly executing = signal(false);

  protected readonly trigger = computed(() => {
    const model = this.model();
    return model ? model.list.triggers?.[model.triggerIndex] : undefined;
  });
  protected readonly isStatusChange = computed(() => this.trigger()?.type === 'STATUS_CHANGE');

  /** SCHEDULED always executable; STATUS_CHANGE needs a chosen message. */
  protected readonly canExecute = computed(
    () => !this.isStatusChange() || (this.messageId() ?? '') !== '',
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => {
          this.mails.set(undefined);
          this.selectedMail.set(undefined);
          this.executing.set(false);
          // Seed the last-used test message for a STATUS_CHANGE trigger.
          this.messageId.set(
            model.list.triggers?.[model.triggerIndex]?.type === 'STATUS_CHANGE'
              ? localStorage.getItem(TEST_MESSAGE_KEY)
              : null,
          );
        });
      }
    });
  }

  protected onVisibleChange(visible: boolean): void {
    if (!visible) {
      this.closed.emit();
    }
  }

  protected execute(): void {
    const model = this.model();
    if (!model || this.executing() || !this.canExecute()) {
      return;
    }
    const messageId = this.messageId() ?? undefined;
    if (this.isStatusChange() && messageId) {
      localStorage.setItem(TEST_MESSAGE_KEY, messageId);
    }
    this.executing.set(true);
    this.selectedMail.set(undefined);
    this.api.testExecuteTrigger(model.list, model.triggerIndex, messageId).subscribe({
      next: (mails) => {
        this.executing.set(false);
        this.mails.set(mails);
      },
      error: () => {
        this.executing.set(false);
        this.notifications.error('admin.mailingLists.test.error');
      },
    });
  }
}
