import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
import { of, switchMap, timer } from 'rxjs';

import { Icon } from '../../icons/icon';
import { PickerPopover } from '../../components/picker-popover/picker-popover';
import { TagScopeSelect } from '../../components/tag-scope-select/tag-scope-select';
import { TAG_TYPE_ICONS } from '../../models/tag-type-icons';
import type { MessageTag, MessageTagType } from '../../models/message-tag.model';
import { latchWhileSettled } from '../../utils/latch-while-settled';
import { MESSAGE_TAGS_DATA_SOURCE } from '../message-tags-field/message-tags-data.token';

/** Stable empty rows for the latch's not-yet-loaded state (avoids a fresh array per read). */
const EMPTY_TAGS: readonly MessageTag[] = [];

/**
 * Single-value message-tag picker exposed as a string `tagId` CVA. Reuses the
 * same anchored popover recipe as the messages tag picker — scope chips, live
 * search (empty query lists the whole catalog), and rows showing the scope icon,
 * name, lock and message count — but single-select (clicking a row picks it and
 * closes) and without the tags-manager link. Tags are resolved through the
 * `MESSAGE_TAGS_DATA_SOURCE` token, which the consumer binds (typically
 * excluding locked tags).
 */
@Component({
  selector: 'app-single-tag-field',
  imports: [PickerPopover, TagScopeSelect, Icon, DecimalPipe, TranslocoPipe],
  templateUrl: './single-tag-field.html',
  host: { class: 'block' },
})
export class SingleTagField implements FormValueControl<string | null> {
  private readonly dataSource = inject(MESSAGE_TAGS_DATA_SOURCE);

  /** Pre-translated placeholder + accessible name (the consumer translates). */
  readonly placeholder = input('');
  readonly ariaLabel = input('');

  private readonly picker = viewChild.required(PickerPopover);

  /** Multi-select scope filter; empty = all scopes (mirrors the tag picker). */
  protected readonly selectedScopes = signal<readonly MessageTagType[]>([]);
  protected readonly search = signal('');
  /** 0 = never opened; each open bumps it so the catalog refetches per open. */
  private readonly openTick = signal(0);

  /** The resolved tag shown as the chip (display); the `value` model holds its id. */
  protected readonly selected = signal<MessageTag | null>(null);
  /** The selected `tagId` — two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string | null>(null);
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  protected readonly tagsResource = rxResource<
    readonly MessageTag[],
    { types: readonly MessageTagType[]; search: string; tick: number } | undefined
  >({
    params: () =>
      this.openTick() === 0
        ? undefined
        : { types: this.selectedScopes(), search: this.search().trim(), tick: this.openTick() },
    // The timer debounces typing: a param change tears the previous stream down,
    // so only the settled search value reaches the API. Empty name = all tags.
    stream: ({ params }) =>
      params === undefined
        ? of<readonly MessageTag[]>([])
        : timer(params.search ? 250 : 0).pipe(
            switchMap(() =>
              this.dataSource.searchScoped({
                name: params.search || undefined,
                types: params.types,
              }),
            ),
          ),
    defaultValue: [],
  });

  /**
   * The last successfully-resolved list so the rows never flash empty during a scope/search
   * refetch — only a settled (loaded, no error, has value) fetch replaces it (shared latch,
   * same as the tag picker).
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
  protected readonly showSpinner = computed(
    () => this.tagsResource.isLoading() && this.tags().length === 0,
  );

  constructor() {
    // Resolve an externally-written `tagId` (form/URL) to the display tag. The guard
    // skips the echo from choose()/clear(), which set both `value` and `selected`.
    effect(() => {
      const tagId = this.value();
      untracked(() => {
        if ((this.selected()?.tagId ?? null) === tagId) {
          return;
        }
        if (tagId) {
          this.dataSource
            .resolveByIds([tagId])
            .subscribe((tags) => this.selected.set(tags[0] ?? null));
        } else {
          this.selected.set(null);
        }
      });
    });
  }

  protected openPicker(anchor: HTMLElement): void {
    if (this.picker().shown()) {
      this.picker().close();
      return;
    }
    this.selectedScopes.set([]);
    this.search.set('');
    this.openTick.update((n) => n + 1);
    this.picker().open(anchor);
  }

  protected close(): void {
    this.picker().close();
  }

  protected isSelected(tag: MessageTag): boolean {
    return this.selected()?.tagId === tag.tagId;
  }

  protected iconForType(type: MessageTagType): string {
    return TAG_TYPE_ICONS[type];
  }

  protected choose(tag: MessageTag): void {
    this.selected.set(tag);
    this.value.set(tag.tagId);
    this.touch.emit();
    this.picker().close();
  }

  protected clear(): void {
    this.selected.set(null);
    this.value.set(null);
    this.touch.emit();
  }
}
