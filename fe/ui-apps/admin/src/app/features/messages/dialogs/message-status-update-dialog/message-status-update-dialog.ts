import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../shared/api/problem-detail';
import { Select } from '../../../../shared/forms/select/select';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';
import { StatusClassPipe } from '../../../../shared/pipes/status-class.pipe';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { validTransitionsFor } from '../../../../shared/models/message-status-transitions';
import { MessageIdBadge } from '../../components/message-id-badge/message-id-badge';
import { Message, MessageStatus } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageListFacade } from '../../services/message-list.facade';
import { MessageSelectionService } from '../../services/message-selection.service';
import { MessagesApiService } from '../../services/api/messages-api.service';

interface BulkTransition {
  readonly from: MessageStatus;
  readonly to: MessageStatus;
}

/**
 * Why the backend's publish gate refuses a row's target (MSG10): the same rule as the editor's
 * own Verify and Publish. `NO_TITLE` and `MANDATORY_PROMULGATION_OFF` refuse VERIFIED and
 * PUBLISHED; `SCHEDULED` refuses PUBLISHED before the message's publish-from date.
 */
type GateReasonCode = 'NO_TITLE' | 'MANDATORY_PROMULGATION_OFF' | 'SCHEDULED';

interface GateReason {
  readonly code: GateReasonCode;
  /** `MANDATORY_PROMULGATION_OFF`: the names of the mandatory channels that are off. */
  readonly types?: readonly string[];
  /** `SCHEDULED`: the publish-from date (ISO string or epoch milliseconds). */
  readonly date?: string | number;
}

/** The targets the publish gate checks. */
const GATED_TARGETS: readonly MessageStatus[] = ['VERIFIED', 'PUBLISHED'];

/**
 * Bulk status-update dialog (editor-gated). Lists the transitionable selection
 * (terminal-status messages excluded), offers a per-message target from the valid
 * transitions plus a "change all" bulk-apply, confirms, then PUTs and refreshes
 * the list. The wire payload keys on the UUID `id` (not `shortId`).
 *
 * The backend refuses a Verify or a Publish the editor's own panel would refuse (no title, a
 * mandatory channel off, a Publish before the publish-from date) with one 409 for the whole list,
 * whose `refusals` name each refused message and why. Before Save the dialog marks the rows whose
 * list data already shows such a reason (no title, a future publish-from date; the list carries no
 * channels); after the 409 it marks the rows the backend named, with its reasons, and stays open.
 */
@Component({
  selector: 'app-message-status-update-dialog',
  imports: [
    Dialog,
    HelpAnchor,
    Select,
    Confirm,
    MessageIdBadge,
    StatusClassPipe,
    LocaleDatePipe,
    TranslocoPipe,
  ],
  providers: [ConfirmService],
  templateUrl: './message-status-update-dialog.html',
  host: { class: 'contents' },
})
export class MessageStatusUpdateDialog {
  private readonly api = inject(MessagesApiService);
  private readonly list = inject(MessageListFacade);
  private readonly selection = inject(MessageSelectionService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly descriptions = inject(MessageDescriptionService);
  private readonly transloco = inject(TranslocoService);

  protected readonly visible = signal(false);
  protected readonly saving = signal(false);
  private readonly messages = signal<readonly Message[]>([]);
  /** Chosen target status per message id (absent = no change). */
  protected readonly targets = signal<Map<string, MessageStatus>>(new Map());

  /** Transitionable rows — terminal-status messages have no valid targets and are excluded. */
  protected readonly rows = computed(() =>
    this.messages().filter((message) => validTransitionsFor(message.status).length > 0),
  );

  /** Distinct from→to transitions present across the rows (the bulk-apply options). */
  protected readonly bulkOptions = computed<BulkTransition[]>(() => {
    const seen = new Set<string>();
    const options: BulkTransition[] = [];
    for (const row of this.rows()) {
      const from = row.status;
      if (!from) {
        continue;
      }
      for (const to of validTransitionsFor(from)) {
        const key = `${from}>${to}`;
        if (!seen.has(key)) {
          seen.add(key);
          options.push({ from, to });
        }
      }
    }
    return options;
  });

  /** A bulk option's label, "from → to" translated, so each option is told apart by its text. */
  protected readonly bulkLabel = (option: BulkTransition): string =>
    `${this.transloco.translate('msg.status.' + option.from)} → ${this.transloco.translate('msg.status.' + option.to)}`;

  protected readonly hasTargets = computed(() => this.targets().size > 0);

  /**
   * The backend's answer in its last gate 409, per row sent: its reasons, or none for a row it let
   * pass. A row's entry is dropped when its target changes, so the list data's guess shows again.
   */
  private readonly refusals = signal<ReadonlyMap<string, readonly GateReason[]>>(new Map());

  /**
   * What the row's list data already shows the gate will refuse for its chosen target: no title in
   * the list's descriptions, or a Publish before a publish-from date still ahead.
   */
  private readonly expectedReasons = computed<ReadonlyMap<string, readonly GateReason[]>>(() => {
    const targets = this.targets();
    const now = Date.now();
    const result = new Map<string, readonly GateReason[]>();
    for (const row of this.rows()) {
      const target = targets.get(row.id);
      if (target === undefined || !GATED_TARGETS.includes(target)) {
        continue;
      }
      const reasons: GateReason[] = [];
      if (!hasTitle(row)) {
        reasons.push({ code: 'NO_TITLE' });
      }
      const publishFrom = epochOf(row.publishDateFrom);
      if (target === 'PUBLISHED' && publishFrom !== undefined && publishFrom > now) {
        reasons.push({ code: 'SCHEDULED', date: row.publishDateFrom });
      }
      if (reasons.length > 0) {
        result.set(row.id, reasons);
      }
    }
    return result;
  });

  /** The marks shown per row: the backend's reasons after a 409, else what the list data shows. */
  protected readonly rowReasons = computed<ReadonlyMap<string, readonly GateReason[]>>(() => {
    const refused = this.refusals();
    const expected = this.expectedReasons();
    const result = new Map<string, readonly GateReason[]>();
    for (const row of this.rows()) {
      const reasons = refused.get(row.id) ?? expected.get(row.id);
      if (reasons !== undefined && reasons.length > 0) {
        result.set(row.id, reasons);
      }
    }
    return result;
  });

  open(messages: readonly Message[]): void {
    this.messages.set(messages);
    this.targets.set(new Map());
    this.refusals.set(new Map());
    this.saving.set(false);
    this.visible.set(true);
  }

  protected targetOptions(row: Message): MessageStatus[] {
    return [...validTransitionsFor(row.status)];
  }

  protected title(row: Message): string {
    return this.descriptions.getTitle(row.descs ?? []);
  }

  protected setTarget(id: string, status: MessageStatus | null): void {
    this.targets.update((current) => {
      const next = new Map(current);
      if (status) {
        next.set(id, status);
      } else {
        next.delete(id);
      }
      return next;
    });
    this.dropRefusals([id]);
  }

  /**
   * Applies a "Change all statuses" pick. The bulk select is an action trigger (`clearOnPick`):
   * it keeps no value, so it always shows its placeholder and the same transition can be picked
   * again, also after the dialog is closed and opened again.
   */
  protected applyBulk(option: BulkTransition): void {
    const changed = this.rows()
      .filter((row) => row.status === option.from)
      .map((row) => row.id);
    this.targets.update((current) => {
      const next = new Map(current);
      for (const id of changed) {
        next.set(id, option.to);
      }
      return next;
    });
    this.dropRefusals(changed);
  }

  protected save(): void {
    const targets = this.targets();
    const updates = this.rows()
      .filter((row) => {
        const target = targets.get(row.id);
        // Only emit a target that is actually a valid transition for the row.
        return target !== undefined && validTransitionsFor(row.status).includes(target);
      })
      .map((row) => ({ messageId: row.id, status: targets.get(row.id) as MessageStatus }));
    if (updates.length === 0) {
      // No-op guard — nothing chosen, just close.
      this.visible.set(false);
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('messages.statusUpdate.confirm.header'),
      message: this.transloco.translate('messages.statusUpdate.confirm.message', {
        count: updates.length,
      }),
      accept: () => {
        this.saving.set(true);
        this.api.updateStatuses(updates).subscribe({
          next: () => {
            this.notifications.success('messages.statusUpdate.toast.success');
            // Recolour the selection snapshots immediately: list.refresh() only re-syncs
            // messages still matching the active filter, but a status change can drop a
            // message out of it — leaving the Selection tab on the old status colour.
            this.selection.applyStatusUpdates(updates);
            this.list.refresh();
            this.visible.set(false);
          },
          error: (error: unknown) => {
            this.saving.set(false);
            // The list is one change: a 409 refused one of its items and changed none of them,
            // so say which and why.
            const conflict = error instanceof HttpErrorResponse && error.status === 409;
            const refused = conflict
              ? this.matchRefusals(error)
              : new Map<string, readonly GateReason[]>();
            if (refused.size > 0) {
              // The publish gate checked every item and named the refused ones: mark those with
              // the backend's reasons, and clear the list data's guess on the rows it let pass.
              const answered = new Map<string, readonly GateReason[]>(
                updates.map((update) => [update.messageId, []]),
              );
              for (const [id, reasons] of refused) {
                answered.set(id, reasons);
              }
              this.refusals.set(answered);
              this.notifications.error(
                refused.size === 1
                  ? 'messages.statusUpdate.toast.rowsRefusedOne'
                  : 'messages.statusUpdate.toast.rowsRefused',
                { count: refused.size },
              );
              return;
            }
            const detail = conflict ? problemDetail(error) : undefined;
            if (detail === undefined) {
              this.notifications.error('messages.statusUpdate.toast.error');
            } else {
              this.notifications.error('messages.statusUpdate.toast.refused', { detail });
            }
          },
        });
      },
    });
  }

  protected close(): void {
    this.visible.set(false);
  }

  /** The 409's `refusals`, keyed by the row they name (by its id, else by its short id). */
  private matchRefusals(error: HttpErrorResponse): ReadonlyMap<string, readonly GateReason[]> {
    const result = new Map<string, readonly GateReason[]>();
    const rows = this.rows();
    for (const refusal of refusalsOf(error)) {
      const row =
        rows.find((candidate) => candidate.id === refusal.messageId) ??
        rows.find(
          (candidate) =>
            refusal.shortId !== undefined &&
            candidate.shortId !== undefined &&
            candidate.shortId === refusal.shortId,
        );
      if (row !== undefined) {
        result.set(row.id, refusal.reasons);
      }
    }
    return result;
  }

  private dropRefusals(ids: readonly string[]): void {
    if (ids.some((id) => this.refusals().has(id))) {
      this.refusals.update((current) => {
        const next = new Map(current);
        for (const id of ids) {
          next.delete(id);
        }
        return next;
      });
    }
  }
}

interface Refusal {
  readonly messageId?: string;
  readonly shortId?: string;
  readonly reasons: readonly GateReason[];
}

function hasTitle(message: Message): boolean {
  return (message.descs ?? []).some((desc) => (desc.title?.trim().length ?? 0) > 0);
}

function epochOf(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isNaN(value) ? undefined : value;
  }
  if (typeof value === 'string' && value !== '') {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? undefined : parsed;
  }
  return undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Reads the publish gate's `refusals` property off a 409 problem document:
 * `[{ messageId, shortId, reasons: [{ code, types?, date? }] }]`. A reason may also come as its
 * bare code. Unknown codes are left out, and a refusal with no known reason is dropped.
 */
function refusalsOf(error: HttpErrorResponse): Refusal[] {
  let body: unknown = error.error;
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      return [];
    }
  }
  if (!isRecord(body) || !Array.isArray(body['refusals'])) {
    return [];
  }
  const result: Refusal[] = [];
  for (const item of body['refusals'] as unknown[]) {
    if (!isRecord(item) || !Array.isArray(item['reasons'])) {
      continue;
    }
    const reasons = (item['reasons'] as unknown[])
      .map(reasonOf)
      .filter((reason): reason is GateReason => reason !== undefined);
    if (reasons.length === 0) {
      continue;
    }
    const messageId = item['messageId'];
    const shortId = item['shortId'];
    result.push({
      messageId: typeof messageId === 'string' ? messageId : undefined,
      shortId: typeof shortId === 'string' && shortId !== '' ? shortId : undefined,
      reasons,
    });
  }
  return result;
}

function reasonOf(value: unknown): GateReason | undefined {
  const raw: unknown = typeof value === 'string' ? { code: value } : value;
  if (!isRecord(raw)) {
    return undefined;
  }
  switch (raw['code']) {
    case 'NO_TITLE':
      return { code: 'NO_TITLE' };
    case 'MANDATORY_PROMULGATION_OFF': {
      const types = Array.isArray(raw['types'])
        ? (raw['types'] as unknown[]).filter((type): type is string => typeof type === 'string')
        : [];
      return { code: 'MANDATORY_PROMULGATION_OFF', types };
    }
    case 'SCHEDULED': {
      const date = raw['date'];
      return {
        code: 'SCHEDULED',
        date: typeof date === 'string' || typeof date === 'number' ? date : undefined,
      };
    }
    default:
      return undefined;
  }
}
