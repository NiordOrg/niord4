import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RowLinkAnchorDirective, RowLinkDirective } from './row-link.directive';

@Component({
  imports: [RowLinkAnchorDirective, RowLinkDirective],
  template: `
    <table>
      <tbody>
        <tr
          class="row-interactive"
          tabindex="-1"
          [appRowLink]="commands()"
          [rowLinkQueryParams]="{ messageId: 'NM-123' }"
          rowLinkQueryParamsHandling="merge"
          (appRowLinkActivate)="activations = activations + 1"
        >
          <td><a appRowLinkAnchor class="row-link">Title</a></td>
          <td class="body">plain cell</td>
          <td><button type="button" class="inner">inner</button></td>
        </tr>
      </tbody>
    </table>
  `,
})
class HostComponent {
  readonly commands = signal<readonly unknown[] | null>([]);
  activations = 0;
}

/** A row whose target is a route segment rather than a query param. */
@Component({
  imports: [RowLinkAnchorDirective, RowLinkDirective],
  template: `
    <table>
      <tbody>
        <tr [appRowLink]="['weekly-ntm']" (appRowLinkActivate)="activations = activations + 1">
          <td><a appRowLinkAnchor>Weekly</a></td>
        </tr>
      </tbody>
    </table>
  `,
})
class SegmentHostComponent {
  activations = 0;
}

let openSpy: ReturnType<typeof vi.spyOn>;
/** `defaultPrevented` as seen after the row's own handlers, one entry per click. */
let prevented: boolean[];

function setup<T>(component: new () => T) {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  const root = fixture.nativeElement as HTMLElement;
  prevented = [];
  // jsdom follows a real href on click and logs a navigation error, so the test
  // records what the directives decided and then swallows the default itself.
  root.addEventListener('click', (event) => {
    prevented.push(event.defaultPrevented);
    event.preventDefault();
  });
  return {
    fixture,
    host: fixture.componentInstance,
    root,
    el: (selector: string) => root.querySelector(selector) as HTMLElement,
    anchor: () => root.querySelector('a') as HTMLAnchorElement,
  };
}

function click(element: HTMLElement, init: MouseEventInit = {}): void {
  element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ...init }));
}

function middleClick(element: HTMLElement): void {
  element.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
}

beforeEach(() => {
  openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('RowLinkDirective — href', () => {
  it('serializes the commands and query params into the anchor href', () => {
    const { anchor } = setup(HostComponent);
    expect(anchor().getAttribute('href')).toContain('messageId=NM-123');
  });

  it('resolves a route segment relative to the row’s own route', () => {
    const { anchor } = setup(SegmentHostComponent);
    expect(anchor().getAttribute('href')).toBe('/weekly-ntm');
  });

  it('leaves a row with no target without an href', () => {
    const { host, fixture, anchor } = setup(HostComponent);
    host.commands.set(null);
    fixture.detectChanges();
    expect(anchor().getAttribute('href')).toBeNull();
  });
});

describe('RowLinkDirective — row body', () => {
  it('emits once on a plain click and navigates nothing by itself', () => {
    const { host, el } = setup(HostComponent);
    click(el('.body'));
    expect(host.activations).toBe(1);
    expect(openSpy).not.toHaveBeenCalled();
    expect(prevented).toEqual([false]);
  });

  it.each([
    ['ctrl', { ctrlKey: true }],
    ['meta', { metaKey: true }],
    ['shift', { shiftKey: true }],
  ])('opens the href in a new tab on a %s click, without activating', (_name, init) => {
    const { host, el, anchor } = setup(HostComponent);
    click(el('.body'), init);
    expect(openSpy).toHaveBeenCalledWith(anchor().getAttribute('href'), '_blank', 'noopener');
    expect(host.activations).toBe(0);
    expect(prevented).toEqual([true]);
  });

  it('opens the href in a new tab on a middle click, without activating', () => {
    const { host, el, anchor } = setup(HostComponent);
    middleClick(el('.body'));
    expect(openSpy).toHaveBeenCalledWith(anchor().getAttribute('href'), '_blank', 'noopener');
    expect(host.activations).toBe(0);
  });

  it('emits on Enter and on Space', () => {
    const { host, root } = setup(HostComponent);
    const row = root.querySelector('tr') as HTMLElement;
    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    row.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(host.activations).toBe(2);
  });

  it('does nothing at all for a click on an inner control', () => {
    const { host, el } = setup(HostComponent);
    click(el('.inner'));
    click(el('.inner'), { ctrlKey: true });
    middleClick(el('.inner'));
    expect(host.activations).toBe(0);
    expect(openSpy).not.toHaveBeenCalled();
  });

  it('treats a modified click as an ordinary activation where the row has no target', () => {
    const { host, fixture, el } = setup(HostComponent);
    host.commands.set(null);
    fixture.detectChanges();
    click(el('.body'), { ctrlKey: true });
    expect(host.activations).toBe(1);
    expect(openSpy).not.toHaveBeenCalled();
  });
});

describe('RowLinkAnchorDirective', () => {
  it('activates the row exactly once on a plain click, and suppresses its own navigation', () => {
    const { host, anchor } = setup(HostComponent);
    click(anchor());
    expect(host.activations).toBe(1);
    expect(openSpy).not.toHaveBeenCalled();
    expect(prevented).toEqual([true]);
  });

  it('leaves a modified click to the browser', () => {
    const { host, anchor } = setup(HostComponent);
    click(anchor(), { ctrlKey: true });
    expect(host.activations).toBe(0);
    expect(openSpy).not.toHaveBeenCalled();
    expect(prevented).toEqual([false]);
  });

  it('leaves a middle click to the browser', () => {
    const { host, anchor } = setup(HostComponent);
    middleClick(anchor());
    expect(host.activations).toBe(0);
    expect(openSpy).not.toHaveBeenCalled();
  });

  it('lets the anchor’s native Enter click activate the row once, not twice', () => {
    const { host, anchor } = setup(HostComponent);
    // Enter on a focused anchor produces a keydown AND a native click. The row
    // must ignore the key and act on the click alone.
    anchor().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(host.activations).toBe(0);
    click(anchor());
    expect(host.activations).toBe(1);
  });
});
