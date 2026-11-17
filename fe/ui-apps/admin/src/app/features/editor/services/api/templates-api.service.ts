import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, type Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { Message } from '../../../messages/models/message.model';
import type { ParamType, TemplateParamValues } from '../../../messages/models/template.model';

/**
 * Adapter for `/rest/templates/*` — the template-EXECUTE surface. Fetches the
 * parameter-type catalogue and runs a message's TEMPLATE categories server-side, returning the
 * whole re-rendered message (which the editor then applies via `EditorFacade.templateExecuted`).
 * Editor-owned because execution writes back into the working message.
 */
@Injectable({ providedIn: 'root' })
export class TemplatesApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * All parameter-type definitions (STANDARD / LIST / COMPOSITE), in ALL configured languages —
   * fetched WITHOUT a `lang` filter on purpose. A LIST value's per-language `descs` must stay
   * complete: the chosen value object is echoed back to `/templates/execute`, which renders each
   * message-body language from those descs. Filtering to the active language here (as the backend
   * does for `?lang=`) would leave the value with a single language's desc, freezing the rendered
   * value in that language across every body — it would never re-localize in the preview / Status
   * tab.
   */
  parameterTypes(): Observable<ParamType[]> {
    return this.http.get<ParamType[]>(`${this.apiBaseUrl}/templates/parameter-types`);
  }

  /**
   * Run the message's TEMPLATE categories with the given per-template parameter values. The backend
   * executes every `type==='TEMPLATE'` category on the inline message (no explicit category is sent)
   * and returns the whole rendered message. `templateParams` is index-aligned with those templates.
   */
  execute(message: Message, templateParams: readonly TemplateParamValues[]): Observable<Message> {
    return this.http.put<Message>(`${this.apiBaseUrl}/templates/execute`, {
      message,
      templateParams,
    });
  }

  /**
   * Validate a `light_character` / `call_sign` parameter value server-side.
   * Returns the (possibly normalized, e.g. uppercased) value and whether it parsed.
   *
   * The verdict arrives as `isValid`, not `valid` — the response bean names the flag after its
   * `getIsValid()` accessor. Reading the wrong key yields `undefined` for every answer, which is
   * indistinguishable from "invalid", so both fields would reject even correct input. Normalized
   * here so nothing downstream has to know.
   */
  validate(
    kind: 'light-character' | 'call-sign',
    value: string,
  ): Observable<{ value: string; valid: boolean }> {
    return this.http
      .post<{ value: string; isValid: boolean }>(`${this.apiBaseUrl}/templates/validate-${kind}`, {
        value,
      })
      .pipe(map((response) => ({ value: response.value, valid: response.isValid })));
  }
}
