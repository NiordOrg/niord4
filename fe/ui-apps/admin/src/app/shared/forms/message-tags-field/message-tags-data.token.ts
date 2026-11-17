import { InjectionToken } from '@angular/core';
import { Observable, of } from 'rxjs';

import type { MessageTag, MessageTagType } from '../../models/message-tag.model';

/** Scope-aware tag search params (empty `name` returns ALL matching tags). */
export interface MessageTagsSearchParams {
  readonly name?: string;
  readonly types?: readonly MessageTagType[];
}

export interface MessageTagsDataSource {
  /** Typeahead lookup — an empty query yields no results (used by the multiselect field). */
  search(query: string): Observable<readonly MessageTag[]>;
  /**
   * Full scope-aware search backing the single-tag picker panel: an empty
   * `name` returns ALL tags (so the popover shows the catalog on open), and
   * `types` narrows by scope (private/domain/public).
   */
  searchScoped(params: MessageTagsSearchParams): Observable<readonly MessageTag[]>;
  resolveByIds(ids: readonly string[]): Observable<readonly MessageTag[]>;
}

/**
 * Default factory: returns empty arrays for the lookup methods. The messages
 * feature route binds the real `TagsApiService` (which talks to the backend).
 * The fallback exists so the field component never fails to inject in test
 * fixtures that don't override the binding.
 */
export const MESSAGE_TAGS_DATA_SOURCE = new InjectionToken<MessageTagsDataSource>(
  'MESSAGE_TAGS_DATA_SOURCE',
  {
    providedIn: 'root',
    factory: () => ({
      search: () => of([]),
      searchScoped: () => of([]),
      resolveByIds: () => of([]),
    }),
  },
);
