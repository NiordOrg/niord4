import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../models/message.model';
import { MessageMapTooltip } from './map-tooltip';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

const SAMPLE_MESSAGES: readonly Message[] = [
  { id: 'm1', shortId: 'NW-1' },
  { id: 'm2', shortId: 'NW-2' },
  { id: 'm3', shortId: 'NW-3' },
  { id: 'm4', shortId: 'NW-4' },
];

@Component({
  template: `<app-message-map-tooltip />`,
  imports: [MessageMapTooltip],
  providers: [MapShellService],
})
class HostComponent {
  readonly tooltip = viewChild.required(MessageMapTooltip);
}

describe('MessageMapTooltip', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('starts hidden', () => {
    expect(fixture.componentInstance.tooltip().state()).toBeUndefined();
  });

  it('shows up to 3 messages when pointer hovers a populated pixel', () => {
    const tooltip = fixture.componentInstance.tooltip();
    tooltip.setResolver(() => SAMPLE_MESSAGES);
    const map = shell.map();
    expect(map).toBeDefined();
    map!.dispatchEvent({
      type: 'pointermove',
      pixel: [100, 200],
      coordinate: [0, 0],
      dragging: false,
    } as never);
    const state = tooltip.state();
    expect(state).toBeDefined();
    expect(state!.messages).toHaveLength(3);
    expect(state!.more).toBe(1);
  });

  it('hides when the resolver returns no messages', () => {
    const tooltip = fixture.componentInstance.tooltip();
    tooltip.setResolver(() => []);
    shell.map()!.dispatchEvent({
      type: 'pointermove',
      pixel: [100, 200],
      coordinate: [0, 0],
      dragging: false,
    } as never);
    expect(tooltip.state()).toBeUndefined();
  });

  it('hides during pointer drag', () => {
    const tooltip = fixture.componentInstance.tooltip();
    tooltip.setResolver(() => SAMPLE_MESSAGES);
    const map = shell.map()!;
    map.dispatchEvent({
      type: 'pointermove',
      pixel: [10, 20],
      coordinate: [0, 0],
      dragging: false,
    } as never);
    expect(tooltip.state()).toBeDefined();
    map.dispatchEvent({
      type: 'pointerdrag',
      pixel: [10, 20],
      coordinate: [0, 0],
      dragging: true,
    } as never);
    expect(tooltip.state()).toBeUndefined();
  });

  describe('where the pointer comes to rest', () => {
    beforeEach(() => {
      vi.useFakeTimers({
        toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'],
      });
    });

    afterEach(() => vi.useRealTimers());

    function move(pixel: [number, number]): void {
      shell.map()!.dispatchEvent({
        type: 'pointermove',
        pixel,
        coordinate: [0, 0],
        dragging: false,
      } as never);
    }

    it('shows the messages under the place the pointer stopped, not a spot it passed', () => {
      const tooltip = fixture.componentInstance.tooltip();
      tooltip.setResolver(([x]) => (x >= 100 ? SAMPLE_MESSAGES.slice(0, 1) : []));

      move([60, 200]);
      move([100, 200]);
      expect(tooltip.state()).toBeUndefined();

      vi.advanceTimersByTime(100);

      expect(tooltip.state()?.messages.map((m) => m.id)).toEqual(['m1']);
    });

    it('hides when the pointer leaves the map', () => {
      const tooltip = fixture.componentInstance.tooltip();
      tooltip.setResolver(() => SAMPLE_MESSAGES);
      move([10, 20]);
      expect(tooltip.state()).toBeDefined();

      shell.map()!.getViewport().dispatchEvent(new Event('pointerleave'));

      expect(tooltip.state()).toBeUndefined();
    });
  });
});
