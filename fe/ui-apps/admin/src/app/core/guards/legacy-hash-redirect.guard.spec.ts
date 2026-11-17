import { TestBed } from '@angular/core/testing';
import {
  type ActivatedRouteSnapshot,
  provideRouter,
  Router,
  type RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { legacyHashRedirectGuard } from './legacy-hash-redirect.guard';

function run(fragment: string | null): boolean | UrlTree {
  const route = { fragment } as unknown as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;
  // The guard is synchronous — it returns `true` or a UrlTree directly.
  return TestBed.runInInjectionContext(() => legacyHashRedirectGuard(route, state)) as
    | boolean
    | UrlTree;
}

describe('legacyHashRedirectGuard', () => {
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    router = TestBed.inject(Router);
  });

  it('redirects a hash-routed /#/message/{id} fragment to /messages?messageId={id}', () => {
    const result = run('/message/NW-069-16');
    expect(result).toBeInstanceOf(UrlTree);
    const tree = result as UrlTree;
    expect(router.serializeUrl(tree).split('?')[0]).toBe('/messages');
    expect(tree.queryParams['messageId']).toBe('NW-069-16');
  });

  it('handles the fragment without a leading slash', () => {
    const result = run('message/NW-069-16');
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).queryParams['messageId']).toBe('NW-069-16');
  });

  it('decodes percent-encoded ids (e.g. B%2F1%202024 -> "B/1 2024")', () => {
    const result = run('/message/B%2F1%202024');
    expect(result).toBeInstanceOf(UrlTree);
    const tree = result as UrlTree;
    expect(router.serializeUrl(tree).split('?')[0]).toBe('/messages');
    expect(tree.queryParams['messageId']).toBe('B/1 2024');
  });

  it('allows activation (returns true) when there is no fragment', () => {
    expect(run(null)).toBe(true);
  });

  it('allows activation (returns true) for an unrelated fragment', () => {
    expect(run('/some/other/anchor')).toBe(true);
    expect(run('section-2')).toBe(true);
  });

  it('does not match a bare "message/" with no id', () => {
    expect(run('/message/')).toBe(true);
  });
});
