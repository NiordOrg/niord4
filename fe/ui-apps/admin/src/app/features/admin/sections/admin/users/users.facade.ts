import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, filter, map, of, startWith, switchMap, take } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  MailingList,
  MailingListsApiService,
} from '../../../services/api/mailing-lists-api.service';
import { Group, User, UsersApiService } from '../../../services/api/users-api.service';

/**
 * Upper bound for the single all-users fetch. The Keycloak search has no total
 * count, so — like the other client-paged admin sections (charts/sources/…) —
 * the whole list is loaded once and paged client-side. This realm's user count
 * is modest (operators, not end-users), so one fetch is fine.
 */
const MAX_USERS = 10000;

interface ListState<T> {
  readonly items: readonly T[];
  readonly loading: boolean;
  readonly error: boolean;
}

/**
 * Page-scoped state for the Users & Contacts section's USER side: the full
 * Keycloak user list (loaded once, then filtered + paged CLIENT-side by the
 * tab — the same numbered-pager pattern as every other admin section), the
 * read-only Keycloak group tree (domain-scoped server-side, loaded once), and
 * the all-mailing-lists catalog for the membership panel (lang-keyed — its
 * descs are localized server-side, so a language switch refetches).
 */
@Injectable()
export class UsersFacade {
  private readonly api = inject(UsersApiService);
  private readonly mailingListsApi = inject(MailingListsApiService);
  private readonly language = inject(LanguageService);

  /**
   * The user-side fetches (kc-users + the group tree) hit Keycloak-proxy
   * endpoints an admin WITHOUT `manage-users` may not call, so they are started
   * only from behind that gate. The gated Users tab activates this side when it
   * mounts; the mailing-lists catalog below stays
   * eager (the Contacts tab's membership panel needs it regardless).
   */
  private readonly usersSideActive = signal(false);

  /** Idempotent — called by the Users tab when it mounts (inside the gate). */
  activateUsersSide(): void {
    if (this.usersSideActive()) return;
    this.usersSideActive.set(true);
    this.loadUsers();
  }

  private readonly usersState = signal<ListState<User>>({
    items: [],
    loading: true,
    error: false,
  });

  readonly users = computed(() => this.usersState().items);
  readonly usersLoading = computed(() => this.usersState().loading);
  readonly usersError = computed(() => this.usersState().error);

  /** Monotonic id so an out-of-order response from a superseded refresh can't clobber a newer one. */
  private usersRequestId = 0;

  /** (Re)load the full user list — initial load + post-mutation refresh. */
  loadUsers(): void {
    const requestId = ++this.usersRequestId;
    this.usersState.update((s) => ({ ...s, loading: true, error: false }));
    this.api.search('', 0, MAX_USERS).subscribe({
      next: (users) => {
        if (requestId !== this.usersRequestId) return;
        this.usersState.set({ items: users, loading: false, error: false });
      },
      error: () => {
        if (requestId !== this.usersRequestId) return;
        // A failed load shows the error state (never a silent empty list).
        this.usersState.set({ items: [], loading: false, error: true });
      },
    });
  }

  // --- The Keycloak group tree (read-only, domain-scoped server-side) -------

  private readonly groupsState = toSignal(
    toObservable(this.usersSideActive).pipe(
      filter(Boolean),
      take(1),
      switchMap(() =>
        this.api.getGroups().pipe(
          map((groups): ListState<Group> => ({ items: groups, loading: false, error: false })),
          catchError(() => of<ListState<Group>>({ items: [], loading: false, error: true })),
          startWith<ListState<Group>>({ items: [], loading: true, error: false }),
        ),
      ),
    ),
    { initialValue: { items: [], loading: true, error: false } as ListState<Group> },
  );

  readonly groups = computed(() => this.groupsState().items);
  readonly groupsError = computed(() => this.groupsState().error);

  // --- The all-mailing-lists catalog (lang-keyed; membership panel rows) ----

  private readonly listsState = toSignal(
    toObservable(this.language.selectedLanguage).pipe(
      switchMap((lang) =>
        this.mailingListsApi.getAll(lang).pipe(
          map((items): ListState<MailingList> => ({ items, loading: false, error: false })),
          catchError(() => of<ListState<MailingList>>({ items: [], loading: false, error: true })),
          startWith<ListState<MailingList>>({ items: [], loading: true, error: false }),
        ),
      ),
    ),
    { initialValue: { items: [], loading: true, error: false } as ListState<MailingList> },
  );

  readonly mailingLists = computed(() => this.listsState().items);
  readonly mailingListsError = computed(() => this.listsState().error);
}
