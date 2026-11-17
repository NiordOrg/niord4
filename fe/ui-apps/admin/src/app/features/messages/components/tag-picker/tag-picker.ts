import {
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { of, switchMap, timer } from 'rxjs';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { TAG_SELECTION_STORE } from '../../../../core/domain/services/tag-selection-store.token';
import type { MessageTag, MessageTagType } from '../../../../shared/models/message-tag.model';
import { MessageTagsDialogService } from '../../dialogs/message-tags-dialog/message-tags-dialog.service';
import { TAG_TYPE_ICONS } from '../../../../shared/models/tag-type-icons';
import { Icon } from '../../../../shared/icons/icon';
import { latchWhileSettled } from '../../../../shared/utils/latch-while-settled';
import { PickerPopover } from '../../../../shared/components/picker-popover/picker-popover';
import { TagScopeSelect } from '../../../../shared/components/tag-scope-select/tag-scope-select';
import { MessageTagActionsService } from '../../services/message-tag-actions.service';
import { TagsApiService } from '../../services/api/tags-api.service';

export type TagPickerMode = 'add' | 'filter';

/** Stable empty rows for the latch's not-yet-loaded state (avoids a fresh array per read). */
const EMPTY_TAGS: readonly MessageTag[] = [];

/** What an add-mode picker operates on. */
export interface TagPickerTarget {
  readonly messageIds: readonly string[];
  /** Human-readable id (shortId) shown under the header for single-message targets. */
  readonly displayId?: string;
  /**
   * Remove flavor: list ONLY the tags the targets already belong to, so the
   * picker reads as "remove from tag" (you can't remove from a tag the
   * selection isn't in). Toggle semantics are unchanged membership editing.
   */
  readonly membershipOnly?: boolean;
}

/** Filter-mode toggle notification — consumers apply it immediately (live-apply). */
export interface TagPickerToggle {
  readonly tag: MessageTag;
  readonly selected: boolean;
}

/**
 * Anchored tag-picker popover serving every "put messages in a tag" and
 * "filter by tag" surface. The scope chips (private/domain/public) multi-
 * select like the manager dialog's — none selected means all scopes.
 *
 * `mode="add"` is a MEMBERSHIP editor for the target messages: each tag's
 * checkbox pre-loads as checked (all targets in the tag), indeterminate (some)
 * or unchecked, and toggling fires the idempotent add/remove API call right
 * away. Locked tags are excluded entirely (`locked=false` on every add-mode
 * fetch) — they are managed centrally and never hand-edited here. With
 * `membershipOnly` the list narrows to the targets' current tags (the
 * "Remove from tag…" flavor).
 *
 * `mode="filter"` owns no state: the consumer passes `selectedIds` and applies
 * each `toggled` event immediately. Locked tags ARE listed and selectable for
 * filtering, and this mode works for anonymous users (domain/public scopes).
 *
 * Done/backdrop/Esc all just close — every change has already been applied.
 */
@Component({
  selector: 'app-tag-picker',
  templateUrl: './tag-picker.html',
  imports: [DecimalPipe, FormsModule, Icon, PickerPopover, TagScopeSelect, TranslocoPipe],
  host: { class: 'contents' },
})
export class TagPicker {
  private readonly api = inject(TagsApiService);
  private readonly tagActions = inject(MessageTagActionsService);
  private readonly lastTagStore = inject(TAG_SELECTION_STORE);
  private readonly managerDialog = inject(MessageTagsDialogService);
  private readonly auth = inject(AuthFacade);
  private readonly destroyRef = inject(DestroyRef);

  readonly mode = input.required<TagPickerMode>();
  /** Filter mode: the currently applied tag ids — selection state lives with the consumer. */
  readonly selectedIds = input<readonly string[]>([]);
  /** Hide the Manage/Create links on hosts that don't mount the tags-manager dialog. */
  readonly showManage = input(true);
  readonly toggled = output<TagPickerToggle>();

  private readonly shell = viewChild.required(PickerPopover);

  /** Multi-select scope filter; empty = all scopes (mirrors the manager dialog). */
  protected readonly selectedScopes = signal<readonly MessageTagType[]>([]);
  protected readonly search = signal('');
  /** 0 = never opened; each open bumps it so the list refetches per open. */
  private readonly openTick = signal(0);

  private targetIds: readonly string[] = [];
  protected readonly targetCount = signal(0);
  protected readonly displayId = signal<string | undefined>(undefined);
  protected readonly membershipOnly = signal(false);

  /** Add mode: tagId → how many of the target messages carry the tag. */
  private readonly membership = signal<ReadonlyMap<string, number>>(new Map());
  /** Add mode: the (unlocked) tags the targets belong to, for membershipOnly lists. */
  private readonly membershipTags = signal<readonly MessageTag[]>([]);
  protected readonly membershipPending = signal(false);

  protected readonly tagsResource = rxResource<
    readonly MessageTag[],
    { types: readonly MessageTagType[]; search: string; tick: number; skip: boolean } | undefined
  >({
    params: () =>
      this.openTick() === 0
        ? undefined
        : {
            types: this.selectedScopes(),
            search: this.search().trim(),
            tick: this.openTick(),
            skip: this.membershipOnly(),
          },
    // The timer debounces typing: a param change tears the previous stream
    // down, so only the settled search value reaches the API.
    stream: ({ params }) =>
      params === undefined || params.skip
        ? of<readonly MessageTag[]>([])
        : timer(params.search ? 250 : 0).pipe(
            switchMap(() =>
              this.api.searchTags({
                name: params.search || undefined,
                types: params.types.length > 0 ? params.types : undefined,
                sortBy: 'NAME',
                sortOrder: 'ASC',
                // Locked tags are not hand-editable; the filter list keeps them.
                locked: this.mode() === 'add' ? false : undefined,
              }),
            ),
          ),
    defaultValue: [],
  });

  /**
   * The last successfully-resolved tag list. The list renders from this instead of
   * `tagsResource.value()` directly so it never sees the empty intermediate state during a
   * scope/search refetch — otherwise the whole result list flashes empty on each keystroke.
   * Only a settled (loaded, no error, has value) fetch replaces it (shared latch primitive).
   */
  private readonly latchedTags = latchWhileSettled<readonly MessageTag[]>(
    computed(
      () =>
        !this.tagsResource.isLoading() &&
        this.tagsResource.error() === undefined &&
        this.tagsResource.hasValue(),
    ),
    computed(() => (this.tagsResource.hasValue() ? this.tagsResource.value() : EMPTY_TAGS)),
  );

  protected readonly tags = computed<readonly MessageTag[]>(() => {
    if (!this.membershipOnly()) {
      return this.latchedTags();
    }
    // membershipOnly lists are small and already fetched — filter client-side
    // with the same scope/search semantics as the server path.
    const scopes = this.selectedScopes();
    const query = this.search().trim().toLowerCase();
    return this.membershipTags().filter(
      (tag) =>
        (scopes.length === 0 || scopes.includes(tag.type)) &&
        (!query || tag.name.toLowerCase().includes(query)),
    );
  });

  /**
   * The spinner only covers states with nothing meaningful to show: the
   * membership pre-check and a first fetch with no previous rows latched.
   * Refetches keep the previous rows visible instead.
   */
  protected readonly showSpinner = computed(
    () =>
      this.membershipPending() ||
      (this.tagsResource.isLoading() && this.latchedTags().length === 0),
  );

  protected readonly headerKey = computed(() => {
    if (this.mode() === 'filter') return 'messages.tagPicker.headerFilter';
    return this.membershipOnly()
      ? 'messages.tagPicker.headerRemove'
      : 'messages.tagPicker.headerAdd';
  });

  /**
   * Tag management needs the session domain's user role, as the tag writes do (they answer 403
   * without it); other pickers only filter.
   */
  protected readonly canManage = computed(() => this.showManage() && this.auth.hasDomainUserRole());

  /** Membership edits hold off until the pre-check resolves. */
  protected readonly togglesPending = computed(
    () => this.mode() === 'add' && this.membershipPending(),
  );

  /**
   * Open anchored at `anchor` (a persistent element — the menu trigger or
   * field button, never a menu item, which is removed on menu close).
   * Add mode requires `target`; filter mode ignores it.
   */
  open(anchor: HTMLElement, target?: TagPickerTarget): void {
    this.targetIds = target?.messageIds ?? [];
    this.targetCount.set(this.targetIds.length);
    this.displayId.set(target?.displayId);
    this.membershipOnly.set(target?.membershipOnly === true);
    this.selectedScopes.set([]);
    this.search.set('');
    this.openTick.update((n) => n + 1);
    if (this.mode() === 'add') {
      this.loadMembership();
    }
    this.shell().open(anchor);
  }

  close(): void {
    this.shell().close();
  }

  /** Filter-chip trigger: a re-click while open closes it (else open fresh). */
  toggle(anchor: HTMLElement): void {
    if (this.shell().shown()) {
      this.close();
    } else {
      this.open(anchor);
    }
  }

  protected isChecked(tag: MessageTag): boolean {
    if (this.mode() === 'filter') {
      return this.selectedIds().includes(tag.tagId);
    }
    const total = this.targetCount();
    return total > 0 && (this.membership().get(tag.tagId) ?? 0) >= total;
  }

  protected isIndeterminate(tag: MessageTag): boolean {
    if (this.mode() !== 'add') return false;
    const count = this.membership().get(tag.tagId) ?? 0;
    return count > 0 && count < this.targetCount();
  }

  protected onToggle(tag: MessageTag): void {
    if (this.mode() === 'filter') {
      this.toggled.emit({ tag, selected: !this.selectedIds().includes(tag.tagId) });
      return;
    }
    if (this.togglesPending()) return;
    const wasFullyIn = this.isChecked(tag);
    // Optimistic flip — indeterminate promotes to "in all targets". Reverted
    // when the call fails (the actions service toasts and resolves undefined).
    // The revert is per-tag against the map AT REVERT TIME: toggles on other
    // tags may still be in flight, and restoring a whole-map snapshot would
    // wipe their optimistic state.
    const previousCount = this.membership().get(tag.tagId) ?? 0;
    this.setMembershipCount(tag.tagId, wasFullyIn ? 0 : this.targetCount());
    const operation = wasFullyIn
      ? this.tagActions.removeMessagesFromTag(this.targetIds, tag)
      : this.tagActions.addMessagesToTag(this.targetIds, tag);
    operation.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((result) => {
      if (!result) {
        this.setMembershipCount(tag.tagId, previousCount);
        return;
      }
      if (!wasFullyIn) {
        this.lastTagStore.saveLastSelection(tag);
      }
    });
  }

  protected openManager(): void {
    // Opening the manager while inside the details drawer is handled by the
    // details drawer itself (replace & restore — it can't stack two drawers).
    this.close();
    this.managerDialog.open({ includeLocked: true });
  }

  protected iconForType(type: MessageTagType): string {
    return TAG_TYPE_ICONS[type];
  }

  private setMembershipCount(tagId: string, count: number): void {
    const next = new Map(this.membership());
    next.set(tagId, count);
    this.membership.set(next);
  }

  private loadMembership(): void {
    const tick = this.openTick();
    this.membership.set(new Map());
    this.membershipTags.set([]);
    if (this.targetIds.length === 0) return;
    this.membershipPending.set(true);
    this.api
      .membershipForMessages(this.targetIds)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (this.openTick() !== tick) return;
          this.membership.set(result.counts);
          this.membershipTags.set(result.tags);
          this.membershipPending.set(false);
        },
        // Membership stays empty: every tag shows unchecked and toggling
        // still works (add is idempotent), which beats a dead picker.
        error: () => {
          if (this.openTick() !== tick) return;
          this.membershipPending.set(false);
        },
      });
  }
}
