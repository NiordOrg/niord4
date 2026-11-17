import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { TagsApiService } from './tags-api.service';
import type { MessageTag } from '../../../../shared/models/message-tag.model';

const API_BASE = '/rest';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(TagsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('TagsApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  describe('searchTags', () => {
    it('GETs /rest/tags/search with name + limit', () => {
      s.api.searchTags({ name: 'foo', limit: 10 }).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(req.request.params.get('name')).toBe('foo');
      expect(req.request.params.get('limit')).toBe('10');
      req.flush([]);
    });

    it('appends repeated type params for each provided type', () => {
      s.api.searchTags({ types: ['PRIVATE', 'PUBLIC'] }).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(req.request.params.getAll('type')).toEqual(['PRIVATE', 'PUBLIC']);
      req.flush([]);
    });

    it('emits locked=false ONLY when explicitly opted out', () => {
      s.api.searchTags({ locked: false }).subscribe();
      const r1 = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(r1.request.params.get('locked')).toBe('false');
      r1.flush([]);
      s.api.searchTags({}).subscribe();
      const r2 = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(r2.request.params.get('locked')).toBeNull();
      r2.flush([]);
    });

    it('forwards sortBy and sortOrder', () => {
      s.api.searchTags({ sortBy: 'MESSAGE_COUNT', sortOrder: 'DESC' }).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(req.request.params.get('sortBy')).toBe('MESSAGE_COUNT');
      expect(req.request.params.get('sortOrder')).toBe('DESC');
      req.flush([]);
    });
  });

  describe('getTagsByIds', () => {
    it('GETs /rest/tags/tag/<csv> with comma-joined IDs', () => {
      s.api.getTagsByIds(['a', 'b', 'c']).subscribe();
      const req = s.httpTesting.expectOne(`/rest/tags/tag/${encodeURIComponent('a,b,c')}`);
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });

    it('short-circuits on empty id list (no HTTP request)', () => {
      s.api.getTagsByIds([]).subscribe();
      s.httpTesting.expectNone(() => true);
    });
  });

  describe('CRUD', () => {
    const tagDraft = { name: 'X', type: 'PRIVATE' as const, locked: false };
    const serverTag: MessageTag = {
      tagId: 'id-1',
      name: 'X',
      type: 'PRIVATE',
      messageCount: 0,
      locked: false,
    };

    it('createMessageTag POSTs /rest/tags/tag/ with the draft body', () => {
      s.api.createMessageTag(tagDraft).subscribe();
      const req = s.httpTesting.expectOne('/rest/tags/tag/');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(tagDraft);
      req.flush(serverTag);
    });

    it('updateMessageTag PUTs /rest/tags/tag/<id> with the body', () => {
      s.api.updateMessageTag(serverTag).subscribe();
      const req = s.httpTesting.expectOne(`/rest/tags/tag/${encodeURIComponent('id-1')}`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(serverTag);
      req.flush(serverTag);
    });

    it('deleteMessageTag DELETEs /rest/tags/tag/<id>', () => {
      s.api.deleteMessageTag('id-1').subscribe();
      const req = s.httpTesting.expectOne(`/rest/tags/tag/${encodeURIComponent('id-1')}`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('clearMessageTagMessages DELETEs /rest/tags/tag/<id>/messages', () => {
      s.api.clearMessageTagMessages('id-1').subscribe();
      const req = s.httpTesting.expectOne(`/rest/tags/tag/${encodeURIComponent('id-1')}/messages`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('unlockMessageTag PUTs /rest/tags/unlock-tag/<id>', () => {
      s.api.unlockMessageTag('id-1').subscribe();
      const req = s.httpTesting.expectOne(`/rest/tags/unlock-tag/${encodeURIComponent('id-1')}`);
      expect(req.request.method).toBe('PUT');
      req.flush(serverTag);
    });
  });

  describe('tagsForMessage', () => {
    it('GETs /rest/tags/message/<id>?locked=false by default', () => {
      s.api.tagsForMessage('m1').subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === `/rest/tags/message/m1`);
      expect(req.request.params.get('locked')).toBe('false');
      req.flush([]);
    });

    it('omits locked param when includeLocked=true', () => {
      s.api.tagsForMessage('m1', { includeLocked: true }).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === `/rest/tags/message/m1`);
      expect(req.request.params.get('locked')).toBeNull();
      req.flush([]);
    });
  });

  describe('createTempMessageTag', () => {
    it('POSTs /rest/tags/temp-tag/ with messageIds body and no ttl param by default', () => {
      s.api.createTempMessageTag(['m1', 'm2']).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/temp-tag/');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(['m1', 'm2']);
      expect(req.request.params.get('ttl')).toBeNull();
      req.flush({});
    });

    it('appends ttl when provided', () => {
      s.api.createTempMessageTag(['m1'], 30).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/temp-tag/');
      expect(req.request.params.get('ttl')).toBe('30');
      req.flush({});
    });
  });

  describe('addMessagesToTag / removeMessagesFromTag', () => {
    it('addMessagesToTag PUTs /rest/tags/tag/<id>/add-messages with body', () => {
      s.api.addMessagesToTag('t1', ['m1', 'm2']).subscribe();
      const req = s.httpTesting.expectOne(
        `/rest/tags/tag/${encodeURIComponent('t1')}/add-messages`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(['m1', 'm2']);
      req.flush(null);
    });

    it('removeMessagesFromTag PUTs /rest/tags/tag/<id>/remove-messages with body', () => {
      s.api.removeMessagesFromTag('t1', ['m1']).subscribe();
      const req = s.httpTesting.expectOne(
        `/rest/tags/tag/${encodeURIComponent('t1')}/remove-messages`,
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(['m1']);
      req.flush(null);
    });
  });

  describe('membershipForMessages', () => {
    const tag = (tagId: string, name: string): MessageTag => ({
      tagId,
      name,
      type: 'DOMAIN',
      messageCount: 0,
      locked: false,
    });

    it('fans out one locked=false request per message and folds counts + name-sorted tags', () => {
      let result: { counts: ReadonlyMap<string, number>; tags: readonly MessageTag[] } | undefined;
      s.api.membershipForMessages(['m1', 'm2']).subscribe((r) => (result = r));

      const r1 = s.httpTesting.expectOne((r) => r.url === '/rest/tags/message/m1');
      const r2 = s.httpTesting.expectOne((r) => r.url === '/rest/tags/message/m2');
      expect(r1.request.params.get('locked')).toBe('false');
      expect(r2.request.params.get('locked')).toBe('false');
      r1.flush([tag('b', 'Beta'), tag('a', 'Alpha')]);
      r2.flush([tag('a', 'Alpha')]);

      expect(result?.counts.get('a')).toBe(2);
      expect(result?.counts.get('b')).toBe(1);
      expect(result?.tags.map((t) => t.name)).toEqual(['Alpha', 'Beta']);
    });

    it('resolves empty membership for an empty id list without any request', () => {
      let result: { counts: ReadonlyMap<string, number>; tags: readonly MessageTag[] } | undefined;
      s.api.membershipForMessages([]).subscribe((r) => (result = r));
      s.httpTesting.expectNone(() => true);
      expect(result?.counts.size).toBe(0);
      expect(result?.tags).toEqual([]);
    });
  });

  describe('MessageTagsDataSource adapter', () => {
    it('search() short-circuits on empty query', () => {
      s.api.search('').subscribe();
      s.httpTesting.expectNone(() => true);
    });

    it('search() delegates to searchTags with limit 10', () => {
      s.api.search('foo').subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === '/rest/tags/search');
      expect(req.request.params.get('name')).toBe('foo');
      expect(req.request.params.get('limit')).toBe('10');
      req.flush([]);
    });
  });
});
