import { inject } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { AuthService } from '../auth/services/auth.service';
import { isNiordBackendRequest } from './backend-prefixes';
import { BACKGROUND_READ, BackgroundReadSkipped } from './background-read';
import { TICKETED_REQUEST } from './ticketed-request';

export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isNiordBackendRequest(req.url) || req.context.get(TICKETED_REQUEST)) {
    return next(req);
  }

  const auth = inject(AuthService);
  if (!auth.isAuthenticated()) {
    return next(req);
  }

  // A read the application makes on its own goes with the token as it is, or not at all: it
  // neither refreshes the token nor ends the session (see BACKGROUND_READ).
  if (req.context.get(BACKGROUND_READ)) {
    const token = auth.currentFreshToken();
    return token
      ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
      : throwError(() => new BackgroundReadSkipped());
  }

  // ensureFreshToken() handles its own refresh-failure fallback internally; here we
  // only need to react to 401 from the HTTP call itself.
  return from(auth.ensureFreshToken()).pipe(
    switchMap((token) => {
      const authed = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
      return next(authed).pipe(
        catchError((err: unknown) => {
          if (err instanceof HttpErrorResponse && err.status === 401) {
            // Backend rejected the token: drop to guest locally. NOT auth.logout() — its
            // full-page redirect loops when this 401 is the on-load bootstrap request.
            auth.sessionExpired();
          }
          return throwError(() => err);
        }),
      );
    }),
  );
};
