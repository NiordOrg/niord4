import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
import { Observable } from 'rxjs';

import { Icon } from '../../../../shared/icons/icon';
import { Autocomplete } from '../../../../shared/forms/autocomplete/autocomplete';
import { AutocompleteSearchField } from '../../../../shared/forms/autocomplete-search-field';
import { AuthFacade } from '../../facades/auth.facade';
import {
  type DisplayUser,
  toDisplayUser,
  usernameStubDisplayUser,
} from '../../models/display-user.model';
import { UserDirectoryApiService } from '../../services/users-api.service';

@Component({
  selector: 'app-user-field',
  templateUrl: './user-field.html',
  imports: [Autocomplete, Icon, TranslocoPipe],
})
export class UserField
  extends AutocompleteSearchField<DisplayUser>
  implements FormValueControl<string | null>
{
  private readonly api = inject(UserDirectoryApiService);
  private readonly auth = inject(AuthFacade);

  readonly placeholder = input('messages.filter.user.field.placeholder');

  /** The selected username — two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string | null>(null);
  /** The resolved display user shown as the chip (display); `value` holds its username. */
  readonly selectedUser = signal<DisplayUser | null>(null);

  /** Chip label accessor for `<app-autocomplete>`. */
  readonly userLabel = (user: DisplayUser): string => user.displayName;
  /** Single value normalized to an array for the autocomplete primitive. */
  readonly valueItems = computed<readonly DisplayUser[]>(() => {
    const current = this.selectedUser();
    return current ? [current] : [];
  });

  readonly preferredUsername = this.auth.preferredUsername;
  readonly userName = this.auth.userName;
  readonly isAuthenticated = this.auth.isAuthenticated;
  /** Disabled when the form disables it OR the user is not authenticated. */
  readonly effectiveDisabled = computed(() => this.disabled() || !this.isAuthenticated());
  /**
   * The directory search (`GET /rest/users/search`, role user) needs the session domain's user
   * role, which it would otherwise answer with 403; Me and Clear call nothing and stay offered.
   */
  readonly searchDisabled = computed(
    () => this.effectiveDisabled() || !this.auth.hasDomainUserRole(),
  );
  readonly canShowMe = computed(() => !!this.preferredUsername() && !this.effectiveDisabled());
  readonly canShowClear = computed(() => !!this.selectedUser() && !this.effectiveDisabled());

  constructor() {
    super();
    // Resolve an externally-written username (form/URL / restored named filter) to a
    // display chip. The guard skips the echo from onSelect/setMe/clear, which set both
    // `value` and `selectedUser`. On a genuine external write we show the username at once,
    // then best-effort upgrade it to the person's full name (there is no get-by-username
    // endpoint, so we match the username against the directory search results).
    effect(() => {
      const username = this.value();
      untracked(() => {
        if ((this.selectedUser()?.username ?? null) === username) {
          return;
        }
        if (!username) {
          this.selectedUser.set(null);
          return;
        }
        this.selectedUser.set(usernameStubDisplayUser(username));
        this.resolveDisplayName(username);
      });
    });
  }

  /**
   * Best-effort username → full-name resolution for restored selections. Keycloak's user
   * search matches by username, so searching the raw username returns the user; we adopt
   * its display name only if it's an exact username match and the field still holds that
   * username (a newer pick/clear may have superseded it in flight). No match → the chip
   * keeps showing the username.
   */
  private resolveDisplayName(username: string): void {
    if (this.searchDisabled()) {
      return;
    }
    this.api
      .search(username)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (users) => {
          const match = users.find((user) => user.username === username);
          if (match && this.value() === username) {
            this.selectedUser.set(toDisplayUser(match));
          }
        },
        error: () => undefined,
      });
  }

  protected override searchDebounceMs(): number {
    return 200;
  }

  protected searchApi(query: string): Observable<readonly DisplayUser[]> {
    return this.api.searchDisplay(query);
  }

  /** Bridge the primitive's `(select)` (an item array) to the single value. */
  onSelectItems(items: readonly DisplayUser[]): void {
    this.onSelect(items[0] ?? null);
  }

  onSelect(value: DisplayUser | string | null): void {
    if (value === null || value === undefined) {
      this.selectedUser.set(null);
      this.value.set(null);
    } else {
      const display = typeof value === 'string' ? usernameStubDisplayUser(value) : value;
      this.selectedUser.set(display);
      this.value.set(display.username);
    }
    this.touch.emit();
  }

  setMe(): void {
    const me = this.preferredUsername();
    if (!me) return;
    // Prefer the full name from the identity claims (already populated for the
    // header's account menu); fall back to the username if absent.
    this.selectedUser.set({ username: me, displayName: this.userName() ?? me });
    this.value.set(me);
    this.touch.emit();
  }

  clear(): void {
    this.selectedUser.set(null);
    this.value.set(null);
    this.touch.emit();
  }
}
