import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/** One batch job type in the status payload — drives the type-list cards. */
export interface BatchType {
  readonly name: string;
  readonly instanceCount: number;
  readonly runningExecutions: number;
}

export interface BatchStatus {
  readonly runningExecutions: number;
  /** Per-type counts — empty for non-admin callers (backend returns an empty status). */
  readonly types: readonly BatchType[];
}

/** Execution-level status enum the backend reports (raw wire values). */
export type BatchExecutionStatus =
  | 'STARTING'
  | 'STARTED'
  | 'STOPPING'
  | 'STOPPED'
  | 'FAILED'
  | 'COMPLETED'
  | 'ABANDONED';

export interface BatchExecution {
  readonly executionId: number;
  /** May be absent/unknown — the status pipe normalizes undefined to the default pill. */
  readonly batchStatus?: BatchExecutionStatus;
  readonly startTime?: number;
  readonly endTime?: number;
  /** Server-computed action flags — rendered verbatim, never re-derived. */
  readonly stoppable: boolean;
  readonly restartable: boolean;
  readonly abandonable: boolean;
}

export interface BatchInstance {
  readonly instanceId: number;
  readonly jobNo?: number;
  readonly fileName?: string;
  readonly user?: string;
  /** Instance-level progress (nullable Integer 0–100); the progress bar reads this. */
  readonly progress?: number;
  readonly executions: readonly BatchExecution[];
}

export interface BatchInstanceSearchResult {
  readonly data: readonly BatchInstance[];
  readonly total: number;
  readonly size: number;
}

interface BatchTypeVo {
  name?: string | null;
  instanceCount?: number | null;
  runningExecutions?: number | null;
}

interface BatchStatusVo {
  runningExecutions?: number | null;
  types?: readonly BatchTypeVo[] | null;
}

interface BatchExecutionVo {
  executionId?: number | null;
  batchStatus?: string | null;
  startTime?: number | null;
  endTime?: number | null;
  stoppable?: boolean | null;
  restartable?: boolean | null;
  abandonable?: boolean | null;
}

interface BatchInstanceVo {
  instanceId?: number | null;
  jobNo?: number | null;
  fileName?: string | null;
  user?: string | null;
  progress?: number | null;
  executions?: readonly BatchExecutionVo[] | null;
}

interface BatchInstanceSearchResultVo {
  data?: readonly BatchInstanceVo[] | null;
  total?: number | null;
  size?: number | null;
}

const typeFromVo = (vo: BatchTypeVo): BatchType => ({
  name: vo.name ?? '',
  instanceCount: vo.instanceCount ?? 0,
  runningExecutions: vo.runningExecutions ?? 0,
});

const executionFromVo = (vo: BatchExecutionVo): BatchExecution => ({
  executionId: vo.executionId ?? 0,
  batchStatus: (vo.batchStatus ?? undefined) as BatchExecutionStatus | undefined,
  startTime: vo.startTime ?? undefined,
  endTime: vo.endTime ?? undefined,
  stoppable: vo.stoppable ?? false,
  restartable: vo.restartable ?? false,
  abandonable: vo.abandonable ?? false,
});

const instanceFromVo = (vo: BatchInstanceVo): BatchInstance => ({
  instanceId: vo.instanceId ?? 0,
  jobNo: vo.jobNo ?? undefined,
  fileName: vo.fileName ?? undefined,
  user: vo.user ?? undefined,
  progress: vo.progress ?? undefined,
  executions: (vo.executions ?? []).map(executionFromVo),
});

/** The fixed script name the execute-JavaScript POST always sends. */
const JAVASCRIPT_SCRIPT_NAME = 'javascript.js';

/**
 * Batch-job API adapter. `getStatus` drives both the rail badge (which reads
 * only `runningExecutions`) and the Batch Jobs page (which also reads the
 * per-type counts) — the `types[]` surface was added ADDITIVELY, so the badge
 * keeps working unchanged. The instance/action/log/execute methods back the
 * full monitoring page. The download + thumbnail endpoints are reached by URL
 * (ticketed anchor / `<img>`), not through this service.
 */
@Injectable({ providedIn: 'root' })
export class BatchApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getStatus(): Observable<BatchStatus> {
    return this.http.get<BatchStatusVo>(`${this.apiBaseUrl}/batch/status`).pipe(
      map((vo) => ({
        runningExecutions: vo.runningExecutions ?? 0,
        types: (vo.types ?? []).map(typeFromVo),
      })),
    );
  }

  /**
   * Server-paged instances for a job type. `page` is 0-based; the backend
   * computes the offset as `page * pageSize`.
   */
  getInstances(
    jobName: string,
    page: number,
    pageSize: number,
  ): Observable<BatchInstanceSearchResult> {
    return this.http
      .get<BatchInstanceSearchResultVo>(`${this.apiBaseUrl}/batch/${jobName}/instances`, {
        params: { page: String(page), pageSize: String(pageSize) },
      })
      .pipe(
        map((vo) => ({
          data: (vo.data ?? []).map(instanceFromVo),
          total: vo.total ?? 0,
          size: vo.size ?? 0,
        })),
      );
  }

  stop(executionId: number): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/batch/execution/${executionId}/stop`, null);
  }

  restart(executionId: number): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/batch/execution/${executionId}/restart`, null);
  }

  abandon(executionId: number): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/batch/execution/${executionId}/abandon`, null);
  }

  getLogFileNames(instanceId: number): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiBaseUrl}/batch/instance/${instanceId}/logs`);
  }

  /**
   * Full-file log content as plain text. The file name is `encodeURIComponent`d
   * (it may contain reserved characters); no `fromLineNo` tail param — the
   * incremental tail mode is deliberately not used.
   */
  getLogFileContent(instanceId: number, name: string): Observable<string> {
    return this.http.get(
      `${this.apiBaseUrl}/batch/instance/${instanceId}/logs/${encodeURIComponent(name)}`,
      { responseType: 'text' },
    );
  }

  /** Schedules a server-side `script-executor` job; the scriptName is fixed. */
  executeJavaScript(javaScript: string): Observable<unknown> {
    return this.http.post(
      `${this.apiBaseUrl}/batch/execute-javascript`,
      { scriptName: JAVASCRIPT_SCRIPT_NAME, javaScript },
      { responseType: 'text' },
    );
  }

  /** Bare instance-download URL (the ticketed download service appends `?ticket=`). */
  downloadUrl(instanceId: number, fileName: string): string {
    // `encodeURI` (not component-encoding): fileName may legitimately contain
    // `/` path separators, which must survive into the URL.
    return `${this.apiBaseUrl}/batch/instance/${instanceId}/download/${encodeURI(fileName)}`;
  }

  /** Instance thumbnail URL (PermitAll image endpoint). */
  thumbnailUrl(instanceId: number): string {
    return `${this.apiBaseUrl}/batch/instance/${instanceId}/thumbnail.png`;
  }

  /** Batch-set ZIP upload endpoint — handed to the shared JSON-import dialog. */
  uploadBatchSetUrl(): string {
    return `${this.apiBaseUrl}/batch/execute-batch-set`;
  }
}
