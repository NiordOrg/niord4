import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import type BaseLayer from 'ol/layer/Base';
import type { EventsKey } from 'ol/events';
import { unByKey } from 'ol/Observable';

import { MapShellService } from './map-shell.service';

interface SwitcherEntry {
  readonly layer: BaseLayer;
  readonly name: string;
  readonly visible: boolean;
  readonly order: number;
}

// Layers without an explicit `switcherOrder` set on the OL layer fall to this
// default — picked so they sort AFTER the explicitly-prioritized ones (WMS at
// 10, OpenSeaMap at 20) but stay in template-declaration order amongst
// themselves (Array.prototype.sort is stable, so ties preserve original order).
const DEFAULT_SWITCHER_ORDER = 100;

@Component({
  selector: 'app-ol-layer-switcher',
  template: `
    @if (entries().length > 0) {
      @if (title()) {
        <h4 class="mb-1 text-xs font-semibold uppercase tracking-wide">{{ title() }}</h4>
      }
      <ul class="flex flex-col gap-1">
        @for (entry of entries(); track entry.layer) {
          <li>
            <label class="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                [checked]="entry.visible"
                (change)="onToggle(entry.layer, $event)"
              />
              <span>{{ entry.name }}</span>
            </label>
          </li>
        }
      </ul>
    }
  `,
  // Visual styling only — positioning is owned by whichever host embeds the
  // switcher, which places it where its own map layout has room.
  host: {
    class:
      'ol-layer-switcher rounded border border-surface-200 bg-surface-0/95 p-2 text-sm shadow dark:border-surface-700 dark:bg-surface-900/95 dark:text-surface-50',
    '[class.hidden]': 'entries().length === 0',
  },
})
export class OlLayerSwitcher {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The panel heading, rendered verbatim. The words belong to the host application, which owns
   * its own translations; required so a host that forgets one fails the build instead of
   * quietly losing the heading, and `''` asks for a panel with none.
   */
  readonly title = input.required<string>();

  // A version counter bumped on every per-layer 'propertychange' so the
  // computed entries() re-evaluates when showInLayerSwitcher / name / visible
  // flips on a layer outside of input changes.
  private readonly layerVersion = signal(0);
  private layerKeys: EventsKey[] = [];

  readonly entries = computed<readonly SwitcherEntry[]>(() => {
    this.layerVersion();
    const mapped = this.shell
      .layers()
      .filter((layer) => layer.get('showInLayerSwitcher') === true)
      .map<SwitcherEntry>((layer) => ({
        layer,
        name: (layer.get('name') as string) ?? '',
        visible: layer.getVisible(),
        order: (layer.get('switcherOrder') as number | undefined) ?? DEFAULT_SWITCHER_ORDER,
      }));
    // Stable sort by `switcherOrder` decouples switcher-list order from the
    // OL render stack: WMS (10) appears at the top of the panel without
    // moving in the template (which would push it under OSM and hide it).
    // Tied orders keep template order — `mapped` already reflects that.
    return [...mapped].sort((a, b) => a.order - b.order);
  });

  constructor() {
    effect(() => {
      const layers = this.shell.layers();
      this.unsubscribeLayerEvents();
      for (const layer of layers) {
        const key = layer.on('propertychange', () => this.layerVersion.update((v) => v + 1));
        this.layerKeys.push(key);
      }
    });

    this.destroyRef.onDestroy(() => this.unsubscribeLayerEvents());
  }

  onToggle(layer: BaseLayer, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    layer.setVisible(checked);
    this.layerVersion.update((v) => v + 1);
  }

  private unsubscribeLayerEvents(): void {
    for (const key of this.layerKeys) {
      unByKey(key);
    }
    this.layerKeys = [];
  }
}
