import { Component, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { disabled, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { debounceTime } from 'rxjs';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../shared/api/problem-detail';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { Drawer } from '../../../../shared/components/drawer/drawer';
import { Icon } from '../../../../shared/icons/icon';
import { DatePicker } from '../../../../shared/forms/date-picker/date-picker';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import type { MessageTag, MessageTagType } from '../../../../shared/models/message-tag.model';
import { TagScopeSelect } from '../../../../shared/components/tag-scope-select/tag-scope-select';
import { TAG_TYPE_ICONS } from '../../../../shared/models/tag-type-icons';
import { latchWhileSettled } from '../../../../shared/utils/latch-while-settled';
import { MessageDateFormatterService } from '../../services/message-date-formatter.service';
import {
  buildTagSearchParams,
  SortOrder,
  TagSearchParams,
  TagSortBy,
  TagsApiService,
} from '../../services/api/tags-api.service';
import { LastTagSelectionService } from '../../services/last-tag-selection.service';
import { MessageTagsDialogService } from './message-tags-dialog.service';

/** Sentinel row id for the inline "new tag" draft prepended to the table. */
const NEW_ROW_ID = '__new__';

/** Stable empty rows for the latch's not-yet-loaded state (avoids a fresh array per read). */
const EMPTY_TAGS: readonly MessageTag[] = [];

interface TagEditFormModel {
  name: string;
  type: MessageTagType;
  expiryDate: Date | null;
  locked: boolean;
}

/** Which row is being edited: a brand-new/duplicated draft, or an existing tag in place. */
interface DraftState {
  readonly mode: 'new' | 'edit';
  readonly tagId: string | null;
}

/**
 * Tag Manager — a right drawer (reached from the toolbar Action menu →
 * "Manage tags…"). CRUD only: tag PICKING happens in the tag-picker popover.
 * Editing is inline in the table (New = blank top row, Edit = in place,
 * Duplicate = prefilled new row with a blank id). An open draft marks the
 * drawer dirty so closing/navigating away is change-gated.
 */
@Component({
  selector: 'app-message-tags-dialog',
  templateUrl: './message-tags-dialog.html',
  providers: [ConfirmService],
  imports: [
    Confirm,
    DatePicker,
    Drawer,
    FormField,
    HelpAnchor,
    Icon,
    TagScopeSelect,
    TranslocoPipe,
  ],
})
export class MessageTagsDialog {
  protected readonly dialog = inject(MessageTagsDialogService);
  private readonly api = inject(TagsApiService);
  private readonly auth = inject(AuthFacade);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly lastTag = inject(LastTagSelectionService);
  private readonly dateFormatter = inject(MessageDateFormatterService);

  protected readonly newRowId = NEW_ROW_ID;

  protected formatDate(iso: string | undefined): string {
    return this.dateFormatter.formatDateOnly(iso);
  }

  protected readonly filterName = signal<string>('');
  /**
   * Debounced mirror of `filterName` — drives `searchParams` so typing into the
   * filter input doesn't fire an HTTP request on every keystroke. The input
   * itself binds to `filterName()` for immediate visual feedback.
   */
  private readonly debouncedFilterName = toSignal(
    toObservable(this.filterName).pipe(debounceTime(500)),
    { initialValue: '' },
  );
  protected readonly filterTypes = signal<readonly MessageTagType[]>([]);
  protected readonly sortBy = signal<TagSortBy>('NAME');
  protected readonly sortOrder = signal<SortOrder>('ASC');

  protected readonly isLoggedIn = this.auth.isAuthenticated;

  // Logged-in users can do everything to a tag EXCEPT remove its lock —
  // unlocking is reserved for admin/sysadmin.
  protected readonly canUnlock = computed(
    () => this.auth.hasRole('admin')() || this.auth.hasRole('sysadmin')(),
  );

  /** The stored type of the tag being edited in place (read only while `draft().mode` is 'edit'). */
  private readonly storedType = signal<MessageTagType | null>(null);

  /**
   * Whether the inline editor may change the tag's type. A new tag (Duplicate included) takes any
   * type. An existing tag's type is changed only by an admin or sysadmin, or by the owner of a
   * private (or temporary) tag — the backend lists those to their owner only, so whoever edits one
   * owns it. A shared (domain or public) tag keeps its type for everyone else: the backend answers
   * 403 to such a change.
   */
  protected readonly canChangeType = computed(() => {
    if (this.draft()?.mode !== 'edit') return true;
    if (this.canUnlock()) return true; // the same admin/sysadmin roles

    const stored = this.storedType();
    return stored !== 'DOMAIN' && stored !== 'PUBLIC';
  });

  // Inline-editor scope options (all three are offered when editing a tag,
  // unlike the filter chips where PRIVATE is login-gated; `canChangeType`
  // decides whether they can be picked). `translateSignal`
  // keeps the labels reactive to language changes.
  private readonly privateLabel = translateSignal('messages.tags.dialog.filter.type.private');
  private readonly domainLabel = translateSignal('messages.tags.dialog.filter.type.domain');
  private readonly publicLabel = translateSignal('messages.tags.dialog.filter.type.public');
  protected readonly typeOptions = computed(() => [
    { value: 'PRIVATE' as const, label: this.privateLabel(), icon: TAG_TYPE_ICONS.PRIVATE },
    { value: 'DOMAIN' as const, label: this.domainLabel(), icon: TAG_TYPE_ICONS.DOMAIN },
    { value: 'PUBLIC' as const, label: this.publicLabel(), icon: TAG_TYPE_ICONS.PUBLIC },
  ]);

  private readonly searchParams = computed<TagSearchParams>(() => ({
    name: this.debouncedFilterName().trim() || undefined,
    types: this.filterTypes().length > 0 ? this.filterTypes() : undefined,
    sortBy: this.sortBy(),
    sortOrder: this.sortOrder(),
    locked: this.dialog.includeLocked() ? undefined : false,
  }));

  // Shares TagsApiService's param builder — that service method stays for the typeahead/data source,
  // but the dialog owns its reactive search as an httpResource. Idle (no fetch) while hidden.
  protected readonly tagsResource = httpResource<readonly MessageTag[]>(
    () =>
      this.dialog.visible()
        ? {
            url: `${this.apiBaseUrl}/tags/search`,
            params: buildTagSearchParams(this.searchParams()),
          }
        : undefined,
    { defaultValue: [] },
  );

  /**
   * The last successfully-resolved tag list — the table reads from this instead of
   * `tagsResource.value()` so it never flashes empty during a filter/sort refetch, and
   * keeps the previous rows on error. Only a settled (loaded, no error, has value) fetch
   * replaces it; a load or error keeps the last good rows.
   */
  protected readonly tags = latchWhileSettled<readonly MessageTag[]>(
    computed(
      () =>
        !this.tagsResource.isLoading() &&
        this.tagsResource.error() === undefined &&
        this.tagsResource.hasValue(),
    ),
    computed(() => (this.tagsResource.hasValue() ? this.tagsResource.value() : EMPTY_TAGS)),
  );

  /** The inline editing state (null = nothing being edited). */
  protected readonly draft = signal<DraftState | null>(null);
  /** An open draft change-gates the drawer (close / refresh / navigate prompt). */
  protected readonly dirty = computed(() => this.draft() !== null);

  /**
   * Table rows: the loaded tags, with the inline "new tag" draft prepended as a
   * sentinel row when a new/duplicate edit is open.
   */
  protected readonly tableValue = computed<MessageTag[]>(() => {
    const rows = this.tags();
    const draft = this.draft();
    if (draft?.mode === 'new') {
      const placeholder: MessageTag = {
        tagId: NEW_ROW_ID,
        name: '',
        type: 'PRIVATE',
        messageCount: 0,
        locked: false,
      };
      return [placeholder, ...rows];
    }
    return [...rows];
  });

  private readonly formModel = signal<TagEditFormModel>({
    name: '',
    type: 'PRIVATE',
    expiryDate: null,
    locked: false,
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.name);
    // Name is the tag's identity — immutable on edit.
    disabled(path.name, { when: () => this.draft()?.mode === 'edit' });
  });

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so a serialized baseline comparison
   * gates Save instead.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.formModel().name.trim().length === 0,
  );

  /** The inline editor's `type` value — drives the segmented control's active state. */
  protected readonly editingType = computed<MessageTagType>(() => this.formModel().type);

  constructor() {
    // Auto-error toast on search failure.
    effect(() => {
      if (this.tagsResource.error() !== undefined) {
        this.notifications.error('messages.tags.dialog.error');
      }
    });
  }

  /** Drawer close (after its own dirty-gate): drop any draft and dismiss. */
  protected onClose(): void {
    this.draft.set(null);
    this.dialog.dismiss();
  }

  protected onTypeChipsChange(value: readonly MessageTagType[] | null): void {
    this.filterTypes.set(value ?? []);
  }

  protected onFilterNameChange(value: string): void {
    this.filterName.set(value);
  }

  protected toggleSort(field: TagSortBy): void {
    // Frozen during an inline edit (see the filter row) so the result set —
    // and the open editor row — can't shift underneath the user.
    if (this.dirty()) return;
    if (this.sortBy() === field) {
      this.sortOrder.update((o) => (o === 'ASC' ? 'DESC' : 'ASC'));
    } else {
      this.sortBy.set(field);
      this.sortOrder.set('ASC');
    }
  }

  protected sortIndicator(field: TagSortBy): 'asc' | 'desc' | null {
    if (this.sortBy() !== field) return null;
    return this.sortOrder() === 'ASC' ? 'asc' : 'desc';
  }

  /** True for the sentinel new-row or the existing row currently being edited. */
  protected isEditingRow(tag: MessageTag): boolean {
    const draft = this.draft();
    if (!draft) return false;
    if (tag.tagId === NEW_ROW_ID) return draft.mode === 'new';
    return draft.mode === 'edit' && draft.tagId === tag.tagId;
  }

  /** Seed the inline editor with `next` and snapshot it as the change baseline. */
  private seedForm(next: TagEditFormModel): void {
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
  }

  protected addTag(): void {
    this.seedForm({ name: '', type: 'PRIVATE', expiryDate: null, locked: false });
    this.draft.set({ mode: 'new', tagId: null });
  }

  protected editTag(tag: MessageTag): void {
    this.seedForm({
      name: tag.name,
      type: tag.type,
      expiryDate: tag.expiryDate ? new Date(tag.expiryDate) : null,
      locked: tag.locked,
    });
    this.storedType.set(tag.type);
    // The disabled(name) constraint keys off draft().mode === 'edit'.
    this.draft.set({ mode: 'edit', tagId: tag.tagId });
  }

  protected copyTag(tag: MessageTag): void {
    // Duplicate = a NEW tag (blank id) prefilled from the source, lock cleared.
    this.seedForm({
      name: tag.name,
      type: tag.type,
      expiryDate: tag.expiryDate ? new Date(tag.expiryDate) : null,
      locked: false,
    });
    this.draft.set({ mode: 'new', tagId: null });
  }

  protected cancelEdit(): void {
    this.draft.set(null);
  }

  /** Segmented-control click: set the editor's tag type (change tracking picks it up). */
  protected setTagType(type: MessageTagType): void {
    if (!this.canChangeType()) return;
    this.formModel.update((m) => ({ ...m, type }));
  }

  protected saveTag(): void {
    if (this.saveDisabled()) return;
    const draft = this.draft();
    if (!draft) return;
    const value = this.formModel();
    const payload = {
      name: value.name,
      type: value.type,
      locked: value.locked,
      expiryDate: value.expiryDate?.toISOString(),
    };
    const op$ =
      draft.mode === 'edit' && draft.tagId
        ? this.api.updateMessageTag({ ...payload, tagId: draft.tagId })
        : this.api.createMessageTag(payload);
    op$.subscribe({
      next: () => {
        this.draft.set(null);
        this.tagsResource.reload();
      },
      // A refusal (a 4xx with a problem detail, e.g. a type change the backend does not allow)
      // names its reason; the row stays open so the user can put it right or cancel.
      error: (err: unknown) => {
        const detail =
          err instanceof HttpErrorResponse && err.status >= 400 && err.status < 500
            ? problemDetail(err)
            : undefined;
        if (detail === undefined) {
          this.notifications.error('messages.tags.dialog.error');
        } else {
          this.notifications.error('messages.tags.dialog.refused', { detail });
        }
      },
    });
  }

  protected deleteTag(tag: MessageTag): void {
    this.confirm.confirmDestructive({
      message: this.transloco.translate('messages.tags.dialog.deleteConfirm.message', {
        name: tag.name,
      }),
      accept: () => {
        this.api.deleteMessageTag(tag.tagId).subscribe({
          next: () => {
            if (this.lastTag.getLastSelection()()?.tagId === tag.tagId) {
              this.lastTag.clearLastSelection();
            }
            this.tagsResource.reload();
          },
          error: () => this.notifications.error('messages.tags.dialog.error'),
        });
      },
    });
  }

  protected clearTagMessages(tag: MessageTag): void {
    this.api.clearMessageTagMessages(tag.tagId).subscribe({
      next: () => this.tagsResource.reload(),
      error: () => this.notifications.error('messages.tags.dialog.error'),
    });
  }

  protected unlockTag(tag: MessageTag): void {
    this.confirm.confirmNeutral({
      message: this.transloco.translate('messages.tags.dialog.unlockConfirm.message', {
        name: tag.name,
      }),
      acceptLabel: this.transloco.translate('messages.tags.dialog.unlockConfirm.accept'),
      rejectLabel: this.transloco.translate('messages.tags.dialog.unlockConfirm.reject'),
      accept: () => {
        this.api.unlockMessageTag(tag.tagId).subscribe({
          next: () => this.tagsResource.reload(),
          error: () => this.notifications.error('messages.tags.dialog.error'),
        });
      },
    });
  }

  protected iconForType(type: MessageTagType): string {
    return TAG_TYPE_ICONS[type];
  }

  /** The type in words, as the type buttons name it; a type this build does not list reads as stored. */
  protected typeLabel(type: MessageTagType | string): string {
    return this.typeOptions().find((option) => option.value === type)?.label ?? type;
  }
}
