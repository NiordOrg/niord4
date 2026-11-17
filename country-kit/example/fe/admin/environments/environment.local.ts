// The example country on the local stack: /rest through the dev server's proxy, Keycloak at
// localhost:8080, where the local realm's issuer is fixed.
import type { AppConfig } from '@niord/admin/api';

export const environment: AppConfig = {
  apiBaseUrl: '/rest',
  appName: 'Niord',
  documentationUrl: 'https://docs.niord.org',
  keycloak: {
    url: 'http://localhost:8080/auth',
    realm: 'niord',
    clientId: 'niord-web',
  },
  map: {
    openSeaMapLayerEnabled: true,
    mapDefaultLatitude: 56,
    mapDefaultLongitude: 11,
    mapDefaultZoomLevel: 6,
  },
  mode: 'test',
  production: false,
};
