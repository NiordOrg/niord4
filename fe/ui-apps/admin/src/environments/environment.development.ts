// The base product's development environment: the app on the dev server (npm start) against
// the local stack (be/: docker compose and the backend on localhost).
//
// `apiBaseUrl` is origin-relative: the dev server proxies /rest to the backend on
// localhost (proxy.conf.mjs), so no CORS setup is needed on the backend. Keycloak is the
// docker-compose one, addressed directly: its realm's issuer is fixed at localhost:8080
// (realm `niord`, user sysadmin/sysadmin). No WMS layer and no map centre: the base product
// knows no country, so the map opens on a world view. A country's own environments are
// swapped in by its build configurations.
import { AppConfig } from '../app/core/config/app-config.interface';

export const environment: AppConfig = {
  apiBaseUrl: '/rest',
  appName: 'Niord',
  // Fallback for the backend `documentationUrl` web setting (DB wins at runtime).
  documentationUrl: 'https://docs.niord.org',
  keycloak: {
    url: 'http://localhost:8080/auth',
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
