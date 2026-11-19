import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap, type ParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { BehaviorSubject } from 'rxjs';

import type { Domain } from '../../../core/domain/models/domain.model';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { MessagesFilterFacade } from './messages-filter.facade';
import { MessagesFilterUrlSync } from './messages-filter-url-sync.service';

interface NavigateExtras {
  readonly queryParams: Record<string, string | string[] | null>;
  readonly queryParamsHandling?: string;
  readonly replaceUrl?: boolean;
}

const URL_WRITE_DEBOUNCE_MS = 200;

function setup(initial: Record<string, string> = {}) {
  const queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap({ ...initial }));
  const navigate = vi.fn<
    (commands: readonly unknown[], extras: NavigateExtras) => Promise<boolean>
  >(() => Promise.resolve(true));

  const route = {
    queryParamMap,
    get snapshot() {
      return { queryParamMap: queryParamMap.value };
    },
  } as unknown as ActivatedRoute;
  const router = { navigate } as unknown as Router;

  // Facade stub — `requestParams` is a writable signal so tests can drive
  // outbound flow; `applyQueryParams` is a spy so we can inspect what the
  // inbound flow forwarded after the rename.
  const requestParams = signal<string>('');
  const applyQueryParams = vi.fn<
    (search: URLSearchParams) => { pendingDomainId: string | undefined }
  >(() => ({ pendingDomainId: undefined }));
  const setSliceValue = vi.fn();
  const setEnabled = vi.fn();
  const facade = {
    requestParams,
    applyQueryParams,
    setSliceValue,
    setEnabled,
  } as unknown as MessagesFilterFacade;

  // Domain facade stub — `availableDomains` empty by default so the deferred-
  // resolution effect short-circuits unless a test pushes a value.
  const availableDomains = signal<readonly Domain[]>([]);
  const resolveById = vi.fn<(id: string) => Domain | undefined>(() => undefined);
  const domain = { availableDomains, resolveById } as unknown as DomainFacade;

  TestBed.configureTestingModule({
    providers: [
      { provide: Router, useValue: router },
      { provide: ActivatedRoute, useValue: route },
      { provide: MessagesFilterFacade, useValue: facade },
      { provide: DomainFacade, useValue: domain },
    ],
  });

  // Instantiate the service — its constructor subscribes both directions.
  const service = TestBed.inject(MessagesFilterUrlSync);
  return {
    service,
    navigate,
    queryParamMap,
    requestParams,
    applyQueryParams,
    availableDomains,
    resolveById,
  };
}

// Real timers — fake timers don't drive Angular's signal-to-observable
// effect bridge cleanly.
const settleDebounce = () =>
  new Promise<void>((resolve) => setTimeout(resolve, URL_WRITE_DEBOUNCE_MS + 50));

// Flush the toObservable() bridge's initial emission so the service's
// `skip(1)` lands on the test-driven .set() rather than swallowing it
// alongside the constructor-time empty string.
async function flushInitialEmission(): Promise<void> {
  TestBed.tick();
  await settleDebounce();
}

describe('MessagesFilterUrlSync — reference filter key rename', () => {
  describe('inbound (URL → facade)', () => {
    it('renames `refMessageId` from the URL into `messageId` before applying', () => {
      const { applyQueryParams } = setup({ refMessageId: 'NM-1' });
      expect(applyQueryParams).toHaveBeenCalledTimes(1);
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('messageId')).toBe('NM-1');
      // The URL key itself does not survive into the facade input.
      expect(search.get('refMessageId')).toBeNull();
    });

    it('skips the dialog `messageId` URL key when forwarding to the facade', () => {
      // `messageId` in the URL is the message-details dialog's deep-link, NOT
      // the reference filter's value — the inbound sync must drop it so the
      // facade doesn't re-enable the reference filter from a dialog open.
      const { applyQueryParams } = setup({ messageId: 'NM-DIALOG' });
      expect(applyQueryParams).toHaveBeenCalledTimes(1);
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('messageId')).toBeNull();
    });

    it('passes through non-conflicting keys unchanged', () => {
      const { applyQueryParams } = setup({ refMessageId: 'NM-1', status: 'PUBLISHED' });
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('messageId')).toBe('NM-1');
      expect(search.get('status')).toBe('PUBLISHED');
    });
  });

  describe('outbound (facade → URL)', () => {
    it('renames `messageId` from facade.requestParams into `refMessageId` on navigate', async () => {
      const { navigate, requestParams } = setup();
      await flushInitialEmission();
      requestParams.set('messageId=NM-2');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['refMessageId']).toBe('NM-2');
      expect(extras.queryParams['messageId']).toBeUndefined();
      expect(extras.queryParamsHandling).toBe('replace');
    });

    it('carries the dialog `messageId` from the current URL across the navigate', async () => {
      // Dialog open at NM-DIALOG. Facade then enables the reference filter
      // (messageId=NM-REF in `requestParams`). The outbound write must keep
      // both the renamed reference filter (`refMessageId=NM-REF`) AND the
      // dialog's `messageId=NM-DIALOG`, otherwise the dialog would close.
      const { navigate, requestParams } = setup({ messageId: 'NM-DIALOG' });
      await flushInitialEmission();
      requestParams.set('messageId=NM-REF');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['refMessageId']).toBe('NM-REF');
      expect(extras.queryParams['messageId']).toBe('NM-DIALOG');
    });

    it('writes only the dialog `messageId` when no reference filter is set', async () => {
      const { navigate, requestParams } = setup({ messageId: 'NM-DIALOG' });
      await flushInitialEmission();
      requestParams.set('status=PUBLISHED');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['messageId']).toBe('NM-DIALOG');
      expect(extras.queryParams['refMessageId']).toBeUndefined();
      expect(extras.queryParams['status']).toBe('PUBLISHED');
    });

    it('debounces rapid facade updates into a single navigate', async () => {
      const { navigate, requestParams } = setup();
      await flushInitialEmission();
      requestParams.set('messageId=NM-1');
      TestBed.tick();
      await new Promise<void>((r) => setTimeout(r, 50));
      requestParams.set('messageId=NM-2');
      TestBed.tick();
      await new Promise<void>((r) => setTimeout(r, 50));
      requestParams.set('messageId=NM-3');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['refMessageId']).toBe('NM-3');
    });
  });
});

describe('MessagesFilterUrlSync — domain filter key rename', () => {
  describe('inbound (URL → facade)', () => {
    it('reads `searchDomain` from the URL as the Domain filter `domain`', () => {
      const { applyQueryParams } = setup({ searchDomain: 'nw' });
      expect(applyQueryParams).toHaveBeenCalledTimes(1);
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('domain')).toBe('nw');
      expect(search.get('searchDomain')).toBeNull();
    });

    it('does not read the session-domain deep link `domain` as a filter', () => {
      const { applyQueryParams } = setup({ domain: 'nw', status: 'PUBLISHED' });
      expect(applyQueryParams).toHaveBeenCalledTimes(1);
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('domain')).toBeNull();
      expect(search.get('status')).toBe('PUBLISHED');
    });

    it('passes a key named like a property of every object through as itself', () => {
      const { applyQueryParams } = setup({ constructor: 'x', toString: 'y' });
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.get('constructor')).toBe('x');
      expect(search.get('toString')).toBe('y');
    });

    it('reads the filter, not the deep link, when the URL has both', () => {
      const { applyQueryParams } = setup({ domain: 'nw', searchDomain: 'nm' });
      const search = applyQueryParams.mock.calls[0][0];
      expect(search.getAll('domain')).toEqual(['nm']);
    });
  });

  describe('outbound (facade → URL)', () => {
    it('writes the Domain filter as `searchDomain`, never as `domain`', async () => {
      const { navigate, requestParams } = setup();
      await flushInitialEmission();
      requestParams.set('domain=nm&status=DRAFT');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['searchDomain']).toBe('nm');
      expect(extras.queryParams['domain']).toBeUndefined();
      expect(extras.queryParams['status']).toBe('DRAFT');
    });

    it('renames the Domain and the reference filters together', async () => {
      const { navigate, requestParams } = setup();
      await flushInitialEmission();
      requestParams.set('domain=nm&messageId=NM-2');
      TestBed.tick();
      await settleDebounce();
      expect(navigate).toHaveBeenCalledTimes(1);
      const [, extras] = navigate.mock.calls[0] as [unknown[], NavigateExtras];
      expect(extras.queryParams['searchDomain']).toBe('nm');
      expect(extras.queryParams['refMessageId']).toBe('NM-2');
      expect(extras.queryParams['domain']).toBeUndefined();
      expect(extras.queryParams['messageId']).toBeUndefined();
    });
  });
});
