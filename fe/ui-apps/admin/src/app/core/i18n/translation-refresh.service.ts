import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Observable, map, tap } from 'rxjs';

import { TranslocoHttpLoader } from '../../transloco-loader';

/**
 * Re-fetches the base bundle + sysadmin Dictionary overrides for the active language and REPLACES
 * the live translation table, so a Dictionary edit shows in the running app without a full page
 * reload. A full replace (not a `merge`) so a DELETED override correctly reverts to the compiled
 * base value — a merge could only ever add or overwrite, never drop a removed key.
 */
@Injectable({ providedIn: 'root' })
export class TranslationRefreshService {
  private readonly transloco = inject(TranslocoService);
  private readonly loader = inject(TranslocoHttpLoader);

  refreshActiveLanguage(): Observable<void> {
    const lang = this.transloco.getActiveLang();
    return this.loader.getTranslation(lang).pipe(
      tap((translation) => this.transloco.setTranslation(translation, lang, { merge: false })),
      map(() => undefined),
    );
  }
}
