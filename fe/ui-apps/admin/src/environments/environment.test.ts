// The base product's test build: a deployment whose app, API and Keycloak share one origin,
// as in production (environment.production.ts), with the TEST badge and the dev routes. Both
// addresses are origin-relative, so the build serves any test deployment. No WMS layer and no
// map centre: the base product knows no country, so the map opens on a world view.
import { AppConfig } from '../app/core/config/app-config.interface';

export const environment: AppConfig = {
  apiBaseUrl: '/rest',
  appName: 'Niord',
  // Fallback for the backend `documentationUrl` web setting (DB wins at runtime).
  documentationUrl: 'https://docs.niord.org',
  keycloak: {
    url: '/auth',
    realm: 'niord',
    clientId: 'niord-web',
  },
  map: {
    openSeaMapLayerEnabled: true,
  },
  // Fallback for the backend `mode` web setting (DB wins at runtime).
  mode: 'test',
  production: false,
};
