import { Component, ElementRef, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { publishHeight } from './publish-height';
import { FakeResizeObserver } from '../../testing/fake-resize-observer';

const PROPERTY = '--publish-height-spec';

@Component({
  selector: 'app-publish-height-host',
  template: `<div #bar></div>`,
})
class PublishHeightHost {
  private readonly bar = viewChild.required<ElementRef<HTMLElement>>('bar');

  constructor() {
    publishHeight(this.bar, PROPERTY);
  }
}

describe('publishHeight', () => {
  const root = document.documentElement;

  afterEach(() => {
    vi.unstubAllGlobals();
    FakeResizeObserver.last = undefined;
    root.style.removeProperty(PROPERTY);
  });

  function render() {
    const fixture = TestBed.createComponent(PublishHeightHost);
    TestBed.tick();
    return fixture;
  }

  it('publishes the border-box height of the element on the document root', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    const fixture = render();
    const observer = FakeResizeObserver.last!;

    expect(observer.observed).toEqual([fixture.nativeElement.querySelector('div')]);
    // The border box, not the content box: a bar with padding or a border is taller than its
    // content, and whatever is placed under it has to clear all of it.
    observer.report({
      borderBoxSize: [{ blockSize: 81, inlineSize: 320 }],
      contentRect: { height: 79 } as DOMRectReadOnly,
    });
    expect(root.style.getPropertyValue(PROPERTY)).toBe('81px');

    // A bar that wraps to a second row says so.
    observer.report({
      borderBoxSize: [{ blockSize: 121, inlineSize: 320 }],
      contentRect: { height: 119 } as DOMRectReadOnly,
    });
    expect(root.style.getPropertyValue(PROPERTY)).toBe('121px');
  });

  it('takes the content rectangle where a browser reports no border box', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    render();

    FakeResizeObserver.last!.report({ contentRect: { height: 40 } as DOMRectReadOnly });
    expect(root.style.getPropertyValue(PROPERTY)).toBe('40px');
  });

  // A route without the bar must not be laid out against the height it had on the last route.
  it('removes the property and stops observing when the component goes', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    const fixture = render();
    const observer = FakeResizeObserver.last!;
    observer.report({ borderBoxSize: [{ blockSize: 40, inlineSize: 320 }] });

    fixture.destroy();

    expect(observer.disconnected).toBe(true);
    expect(root.style.getPropertyValue(PROPERTY)).toBe('');
  });

  it('publishes nothing, and does not fail, where there is no resize observer', () => {
    vi.stubGlobal('ResizeObserver', undefined);

    expect(() => render()).not.toThrow();
    expect(root.style.getPropertyValue(PROPERTY)).toBe('');
  });
});
