import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { MessageTag, MessageTagType } from '../../../../shared/models/message-tag.model';
import type {
  MessageTagsDataSource,
  MessageTagsSearchParams,
} from '../../../../shared/forms/message-tags-field/message-tags-data.token';

export type TagSortBy = 'NAME' | 'TYPE' | 'CREATED' | 'EXPIRY_DATE' | 'MESSAGE_COUNT';
export type SortOrder = 'ASC' | 'DESC';

export interface TagSearchParams {
  readonly name?: string;
  readonly types?: readonly MessageTagType[];
  readonly sortBy?: TagSortBy;
  readonly sortOrder?: SortOrder;
  readonly locked?: boolean;
  readonly limit?: number;
}

export type MessageTagDraft = Pick<MessageTag, 'name' | 'type' | 'locked'> & {
  readonly expiryDate?: string;
};

/** Which (unlocked) tags a set of messages belongs to, and how completely. */
export interface TagMembership {
  /** tagId → number of the queried messages carrying the tag. */
  readonly counts: ReadonlyMap<string, number>;
  /** The distinct tags involved, name-sorted. */
  readonly tags: readonly MessageTag[];
}

/**
 * Build the `/tags/search` query params. Exported so the reactive Tag-manager dialog (which drives
 * its own `httpResource`) shares the exact param shape instead of re-deriving it — a new backend
 * filter added here then reaches both call sites.
 */
export function buildTagSearchParams(params: TagSearchParams): HttpParams {
  let httpParams = new HttpParams();
  if (params.name) httpParams = httpParams.set('name', params.name);
  if (params.sortBy) httpParams = httpParams.set('sortBy', params.sortBy);
  if (params.sortOrder) httpParams = httpParams.set('sortOrder', params.sortOrder);
  if (params.locked === false) httpParams = httpParams.set('locked', 'false');
  if (typeof params.limit === 'number') httpParams = httpParams.set('limit', String(params.limit));
  for (const type of params.types ?? []) {
    httpParams = httpParams.append('type', type);
  }
  return httpParams;
}

@Injectable({ providedIn: 'root' })
export class TagsApiService implements MessageTagsDataSource {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Generic search used by both the typeahead (single name + limit) and the dialog (full filter shape). */
  searchTags(params: TagSearchParams): Observable<readonly MessageTag[]> {
    return this.http.get<readonly MessageTag[]>(`${this.apiBaseUrl}/tags/search`, {
      params: buildTagSearchParams(params),
    });
  }

  getTagsByIds(ids: readonly string[]): Observable<readonly MessageTag[]> {
    if (ids.length === 0) {
      return of([]);
    }
    const csv = ids.join();
    return this.http.get<readonly MessageTag[]>(
      `${this.apiBaseUrl}/tags/tag/${encodeURIComponent(csv)}`,
    );
  }

  /** Lives here rather than in MessagesApiService — the endpoint is `/tags/...`. */
  tagsForMessage(
    messageId: string,
    options?: { includeLocked?: boolean },
  ): Observable<readonly MessageTag[]> {
    let params = new HttpParams();
    if (!options?.includeLocked) {
      params = params.set('locked', 'false');
    }
    return this.http.get<readonly MessageTag[]>(
      `${this.apiBaseUrl}/tags/message/${encodeURIComponent(messageId)}`,
      { params },
    );
  }

  /**
   * Tag membership of the given messages — the dataset behind the
   * membership-editor picker. The backend has no bulk endpoint, so this fans
   * out one tags-for-message request per id and folds the responses; swap the
   * body if such an endpoint ever ships. Locked tags are excluded
   * (membership editing never touches them).
   */
  membershipForMessages(messageIds: readonly string[]): Observable<TagMembership> {
    if (messageIds.length === 0) {
      return of<TagMembership>({ counts: new Map(), tags: [] });
    }
    return forkJoin(messageIds.map((id) => this.tagsForMessage(id))).pipe(
      map((perMessage) => {
        const counts = new Map<string, number>();
        const byId = new Map<string, MessageTag>();
        for (const tags of perMessage) {
          for (const tag of tags) {
            counts.set(tag.tagId, (counts.get(tag.tagId) ?? 0) + 1);
            byId.set(tag.tagId, tag);
          }
        }
        return {
          counts,
          tags: [...byId.values()].sort((a, b) => a.name.localeCompare(b.name)),
        };
      }),
    );
  }

  createMessageTag(tag: MessageTagDraft): Observable<MessageTag> {
    return this.http.post<MessageTag>(`${this.apiBaseUrl}/tags/tag/`, tag);
  }

  updateMessageTag(tag: MessageTagDraft & { tagId: string }): Observable<MessageTag> {
    return this.http.put<MessageTag>(
      `${this.apiBaseUrl}/tags/tag/${encodeURIComponent(tag.tagId)}`,
      tag,
    );
  }

  deleteMessageTag(tagId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiBaseUrl}/tags/tag/${encodeURIComponent(tagId)}`);
  }

  clearMessageTagMessages(tagId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiBaseUrl}/tags/tag/${encodeURIComponent(tagId)}/messages`,
    );
  }

  unlockMessageTag(tagId: string): Observable<MessageTag> {
    return this.http.put<MessageTag>(
      `${this.apiBaseUrl}/tags/unlock-tag/${encodeURIComponent(tagId)}`,
      null,
    );
  }

  addMessagesToTag(tagId: string, messageIds: readonly string[]): Observable<void> {
    return this.http.put<void>(
      `${this.apiBaseUrl}/tags/tag/${encodeURIComponent(tagId)}/add-messages`,
      [...messageIds],
    );
  }

  removeMessagesFromTag(tagId: string, messageIds: readonly string[]): Observable<void> {
    return this.http.put<void>(
      `${this.apiBaseUrl}/tags/tag/${encodeURIComponent(tagId)}/remove-messages`,
      [...messageIds],
    );
  }

  /**
   * Creates a server-side temporary tag bundling the given messages. Used by
   * the print-selection and mail flows as a stable handle for the selection.
   * `ttl` is optional — backend default applies when omitted.
   */
  createTempMessageTag(messageIds: readonly string[], ttl?: number): Observable<MessageTag> {
    let params = new HttpParams();
    if (typeof ttl === 'number') {
      params = params.set('ttl', String(ttl));
    }
    return this.http.post<MessageTag>(`${this.apiBaseUrl}/tags/temp-tag/`, [...messageIds], {
      params,
    });
  }

  /** MessageTagsDataSource adapter — the shared field consumes these. */
  search(query: string): Observable<readonly MessageTag[]> {
    if (!query || query.length === 0) return of([]);
    return this.searchTags({ name: query, limit: 10 });
  }

  /** Scope-aware search for the single-tag picker — empty `name` returns all tags. */
  searchScoped(params: MessageTagsSearchParams): Observable<readonly MessageTag[]> {
    return this.searchTags({
      name: params.name || undefined,
      types: params.types && params.types.length > 0 ? params.types : undefined,
      sortBy: 'NAME',
      sortOrder: 'ASC',
    });
  }

  resolveByIds(ids: readonly string[]): Observable<readonly MessageTag[]> {
    return this.getTagsByIds(ids);
  }
}
