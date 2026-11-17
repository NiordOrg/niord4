import { Component, computed, inject } from '@angular/core';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { formatLatLon } from '../../../../../shared/utils/coordinate-format';
import { MAP_EXTENT_TOKEN } from '../../../tokens/map-extent.token';

@Component({
  selector: 'app-map-filter-panel',
  templateUrl: './map-filter-panel.html',
  imports: [FilterPanel],
})
export class MapFilterPanel {
  private readonly extent = inject(MAP_EXTENT_TOKEN);
  private readonly language = inject(LanguageService);

  // extent tuple is [minLon, minLat, maxLon, maxLat] in EPSG:4326 degrees.
  readonly bottomLeft = computed(() => {
    const e = this.extent()?.extent;
    if (!e) return '—';
    return formatLatLon(e[1], e[0], { decimals: 2, locale: this.language.activeLocale() });
  });

  readonly topRight = computed(() => {
    const e = this.extent()?.extent;
    if (!e) return '—';
    return formatLatLon(e[3], e[2], { decimals: 2, locale: this.language.activeLocale() });
  });
}
