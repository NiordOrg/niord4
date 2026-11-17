import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { Icon } from '../../../../shared/icons/icon';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../shared/components/tabs/tabs';
import type { BasePromulgation, MessageStatus } from '../../../messages/models/message.model';
import { PromulgationsApiService } from '../../../messages/services/api/promulgations-api.service';
import { canonicalKey, promulgationsContent } from '../../util/message-change.util';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldRow } from '../../components/editor-field-row/editor-field-row';
import { PromulgationEditorOutlet } from '../promulgation-editor-outlet';
import type { PromulgationEditorContext } from '../promulgation-editor.token';

/**
 * The Promulgation row in the Misc section: a collapsed summary of the on-channel type names and,
 * when expanded, one `<app-tabs>` tab per promulgation entry (checkbox + type name heading) whose
 * panel hosts the per-channel editor via `PromulgationEditorOutlet`. It also owns the editor
 * CONTEXT the channel editors call: `patch`/`generate`/`reformat` route to the facade's single write
 * path, `busy` reflects an in-flight round-trip, and `messageStatus` gates the SafetyNET id.
 *
 * The row shows only when the message actually carries promulgations (server-computed
 * per type/series/domain via adjust); the checkbox can turn a MANDATORY channel ON but never OFF;
 * an unchecked (or non-editable) channel body is disabled + greyed via a native `<fieldset>`;
 * Generate confirms once per channel (or once for Generate-all) and deep-merges the response by
 * `typeId`, leaving sibling channels' unsaved edits intact.
 */
@Component({
  selector: 'app-promulgation-field',
  imports: [
    TranslocoPipe,
    Icon,
    EditorFieldRow,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    PromulgationEditorOutlet,
  ],
  templateUrl: './promulgation-field.html',
  host: { class: 'contents' },
})
export class PromulgationField {
  protected readonly facade = inject(EditorFacade);
  private readonly promulgationsApi = inject(PromulgationsApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notify = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly busyTypes = signal<ReadonlySet<string>>(new Set());

  /** The working message's promulgation entries (server-owned, replaced on adjust). */
  protected readonly promulgations = computed<readonly BasePromulgation[]>(
    () => this.facade.message()?.promulgations ?? [],
  );

  /** The row renders only when the message carries a promulgation array. */
  protected readonly present = computed<boolean>(() => !!this.facade.message()?.promulgations);

  /** Collapsed summary: the on-channel type names, joined; empty ⇒ the localized "none". */
  protected readonly onChannelSummary = computed<string>(() =>
    this.promulgations()
      .filter((entry) => entry.promulgate)
      .map((entry) => entry.type.name)
      .join(', '),
  );

  /** The row is invalid while any MANDATORY channel is not promulgating (publish blocker). */
  protected readonly invalid = computed<boolean>(() =>
    this.promulgations().some((entry) => this.isMandatoryMissing(entry)),
  );

  /**
   * Edited-dot: the promulgating channels' content differs from the loaded baseline (no service
   * slice covers promulgations). Uses the same {@link promulgationsContent} projection as the
   * message-level unsaved-changes signature, so the dot and the Save/discard guard agree — and a
   * Type change that only shows/hides/re-ids a not-chosen channel does not light it.
   */
  protected readonly edited = computed<boolean>(() => {
    const current = this.facade.message();
    const baseline = this.facade.loadedBaseline();
    if (!current || !baseline) {
      return false;
    }
    return (
      canonicalKey(promulgationsContent(current.promulgations)) !==
      canonicalKey(promulgationsContent(baseline.promulgations))
    );
  });

  /** Active tab = a promulgation `typeId`; keeps the current tab across message updates (edits). */
  protected readonly activeTab = linkedSignal<readonly BasePromulgation[], string | undefined>({
    source: () => this.promulgations(),
    computation: (entries, previous) => {
      const ids = entries.map((entry) => entry.type.typeId);
      if (previous && ids.includes(previous.value ?? '')) {
        return previous.value;
      }
      return ids[0];
    },
  });

  /**
   * The context every channel editor receives (via the outlet input). Stable object; the closures
   * read live signals so `busy`/`messageStatus` stay reactive in the child templates.
   */
  protected readonly context: PromulgationEditorContext = {
    patch: (typeId, partial) => this.facade.patchPromulgation(typeId, partial),
    generate: (typeId) => this.confirmAndGenerate(typeId),
    reformat: (serviceId, typeId) => this.runReformat(serviceId, typeId),
    busy: (typeId) => this.busyTypes().has(typeId),
    messageStatus: computed<MessageStatus | undefined>(() => this.facade.message()?.status),
  };

  protected isMandatoryMissing(entry: BasePromulgation): boolean {
    return entry.type.requirement === 'MANDATORY' && !entry.promulgate;
  }

  /** The MANDATORY asymmetry: checkable ON, never OFF once promulgating (facade double-enforces too). */
  protected checkboxDisabled(entry: BasePromulgation): boolean {
    return (
      !this.facade.editableNow() || (entry.type.requirement === 'MANDATORY' && entry.promulgate)
    );
  }

  protected onToggle(typeId: string, promulgate: boolean): void {
    this.facade.setPromulgate(typeId, promulgate);
  }

  /**
   * Keep Space/Enter on the heading checkbox from bubbling to the aria tablist, whose
   * KeyboardEventManager would `preventDefault` the key (cancelling the native checkbox toggle) and
   * re-select the active tab.
   */
  protected onCheckboxKeydown(event: KeyboardEvent): void {
    if (event.key === ' ' || event.key === 'Enter') {
      event.stopPropagation();
    }
  }

  /** Generate-all: one confirm, then regenerate every promulgating channel (disabled ones skipped). */
  protected generateAll(): void {
    this.confirm.confirmNeutral({
      message: this.transloco.translate('promulgation.confirm.generate_all'),
      accept: () => {
        for (const entry of this.promulgations()) {
          if (entry.promulgate) {
            this.runGenerate(entry.type.typeId);
          }
        }
      },
    });
  }

  private confirmAndGenerate(typeId: string): void {
    const entry = this.entryFor(typeId);
    if (!entry) {
      return;
    }
    this.confirm.confirmNeutral({
      message: this.transloco.translate('promulgation.confirm.generate', {
        type: entry.type.name,
      }),
      accept: () => this.runGenerate(typeId),
    });
  }

  private runGenerate(typeId: string): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    this.setBusy(typeId, true);
    // Body = the FULL working message (unsaved sibling edits included); the response is deep-merged
    // back into this entry by `typeId`, so siblings survive.
    this.promulgationsApi.generatePromulgation(typeId, message).subscribe({
      next: (result) => {
        this.facade.applyGeneratedPromulgation(typeId, result);
        this.setBusy(typeId, false);
      },
      error: () => {
        this.setBusy(typeId, false);
        this.notify.error('editor.promulgation.generateError', {
          type: this.entryFor(typeId)?.type.name ?? '',
        });
      },
    });
  }

  private runReformat(serviceId: string, typeId: string): void {
    const entry = this.entryFor(typeId);
    if (!entry) {
      return;
    }
    this.setBusy(typeId, true);
    this.promulgationsApi.reformat(serviceId, entry).subscribe({
      next: (result) => {
        // Only the reformatted `text` is consumed.
        const text = (result as BasePromulgation & { text?: string }).text;
        this.facade.patchPromulgation(typeId, { text });
        this.setBusy(typeId, false);
      },
      error: () => {
        this.setBusy(typeId, false);
        this.notify.error('editor.promulgation.formatError', { type: entry.type.name });
      },
    });
  }

  private entryFor(typeId: string): BasePromulgation | undefined {
    return this.promulgations().find((entry) => entry.type.typeId === typeId);
  }

  private setBusy(typeId: string, busy: boolean): void {
    this.busyTypes.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(typeId);
      } else {
        next.delete(typeId);
      }
      return next;
    });
  }
}
