import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';

import { APP_CONFIG } from '../config/app-config.token';

interface DictionaryEntryDescVo {
  lang: string;
  value?: string | null;
  /** True while the value still equals what the backend's start-up added or adopted. */
  asAdded?: boolean | null;
}

interface DictionaryEntryVo {
  key: string;
  descs?: DictionaryEntryDescVo[] | null;
}

/**
 * Fetches the runtime UI-text overrides an administrator has entered in the sysadmin Dictionary,
 * so they reach the running app. Only the `web` and `message` dictionaries feed the frontend.
 * Their flat keys are the same keyspace as the app's translation keys, so the result merges
 * straight over the base bundle by key. The read endpoints are anonymous (PermitAll), so this
 * resolves pre-login and pre-domain.
 *
 * A row the backend added itself and nobody has changed since (`asAdded`) is no override: it
 * carries the backend's wording, not an administrator's, and must not mask the app's own text.
 */
@Injectable({ providedIn: 'root' })
export class DictionaryOverridesService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** The dictionaries whose entries render as frontend UI text. */
  private static readonly UI_DICTIONARIES = ['web', 'message'] as const;

  /** Flat `key → value` overrides for one language, merged across the UI dictionaries. */
  fetch(lang: string, defaultLanguage?: string): Observable<Record<string, string>> {
    return forkJoin(
      DictionaryOverridesService.UI_DICTIONARIES.map((name) =>
        this.fetchOne(name, lang, defaultLanguage),
      ),
    ).pipe(map((maps) => Object.assign({}, ...maps) as Record<string, string>));
  }

  private fetchOne(
    name: string,
    lang: string,
    defaultLanguage?: string,
  ): Observable<Record<string, string>> {
    return this.http
      .get<
        DictionaryEntryVo[]
      >(`${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}/entries`)
      .pipe(
        map((entries) => {
          const overrides: Record<string, string> = {};
          for (const entry of entries ?? []) {
            // Only a non-empty value in the requested language overrides — a blank or absent
            // value must not blank out the compiled label.
            const descs = entry.descs ?? [];
            const desc =
              descs.find((d) => d.lang === lang && d.value?.trim()) ??
              (defaultLanguage
                ? (descs.find((d) => d.lang === defaultLanguage && d.value?.trim()) ??
                  descs.find((d) => d.value?.trim()))
                : undefined);
            // The requested language's row, else the default language's, else any: the fallback
            // stays. The picked row gives no override while it is unchanged since start-up added
            // it (asAdded), and the pick never moves on to another row, so an unchanged
            // system-added English row leaves the app's Danish alone. An English-only row an
            // operator edited still replaces the Danish text, by design. A missing flag (an older
            // backend) counts as false.
            if (desc?.value && desc.asAdded !== true) {
              overrides[entry.key] = desc.value;
            }
          }
          return overrides;
        }),
        // One dictionary being unavailable must not lose the other, nor break i18n.
        catchError(() => of<Record<string, string>>({})),
      );
  }
}
