import { InjectionToken, Signal, inject } from '@angular/core';

import type { MapExtent } from '../models/messages-filter-params.model';
import { MapExtentService } from '../services/map-extent.service';

export const MAP_EXTENT_TOKEN = new InjectionToken<Signal<MapExtent | undefined>>(
  'MAP_EXTENT_TOKEN',
  {
    providedIn: 'root',
    factory: () => inject(MapExtentService).extent,
  },
);
