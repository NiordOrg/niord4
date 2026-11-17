import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { HelpAnchor } from '../../../core/main-shell/help/help-anchor';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { Drawer } from '../../../shared/components/drawer/drawer';
import { Icon } from '../../../shared/icons/icon';
import { BaseMapLayers } from '../../../shared/components/ol-map/base-map-layers';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { OlMap } from '@niord/map/ol-map';
import { MapShellService } from '@niord/map/map-shell.service';
import { MessageDetailsMenu } from '../message-details-menu/message-details-menu';
import { MessageDetailsRenderer } from '../components/message-details-renderer/message-details-renderer';
import { OlMessageDetailsLayer } from '../components/map-layers/ol-message-details-layer';
import { OlMessageLabelsLayer } from '../components/map-layers/ol-message-labels-layer';
import { MessageTagsDialogService } from '../dialogs/message-tags-dialog/message-tags-dialog.service';
import { MessageSelectionService } from '../services/message-selection.service';
import {
  MessageDetailsDialogService,
  type DetailsNavSnapshot,
} from '../services/message-details-dialog.service';
import type { Message } from '../models/message.model';
import { PromulgationList } from '../promulgation/promulgation-list/promulgation-list';

@Component({
  selector: 'app-message-details-dialog',
  imports: [
    BaseMapLayers,
    Drawer,
    HelpAnchor,
    Icon,
    MessageDetailsMenu,
    MessageDetailsRenderer,
    OlLayerSwitcher,
    OlMap,
    OlMessageDetailsLayer,
    OlMessageLabelsLayer,
    PromulgationList,
    TranslocoPipe,
  ],
  templateUrl: './message-details-dialog.html',
  // Own MapShellService so the embedded OlMap can never inherit (and leak
  // into) another view's shell — e.g. the messages map view's — regardless of
  // where this component is declared in the tree.
  providers: [MapShellService],
})
export class MessageDetailsDialog {
  protected readonly dialog = inject(MessageDetailsDialogService);
  private readonly tagsManager = inject(MessageTagsDialogService);
  private readonly language = inject(LanguageService);
  private readonly selection = inject(MessageSelectionService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly config = inject(APP_CONFIG);

  // Translated layer-switcher label. Reads as `translateSignal('key')()`
  // in the template (the function-call form is required because translateSignal
  // returns a Signal, not the value directly).
  protected readonly labelsName = translateSignal('messages.map.layers.labels');

  protected readonly messageResource = httpResource<Message | null>(
    () => {
      const id = this.dialog.currentMessageId();
      if (id === null) return undefined;
      return {
        url: `${this.config.apiBaseUrl}/messages/message/${encodeURIComponent(id)}`,
        params: { lang: this.language.selectedLanguage() },
      };
    },
    { defaultValue: null },
  );

  protected readonly loading = computed(() => this.messageResource.isLoading());

  /** The lookup failed in any way; `value()` throws then, so every read of it checks this first. */
  private readonly failed = computed(() => this.messageResource.error() !== undefined);

  /**
   * The backend refused the lookup (401 or 403): a guest following a link to a message that is
   * not public, or a user whose session no longer reaches it. To that reader the message is not
   * there, which is what the backend says by refusing, so the drawer shows its not-found line;
   * a retry could never succeed, so no toast asks for one.
   */
  private readonly refused = computed(() => {
    const error = this.messageResource.error();
    return error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403);
  });

  /** A failure a retry may cure (the network, the server): the error toast is shown for it. */
  protected readonly errored = computed(() => this.failed() && !this.refused());

  /**
   * The previously-resolved (non-null) message, mirrored from the resource.
   * Used as the fallback for `message()` during prev/next/reference navigation
   * so the map and its layer switcher state survive the reload window — when
   * `messageResource.value()` briefly returns null between params change and
   * the new stream's first emit, the inline `@if (message())` block in the
   * template would otherwise unmount the entire `<app-ol-map>` and lose any
   * checkbox toggles the user made.
   *
   * NOT the shared `latchWhileSettled`: that keeps the last value on load OR error, whereas
   * `message()` must resolve to null on error AND on not-found (so the error toast / "not found"
   * warning surface). Here the retained value is used ONLY during the loading window — the
   * opposite error contract — so the latch is hand-rolled deliberately.
   */
  private readonly lastResolvedMessage = signal<Message | null>(null);

  /** Resolved message, or null when loading / errored / not-found. */
  protected readonly message = computed<Message | null>(() => {
    if (this.failed()) return null;
    if (!this.messageResource.hasValue()) return null;
    const current = this.messageResource.value();
    if (current !== null) return current;
    // value() is null but resource is loading — fall back to the last resolved
    // message so the map stays mounted across navigation.
    if (this.loading()) {
      const previous = this.lastResolvedMessage();
      if (previous !== null) return previous;
    }
    return null;
  });

  /**
   * Non-null when the message is not there: the resource resolved to null, or the backend refused
   * the lookup (see `refused`).
   */
  protected readonly warning = computed<string | null>(() => {
    if (this.loading() || this.errored()) return null;
    const id = this.dialog.currentMessageId();
    if (id === null) return null;
    if (this.refused()) {
      return this.transloco.translate('messages.dialog.notFound', { messageId: id });
    }
    if (!this.messageResource.hasValue()) return null;
    if (this.messageResource.value() !== null) return null;
    return this.transloco.translate('messages.dialog.notFound', { messageId: id });
  });

  protected readonly hasVisiblePromulgations = computed(() => {
    const m = this.message();
    if (!m) return false;
    return (m.promulgations ?? []).some((p) => p.promulgate);
  });

  protected readonly hasGeometry = computed(() => {
    const m = this.message();
    if (!m) return false;
    return (m.parts ?? []).some((p) => p.geometry != null);
  });

  protected readonly hasAttachmentsAbove = computed(() => {
    const m = this.message();
    if (!m) return false;
    return (m.attachments ?? []).some((a) => a.display === 'ABOVE');
  });

  /** Map placeholder visibility — hidden when there's no geometry OR when an above-attachment claims that slot. */
  protected readonly showMap = computed(() => this.hasGeometry() && !this.hasAttachmentsAbove());

  protected readonly rootIndex = computed(() => {
    const root = this.dialog.rootMessageId();
    if (root === null) return -1;
    return this.dialog.navigationContext().indexOf(root);
  });

  protected readonly stackDepth = computed(() => this.dialog.navigationStack().length);

  /** Stack depth > 1 → drill mode → back button is visible (and prev/next is hidden). */
  protected readonly showBack = computed(() => this.stackDepth() > 1);

  /** Show prev/next only when root is in the supplied context AND we're at the root of the drill stack. */
  protected readonly showNavigation = computed(
    () => this.rootIndex() >= 0 && this.stackDepth() === 1,
  );

  protected readonly prevDisabled = computed(() => this.rootIndex() <= 0);

  protected readonly nextDisabled = computed(() => {
    const i = this.rootIndex();
    return i < 0 || i >= this.dialog.navigationContext().length - 1;
  });

  // Compare against the LOADED message's id (the backend's UUID) rather than
  // `dialog.currentMessageId()`. The latter is the navigation key, which can
  // be a short id like "NM-268-19" when the user drilled in via a reference
  // link (references carry shortIds). The selection set is keyed by UUID, so
  // matching against the loaded message keeps the star in sync regardless of
  // how the dialog was opened (row click vs reference drill vs deep link).
  protected readonly isSelected = computed(() => {
    const m = this.message();
    return m !== null && this.selection.isSelected(m.id);
  });

  // Replace-&-restore state for the "Manage tags…" hand-off (see the effect below).
  private managerWasVisible = false;
  private pendingRestore: DetailsNavSnapshot | null = null;

  constructor() {
    effect(() => {
      if (this.errored()) {
        this.notifications.notify({
          severity: 'error',
          summaryKey: 'messages.dialog.errorToast.summary',
          detailKey: 'messages.dialog.errorToast.detail',
        });
      }
    });

    // Replace & restore: the tag manager is also a right drawer, so it can't
    // stack on top of this one. When it opens while details is showing, step
    // aside (remember the root id + nav context, close details); when it closes
    // again, reopen details where it was. Only `tagsManager.visible()` is a
    // reactive dependency — the dialog reads are untracked so closing/reopening
    // details here doesn't re-trigger this effect.
    effect(() => {
      const managerVisible = this.tagsManager.visible();
      untracked(() => {
        const wasVisible = this.managerWasVisible;
        this.managerWasVisible = managerVisible;
        if (managerVisible && !wasVisible) {
          // Capture the full nav state (incl. the in-memory drill stack) so a
          // drilled-into reference is restored, not just the root message.
          const snap = this.dialog.snapshot();
          if (snap) {
            this.pendingRestore = snap;
            this.dialog.close();
          }
        } else if (!managerVisible && wasVisible && this.pendingRestore) {
          const snap = this.pendingRestore;
          this.pendingRestore = null;
          this.dialog.restore(snap);
        }
      });
    });

    // Mirror every non-null resource value into `lastResolvedMessage` so the
    // fallback in `message()` has something to return during the reload
    // window. Guarded against the errored state — `value()` throws when the
    // resource is in error, and we don't want a successful stash from the
    // previous load to be wiped just because a follow-up navigation 4xx'd.
    effect(() => {
      if (this.failed() || !this.messageResource.hasValue()) return;
      const v = this.messageResource.value();
      if (v !== null && v !== undefined) {
        this.lastResolvedMessage.set(v);
      }
    });

    // Drop the retained message once the drawer is fully closed. The fallback
    // exists only to keep the map mounted across prev/next while the drawer
    // stays OPEN; without this reset, reopening the drawer on a DIFFERENT
    // message flashes the previously-shown content during its load window.
    effect(() => {
      if (!this.dialog.isOpen()) {
        untracked(() => this.lastResolvedMessage.set(null));
      }
    });
  }

  protected onReferenceClicked(messageId: string): void {
    this.dialog.selectReference(messageId);
  }

  protected toggleSelectMessage(): void {
    const m = this.message();
    if (m === null) return;
    this.selection.toggle(m);
  }
}
