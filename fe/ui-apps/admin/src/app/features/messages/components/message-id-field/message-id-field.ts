import { Component, computed, effect, inject, input, model, output, signal } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { Observable } from 'rxjs';

import { Icon } from '../../../../shared/icons/icon';
import { Autocomplete } from '../../../../shared/forms/autocomplete/autocomplete';
import { AutocompleteSearchField } from '../../../../shared/forms/autocomplete-search-field';
import {
  KNOWN_MATCH_TYPES,
  MATCH_TYPE_LABEL_KEYS,
  MessageIdMatchType,
  MessageIdSearchResult,
} from '../../models/message-id-search-result.model';
import { KNOWN_MESSAGE_STATUSES, MessageStatus } from '../../models/message.model';
import { MessagesApiService } from '../../services/api/messages-api.service';
import { messageStatusToStateClass } from '../../../../shared/pipes/status-class.pipe';

@Component({
  selector: 'app-message-id-field',
  templateUrl: './message-id-field.html',
  imports: [Autocomplete, Icon, TranslocoPipe],
})
export class MessageIdField
  extends AutocompleteSearchField<MessageIdSearchResult>
  implements FormValueControl<string | null>
{
  private readonly messagesApi = inject(MessagesApiService);
  private readonly transloco = inject(TranslocoService);

  // NB: not `minLength` — that name is a reserved Signal Forms constraint on FormValueControl.
  readonly minSearchLength = input(3);
  readonly includeDeleted = input(false);
  readonly allowFreeText = input(false);
  readonly placeholder = input('');

  readonly selectedResultChange = output<MessageIdSearchResult | null>();

  /** The selected message id (or free text) — two-way bound by `[formField]`/`[value]`. */
  readonly value = model<string | null>(null);
  // Status of the message the user selected. Drives the chip's color via
  // `.is-{status}` so the reference renders in the same hue as its status
  // badge elsewhere. Null when no message is selected, when the value came
  // from free-text input, or when the value was set programmatically (e.g.
  // URL prefill) without status info — in that case the chip falls back to
  // the neutral surface tint defined on `.status-chip`.
  readonly selectedStatus = signal<MessageStatus | null>(null);
  readonly statusStateClass = computed(() => messageStatusToStateClass(this.selectedStatus()));
  // True when the field should render its selected value as a chip rather
  // than keep the autocomplete input visible. Suppressed when allowFreeText
  // is enabled — free-text consumers (search-style fields) want the typed
  // value to remain editable in the input.
  readonly showAsChip = computed(() => !!this.value() && !this.allowFreeText());

  constructor() {
    super();
    // A programmatic clear (form/URL write of null) drops the status chip's colour.
    effect(() => {
      if (this.value() === null) {
        this.selectedStatus.set(null);
      }
    });
  }

  /** Only search once the query reaches the minimum length. */
  protected override shouldSearch(query: string): boolean {
    return query.length >= this.minSearchLength();
  }

  protected searchApi(query: string): Observable<readonly MessageIdSearchResult[]> {
    return this.messagesApi.searchMessageIds(query, { includeDeleted: this.includeDeleted() });
  }

  matchTypeLabel(type: MessageIdMatchType | string | undefined): string {
    if (!type) return '';
    if (KNOWN_MATCH_TYPES.has(type as MessageIdMatchType)) {
      return this.transloco.translate(MATCH_TYPE_LABEL_KEYS[type as MessageIdMatchType]);
    }
    return type;
  }

  statusLabel(status: MessageStatus | string | undefined): string {
    if (!status) return '';
    if (KNOWN_MESSAGE_STATUSES.has(status as MessageStatus)) {
      return this.transloco.translate(`msg.status.${status}`);
    }
    return status;
  }

  /** Chip label accessor for `<app-autocomplete>` rows. */
  readonly messageIdLabel = (result: MessageIdSearchResult): string => result.messageId;

  /**
   * Primitive `(query)`: feed the debounced search and, when free-text is
   * allowed, adopt the typed text as the value (the old `onKeyUp` free-text path).
   */
  onType(query: string): void {
    this.onSearch(query);
    if (this.allowFreeText()) {
      this.onInput(query);
    }
  }

  /** Primitive `(select)`: adopt the picked suggestion. */
  onPanelSelect(results: readonly MessageIdSearchResult[]): void {
    const result = results[0];
    if (result) this.onSelect(result);
  }

  onSelect(result: MessageIdSearchResult): void {
    this.value.set(result.messageId);
    this.selectedStatus.set(this.toKnownStatus(result.status));
    this.touch.emit();
    this.selectedResultChange.emit(result);
  }

  onInput(value: string | null): void {
    if (this.allowFreeText()) {
      const next = value || null;
      this.value.set(next);
      this.selectedStatus.set(null);
      this.selectedResultChange.emit(null);
    }
  }

  onClear(): void {
    this.value.set(null);
    this.selectedStatus.set(null);
    this.touch.emit();
    this.selectedResultChange.emit(null);
  }

  private toKnownStatus(raw: string | undefined): MessageStatus | null {
    return raw && KNOWN_MESSAGE_STATUSES.has(raw as MessageStatus) ? (raw as MessageStatus) : null;
  }
}
