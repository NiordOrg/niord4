import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';

import { NotificationService } from '../../notifications/notification.service';
import type { AuthRole } from '../facades/auth.facade';
import { AuthFacade } from '../facades/auth.facade';

/**
 * Declarative role guard. Routes opt in via `data: { rolesRequired: ['editor', ...] }`.
 *
 * Authorization rules:
 *   - no rolesRequired → allow
 *   - has at least one required role → allow
 *   - authenticated + unauthorized → redirect to '/' with access-denied toast
 *   - anonymous + unauthorized → kick to Keycloak login with current URL as return target
 */
export const roleGuard: CanMatchFn = (route) => {
  const required = route.data?.['rolesRequired'] as readonly AuthRole[] | undefined;
  if (!required || required.length === 0) return true;

  const auth = inject(AuthFacade);
  if (auth.hasAnyRole(required)) return true;

  if (auth.isAuthenticated()) {
    inject(NotificationService).error('shared.auth.accessDenied');
    return inject(Router).createUrlTree(['/']);
  }

  auth.login(window.location.href);
  return false;
};
