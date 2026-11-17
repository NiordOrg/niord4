import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { By } from '@angular/platform-browser';
import { MessageMapViewComponent } from './message-map-view.component';
import { MessageMapComponent } from '../message-map/message-map.component';
import { MessagesStore } from '../../messages.store';
import {
  fakeMessagesStore,
  FakeMessagesStoreSeed,
  getTranslocoTestingModule,
  provideTestNiordConfig,
} from '../../../../testing/test-config';

describe('MessageMapViewComponent', () => {
  function setUp(seed?: FakeMessagesStoreSeed) {
    const store = fakeMessagesStore(seed);
    TestBed.configureTestingModule({
      imports: [MessageMapViewComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig(), { provide: MessagesStore, useValue: store }],
      schemas: [NO_ERRORS_SCHEMA],
    });
    const fixture = TestBed.createComponent(MessageMapViewComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, store, el, frame: el.firstElementChild as HTMLElement };
  }

  // The view is a frame around the chart, and the chart draws the route store's list and frames
  // its root area; what the chart does with them is message-map.component.spec.ts's.
  it("hands the chart the store's list and root area, and follows them", () => {
    const { fixture, store } = setUp({
      messages: [{ id: 'msg-1', mainType: 'NW' }],
      rootArea: { id: 453 },
    });
    const map = fixture.debugElement.query(By.directive(MessageMapComponent))
      .componentInstance as MessageMapComponent;
    expect(map.messages()?.map((m) => m.id)).toEqual(['msg-1']);
    expect(map.rootArea()?.id).toBe(453);

    store.messages.set([
      { id: 'msg-2', mainType: 'NM' },
      { id: 'msg-3', mainType: 'NW' },
    ]);
    fixture.detectChanges();
    expect(map.messages()?.map((m) => m.id)).toEqual(['msg-2', 'msg-3']);
  });

  // A chart with nothing on it reads as "no warnings in this area"; the other two views say a
  // read failed in words, and so does this one, in a live region that was there before it.
  it('says a read failed over the chart, where an empty chart says nothing', () => {
    const { fixture, store, el } = setUp();
    const alert = el.querySelector('[role="alert"]')!;
    expect(alert.textContent!.trim()).toBe('');

    store.failed.set(true);
    fixture.detectChanges();
    expect(alert.textContent!.trim()).toBe('Failed to load messages');
    // The chart's zoom buttons sit in the same corner and stay usable.
    expect(alert.classList).toContain('pointer-events-none');
  });

  it('says it is reading while the list is on its way', () => {
    const { fixture, store, frame } = setUp({ loading: true });
    expect(frame.getAttribute('aria-busy')).toBe('true');
    expect(frame.querySelector(':scope > [aria-hidden="true"] app-icon')).not.toBeNull();

    store.loading.set(false);
    fixture.detectChanges();
    expect(frame.getAttribute('aria-busy')).toBe('false');
    expect(frame.querySelector(':scope > [aria-hidden="true"] app-icon')).toBeNull();
  });

  // An absolutely positioned frame takes no room in the flow, and the footer that follows it
  // then starts at the top of the page, under the bars. The frame is in the flow instead and
  // sized against what is pinned; the sizes themselves are checked in a browser by
  // npm run layout:info.
  it('frames the map in the flow, as tall as the viewport less the pinned bars', () => {
    const { frame } = setUp();
    const classes = frame.className.split(/\s+/);
    expect(classes).toContain('relative');
    expect(classes).not.toContain('absolute');
    expect(classes).toContain('h-[calc(100dvh-var(--niord-pinned-bars-height))]');
  });
});
