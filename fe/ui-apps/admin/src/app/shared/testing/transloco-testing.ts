import { Injectable } from '@angular/core';
import { provideTransloco, Translation, TranslocoLoader } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class StubTranslocoLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of<Translation>({});
  }
}

/**
 * Two languages, not one.
 *
 * A lang Transloco has not been told about is read as a SCOPE, so a spec that
 * switches to it silently starts looking up `da.some.key` and every assertion
 * about a label fails for a reason that has nothing to do with the code under
 * test. The app is multi-language by contract; its test harness has to be too.
 * Translations still come from the stub loader — a spec seeds whatever it needs
 * with `setTranslation`.
 */
export function provideTestTransloco() {
  return provideTransloco({
    config: { availableLangs: ['en', 'da'], defaultLang: 'en' },
    loader: StubTranslocoLoader,
  });
}
