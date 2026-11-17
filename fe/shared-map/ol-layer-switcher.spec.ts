import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import TileLayer from 'ol/layer/Tile';
import OSM from 'ol/source/OSM';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from './map-shell.service';
import { OlLayerSwitcher } from './ol-layer-switcher';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-ol-layer-switcher [title]="heading()" />`,
  imports: [OlLayerSwitcher],
  providers: [MapShellService],
})
class HostComponent {
  readonly heading = signal('Layers');
}

describe('OlLayerSwitcher', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('lists only layers tagged showInLayerSwitcher=true', () => {
    const visibleLayer = new TileLayer({ source: new OSM() });
    visibleLayer.set('name', 'Layer A');
    visibleLayer.set('showInLayerSwitcher', true);
    const hiddenLayer = new TileLayer({ source: new OSM() });
    hiddenLayer.set('name', 'Layer B');
    hiddenLayer.set('showInLayerSwitcher', false);
    shell.registerLayer(visibleLayer);
    shell.registerLayer(hiddenLayer);
    fixture.detectChanges();
    const switcherText = fixture.nativeElement.textContent as string;
    expect(switcherText).toContain('Layer A');
    expect(switcherText).not.toContain('Layer B');
  });

  it('toggling the checkbox flips the layer visibility', () => {
    const layer = new TileLayer({ source: new OSM() });
    layer.set('name', 'OSM');
    layer.set('showInLayerSwitcher', true);
    layer.setVisible(true);
    shell.registerLayer(layer);
    fixture.detectChanges();
    const checkbox = fixture.nativeElement.querySelector(
      'input[type=checkbox]',
    ) as HTMLInputElement;
    expect(checkbox).toBeDefined();
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));
    expect(layer.getVisible()).toBe(false);
  });

  it('hides the panel host when no layer is tagged', () => {
    fixture.detectChanges();
    // Box styling lives on the host now; with no entries it gets the
    // `hidden` class (display: none) instead of being removed from the DOM.
    const host = fixture.nativeElement.querySelector('app-ol-layer-switcher');
    expect(host).not.toBeNull();
    expect(host.classList.contains('hidden')).toBe(true);
    expect(host.textContent.trim()).toBe('');
  });

  it('renders the heading the host passes, verbatim', () => {
    const layer = new TileLayer({ source: new OSM() });
    layer.set('showInLayerSwitcher', true);
    shell.registerLayer(layer);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h4').textContent.trim()).toBe('Layers');
  });

  it('omits the heading when the host passes an empty one', () => {
    const layer = new TileLayer({ source: new OSM() });
    layer.set('showInLayerSwitcher', true);
    shell.registerLayer(layer);
    fixture.componentInstance.heading.set('');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h4')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[type=checkbox]')).not.toBeNull();
  });
});
