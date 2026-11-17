import { computed, inject, Injectable, signal, type Signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { translateSignal, TranslocoService } from '@jsverse/transloco';
import { EMPTY, map, Observable, tap } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { NotificationService } from '../../../core/notifications/notification.service';
import { ConfirmService } from '../../../shared/services/confirm.service';
import type { NamedFilter } from '../models/named-filter.model';
import { MessagesFacade } from './messages-facade.service';
import { NamedFiltersApiService } from './api/named-filters-api.service';

@Injectable({ providedIn: 'root' })
export class NamedFiltersFacade {
  private readonly api = inject(NamedFiltersApiService);
  private readonly auth = inject(AuthFacade);
  private readonly messages = inject(MessagesFacade);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  // Lazy gate so the dropdown's first open triggers the fetch, not login.
  private readonly loadRequested = signal(false);

  // The request URL is undefined when not authenticated OR the dropdown has not been opened yet —
  // httpResource does not fetch. Logout flips the URL to undefined automatically, which returns the
  // resource to 'idle' and value() to defaultValue. No manual reset effect needed.
  private readonly resource = httpResource<readonly NamedFilter[]>(
    () =>
      this.auth.isAuthenticated() && this.loadRequested()
        ? `${this.apiBaseUrl}/filters/all`
        : undefined,
    { defaultValue: [] },
  );

  private readonly writeError = signal<unknown | undefined>(undefined);

  private readonly deleteHeader = translateSignal('messages.namedFilter.delete.title');

  // resource.value() throws when the resource is in an 'error' state — always read it via
  // hasValue() guard so consumers get an empty list instead of a thrown exception.
  readonly namedFilters: Signal<readonly NamedFilter[]> = computed(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );
  readonly loading: Signal<boolean> = this.resource.isLoading;
  /**
   * True once a list is in hand: resolved, or reloading after a good load (a save or delete). A
   * reload that retries a failed load is not loaded: during it resource.error() still holds the
   * old error and the list is [], so the menu shows its loading row rather than an empty list.
   */
  readonly hasLoaded: Signal<boolean> = computed(() => {
    const status = this.resource.status();
    return status === 'resolved' || (status === 'reloading' && this.resource.error() === undefined);
  });
  /**
   * True when the list could not be loaded, so the menu can say so instead of reading as
   * "no saved filters" (namedFilters() is [] in that state). False while anonymous.
   */
  readonly loadFailed: Signal<boolean> = computed(
    () => this.auth.isAuthenticated() && this.resource.status() === 'error',
  );
  readonly lastError: Signal<unknown | undefined> = computed(() => {
    if (!this.auth.isAuthenticated()) return undefined;
    return this.resource.error() ?? this.writeError();
  });

  load(): void {
    if (!this.auth.isAuthenticated()) return;
    // A failed load is tried again the next time the menu opens; otherwise it stays failed until reload.
    if (this.resource.status() === 'error') {
      this.resource.reload();
      return;
    }
    this.loadRequested.set(true);
  }

  save(name: string): Observable<void> {
    const trimmed = name.trim();
    if (trimmed.length === 0) return EMPTY;
    return this.api.add({ name: trimmed, parameters: this.messages.filter.requestParams() }).pipe(
      tap({
        next: () => {
          this.writeError.set(undefined);
          this.refresh();
        },
        error: (err) => this.writeError.set(err),
      }),
      map(() => undefined),
    );
  }

  apply(filter: NamedFilter): void {
    this.messages.filter.applyQueryString(filter.parameters);
  }

  private refresh(): void {
    if (!this.loadRequested()) {
      this.loadRequested.set(true);
      return;
    }
    this.resource.reload();
  }

  /** Confirm-then-delete a saved filter. Public so the dropdown's delete action can call it. */
  confirmRemove(filter: NamedFilter): void {
    // One-shot `transloco.translate(...)` with params — `translateSignal` would
    // run the value through the interpolator with no params at signal-creation
    // time, replacing `{{name}}` with `""` before we ever get a chance to swap
    // it for the filter name.
    this.confirm.confirmDestructive({
      header: this.deleteHeader(),
      message: this.transloco.translate('messages.namedFilter.delete.confirm', {
        name: filter.name,
      }),
      accept: () => this.removeFilter(filter),
    });
  }

  private removeFilter(filter: NamedFilter): void {
    this.api.remove(filter.id).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        this.writeError.set(err);
        this.notifications.error('messages.namedFilter.error.remove');
      },
    });
  }
}
