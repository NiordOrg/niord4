import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { LastMessagesViewService } from './last-messages-view.service';

/** Minimal Router stub exposing an `events` stream we can drive with NavigationEnd. */
class RouterStub {
  readonly events = new Subject<NavigationEnd>();
  emit(url: string): void {
    // id/url unused by the service; only `urlAfterRedirects` matters.
    this.events.next(new NavigationEnd(1, url, url));
  }
}

function setup() {
  const router = new RouterStub();
  TestBed.configureTestingModule({
    providers: [LastMessagesViewService, { provide: Router, useValue: router }],
  });
  const service = TestBed.inject(LastMessagesViewService);
  return { service, router };
}

describe('LastMessagesViewService', () => {
  it('falls back to /messages before any navigation', () => {
    const { service } = setup();
    expect(service.lastUrl()).toBe('/messages');
  });

  it('remembers the last messages URL including query params', () => {
    const { service, router } = setup();
    router.emit('/messages/table?tag=abc');
    expect(service.lastUrl()).toBe('/messages/table?tag=abc');

    router.emit('/messages/map?domain=nw');
    expect(service.lastUrl()).toBe('/messages/map?domain=nw');
  });

  it('remembers the bare /messages URL', () => {
    const { service, router } = setup();
    router.emit('/messages');
    expect(service.lastUrl()).toBe('/messages');
  });

  it('ignores non-messages navigations (keeps the last messages URL)', () => {
    const { service, router } = setup();
    router.emit('/messages/grid');
    router.emit('/editor/edit/42');
    router.emit('/admin/charts');
    expect(service.lastUrl()).toBe('/messages/grid');
  });

  it('does not treat a sibling /messages-prefixed route as the messages area', () => {
    const { service, router } = setup();
    router.emit('/messages/table');
    router.emit('/messagesx/other');
    expect(service.lastUrl()).toBe('/messages/table');
  });
});
