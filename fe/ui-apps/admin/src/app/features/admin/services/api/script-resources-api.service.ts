import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

export type ScriptResourceType = 'FM' | 'JS';

export interface ScriptResource {
  readonly id?: number;
  readonly type: ScriptResourceType;
  /** Repo path — the human-friendly identifier; may contain `/` and a `.ftl`/`.js` extension. */
  readonly path: string;
  /** The Freemarker / JavaScript source. */
  readonly content: string;
}

/** What wrote a revision: an import (the page's or the bootstrap's) or the page's editor. */
export type ScriptResourceHistorySource = 'import' | 'editor';

/**
 * One audited revision of a script resource. `snapshot` is the JSON-serialized
 * resource at that revision — its `content` is FM/JS text, so the diff between
 * two snapshots is a text diff over the two `content` strings.
 */
export interface ScriptResourceHistory {
  readonly version: number;
  /** Revision timestamp (epoch millis). */
  readonly created: number;
  /** Revision author; `undefined` renders as a "System" fallback. */
  readonly user?: string;
  /** `undefined` for a revision written before the source was recorded. */
  readonly source?: ScriptResourceHistorySource;
  readonly snapshot: string;
}

/** A path of the import file the deployment has no row for. */
export interface ScriptResourceImportAdd {
  readonly path: string;
  readonly type: ScriptResourceType;
  readonly content: string;
  /** The missing-path report lists the path: something in the deployment names it. */
  readonly listed: boolean;
}

/** A path the deployment has with other content than the file's. */
export interface ScriptResourceImportOverwrite {
  readonly path: string;
  readonly type: ScriptResourceType;
  /** The deployment's row. */
  readonly current: string;
  /** The file's content. */
  readonly incoming: string;
  /**
   * `sha256:<hex>` of the row's content as the preview read it; the tick sends it back, so the job
   * keeps a row changed since the preview. `undefined` from a backend that does not send it.
   */
  readonly basedOn?: string;
}

/** A ticked overwrite: the path, and the row's content hash the preview showed when there is one. */
export interface ScriptResourceOverwriteTick {
  readonly path: string;
  readonly basedOn?: string;
}

/** A file entry the import refuses, with the backend's reasons. */
export interface ScriptResourceImportRefusal {
  readonly path: string;
  readonly reason: string;
}

/** What an import of the file would do; the preview writes nothing. */
export interface ScriptResourceImportPreview {
  readonly add: readonly ScriptResourceImportAdd[];
  readonly overwrite: readonly ScriptResourceImportOverwrite[];
  /** Paths whose row already equals the file's content. */
  readonly unchanged: readonly string[];
  readonly refused: readonly ScriptResourceImportRefusal[];
}

interface ScriptResourceVo {
  id?: number | null;
  type: ScriptResourceType;
  path?: string | null;
  content?: string | null;
}

interface ScriptResourceHistoryVo {
  version: number;
  created: number;
  user?: string | null;
  source?: string | null;
  snapshot: string;
}

interface ScriptResourceImportPreviewVo {
  add?: {
    path: string;
    type: ScriptResourceType;
    content?: string | null;
    listed?: boolean | null;
  }[];
  overwrite?: {
    path: string;
    type: ScriptResourceType;
    current?: string | null;
    incoming?: string | null;
    basedOn?: string | null;
  }[];
  unchanged?: string[] | null;
  refused?: { path?: string | null; reason?: string | null }[] | null;
}

const fromVo = (vo: ScriptResourceVo): ScriptResource => ({
  id: vo.id ?? undefined,
  type: vo.type,
  path: vo.path ?? '',
  content: vo.content ?? '',
});

const fromHistoryVo = (vo: ScriptResourceHistoryVo): ScriptResourceHistory => ({
  version: vo.version,
  created: vo.created,
  user: vo.user ?? undefined,
  source: vo.source === 'import' || vo.source === 'editor' ? vo.source : undefined,
  snapshot: vo.snapshot,
});

const fromPreviewVo = (vo: ScriptResourceImportPreviewVo): ScriptResourceImportPreview => ({
  add: (vo.add ?? []).map((entry) => ({
    path: entry.path,
    type: entry.type,
    content: entry.content ?? '',
    listed: entry.listed ?? false,
  })),
  overwrite: (vo.overwrite ?? []).map((entry) => ({
    path: entry.path,
    type: entry.type,
    current: entry.current ?? '',
    incoming: entry.incoming ?? '',
    basedOn: entry.basedOn ?? undefined,
  })),
  unchanged: vo.unchanged ?? [],
  refused: (vo.refused ?? []).map((entry) => ({
    path: entry.path ?? '',
    reason: entry.reason ?? '',
  })),
});

/**
 * Script-resources CRUD + revision history + ticketed export + the import's
 * preview and upload. Wraps the `/rest/script-resources/*` endpoints. Mutations
 * key on the numeric `id`; the list/picker filter by `type`. The export is
 * `@RolesAllowed(admin)`, so it goes through a ticket.
 */
@Injectable({ providedIn: 'root' })
export class ScriptResourcesApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Full list. `type` narrows to FM or JS (the picker). */
  getAll(type?: ScriptResourceType): Observable<ScriptResource[]> {
    let params = new HttpParams();
    if (type) {
      params = params.set('type', type);
    }
    return this.http
      .get<ScriptResourceVo[]>(`${this.apiBaseUrl}/script-resources/all`, { params })
      .pipe(map((vos) => vos.map(fromVo)));
  }

  create(resource: ScriptResource): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/script-resources/script-resource/`, resource);
  }

  update(resource: ScriptResource): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/script-resources/script-resource/${resource.id}`,
      resource,
    );
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/script-resources/script-resource/${id}`);
  }

  getHistory(id: number): Observable<ScriptResourceHistory[]> {
    return this.http
      .get<
        ScriptResourceHistoryVo[]
      >(`${this.apiBaseUrl}/script-resources/script-resource/${id}/history`)
      .pipe(map((vos) => vos.map(fromHistoryVo)));
  }

  /** Bare export URL — `TicketedDownloadService.openTicketed(.., 'admin')` appends `?ticket=`. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/script-resources/all`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/script-resources/upload-script-resources`;
  }

  /** What importing the file would add, overwrite, leave and refuse (SYSADMIN); writes nothing. */
  previewImport(file: File): Observable<ScriptResourceImportPreview> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http
      .post<ScriptResourceImportPreviewVo>(
        `${this.apiBaseUrl}/script-resources/import-preview`,
        formData,
      )
      .pipe(map(fromPreviewVo));
  }

  /**
   * Starts the import job: it adds the missing paths named in `add` and overwrites the paths named
   * in `overwrite`, nothing else. Both fields are always sent, so an empty `add` adds nothing (the
   * backend reads an absent one as every missing path). An overwrite goes as
   * `{"path", "basedOn"}` when the preview gave its hash, so the job keeps a row changed since the
   * preview, and as the bare path otherwise. Answers the backend's text about the job.
   */
  importFile(
    file: File,
    add: readonly string[],
    overwrite: readonly ScriptResourceOverwriteTick[],
  ): Observable<string> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('add', JSON.stringify(add));
    formData.append(
      'overwrite',
      JSON.stringify(
        overwrite.map(({ path, basedOn }) => (basedOn === undefined ? path : { path, basedOn })),
      ),
    );
    return this.http.post(this.uploadUrl(), formData, { responseType: 'text' });
  }
}
