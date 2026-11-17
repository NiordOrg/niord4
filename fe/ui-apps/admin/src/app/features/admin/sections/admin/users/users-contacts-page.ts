import { Component, inject, linkedSignal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import { Icon } from '../../../../../shared/icons/icon';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ContactsTab } from './contacts-tab';
import { UsersFacade } from './users.facade';
import { UsersTab } from './users-tab';

/**
 * Users & Contacts section: two page-local tabs (no URL state — a reload lands
 * on the first tab the user's roles allow). The Users tab additionally
 * requires the Keycloak `manage-users` client role on `realm-management`
 * (`AuthFacade.canManageUsers()` — a verbatim client-role gate with no realm
 * or domain fallback); without it only Contacts shows.
 */
@Component({
  selector: 'app-users-contacts-page',
  imports: [
    AdminSectionPage,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    Confirm,
    UsersTab,
    ContactsTab,
    TranslocoPipe,
    Icon,
  ],
  providers: [UsersFacade, ConfirmService],
  template: `
    <app-admin-section-page
      titleKey="admin.sections.users.label"
      descriptionKey="admin.users.intro"
    >
      <app-tabs [value]="activeTab()" (valueChange)="activeTab.set($any($event))">
        <app-tab-list>
          @if (canManageUsers()) {
            <app-tab value="users">
              <app-icon name="lucideUser" class="mr-1 text-xs" />
              {{ 'admin.users.tab.users' | transloco }}
            </app-tab>
          }
          <app-tab value="contacts">
            <app-icon name="lucideMail" class="mr-1 text-xs" />
            {{ 'admin.users.tab.contacts' | transloco }}
          </app-tab>
        </app-tab-list>
        <app-tab-panels>
          @if (canManageUsers()) {
            <app-tab-panel value="users">
              <app-users-tab />
            </app-tab-panel>
          }
          <app-tab-panel value="contacts">
            <app-contacts-tab />
          </app-tab-panel>
        </app-tab-panels>
      </app-tabs>

      <app-confirm />
    </app-admin-section-page>
  `,
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class UsersContactsPage {
  private readonly auth = inject(AuthFacade);

  protected readonly canManageUsers = this.auth.canManageUsers;

  /** Page-local tab state (no URL); re-defaults if the gate flips mid-session. */
  protected readonly activeTab = linkedSignal<'users' | 'contacts'>(() =>
    this.canManageUsers() ? 'users' : 'contacts',
  );
}
