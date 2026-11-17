import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

/**
 * Forwards the hash-routed message link `/#/message/{id}`, the form older mails
 * and tweets carry, to the shareable-message surface `/messages?messageId={id}`.
 *
 * Such a link reaches this application at its root, directly or through the
 * backend's root redirect to its `adminAppUrl` setting (the browser keeps the
 * fragment across a redirect). Under path routing it parses as path `/` +
 * fragment `/message/{id}`, so without this guard, on the home route, it would
 * land on the bare home page.
 */
export const legacyHashRedirectGuard: CanActivateFn = (route) => {
  const match = /^\/?message\/(.+)$/.exec(route.fragment ?? '');
  if (!match) {
    return true;
  }
  return inject(Router).createUrlTree(['/messages'], {
    queryParams: { messageId: decodeURIComponent(match[1]) },
  });
};
