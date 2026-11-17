import { HttpClient, HttpParameterCodec, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

export interface DictionaryEntryDesc {
  readonly lang: string;
  readonly value: string;
  /**
   * `undefined` = the backend sent no long value; an EMPTY STRING still counts
   * as "defined" (it drives the extended-columns heuristic).
   */
  readonly longValue?: string;
}

export interface DictionaryEntry {
  readonly id?: number;
  readonly key: string;
  readonly descs: readonly DictionaryEntryDesc[];
  /** Template-category AtoN matching expression — edited in extended mode. */
  readonly atonFilter?: string;
}

/**
 * The parts of the backend that read a dictionary key in code (`RequiredTexts.Reader`), and
 * `OTHER` for an id this build does not know yet.
 */
export type DictionaryKeyReader =
  | 'MESSAGE_CANCELLATION'
  | 'FIRING_EXERCISES'
  | 'S124_AGENCY'
  | 'COMMENT_MAIL'
  | 'MAILING_LIST_MAIL'
  | 'AUDIO_TEXT'
  | 'DATE_INTERVAL_DIRECTIVE'
  | 'FROM_TO_DATE_DIRECTIVE'
  | 'LAT_LON_DIRECTIVE'
  | 'OTHER';

const KNOWN_READERS: ReadonlySet<string> = new Set<DictionaryKeyReader>([
  'MESSAGE_CANCELLATION',
  'FIRING_EXERCISES',
  'S124_AGENCY',
  'COMMENT_MAIL',
  'MAILING_LIST_MAIL',
  'AUDIO_TEXT',
  'DATE_INTERVAL_DIRECTIVE',
  'FROM_TO_DATE_DIRECTIVE',
  'LAT_LON_DIRECTIVE',
]);

/** Who uses a dictionary key: what the delete confirmation lists. */
export interface DictionaryKeyUsers {
  /** Paths of the script resources whose content names the key (or its runtime-built prefix). */
  readonly templates: readonly string[];
  /** The runtime-built prefix the scan also used (`msg.status.` …), when the key has one. */
  readonly prefix?: string;
  readonly readers: readonly DictionaryKeyReader[];
  /**
   * Names of the parameter types whose list holds the entry, sorted; the backend refuses the delete
   * while any does. Empty from a backend that does not send it.
   */
  readonly paramTypes: readonly string[];
}

/** One import unit: a key of a dictionary. */
export interface DictionaryKeyRef {
  readonly dictionary: string;
  readonly key: string;
}

/** An entry's import-relevant values, by language code. */
export interface DictionaryImportValues {
  readonly atonFilter?: string;
  readonly values: Readonly<Record<string, string>>;
  /** `undefined` when no language carries a long value. */
  readonly longValues?: Readonly<Record<string, string>>;
}

/** A key of the file the deployment does not have. */
export interface DictionaryImportAdd extends DictionaryKeyRef, DictionaryImportValues {
  /** An operator deleted the key here; the import adds it back only when ticked. */
  readonly deleted: boolean;
}

/** A key the deployment has with other values than the file's. */
export interface DictionaryImportOverwrite extends DictionaryKeyRef {
  readonly current: DictionaryImportValues;
  readonly incoming: DictionaryImportValues;
  /**
   * `sha256:<hex>` of the row's values as the preview read them; the tick sends it back, so the job
   * keeps a key changed since the preview. `undefined` from a backend that does not send it.
   */
  readonly basedOn?: string;
}

/** A ticked overwrite: the key, and the row's hash the preview showed when there is one. */
export interface DictionaryOverwriteTick extends DictionaryKeyRef {
  readonly basedOn?: string;
}

/** A file entry the import refuses, with the backend's reason. */
export interface DictionaryImportRefusal extends DictionaryKeyRef {
  readonly reason: string;
}

/** What an import of the file would do; the preview writes nothing. */
export interface DictionaryImportPreview {
  readonly add: readonly DictionaryImportAdd[];
  readonly overwrite: readonly DictionaryImportOverwrite[];
  /** How many keys already equal the file's values. */
  readonly unchanged: number;
  readonly refused: readonly DictionaryImportRefusal[];
}

interface DictionaryImportValuesVo {
  atonFilter?: string | null;
  values?: Record<string, string> | null;
  longValues?: Record<string, string> | null;
}

interface DictionaryImportPreviewVo {
  add?: (DictionaryImportValuesVo & {
    dictionary: string;
    key: string;
    deleted?: boolean | null;
  })[];
  overwrite?: {
    dictionary: string;
    key: string;
    current?: DictionaryImportValuesVo | null;
    incoming?: DictionaryImportValuesVo | null;
    basedOn?: string | null;
  }[];
  unchanged?: number | null;
  refused?: { dictionary?: string | null; key?: string | null; reason?: string | null }[] | null;
}

const importValuesFromVo = (vo: DictionaryImportValuesVo | null | undefined) => ({
  atonFilter: vo?.atonFilter ?? undefined,
  values: vo?.values ?? {},
  longValues: vo?.longValues ?? undefined,
});

const previewFromVo = (vo: DictionaryImportPreviewVo): DictionaryImportPreview => ({
  add: (vo.add ?? []).map((entry) => ({
    dictionary: entry.dictionary,
    key: entry.key,
    ...importValuesFromVo(entry),
    deleted: entry.deleted ?? false,
  })),
  overwrite: (vo.overwrite ?? []).map((entry) => ({
    dictionary: entry.dictionary,
    key: entry.key,
    current: importValuesFromVo(entry.current),
    incoming: importValuesFromVo(entry.incoming),
    basedOn: entry.basedOn ?? undefined,
  })),
  unchanged: vo.unchanged ?? 0,
  refused: (vo.refused ?? []).map((entry) => ({
    dictionary: entry.dictionary ?? '',
    key: entry.key ?? '',
    reason: entry.reason ?? '',
  })),
});

interface DictionaryEntryDescVo {
  lang: string;
  value?: string | null;
  longValue?: string | null;
  /** Sent by the backend, not shown on this page. */
  asAdded?: boolean | null;
}

interface DictionaryKeyUsersVo {
  templates?: string[] | null;
  prefix?: string | null;
  readers?: string[] | null;
  paramTypes?: string[] | null;
}

/**
 * Query encoding without Angular's default exceptions: `HttpUrlEncodingCodec` leaves `+ ; = ? / @ : $ ,`
 * unescaped, and the backend reads a bare `+` as a space.
 */
const STRICT_QUERY_CODEC: HttpParameterCodec = {
  encodeKey: encodeURIComponent,
  encodeValue: encodeURIComponent,
  decodeKey: decodeURIComponent,
  decodeValue: decodeURIComponent,
};

const keyUsersFromVo = (vo: DictionaryKeyUsersVo): DictionaryKeyUsers => ({
  templates: vo.templates ?? [],
  prefix: vo.prefix ?? undefined,
  readers: (vo.readers ?? []).map((id) =>
    KNOWN_READERS.has(id) ? (id as DictionaryKeyReader) : 'OTHER',
  ),
  paramTypes: vo.paramTypes ?? [],
});

interface DictionaryEntryVo {
  id?: number | null;
  key: string;
  descs?: DictionaryEntryDescVo[] | null;
  atonFilter?: string | null;
}

const fromVo = (vo: DictionaryEntryVo): DictionaryEntry => ({
  id: vo.id ?? undefined,
  key: vo.key,
  descs: (vo.descs ?? []).map((desc) => ({
    lang: desc.lang,
    value: desc.value ?? '',
    // null still counts as "defined" for the extended heuristic → normalize to ''.
    longValue: desc.longValue === undefined ? undefined : (desc.longValue ?? ''),
  })),
  atonFilter: vo.atonFilter ?? undefined,
});

/**
 * Dictionaries CRUD, and the import's preview and upload. Standalone injectable methods (not page-private) — the
 * parameter-types LIST picker reuses `getDictionaryNames`/`getDictionaryEntries`
 * later without relocation.
 *
 * Contract: dictionary names and entry keys are URL-encoded EVERYWHERE —
 * including the export URL, so a comma in a name cannot flip the backend into
 * multi-export. No call carries a `lang` param: the payload holds all languages
 * and ordering is a client-side concern.
 */
@Injectable({ providedIn: 'root' })
export class DictionariesApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getDictionaryNames(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiBaseUrl}/dictionaries/names`);
  }

  getDictionaryEntries(name: string): Observable<DictionaryEntry[]> {
    return this.http
      .get<
        DictionaryEntryVo[]
      >(`${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}/entries`)
      .pipe(map((entries) => entries.map(fromVo)));
  }

  createEntry(name: string, entry: DictionaryEntry): Observable<unknown> {
    return this.http.post(
      `${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}`,
      entry,
    );
  }

  updateEntry(name: string, entry: DictionaryEntry): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}/${encodeURIComponent(entry.key)}`,
      entry,
    );
  }

  deleteEntry(name: string, key: string): Observable<unknown> {
    return this.http.delete(
      `${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}/${encodeURIComponent(key)}`,
    );
  }

  /**
   * The script resources that name a key, the code that reads it and the parameter types whose list
   * holds it (SYSADMIN). The key goes as
   * a query parameter encoded in full ({@link STRICT_QUERY_CODEC}), so any key character survives.
   */
  getKeyUsers(name: string, key: string): Observable<DictionaryKeyUsers> {
    return this.http
      .get<DictionaryKeyUsersVo>(
        `${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}/key-users`,
        { params: new HttpParams({ encoder: STRICT_QUERY_CODEC }).set('key', key) },
      )
      .pipe(map(keyUsersFromVo));
  }

  /** Public per-dictionary JSON export — PermitAll, opened directly, name encoded. */
  exportUrl(name: string): string {
    return `${this.apiBaseUrl}/dictionaries/dictionary/${encodeURIComponent(name)}.json`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/dictionaries/upload-dictionaries`;
  }

  /** What importing the file would add, overwrite, leave and refuse (SYSADMIN); writes nothing. */
  previewImport(file: File): Observable<DictionaryImportPreview> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http
      .post<DictionaryImportPreviewVo>(`${this.apiBaseUrl}/dictionaries/import-preview`, formData)
      .pipe(map(previewFromVo));
  }

  /**
   * Starts the import job: it adds the missing keys named in `add` and overwrites the keys named in
   * `overwrite`, nothing else. Both fields are always sent, so an empty `add` adds nothing (the
   * backend reads an absent one as every missing key not recorded as deleted). An overwrite carries
   * the preview's `basedOn` when it had one, so the job keeps a key changed since the preview.
   * Answers the backend's text about the job.
   */
  importFile(
    file: File,
    add: readonly DictionaryKeyRef[],
    overwrite: readonly DictionaryOverwriteTick[],
  ): Observable<string> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('add', JSON.stringify(add.map(({ dictionary, key }) => ({ dictionary, key }))));
    formData.append(
      'overwrite',
      JSON.stringify(
        overwrite.map(({ dictionary, key, basedOn }) =>
          basedOn === undefined ? { dictionary, key } : { dictionary, key, basedOn },
        ),
      ),
    );
    return this.http.post(this.uploadUrl(), formData, { responseType: 'text' });
  }
}
