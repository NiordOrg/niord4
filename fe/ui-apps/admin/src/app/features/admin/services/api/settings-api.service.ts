import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/**
 * One editable backend system setting. `type` is the RAW wire string (one of
 * `Boolean`/`Integer`/`Long`/`Float`/`Double`/`Password`/`String`/`json`, or
 * absent) — it is NOT normalized here: the edit form's type dispatch interprets
 * the exact string (the type dispatch mixes case and operators). `value`
 * is whatever the backend serialized (a string, number, boolean, or — for a
 * `json` setting — a parsed object/array).
 */
export interface Setting {
  readonly key: string;
  readonly description?: string;
  readonly type?: string;
  readonly value: unknown;
}

interface SettingVo {
  key: string;
  description?: string | null;
  type?: string | null;
  value?: unknown;
}

const fromVo = (vo: SettingVo): Setting => ({
  key: vo.key,
  description: vo.description ?? undefined,
  // Preserve the absence of `type` (an untyped setting renders as a String,
  // distinct from any declared type) — never coerce to ''.
  type: vo.type ?? undefined,
  value: vo.value,
});

// Absent description/type stay absent on the wire — undefined keys drop at
// serialization; never invent nulls.
const toVo = (setting: Setting): SettingVo => ({
  key: setting.key,
  description: setting.description,
  type: setting.type,
  value: setting.value,
});

/**
 * The editable-settings adapter. Endpoint contract:
 * - `getEditableSettings` reads the same list the export downloads;
 * - `updateSetting` PUTs to `/setting/{key}` with the key URL-encoded (settings
 *   keys contain `.`, and component-encoding also covers any `/`); the path key
 *   must equal the body key (the key is read-only in the form, so they can't
 *   diverge);
 * - the export is the BARE list URL — `TicketedDownloadService.openTicketed`
 *   appends the one-time `?ticket=`;
 * - the upload URL is handed to the shared `JsonImportDialog`.
 * No settings call carries a `?lang=` param (settings are not localized).
 */
@Injectable({ providedIn: 'root' })
export class SettingsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getEditableSettings(): Observable<Setting[]> {
    return this.http
      .get<SettingVo[]>(`${this.apiBaseUrl}/settings/editable-settings`)
      .pipe(map((settings) => settings.map(fromVo)));
  }

  /**
   * The anonymous web-settings map (`{key: value}`, the same one the bootstrap overlay
   * reads): every `web: true` setting, plus the booleans `s124Enabled` and `secomEnabled`,
   * which the backend computes from its running `niord.s124.enabled` and
   * `niord.secom.enabled` and never stores. Values are passed through as the backend
   * serialized them (some booleans arrive as the strings `"true"`/`"false"`). Transport
   * errors propagate; callers that gate UI off this default to hidden.
   */
  getWebSettings(): Observable<Readonly<Record<string, unknown>>> {
    return this.http.get<Readonly<Record<string, unknown>>>(`${this.apiBaseUrl}/settings/web`);
  }

  updateSetting(setting: Setting): Observable<Setting> {
    return this.http
      .put<SettingVo>(
        `${this.apiBaseUrl}/settings/setting/${encodeURIComponent(setting.key)}`,
        toVo(setting),
      )
      .pipe(map(fromVo));
  }

  /** Bare editable-settings URL — the ticketed download service appends `?ticket=`. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/settings/editable-settings`;
  }

  /** Multipart import endpoint handed to the shared JSON-import dialog. */
  uploadUrl(): string {
    return `${this.apiBaseUrl}/settings/upload-settings`;
  }
}
