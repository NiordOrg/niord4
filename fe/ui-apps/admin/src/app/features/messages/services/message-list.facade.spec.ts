import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { MessagesApiService } from './api/messages-api.service';
import { MessageListFacade } from './message-list.facade';
import { MessageSelectionService } from './message-selection.service';
import { MessagesFilterFacade } from './messages-filter.facade';
import { MessagesShellFacade } from './messages-shell.facade';
import type { Message, MessageArea } from '../models/message.model';
import type { MessageSearchResult } from '../models/message-search-result.model';
import type { SortBy } from '../models/messages-filter-params.model';

function makeMessages(ids: string[]): Message[] {
  return ids.map((id) => ({ id }) as Message);
}

interface FakeShell {
  requiresSearch: ReturnType<typeof signal<boolean>>;
  isMap: ReturnType<typeof signal<boolean>>;
  maxPageSize: ReturnType<typeof signal<number>>;
}

interface FakeFilter {
  requestParams: ReturnType<typeof signal<string>>;
  sortBy: ReturnType<typeof signal<SortBy>>;
}

interface FakeApi {
  searchMessages: ReturnType<typeof vi.fn>;
}

interface FakeSelection {
  refreshSnapshotsFromSearchResults: ReturnType<typeof vi.fn>;
}

interface FakeLanguage {
  selectedLanguage: ReturnType<typeof signal<string>>;
}

interface Setup {
  facade: MessageListFacade;
  shell: FakeShell;
  filter: FakeFilter;
  api: FakeApi;
  selection: FakeSelection;
  language: FakeLanguage;
  emitNext: (result: MessageSearchResult) => void;
  emitError: (err: unknown) => void;
  currentSubjects: Subject<MessageSearchResult>[];
}

function setup(opts?: { isMap?: boolean; requiresSearch?: boolean }): Setup {
  const shell: FakeShell = {
    requiresSearch: signal(opts?.requiresSearch ?? true),
    isMap: signal(opts?.isMap ?? false),
    maxPageSize: signal(opts?.isMap ? 1000 : 200),
  };

  const filter: FakeFilter = {
    requestParams: signal(''),
    sortBy: signal<SortBy>('AREA'),
  };

  const currentSubjects: Subject<MessageSearchResult>[] = [];
  const api: FakeApi = {
    searchMessages: vi.fn(() => {
      const s = new Subject<MessageSearchResult>();
      currentSubjects.push(s);
      return s.asObservable();
    }),
  };

  const selection: FakeSelection = {
    refreshSnapshotsFromSearchResults: vi.fn(),
  };

  const language: FakeLanguage = {
    selectedLanguage: signal('en'),
  };

  TestBed.configureTestingModule({
    providers: [
      { provide: MessagesShellFacade, useValue: shell },
      { provide: MessagesFilterFacade, useValue: filter },
      { provide: MessagesApiService, useValue: api },
      { provide: MessageSelectionService, useValue: selection },
      { provide: LanguageService, useValue: language },
    ],
  });

  const facade = TestBed.inject(MessageListFacade);

  const emitNext = (result: MessageSearchResult): void => {
    const s = currentSubjects[currentSubjects.length - 1];
    s.next(result);
    s.complete();
  };

  const emitError = (err: unknown): void => {
    const s = currentSubjects[currentSubjects.length - 1];
    s.error(err);
  };

  return {
    facade,
    shell,
    filter,
    api,
    selection,
    language,
    emitNext,
    emitError,
    currentSubjects,
  };
}

function flushDebounce(): void {
  vi.advanceTimersByTime(150);
  TestBed.tick();
}

describe('MessageListFacade', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('debounces filter-driven searches by 100ms', () => {
    const { facade, filter, api, emitNext } = setup();

    filter.requestParams.set('a=1');
    TestBed.tick();
    filter.requestParams.set('a=2');
    TestBed.tick();
    filter.requestParams.set('a=3');
    TestBed.tick();

    expect(api.searchMessages).not.toHaveBeenCalled();

    flushDebounce();

    expect(api.searchMessages).toHaveBeenCalledTimes(1);
    expect(api.searchMessages).toHaveBeenCalledWith('a=3', 0, 200, {
      viewModeMap: false,
      includeNoPos: false,
    });

    emitNext({ data: [], total: 0 });
    expect(facade.loading()).toBe(false);
  });

  it('gates: no search fires when requiresSearch is false', () => {
    const { filter, api } = setup({ requiresSearch: false });

    filter.requestParams.set('a=1');
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).not.toHaveBeenCalled();
  });

  it('re-entering a message view refetches even though the filter never changed', () => {
    const { filter, api, shell, emitNext } = setup();

    filter.requestParams.set('a=1');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m1']), total: 1 });
    expect(api.searchMessages).toHaveBeenCalledTimes(1);

    // Leave the messages area (the editor is not a message view) — nothing refetches...
    shell.requiresSearch.set(false);
    TestBed.tick();
    flushDebounce();
    expect(api.searchMessages).toHaveBeenCalledTimes(1);

    // ...and come back. The params are byte-identical, so this flip is the ONLY change in
    // the trigger key: it is what picks up a message created while the user was away.
    shell.requiresSearch.set(true);
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).toHaveBeenCalledTimes(2);
    expect(api.searchMessages).toHaveBeenLastCalledWith('a=1', 0, 200, expect.anything());
  });

  it('re-entry refetches from page 0, dropping any load-more pages', () => {
    const { facade, filter, api, shell, emitNext } = setup();

    filter.requestParams.set('a=1');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m1']), total: 50 });

    facade.loadMore();
    TestBed.tick();
    emitNext({ data: makeMessages(['m2']), total: 50 });
    expect(api.searchMessages).toHaveBeenLastCalledWith('a=1', 1, 200, expect.anything());

    shell.requiresSearch.set(false);
    TestBed.tick();
    flushDebounce();
    shell.requiresSearch.set(true);
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).toHaveBeenLastCalledWith('a=1', 0, 200, expect.anything());
  });

  it('cancels superseded in-flight requests via switchMap', () => {
    const { facade, filter, api, emitNext, currentSubjects, selection } = setup();

    filter.requestParams.set('q=first');
    TestBed.tick();
    flushDebounce();
    expect(api.searchMessages).toHaveBeenCalledTimes(1);

    filter.requestParams.set('q=second');
    TestBed.tick();
    flushDebounce();
    expect(api.searchMessages).toHaveBeenCalledTimes(2);

    // Emit on the FIRST subject after it has been superseded - its value must not reach signals.
    const firstSubject = currentSubjects[0];
    firstSubject.next({ data: makeMessages(['stale-1']), total: 999 });
    firstSubject.complete();

    expect(facade.messages()).toEqual([]);
    expect(facade.total()).toBe(0);

    emitNext({ data: makeMessages(['fresh-1']), total: 1 });

    expect(facade.messages().map((m) => m.id)).toEqual(['fresh-1']);
    expect(facade.total()).toBe(1);
    expect(selection.refreshSnapshotsFromSearchResults).toHaveBeenCalledTimes(1);
  });

  it('refetches when active language changes (resets page to 0)', () => {
    const { facade, filter, language, api, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1', 'm-2']), total: 400 });

    facade.loadMore();
    TestBed.tick();
    emitNext({ data: makeMessages(['m-3']), total: 400 });
    expect(facade.page()).toBe(1);
    expect(api.searchMessages).toHaveBeenCalledTimes(2);

    language.selectedLanguage.set('da');
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).toHaveBeenCalledTimes(3);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 0, 200, expect.anything());
    expect(facade.page()).toBe(0);

    emitNext({ data: makeMessages(['da-1']), total: 5 });
    expect(facade.messages().map((m) => m.id)).toEqual(['da-1']);
  });

  it('loadMore appends results and increments page', () => {
    const { facade, filter, api, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1', 'm-2']), total: 400 });

    expect(facade.page()).toBe(0);
    expect(facade.hasMore()).toBe(true);

    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(2);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 1, 200, expect.anything());
    emitNext({ data: makeMessages(['m-3', 'm-4']), total: 400 });

    expect(facade.messages().map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3', 'm-4']);
    expect(facade.page()).toBe(1);
  });

  it('filter change resets page to 0 and replaces the list', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 400 });

    facade.loadMore();
    TestBed.tick();
    emitNext({ data: makeMessages(['m-2']), total: 400 });
    expect(facade.page()).toBe(1);

    filter.requestParams.set('q=y');
    TestBed.tick();
    flushDebounce();

    expect(facade.page()).toBe(0);

    emitNext({ data: makeMessages(['n-1']), total: 50 });
    expect(facade.messages().map((m) => m.id)).toEqual(['n-1']);
  });

  it('passes viewMode=map and includeNoPos when shell is in map mode', () => {
    const { filter, shell, api } = setup({ isMap: true });

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).toHaveBeenCalledWith('q=x', 0, shell.maxPageSize(), {
      viewModeMap: true,
      includeNoPos: true,
    });
  });

  it('does not pass map flags when shell is not in map mode', () => {
    const { filter, api } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();

    expect(api.searchMessages).toHaveBeenCalledWith('q=x', 0, 200, {
      viewModeMap: false,
      includeNoPos: false,
    });
  });

  it('resets list and re-issues search when isMap flips', () => {
    const { facade, filter, shell, api, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1', 'm-2']), total: 200 });
    expect(facade.messages().length).toBe(2);

    shell.isMap.set(true);
    shell.maxPageSize.set(1000);
    TestBed.tick();

    expect(facade.messages()).toEqual([]);
    expect(facade.total()).toBe(0);
    expect(facade.page()).toBe(0);

    flushDebounce();
    expect(api.searchMessages).toHaveBeenCalledTimes(2);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 0, 1000, {
      viewModeMap: true,
      includeNoPos: true,
    });
  });

  it('calls refreshSnapshotsFromSearchResults once per successful search', () => {
    const { filter, selection, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 1 });

    expect(selection.refreshSnapshotsFromSearchResults).toHaveBeenCalledTimes(1);
    expect(selection.refreshSnapshotsFromSearchResults).toHaveBeenCalledWith([{ id: 'm-1' }]);
  });

  it('carries the incomplete-index mark of each answer, and drops it with the next answer without it', () => {
    const { facade, filter, emitNext } = setup();
    expect(facade.indexIncomplete()).toBe(false);

    filter.requestParams.set('query=buoy');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 1, indexIncomplete: true });
    expect(facade.indexIncomplete()).toBe(true);
    expect(facade.messages().map((m) => m.id)).toEqual(['m-1']);

    filter.requestParams.set('query=light');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-2']), total: 1 });
    expect(facade.indexIncomplete()).toBe(false);
  });

  it('keeps the incomplete-index mark of page 0 when a page without it is appended', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('query=buoy');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 2, indexIncomplete: true });
    expect(facade.indexIncomplete()).toBe(true);

    // The index completes before the next page is fetched: the rows of page 0 are still on
    // screen and may still miss messages.
    facade.loadMore();
    TestBed.tick();
    emitNext({ data: makeMessages(['m-2']), total: 2 });
    expect(facade.messages().map((m) => m.id)).toEqual(['m-1', 'm-2']);
    expect(facade.indexIncomplete()).toBe(true);
  });

  it('sets the incomplete-index mark when only an appended page carries it', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('query=buoy');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 2 });
    expect(facade.indexIncomplete()).toBe(false);

    facade.loadMore();
    TestBed.tick();
    emitNext({ data: makeMessages(['m-2']), total: 2, indexIncomplete: true });
    expect(facade.indexIncomplete()).toBe(true);
  });

  it('sets error on failure and retry re-issues exact request', () => {
    const { facade, filter, api, emitNext, emitError } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 400 });

    facade.loadMore();
    TestBed.tick();
    emitError(new Error('boom'));

    expect(facade.error()).not.toBeNull();
    expect(facade.loading()).toBe(false);
    expect(facade.page()).toBe(1);

    facade.retry();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(3);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 1, 200, expect.anything());
    emitNext({ data: makeMessages(['m-2']), total: 400 });

    expect(facade.error()).toBeNull();
    expect(facade.messages().map((m) => m.id)).toEqual(['m-1', 'm-2']);
  });

  it('does not fire loadMore after a failed load-more (no page skip)', () => {
    const { facade, filter, api, emitNext, emitError } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 400 });

    facade.loadMore();
    TestBed.tick();
    emitError(new Error('boom'));

    expect(facade.error()).not.toBeNull();
    expect(facade.page()).toBe(1);
    expect(api.searchMessages).toHaveBeenCalledTimes(2);

    // Clicking Load More again must not advance to page 2 and skip the failed page 1.
    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(2);
    expect(facade.page()).toBe(1);
  });

  it('retry after a failed load-more re-issues the exact page, then loadMore resumes at the next page', () => {
    const { facade, filter, api, emitNext, emitError } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 400 });

    facade.loadMore();
    TestBed.tick();
    emitError(new Error('boom'));
    expect(facade.page()).toBe(1);

    facade.retry();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(3);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 1, 200, expect.anything());
    emitNext({ data: makeMessages(['m-2']), total: 400 });

    expect(facade.error()).toBeNull();
    expect(facade.messages().map((m) => m.id)).toEqual(['m-1', 'm-2']);

    // With the error cleared, Load More works again and advances to the next page.
    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(4);
    expect(api.searchMessages).toHaveBeenLastCalledWith('q=x', 2, 200, expect.anything());
    expect(facade.page()).toBe(2);
  });

  it('does not fire loadMore after a failed page-0 search (error gate applies universally)', () => {
    const { facade, filter, api, emitNext, emitError } = setup();

    // Seed a known total so hasMore() stays true independently of the pending error,
    // isolating the error gate from the hasMore gate.
    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 400 });
    expect(facade.hasMore()).toBe(true);

    // A fresh page-0 search (filter change) fails.
    filter.requestParams.set('q=y');
    TestBed.tick();
    flushDebounce();
    emitError(new Error('boom'));

    expect(facade.error()).not.toBeNull();
    expect(facade.page()).toBe(0);
    expect(facade.hasMore()).toBe(true);
    expect(api.searchMessages).toHaveBeenCalledTimes(2);

    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(2);
  });

  it('does nothing on retry without a prior request', () => {
    const { facade, api } = setup();

    facade.retry();
    TestBed.tick();

    expect(api.searchMessages).not.toHaveBeenCalled();
  });

  it('does nothing on loadMore when hasMore is false', () => {
    const { facade, filter, api, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['m-1']), total: 1 });

    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(1);
  });

  it('does nothing on loadMore while loading', () => {
    const { facade, filter, api } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    expect(facade.loading()).toBe(true);

    facade.loadMore();
    TestBed.tick();

    expect(api.searchMessages).toHaveBeenCalledTimes(1);
  });

  it('hasMore boundary: zero/zero', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: [], total: 0 });

    expect(facade.hasMore()).toBe(false);
  });

  it('hasMore boundary: equal counts', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['a', 'b', 'c']), total: 3 });

    expect(facade.hasMore()).toBe(false);
  });

  it('hasMore boundary: less than total', () => {
    const { facade, filter, emitNext } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitNext({ data: makeMessages(['a']), total: 3 });

    expect(facade.hasMore()).toBe(true);
  });

  it('error flow propagates from API throwError without subject path', () => {
    const { facade, filter, api } = setup();

    api.searchMessages.mockReturnValueOnce(throwError(() => new Error('immediate')));
    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();

    expect(facade.error()).not.toBeNull();
    expect(facade.loading()).toBe(false);
  });

  /**
   * A publication the backend cannot resolve is ONE filter's problem.
   *
   * Reported only as a list failure it reads as "search is broken"; swallowed as
   * an empty result it reads as "this publication contains nothing" — an answer,
   * and the wrong one. The chip says what actually happened instead.
   */
  it('surfaces a 400 about the publication filter as that filter’s own error', () => {
    const { facade, filter, emitError } = setup();

    filter.requestParams.set('publication=efs-27-2026');
    TestBed.tick();
    flushDebounce();
    emitError({
      status: 400,
      error: { code: 'PUBLICATION_UNRESOLVABLE', message: 'no resolvable message set' },
    });

    expect(facade.publicationFilterError()).toBe('no resolvable message set');
    // Still a list error as well: the rows left on screen are not an answer either.
    expect(facade.error()).not.toBeNull();
  });

  it('leaves the publication chip alone for a failure that is not its own', () => {
    const { facade, filter, emitError } = setup();

    filter.requestParams.set('q=x');
    TestBed.tick();
    flushDebounce();
    emitError({ status: 500, error: { code: 'INTERNAL' } });

    expect(facade.publicationFilterError()).toBeUndefined();
    expect(facade.error()).not.toBeNull();
  });
});

function area(id: number, name = `area-${id}`, parent?: MessageArea): MessageArea {
  return { id, descs: [{ lang: 'en', name }], parent };
}

function messageWithArea(id: string, areaRef: MessageArea | undefined): Message {
  return { id, areas: areaRef ? [areaRef] : undefined };
}

describe('MessageListFacade.groupedMessages', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function fillMessages(env: Setup, list: Message[]): void {
    env.filter.requestParams.set('q=' + Math.random());
    TestBed.tick();
    vi.advanceTimersByTime(150);
    TestBed.tick();
    env.emitNext({ data: list, total: list.length });
  }

  it('empty list returns empty array', () => {
    const { facade } = setup();
    expect(facade.groupedMessages()).toEqual([]);
  });

  it('sortBy=ID returns entries with areaHeading undefined for every message', () => {
    const env = setup();
    env.filter.sortBy.set('ID');
    fillMessages(env, [messageWithArea('m1', area(10)), messageWithArea('m2', area(11))]);

    const entries = env.facade.groupedMessages();
    expect(entries).toHaveLength(2);
    expect(entries.every((e) => e.areaHeading === undefined)).toBe(true);
  });

  it('sortBy=AREA, all share top area -> single heading on first entry', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    const top = area(10);
    fillMessages(env, [
      messageWithArea('m1', top),
      messageWithArea('m2', top),
      messageWithArea('m3', top),
    ]);

    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(10);
    expect(entries[1].areaHeading).toBeUndefined();
    expect(entries[2].areaHeading).toBeUndefined();
  });

  it('sortBy=AREA, three areas alternating -> three headings interleaved', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    fillMessages(env, [
      messageWithArea('m1', area(10)),
      messageWithArea('m2', area(20)),
      messageWithArea('m3', area(30)),
    ]);

    const entries = env.facade.groupedMessages();
    expect(entries.map((e) => e.areaHeading?.id)).toEqual([10, 20, 30]);
  });

  it('sortBy=AREA, three consecutive no-area messages -> one sentinel heading on the first', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    fillMessages(env, [
      messageWithArea('m1', undefined),
      messageWithArea('m2', undefined),
      messageWithArea('m3', undefined),
    ]);

    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(-999999);
    expect(entries[1].areaHeading).toBeUndefined();
    expect(entries[2].areaHeading).toBeUndefined();
  });

  it('sortBy=AREA mix: area, area, no-area, area -> 3 real headings + 1 sentinel', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    fillMessages(env, [
      messageWithArea('m1', area(10)),
      messageWithArea('m2', area(20)),
      messageWithArea('m3', undefined),
      messageWithArea('m4', area(30)),
    ]);

    const entries = env.facade.groupedMessages();
    expect(entries.map((e) => e.areaHeading?.id)).toEqual([10, 20, -999999, 30]);
  });

  it('sortBy=AREA, climbs at most two parent levels (maxLevels=2)', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    const grandparent = area(100, 'gp');
    const parent = area(50, 'p', grandparent);
    const leaf = area(10, 'leaf', parent);

    fillMessages(env, [messageWithArea('m1', leaf)]);
    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(50);
  });

  it('sortBy=AREA, 2-deep chain picks the leaf', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    const root = area(100, 'root');
    const leaf = area(10, 'leaf', root);

    fillMessages(env, [messageWithArea('m1', leaf)]);
    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(10);
  });

  it('sortBy=AREA, 4-deep chain picks the second-from-root level', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    const root = area(100, 'root');
    const second = area(50, 'second', root);
    const third = area(30, 'third', second);
    const leaf = area(10, 'leaf', third);

    fillMessages(env, [messageWithArea('m1', leaf)]);
    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(50);
  });

  it('sortBy=AREA, two messages with different leaves but same second-from-root group together', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    const root = area(100, 'root');
    const second = area(50, 'second', root);
    const leafA = area(11, 'a', second);
    const leafB = area(12, 'b', second);

    fillMessages(env, [messageWithArea('m1', leafA), messageWithArea('m2', leafB)]);
    const entries = env.facade.groupedMessages();
    expect(entries[0].areaHeading?.id).toBe(50);
    expect(entries[1].areaHeading).toBeUndefined();
  });

  it('reactivity: changing sortBy re-derives heading visibility', () => {
    const env = setup();
    env.filter.sortBy.set('AREA');
    fillMessages(env, [messageWithArea('m1', area(10)), messageWithArea('m2', area(20))]);
    expect(env.facade.groupedMessages().some((e) => e.areaHeading)).toBe(true);

    env.filter.sortBy.set('ID');
    expect(env.facade.groupedMessages().every((e) => e.areaHeading === undefined)).toBe(true);
  });
});
