import { type Signal, computed } from '@angular/core';

import type { AuthFacade } from '../../../core/auth/facades/auth.facade';
import type { MapConfig } from '../../../core/config/app-config.interface';

/**
 * Returns a Signal that's `true` when the WMS layer should appear in the
 * layer switcher: enabled via deployment config AND — for a protected WMS —
 * the user is authenticated (anonymous requests would 403 even with the
 * config flag on). Tracks `auth.isAuthenticated` so the checkbox appears
 * after a mid-session login without a refresh.
 */
export function wmsLayerVisibility(map: MapConfig | undefined, auth: AuthFacade): Signal<boolean> {
  return computed(() => {
    if (map?.wmsLayerEnabled !== true) return false;
    if (map.wmsProtected === true) return auth.isAuthenticated();
    return true;
  });
}
