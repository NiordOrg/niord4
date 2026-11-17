import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import { Message } from '../../../../core/models/message.model';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../../../testing/test-config';
import { MessageMapTooltip } from './message-map-tooltip';

const MESSAGES: readonly Message[] = [
  { id: 'm1', mainType: 'NW', shortId: 'NW-1' },
  { id: 'm2', mainType: 'NW', shortId: 'NW-2' },
  { id: 'm3', mainType: 'NW', shortId: 'NW-3' },
  { id: 'm4', mainType: 'NW', shortId: 'NW-4' },
];

@Component({
  template: `<app-message-map-tooltip />`,
  imports: [MessageMapTooltip],
  providers: [MapShellService],
})
class HostComponent {
  readonly tooltip = viewChild.required(MessageMapTooltip);
}

function pointerMove(map: OlMapImpl, pixel: [number, number]): void {
  map.dispatchEvent({ type: 'pointermove', pixel, coordinate: [0, 0], dragging: false } as never);
}

describe('MessageMapTooltip', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;
  let map: OlMapImpl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [provideTestNiordConfig()],
    });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    map = new OlMapImpl({});
    shell.setMap(map);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  function rows(): number {
    return fixture.nativeElement.querySelectorAll('app-message-id-badge').length;
  }

  it('shows nothing until the pointer rests on something', () => {
    expect(fixture.componentInstance.tooltip().state()).toBeUndefined();
    expect(rows()).toBe(0);
  });

  it('names three messages and counts the rest', () => {
    fixture.componentInstance.tooltip().setResolver(() => MESSAGES);
    pointerMove(map, [100, 200]);
    fixture.detectChanges();

    expect(rows()).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('NW-1');
    expect(fixture.nativeElement.textContent).toContain('and 1 more messages');
  });

  it('leaves the cursor clear of what it names', () => {
    fixture.componentInstance.tooltip().setResolver(() => MESSAGES.slice(0, 1));
    pointerMove(map, [100, 200]);
    fixture.detectChanges();

    const panel = fixture.nativeElement.querySelector('div');
    expect(panel.style.left).toBe('100px');
    expect(panel.style.top).toBe('185px');
  });

  // A message with no English text arrives in Danish on an English page; its title in the
  // tooltip says so, and one in the page's own language says nothing.
  it('marks a title that is not in the language of the page', () => {
    fixture.componentInstance.tooltip().setResolver(() => [
      { ...MESSAGES[0], descs: [{ lang: 'da', title: 'Skydeøvelser' }] },
      { ...MESSAGES[1], descs: [{ lang: 'en', title: 'Firing' }] },
    ]);
    pointerMove(map, [100, 200]);
    fixture.detectChanges();

    const titles = [...(fixture.nativeElement as HTMLElement).querySelectorAll('strong')].map(
      (title) => [title.textContent!.trim(), title.getAttribute('lang')],
    );
    expect(titles).toEqual([
      ['Skydeøvelser', 'da'],
      ['Firing', null],
    ]);
  });

  it('goes away while the reader drags the map', () => {
    fixture.componentInstance.tooltip().setResolver(() => MESSAGES);
    pointerMove(map, [10, 20]);
    fixture.detectChanges();
    expect(rows()).toBe(3);

    map.dispatchEvent({ type: 'pointerdrag', pixel: [10, 20], coordinate: [0, 0] } as never);
    fixture.detectChanges();

    expect(rows()).toBe(0);
  });

  it('reads the map at most thirty times a second', () => {
    let resolved = 0;
    fixture.componentInstance.tooltip().setResolver(() => {
      resolved++;
      return MESSAGES;
    });

    pointerMove(map, [10, 20]);
    pointerMove(map, [11, 21]);

    expect(resolved).toBe(1);
  });

  describe('where the pointer comes to rest', () => {
    beforeEach(() => {
      vi.useFakeTimers({
        toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'],
      });
    });

    afterEach(() => vi.useRealTimers());

    // A pointer that stops within the throttle interval of its previous move must still get the
    // tooltip of where it stopped, not of a spot a few pixels back.
    it('names what is under the place the pointer stopped, not a spot it passed', () => {
      const tooltip = fixture.componentInstance.tooltip();
      tooltip.setResolver(([x]) => (x >= 100 ? MESSAGES.slice(0, 1) : []));

      pointerMove(map, [60, 200]);
      pointerMove(map, [100, 200]);
      fixture.detectChanges();
      expect(rows()).toBe(0);

      vi.advanceTimersByTime(100);
      fixture.detectChanges();

      expect(rows()).toBe(1);
      expect(tooltip.state()?.left).toBe(100);
    });

    it('goes away when the pointer leaves the map', () => {
      fixture.componentInstance.tooltip().setResolver(() => MESSAGES);
      pointerMove(map, [10, 20]);
      fixture.detectChanges();
      expect(rows()).toBe(3);

      map.getViewport().dispatchEvent(new Event('pointerleave'));
      fixture.detectChanges();

      expect(rows()).toBe(0);
    });
  });
});
