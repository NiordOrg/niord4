import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { hashUrlTarget } from './app/routes/hash-url';

// A link written for the hash-routed site this one replaces is rewritten before the router reads
// the address. Here rather than in an inline script of index.html, so the Content-Security-Policy
// the image serves can allow the site's own script files and nothing else. A rewrite the browser
// refuses leaves the address as it was; the site starts either way.
const target = hashUrlTarget(location);
if (target !== null) {
  try {
    history.replaceState(null, '', target);
  } catch (err) {
    console.error(err);
  }
}

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
