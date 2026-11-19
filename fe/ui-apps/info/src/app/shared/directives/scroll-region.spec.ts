import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ScrollRegion } from './scroll-region';
import { FakeResizeObserver } from '../../testing/fake-resize-observer';

@Component({
  selector: 'app-scroll-region-host',
  imports: [ScrollRegion],
  template: `<div class="overflow-x-auto" [appScrollRegion]="name()" [scrollsDown]="down()">
    <span [class]="kind()">{{ text() }}</span
    ><img alt="" />
  </div>`,
})
class ScrollRegionHost {
  readonly name = signal('Positions');
  readonly down = signal(false);
  readonly text = signal('55° 41,123′N');
  readonly kind = signal('short');
}

describe('ScrollRegion', () => {
  /** The frames the directive asked for, run by hand: jsdom paints nothing. */
  let frames: Map<number, FrameRequestCallback>;

  beforeEach(() => {
    frames = new Map();
    let next = 1;
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames.set(next, callback);
      return next++;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    FakeResizeObserver.last = undefined;
  });

  function render() {
    const fixture = TestBed.createComponent(ScrollRegionHost);
    TestBed.tick();
    const box = fixture.nativeElement.querySelector('div') as HTMLElement;
    let reads = 0;
    let content = 0;
    let width = 0;
    Object.defineProperty(box, 'scrollWidth', {
      configurable: true,
      get: () => {
        reads++;
        return content;
      },
    });
    Object.defineProperty(box, 'clientWidth', { configurable: true, get: () => width });
    /** Lays the content out `wide` px wide in a box `boxWidth` px wide, and tells no one. */
    const layOutSilently = (wide: number, boxWidth: number) => {
      content = wide;
      width = boxWidth;
    };
    /** The same, as a browser does when the box itself changes size. */
    const layOut = (wide: number, boxWidth: number) => {
      layOutSilently(wide, boxWidth);
      FakeResizeObserver.last!.report();
      TestBed.tick();
    };
    /** Delivers what changed in the page, then draws the next frame. */
    const nextFrame = async () => {
      await Promise.resolve();
      const due = [...frames.values()];
      frames.clear();
      for (const callback of due) callback(0);
      TestBed.tick();
    };
    return { fixture, box, layOut, layOutSilently, nextFrame, reads: () => reads };
  }

  it('makes a box whose content is wider than itself a named tab stop', () => {
    const { box, layOut } = render();

    layOut(480, 160);

    expect(box.getAttribute('tabindex')).toBe('0');
    expect(box.getAttribute('role')).toBe('region');
    expect(box.getAttribute('aria-label')).toBe('Positions');
  });

  // A tab stop on every part of every message would double the walk through the list.
  it('leaves a box that fits out of the tab order and unnamed', () => {
    const { box, layOut } = render();

    layOut(160, 160);

    expect(box.hasAttribute('tabindex')).toBe(false);
    expect(box.hasAttribute('role')).toBe(false);
    expect(box.hasAttribute('aria-label')).toBe(false);
  });

  // A bordered position table overhangs its box by a fraction of a pixel, which scrollWidth
  // rounds up to a whole one: nothing is hidden, and a tab stop there leads nowhere.
  it('counts a box that is wider by a rounded pixel as fitting', () => {
    const { box, layOut } = render();

    layOut(233, 232);

    expect(box.hasAttribute('tabindex')).toBe(false);
  });

  it('follows the box when the screen changes width', () => {
    const { box, layOut } = render();

    layOut(480, 160);
    expect(box.getAttribute('tabindex')).toBe('0');

    layOut(480, 660);
    expect(box.hasAttribute('tabindex')).toBe(false);
  });

  // A language switch keeps the card and puts the other language's table into the same box: the
  // box keeps its size, so nothing but its content says it scrolls now.
  it('follows the content when it becomes wider than a box that keeps its size', async () => {
    const { fixture, box, layOut, layOutSilently, nextFrame } = render();
    layOut(160, 160);

    fixture.componentInstance.text.set('55° 41,123′N · 012° 31,567′E · 55° 42,000′N');
    TestBed.tick();
    layOutSilently(480, 160);
    await nextFrame();

    expect(box.getAttribute('tabindex')).toBe('0');
    expect(box.getAttribute('role')).toBe('region');
    expect(box.getAttribute('aria-label')).toBe('Positions');
  });

  it('follows the content when it fits a box that keeps its size again', async () => {
    const { fixture, box, layOut, layOutSilently, nextFrame } = render();
    layOut(480, 160);

    fixture.componentInstance.kind.set('narrow');
    TestBed.tick();
    layOutSilently(160, 160);
    await nextFrame();

    expect(box.hasAttribute('tabindex')).toBe(false);
    expect(box.hasAttribute('role')).toBe(false);
    expect(box.hasAttribute('aria-label')).toBe(false);
  });

  it('measures the content again once an image inside it has loaded', async () => {
    const { box, layOut, layOutSilently, nextFrame } = render();
    layOut(160, 160);

    layOutSilently(480, 160);
    box.querySelector('img')!.dispatchEvent(new Event('load'));
    await nextFrame();

    expect(box.getAttribute('tabindex')).toBe('0');
  });

  it('measures once for every change that lands in one frame', async () => {
    const { fixture, layOut, nextFrame, reads } = render();
    layOut(160, 160);
    const before = reads();

    fixture.componentInstance.text.set('a longer text');
    fixture.componentInstance.kind.set('wide');
    TestBed.tick();
    fixture.componentInstance.text.set('a longer text still');
    TestBed.tick();
    await nextFrame();

    expect(reads() - before).toBe(1);
  });

  // Its own tab stop, role and name change nothing inside the box.
  it('does not measure again for the attributes it sets itself', async () => {
    const { layOut, nextFrame, reads } = render();

    layOut(480, 160);
    const before = reads();
    await nextFrame();

    expect(reads()).toBe(before);
  });

  // A box that scrolls only sideways is not made a stop by content taller than itself: the
  // message parts grow with their content, and only a box that says it scrolls down counts it.
  it('leaves content taller than the box alone unless the box scrolls down', () => {
    const { fixture, box, layOut } = render();
    Object.defineProperty(box, 'scrollHeight', { configurable: true, get: () => 900 });
    Object.defineProperty(box, 'clientHeight', { configurable: true, get: () => 300 });

    layOut(160, 160);
    expect(box.hasAttribute('tabindex')).toBe(false);

    fixture.componentInstance.down.set(true);
    TestBed.tick();
    layOut(160, 160);
    expect(box.getAttribute('tabindex')).toBe('0');
    expect(box.getAttribute('role')).toBe('region');
    expect(box.getAttribute('aria-label')).toBe('Positions');
  });

  it('counts a box that scrolls down as fitting while its content is no taller', () => {
    const { fixture, box, layOut } = render();
    fixture.componentInstance.down.set(true);
    TestBed.tick();
    Object.defineProperty(box, 'scrollHeight', { configurable: true, get: () => 301 });
    Object.defineProperty(box, 'clientHeight', { configurable: true, get: () => 300 });

    layOut(160, 160);

    expect(box.hasAttribute('tabindex')).toBe(false);
  });

  it('renames the region when its label changes', () => {
    const { fixture, box, layOut } = render();
    layOut(480, 160);

    fixture.componentInstance.name.set('Positioner');
    TestBed.tick();

    expect(box.getAttribute('aria-label')).toBe('Positioner');
  });

  it('stops observing when the box goes', async () => {
    const { fixture, box, nextFrame, reads } = render();
    const observer = FakeResizeObserver.last!;
    fixture.componentInstance.text.set('a change whose frame is still to come');
    TestBed.tick();
    await Promise.resolve();

    fixture.destroy();
    box.querySelector('span')!.textContent = 'a change after the box went';
    box.querySelector('img')!.dispatchEvent(new Event('load'));
    await nextFrame();

    expect(observer.disconnected).toBe(true);
    expect(frames.size).toBe(0);
    expect(reads()).toBe(0);
  });
});
