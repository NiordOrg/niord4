/**
 * A resize observer a spec drives by hand: jsdom has none, and it lays nothing out to measure.
 *
 * Install it with `vi.stubGlobal('ResizeObserver', FakeResizeObserver)` and take it down with
 * `vi.unstubAllGlobals()`. `FakeResizeObserver.last` is the observer the code under test created,
 * and `report()` delivers a measurement the way a browser does after a layout.
 */
export class FakeResizeObserver {
  static last: FakeResizeObserver | undefined;

  readonly observed: Element[] = [];
  disconnected = false;

  constructor(private readonly callback: ResizeObserverCallback) {
    FakeResizeObserver.last = this;
  }

  observe(element: Element): void {
    this.observed.push(element);
  }

  disconnect(): void {
    this.disconnected = true;
  }

  /** One measurement: the fields a test leaves out are the ones the code must not need. */
  report(entry: Partial<ResizeObserverEntry> = {}): void {
    this.callback([entry as ResizeObserverEntry], this as unknown as ResizeObserver);
  }
}
