import { TestBed } from '@angular/core/testing';
import { Router, convertToParamMap, type ParamMap } from '@angular/router';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { BehaviorSubject } from 'rxjs';

import { MessageDetailsDialogService } from './message-details-dialog.service';

function setup(initialQueryParams: Record<string, string | null> = {}) {
  const initialMap: ParamMap = convertToParamMap({ ...initialQueryParams });
  const queryParamMap = new BehaviorSubject<ParamMap>(initialMap);

  interface NavigateExtras {
    queryParams: Record<string, string | null>;
    queryParamsHandling?: string;
    replaceUrl?: boolean;
    relativeTo?: unknown;
  }
  const navigate = vi.fn((_commands: unknown[], extras: NavigateExtras) => {
    // Mirror the real Router's effect on the queryParamMap so tests can observe
    // that the service's `rootMessageId` signal updates in response to its own
    // navigate calls.
    const current: Record<string, string> = {};
    queryParamMap.value.keys.forEach((k) => {
      const v = queryParamMap.value.get(k);
      if (v !== null) current[k] = v;
    });
    const merged: Record<string, string> = { ...current };
    for (const [k, v] of Object.entries(extras.queryParams)) {
      if (v === null) delete merged[k];
      else merged[k] = v;
    }
    queryParamMap.next(convertToParamMap(merged));
    return Promise.resolve(true);
  });
  // The service reads queryParams from `router.routerState.root.queryParamMap`
  // rather than injecting ActivatedRoute (it's a singleton service — root inject
  // would resolve to `/`, not the active leaf). Test stub mirrors that shape.
  const router = {
    navigate,
    routerState: { root: { queryParamMap } },
  } as unknown as Router;

  TestBed.configureTestingModule({
    providers: [{ provide: Router, useValue: router }],
  });

  const service = TestBed.inject(MessageDetailsDialogService);
  return { service, navigate, queryParamMap };
}

describe('MessageDetailsDialogService — open/close URL sync', () => {
  it('starts closed when ?messageId is absent', () => {
    const { service } = setup();
    expect(service.isOpen()).toBe(false);
    expect(service.rootMessageId()).toBeNull();
  });

  it('open(rootId, ctx) writes ?messageId with queryParamsHandling: merge', () => {
    const { service, navigate } = setup({ status: 'PUBLISHED', areaId: '12' });
    service.open('NM-1', ['NM-1', 'NM-2', 'NM-3']);
    expect(navigate).toHaveBeenCalledTimes(1);
    const [, extras] = navigate.mock.calls[0];
    expect(extras.queryParams).toEqual({ messageId: 'NM-1' });
    expect(extras.queryParamsHandling).toBe('merge');
    expect(extras.replaceUrl).toBe(false);
  });

  it('open updates rootMessageId, isOpen, currentMessageId, navigationContext', () => {
    const { service } = setup();
    service.open('NM-X', ['NM-1', 'NM-X', 'NM-2']);
    expect(service.rootMessageId()).toBe('NM-X');
    expect(service.isOpen()).toBe(true);
    expect(service.currentMessageId()).toBe('NM-X');
    expect(service.navigationContext()).toEqual(['NM-1', 'NM-X', 'NM-2']);
    expect(service.navigationStack()).toEqual(['NM-X']);
  });

  it('close() removes ?messageId and clears in-memory state', () => {
    const { service, navigate } = setup({ messageId: 'NM-1' });
    service.open('NM-1', ['NM-1', 'NM-2']);
    navigate.mockClear();

    service.close();
    expect(navigate).toHaveBeenCalledTimes(1);
    const [, extras] = navigate.mock.calls[0];
    expect(extras.queryParams).toEqual({ messageId: null });
    expect(extras.queryParamsHandling).toBe('merge');
    expect(service.isOpen()).toBe(false);
    expect(service.rootMessageId()).toBeNull();
    expect(service.navigationContext()).toEqual([]);
    expect(service.navigationStack()).toEqual([]);
  });

  it('bootstraps open=true when ?messageId is present at construction (deep link)', () => {
    const { service } = setup({ messageId: 'NM-DEEP', status: 'PUBLISHED' });
    expect(service.isOpen()).toBe(true);
    expect(service.rootMessageId()).toBe('NM-DEEP');
    expect(service.currentMessageId()).toBe('NM-DEEP');
    // No caller-provided context at bootstrap → prev/next will be hidden by the dialog
    expect(service.navigationContext()).toEqual([]);
  });
});

describe('MessageDetailsDialogService — prev/next', () => {
  it('selectNext navigates to the next id and uses replaceUrl: true', () => {
    const { service, navigate } = setup();
    service.open('NM-1', ['NM-1', 'NM-2', 'NM-3']);
    navigate.mockClear();

    service.selectNext();
    expect(navigate).toHaveBeenCalledTimes(1);
    const [, extras] = navigate.mock.calls[0];
    expect(extras.queryParams).toEqual({ messageId: 'NM-2' });
    expect(extras.replaceUrl).toBe(true);
    expect(service.rootMessageId()).toBe('NM-2');
  });

  it('selectPrev navigates to the previous id and uses replaceUrl: true', () => {
    const { service, navigate } = setup();
    service.open('NM-2', ['NM-1', 'NM-2', 'NM-3']);
    navigate.mockClear();

    service.selectPrev();
    const [, extras] = navigate.mock.calls[0];
    expect(extras.queryParams).toEqual({ messageId: 'NM-1' });
    expect(extras.replaceUrl).toBe(true);
  });

  it('selectNext is a no-op at the end of the list', () => {
    const { service, navigate } = setup();
    service.open('NM-3', ['NM-1', 'NM-2', 'NM-3']);
    navigate.mockClear();

    service.selectNext();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('selectPrev is a no-op at index 0', () => {
    const { service, navigate } = setup();
    service.open('NM-1', ['NM-1', 'NM-2']);
    navigate.mockClear();

    service.selectPrev();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('selectPrev / selectNext are no-ops when the root is not in the context (deep-link case)', () => {
    const { service, navigate } = setup({ messageId: 'NM-EXTERNAL' });
    navigate.mockClear();

    service.selectPrev();
    service.selectNext();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('navigating with prev/next clears any in-memory drill stack', () => {
    const { service } = setup();
    service.open('NM-1', ['NM-1', 'NM-2']);
    service.selectReference('REF-A');
    expect(service.navigationStack()).toEqual(['NM-1', 'REF-A']);

    service.selectNext();
    expect(service.navigationStack()).toEqual(['NM-2']);
    expect(service.currentMessageId()).toBe('NM-2');
  });
});

describe('MessageDetailsDialogService — drill stack', () => {
  it('selectReference pushes onto navigationStack; URL is not touched', () => {
    const { service, navigate } = setup();
    service.open('NM-1', ['NM-1']);
    navigate.mockClear();

    service.selectReference('REF-A');
    service.selectReference('REF-B');
    expect(navigate).not.toHaveBeenCalled();
    expect(service.navigationStack()).toEqual(['NM-1', 'REF-A', 'REF-B']);
    expect(service.currentMessageId()).toBe('REF-B');
  });

  it('back() pops the drill stack', () => {
    const { service } = setup();
    service.open('NM-1', ['NM-1']);
    service.selectReference('REF-A');
    service.selectReference('REF-B');

    service.back();
    expect(service.navigationStack()).toEqual(['NM-1', 'REF-A']);
    expect(service.currentMessageId()).toBe('REF-A');

    service.back();
    expect(service.navigationStack()).toEqual(['NM-1']);
    expect(service.currentMessageId()).toBe('NM-1');
  });

  it('back() is a no-op when the stack is already at root', () => {
    const { service } = setup();
    service.open('NM-1', ['NM-1']);

    service.back();
    expect(service.navigationStack()).toEqual(['NM-1']);
    expect(service.currentMessageId()).toBe('NM-1');
  });

  it('close() clears the drill stack', () => {
    const { service } = setup();
    service.open('NM-1', ['NM-1']);
    service.selectReference('REF-A');
    service.close();
    expect(service.navigationStack()).toEqual([]);
    expect(service.currentMessageId()).toBeNull();
  });
});

describe('MessageDetailsDialogService — non-messageId params survive', () => {
  it('open then close preserves filter params (queryParamsHandling: merge)', () => {
    const { service, queryParamMap } = setup({ status: 'PUBLISHED', areaId: '12' });
    service.open('NM-1', ['NM-1']);
    expect(queryParamMap.value.get('status')).toBe('PUBLISHED');
    expect(queryParamMap.value.get('areaId')).toBe('12');
    expect(queryParamMap.value.get('messageId')).toBe('NM-1');

    service.close();
    expect(queryParamMap.value.get('status')).toBe('PUBLISHED');
    expect(queryParamMap.value.get('areaId')).toBe('12');
    expect(queryParamMap.value.get('messageId')).toBeNull();
  });
});

describe('MessageDetailsDialogService — re-open with a fresh context', () => {
  beforeEach(() => undefined);
  it('open replaces the previous context snapshot', () => {
    const { service } = setup();
    service.open('NM-1', ['NM-1', 'NM-2']);
    expect(service.navigationContext()).toEqual(['NM-1', 'NM-2']);

    service.close();
    service.open('NM-9', ['NM-9', 'NM-10', 'NM-11']);
    expect(service.navigationContext()).toEqual(['NM-9', 'NM-10', 'NM-11']);
  });
});
