// The base product's default environment: the local stack (be/), as environment.development.ts.
//
// `apiBaseUrl` is origin-relative: the dev server proxies /rest to the backend on
// localhost (proxy.conf.mjs), so no CORS setup is needed on the backend. Keycloak is the
// docker-compose one, addressed directly: its realm's issuer is fixed at localhost:8080
// (realm `niord`, user sysadmin/sysadmin). No WMS layer and no map centre: the base product
// knows no country, so the map opens on a world view. A country's own environments are
// swapped in by its build configurations.
//
// No tile addresses here: the backend's `osmUrl` and `openSeaMapUrl` web settings supply them,
// and without those the map kit's defaults apply — OL's single-host tile.openstreetmap.org and
// the public OpenSeaMap server, both over https. Keep an address single-host: OSM deprecated the
// a/b/c subdomains and serves tiles over HTTP/2, whose multiplexing makes subdomain sharding
// pointless (and sharding breaks OSM's tile usage policy).
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
