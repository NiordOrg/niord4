// The example country's test deployments: as production, on the deployment's own origin, with
// the TEST badge.
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
    mapDefaultLatitude: 56,
    mapDefaultLongitude: 11,
    mapDefaultZoomLevel: 6,
  },
  mode: 'test',
  production: false,
};
