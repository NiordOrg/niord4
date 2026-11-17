// The example country's deployments: the app, the API and Keycloak share one origin, so every
// address is origin-relative — /rest, /auth and the backend's WMS proxy /wms/ — which set-up
// checks. The map opens on the example's waters.
import type { AppConfig } from '@niord/admin/api';

export const environment: AppConfig = {
  apiBaseUrl: '/rest',
  appName: 'Niord',
  documentationUrl: 'https://docs.niord.org',
  keycloak: {
    url: '/auth',
    realm: 'niord',
    clientId: 'niord-web',
  },
  map: {
    openSeaMapLayerEnabled: true,
    wmsUrl: '/wms/',
    mapDefaultLatitude: 56,
    mapDefaultLongitude: 11,
    mapDefaultZoomLevel: 6,
  },
  production: true,
};
