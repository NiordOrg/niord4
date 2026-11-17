import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';

interface DomainsState {
  readonly domains: readonly AdminDomain[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: DomainsState = { domains: [], loading: true, error: false };

/**
 * Page-scoped list state for the Domains section. The fetch trigger includes
 * the selected UI language (the `?lang=` sorts each domain's area/category
 * descs server-side), so a language switch refetches; mutations bump
 * `refresh()` to refetch after create/update/delete/provisioning.
 */
@Injectable()
export class DomainsFacade {
  private readonly api = inject(DomainsAdminApiService);
  private readonly language = inject(LanguageService);

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    lang: this.language.selectedLanguage(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ lang }) =>
        this.api.getAll(lang).pipe(
          map((domains): DomainsState => ({ domains, loading: false, error: false })),
          catchError(() => of<DomainsState>({ domains: [], loading: false, error: true })),
          // Every (re)fetch — initial, language switch, refresh — shows the
          // loading shell until the response lands.
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly domains = computed(() => this.state().domains);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /** Refetch the list — after every successful mutation, and for error retry. */
  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
