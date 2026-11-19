import { isPlatformBrowser } from '@angular/common';
import { effect, inject, Injectable, PLATFORM_ID, signal, untracked } from '@angular/core';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';

import type { Domain } from '../models/domain.model';
import { DomainsApiService } from './domains-api.service';
import { LanguageService } from '../../main-shell/services/language.service';

const SESSION_DOMAIN_LS_KEY = 'niord.sessionDomainId';
const BOOTSTRAP_TIMEOUT_MS = 5_000;

@Injectable({ providedIn: 'root' })
export class DomainService {
  private readonly api = inject(DomainsApiService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly language = inject(LanguageService);

  readonly availableDomains = signal<readonly Domain[]>([]);
  readonly sessionDomain = signal<Domain | undefined>(undefined);
  readonly searchDomain = signal<Domain | undefined>(undefined);
  /**
   * The start-up read of the domains failed or timed out: the app has no domain, so its lists
   * would be empty for that reason alone. The shell says so and offers a reload, which starts
   * the app again, as a desk change does.
   */
  readonly bootstrapFailed = signal(false);

  constructor() {
    // Domain name/descs are backend-localized (lookup sends ?lang=). bootstrap()
    // fetches them once at startup, so a runtime language switch would otherwise
    // leave the domain switcher / domain field showing bootstrap-language names.
    // Re-fetch on language change — architecture's "server-side translated data:
    // refetch on language change" Pattern 3 (subscribe to selectedLanguage() and
    // re-fire the last request). The first emission is the bootstrap language, so
    // skip it; bootstrap() already loaded that.
    let languageInitialised = false;
    effect(() => {
      const lang = this.language.selectedLanguage();
      if (!languageInitialised) {
        languageInitialised = true;
        return;
      }
      // Subscribing to the fetch runs the HTTP interceptor chain synchronously in this
      // reactive context. For a guest, authTokenInterceptor passes straight through to
      // niordDomainInterceptor, which reads sessionDomain() — that read would otherwise
      // become an effect dependency. The response then rebinds sessionDomain to a fresh
      // object, which would re-run this effect and loop forever. untracked() keeps the
      // effect keyed on language alone. (Authenticated requests defer next() into a
      // Promise, so the read escapes tracking and the loop never appeared there.)
      untracked(() => this.refreshDomainsForLanguage(lang));
    });
  }

  /**
   * Re-fetch the domain catalog at the active language and rebind the
   * session/search domains to the fresh objects. The domains admin page calls
   * this after a successful create/update/delete so the header switcher and
   * capabilities reflect the change without a reload. Known limit: deleting or
   * deactivating the CURRENT session domain leaves the session on the stale
   * object until reload (the rebind only re-points ids that still exist).
   */
  refresh(): void {
    this.refreshDomainsForLanguage(this.language.selectedLanguage());
  }

  private refreshDomainsForLanguage(language: string): void {
    this.api
      .lookup({ language })
      .pipe(
        timeout(BOOTSTRAP_TIMEOUT_MS),
        catchError(() => of(null)),
      )
      .subscribe((domains) => {
        // Drop a stale response if the language changed again mid-flight, and keep
        // the current list on error/timeout (null). DomainService is a root
        // singleton, so the HttpClient subscription needs no teardown.
        if (!domains || this.language.selectedLanguage() !== language) {
          return;
        }
        this.availableDomains.set(domains);
        this.rebindActiveDomains(domains);
      });
  }

  // Re-point session/search domains to the refreshed Domain objects (same ids) so
  // their localized names update. Because the domainId is unchanged, the
  // tag-selection effect treats this as "no switch" and does not clear the selection.
  private rebindActiveDomains(domains: readonly Domain[]): void {
    const sessionId = this.sessionDomain()?.domainId;
    if (sessionId) {
      const next = domains.find((d) => d.domainId === sessionId);
      if (next) {
        this.sessionDomain.set(next);
      }
    }
    const searchId = this.searchDomain()?.domainId;
    if (searchId) {
      const next = domains.find((d) => d.domainId === searchId);
      if (next) {
        this.searchDomain.set(next);
      }
    }
  }

  /**
   * The start-up read of the domains. A failure or a timeout leaves the app without a domain and
   * sets {@link bootstrapFailed}; the remembered session domain is kept, so the reload the shell
   * offers returns the person to it.
   */
  async bootstrap(deepLinkDomainId?: string): Promise<void> {
    const domains = await firstValueFrom(
      this.api.lookup().pipe(
        timeout(BOOTSTRAP_TIMEOUT_MS),
        catchError(() => of(null)),
      ),
    );
    if (!domains) {
      this.bootstrapFailed.set(true);
      return;
    }
    this.bootstrapFailed.set(false);
    this.availableDomains.set(domains);
    this.setSessionDomain(this.resolveInitialSessionDomain(domains, deepLinkDomainId));
  }

  setSessionDomain(domain: Domain | undefined): void {
    this.sessionDomain.set(domain);
    this.persistSessionDomainId(domain?.domainId);
  }

  setSearchDomain(domain: Domain | undefined): void {
    this.searchDomain.set(domain);
  }

  resolveById(domainId: string | undefined): Domain | undefined {
    if (!domainId) {
      return undefined;
    }
    return this.availableDomains().find((d) => d.domainId === domainId);
  }

  private resolveInitialSessionDomain(
    domains: readonly Domain[],
    deepLinkDomainId: string | undefined,
  ): Domain | undefined {
    const deepLinkMatch = deepLinkDomainId
      ? domains.find((d) => d.domainId === deepLinkDomainId)
      : undefined;
    if (deepLinkMatch) {
      return deepLinkMatch;
    }
    const persistedId = this.readPersistedSessionDomainId();
    const persistedMatch = persistedId
      ? domains.find((d) => d.domainId === persistedId)
      : undefined;
    if (persistedMatch) {
      return persistedMatch;
    }
    return domains[0];
  }

  private readPersistedSessionDomainId(): string | undefined {
    if (!isPlatformBrowser(this.platformId)) {
      return undefined;
    }
    try {
      return localStorage.getItem(SESSION_DOMAIN_LS_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  }

  private persistSessionDomainId(domainId: string | undefined): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }
    try {
      if (domainId) {
        localStorage.setItem(SESSION_DOMAIN_LS_KEY, domainId);
      } else {
        localStorage.removeItem(SESSION_DOMAIN_LS_KEY);
      }
    } catch {
      // Swallow storage errors — selection still applies for the current session.
    }
  }
}
