// A deployment: the app, the API and Keycloak share one origin. The deployment's
// proxy routes /rest to the backend and /auth to Keycloak, and serves the app
// everywhere else. Both addresses are origin-relative, so one build serves every
// environment of a country, and the backend needs no CORS setup.
import { AppConfig } from '../app/core/config/app-config.interface';

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
  },
  production: true,
};
