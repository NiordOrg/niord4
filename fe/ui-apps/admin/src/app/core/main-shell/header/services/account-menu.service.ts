import { computed, inject, Injectable, Signal } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../shared/models/menu-item.model';

import { AuthFacade } from '../../../auth/facades/auth.facade';

@Injectable({ providedIn: 'root' })
export class AccountMenuService {
  private readonly authFacade = inject(AuthFacade);
  private readonly loginLabel = translateSignal('menu.login');
  private readonly logoutLabel = translateSignal('menu.logout');
  private readonly accountLabel = translateSignal('shared.auth.account');

  // The display name. AuthService.userName resolves to the OIDC `name` claim
  // (full display name from Keycloak, e.g. "Jane Doe") and only falls
  // back to preferred_username when the IdP didn't populate `name`; when there is
  // no name at all it falls back to the translated "Account" label.
  private readonly userLabel = computed(() => this.authFacade.userName() ?? this.accountLabel());

  // "Edit <name>". translateSignal interpolates the reactive `{{name}}` param
  // (via the Signal<HashMap> params overload) and — unlike a synchronous
  // translate() — waits for the translation scope to load instead of logging a
  // missing-translation warning during app init. Re-resolves on language change
  // and whenever the name changes.
  private readonly editLabel = translateSignal(
    'shared.auth.editAccount',
    computed(() => ({ name: this.userLabel() })),
  );

  readonly menuItem: Signal<MenuItem> = computed(() => {
    if (!this.authFacade.isAuthenticated()) {
      return {
        label: this.loginLabel(),
        icon: 'lucideLogIn',
        command: () => this.authFacade.toggleAuthentication(),
      };
    }
    return {
      label: this.userLabel(),
      icon: 'lucideUser',
      items: [
        {
          label: this.editLabel(),
          icon: 'lucideSettings',
          command: () => this.authFacade.accountManagement(),
        },
        {
          label: this.logoutLabel(),
          icon: 'lucideLogOut',
          command: () => this.authFacade.toggleAuthentication(),
        },
      ],
    };
  });
}
