import { Provider, inject } from '@angular/core';

import {
  MESSAGE_TAGS_DATA_SOURCE,
  type MessageTagsSearchParams,
} from '../../../../shared/forms/message-tags-field/message-tags-data.token';
import { TagsApiService } from './tags-api.service';

/**
 * Backs the shared `MESSAGE_TAGS_DATA_SOURCE` token with the real tags API. Provide
 * this on any component/route that mounts a tag field (`app-single-tag-field` or the
 * tags picker) so it can search + resolve tags against the backend.
 */
export function provideMessageTagsDataSource(): Provider {
  return {
    provide: MESSAGE_TAGS_DATA_SOURCE,
    useFactory: () => {
      const api = inject(TagsApiService);
      return {
        search: (query: string) => api.searchTags({ name: query, locked: false, limit: 10 }),
        searchScoped: (params: MessageTagsSearchParams) =>
          api.searchTags({
            name: params.name || undefined,
            types: params.types && params.types.length > 0 ? params.types : undefined,
            sortBy: 'NAME',
            sortOrder: 'ASC',
            locked: false,
          }),
        resolveByIds: (ids: readonly string[]) => api.getTagsByIds(ids),
      };
    },
  };
}
