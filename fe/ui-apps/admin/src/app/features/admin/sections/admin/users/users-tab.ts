import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { KEYCLOAK } from '../../../../../core/auth/services/keycloak.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Icon } from '../../../../../shared/icons/icon';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { User, UsersApiService } from '../../../services/api/users-api.service';
import { createClientPager } from '../../../utils/client-pager';
import { keycloakConsoleUrl } from '../../../utils/keycloak-console-url';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { UserEditor, UserEditorModel } from './user-editor';
import { UsersFacade } from './users.facade';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/**
 * The Users tab: Keycloak user management via the backend proxy. The full user
 * list is loaded once and filtered + paged CLIENT-side (the standard admin
 * numbered-pager pattern). The toolbar carries the standalone external
 * "Keycloak…" console link (no cog menu — design canon).
 */
@Component({
  selector: 'app-users-tab',
  imports: [
    LanguageFlag,
    Icon,
    Pager,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    UserEditor,
    TranslocoPipe,
  ],
  templateUrl: './users-tab.html',
  host: { class: 'flex min-w-0 flex-col' },
})
export class UsersTab {
  private readonly languageSettings = inject(LanguageService);
  protected readonly facade = inject(UsersFacade);
  private readonly api = inject(UsersApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly keycloak = inject(KEYCLOAK);

  protected readonly filter = signal('');
  protected readonly editorModel = signal<UserEditorModel | undefined>(undefined);

  /** Client filter over username / email / name — case-insensitive substring. */
  protected readonly filtered = computed(() => {
    const term = this.filter().trim().toLowerCase();
    const users = this.facade.users();
    if (!term) return users;
    return users.filter(
      (user) =>
        user.username.toLowerCase().includes(term) ||
        user.email.toLowerCase().includes(term) ||
        this.fullName(user).toLowerCase().includes(term),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  /** The external Keycloak admin-console link (URL from the auth server config). */
  protected readonly keycloakConsoleUrl = this.buildKeycloakConsoleUrl();

  constructor() {
    // This tab only mounts INSIDE the manage-users gate — activating the
    // user-side fetches here keeps ungated admins off the Keycloak proxy.
    this.facade.activateUsersSide();
  }

  protected onSearch(value: string): void {
    this.filter.set(value);
    this.pager.reset();
  }

  protected fullName(user: User): string {
    return [user.firstName, user.lastName].filter(Boolean).join(' ');
  }

  protected flagPathFor(lang: string): string | undefined {
    return this.languageSettings.modelLanguages.find((language) => language.value === lang)
      ?.flagPath;
  }

  protected languageLabelFor(lang: string): string {
    return (
      this.languageSettings.modelLanguages.find((language) => language.value === lang)?.label ??
      lang
    );
  }

  protected addUser(): void {
    // Add defaults: everything blank, the three required-actions unset.
    this.editorModel.set({
      mode: 'add',
      user: { keycloakId: '', username: '', email: '' },
    });
  }

  protected editUser(user: User): void {
    // The search row carries the full record — no details fetch;
    // the editor deep-copies via the form and loads groups/memberships itself.
    this.editorModel.set({ mode: 'edit', user });
  }

  protected deleteUser(user: User): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.users.confirmDelete.header'),
      message: this.transloco.translate('admin.users.confirmDelete.message', {
        username: user.username,
      }),
      accept: () => {
        this.api.delete(user.keycloakId).subscribe({
          next: () => {
            this.notifications.success('admin.users.toast.deleted');
            this.facade.loadUsers();
          },
          error: () => this.notifications.error('admin.users.toast.deleteError'),
        });
      },
    });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.loadUsers();
  }

  private buildKeycloakConsoleUrl(): string {
    // The URL/realm come from the live Keycloak adapter (never a hardcoded
    // realm name in the console path); the join is shared.
    return keycloakConsoleUrl({
      url: this.keycloak.authServerUrl ?? '',
      realm: this.keycloak.realm ?? 'niord',
    });
  }
}
