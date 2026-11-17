import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ViewportScroller } from '@angular/common';
import { NavigationEnd, Router, Scroll } from '@angular/router';
import { Subject } from 'rxjs';
import { restoreScrollWhenSettled } from './restore-scroll';

const settled = signal(false);

@Component({ selector: 'app-host', template: '' })
class Host {
  constructor() {
    restoreScrollWhenSettled(() => settled());
  }
}

describe('restoreScrollWhenSettled', () => {
  let events: Subject<unknown>;
  let scrollToPosition: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    settled.set(false);
    events = new Subject();
    scrollToPosition = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events } },
        { provide: ViewportScroller, useValue: { scrollToPosition } },
      ],
    });
    TestBed.createComponent(Host);
    TestBed.tick();
  });

  /** What the router emits after a navigation, with the position it restores, if any. */
  function navigated(position: [number, number] | null): void {
    events.next(new Scroll(new NavigationEnd(1, '/messages', '/messages'), position, null));
    TestBed.tick();
  }

  // Back to a list that is read again: the router's own restore lands on a page too short to
  // hold the position, so it is applied once more when the list is there.
  it('scrolls to the position Back restores once the content has arrived', () => {
    navigated([0, 4000]);
    expect(scrollToPosition).not.toHaveBeenCalled();

    settled.set(true);
    TestBed.tick();
    expect(scrollToPosition).toHaveBeenCalledExactlyOnceWith([0, 4000]);
  });

  it('applies it once, not again on a later read of the same page', () => {
    navigated([0, 4000]);
    settled.set(true);
    TestBed.tick();

    settled.set(false);
    TestBed.tick();
    settled.set(true);
    TestBed.tick();
    expect(scrollToPosition).toHaveBeenCalledOnce();
  });

  // A forward navigation starts at the top, which the router does by itself.
  it('does nothing after a navigation that restores no position', () => {
    navigated(null);
    settled.set(true);
    TestBed.tick();
    expect(scrollToPosition).not.toHaveBeenCalled();
  });
});
