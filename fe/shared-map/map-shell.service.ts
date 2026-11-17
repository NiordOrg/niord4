import { Injectable, Signal, signal } from '@angular/core';
import type OlMap from 'ol/Map';
import type BaseLayer from 'ol/layer/Base';

@Injectable()
export class MapShellService {
  private readonly _map = signal<OlMap | undefined>(undefined);
  private readonly _layers = signal<readonly BaseLayer[]>([]);

  readonly map: Signal<OlMap | undefined> = this._map.asReadonly();
  readonly layers: Signal<readonly BaseLayer[]> = this._layers.asReadonly();

  setMap(map: OlMap): void {
    this._map.set(map);
    // Content-projected layers register during their own constructor effects, which
    // run before the parent OlMap's ngAfterViewInit. Flush the queue now so layers
    // that registered pre-mount actually attach to the map.
    const attached = map.getLayers().getArray();
    for (const layer of this._layers()) {
      if (!attached.includes(layer)) {
        map.addLayer(layer);
      }
    }
  }

  clearMap(): void {
    this._map.set(undefined);
    this._layers.set([]);
  }

  registerLayer(layer: BaseLayer): void {
    const map = this._map();
    if (map && !map.getLayers().getArray().includes(layer)) {
      map.addLayer(layer);
    }
    this._layers.update((prev) => (prev.includes(layer) ? prev : [...prev, layer]));
  }

  unregisterLayer(layer: BaseLayer): void {
    const map = this._map();
    if (map && map.getLayers().getArray().includes(layer)) {
      map.removeLayer(layer);
    }
    this._layers.update((prev) => prev.filter((l) => l !== layer));
  }
}
