import { Injectable, Signal, computed, signal } from '@angular/core';

import type { MapExtent } from '../models/messages-filter-params.model';

@Injectable({ providedIn: 'root' })
export class MapExtentService {
  private readonly _extent = signal<MapExtent | undefined>(undefined);

  readonly extent: Signal<MapExtent | undefined> = computed(() => this._extent());

  setFromMap(
    zoom: number,
    center: readonly [number, number],
    extent: readonly [number, number, number, number],
  ): void {
    this._extent.set({ zoom, center, extent });
  }

  setFromUrl(value: MapExtent | undefined): void {
    this._extent.set(value);
  }

  clear(): void {
    this._extent.set(undefined);
  }
}
