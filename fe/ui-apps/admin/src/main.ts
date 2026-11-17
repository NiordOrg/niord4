// FIRST import on purpose: its module body opens the Keycloak/API connections before the rest of
// the bundle evaluates, so the handshakes overlap JS evaluation instead of following it.
import './app/core/config/boot-resource-hints.install';

import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { fetchKeycloakConfig, setRuntimeKeycloakConfig } from './app/core/config/web-settings';
import { environment } from './environments/environment';
import { enableWillReadFrequentlyHint } from './app/shared/canvas-readback-hint';

// Must run before OL's first map mounts so the hint applies to every 2D
// canvas OL creates. See the helper's docstring for the rationale.
enableWillReadFrequentlyHint();

// The deployment's Keycloak (its URL, the realm, the client id) comes from the backend,
// before bootstrap: the KEYCLOAK factory reads it when the injector is built. One small
// request on the warm connection boot-resource-hints opened, one round trip before the
// first paint; a backend without the endpoint (404) leaves the build's values in place,
// and an unreachable one is retried briefly (web-settings.ts, fetchKeycloakConfig).
fetchKeycloakConfig(environment.apiBaseUrl)
  .then(setRuntimeKeycloakConfig)
  .then(() => bootstrapApplication(App, appConfig))
  .catch((err) => console.error(err));
