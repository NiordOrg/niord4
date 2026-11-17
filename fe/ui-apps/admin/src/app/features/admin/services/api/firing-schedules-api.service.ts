import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { FiringRunResult } from '../../../../shared/models/firing-run-result.model';

/** A domain reference. The list fetch carries the full VO (localized name); save sends only `{ domainId }`. */
export interface FiringScheduleDomainRef {
  readonly domainId: string;
  readonly name?: string;
}

export interface FiringSchedule {
  readonly id?: number;
  readonly domain?: FiringScheduleDomainRef;
  readonly targetDomain?: FiringScheduleDomainRef;
  readonly targetSeriesId?: string;
  readonly scheduleDays?: number;
  readonly active: boolean;
}

interface FiringScheduleDomainVo {
  domainId?: string | null;
  name?: string | null;
}

interface FiringScheduleVo {
  id?: number | null;
  domain?: FiringScheduleDomainVo | null;
  targetDomain?: FiringScheduleDomainVo | null;
  targetSeriesId?: string | null;
  scheduleDays?: number | null;
  active?: boolean | null;
}

const domainFromVo = (
  vo: FiringScheduleDomainVo | null | undefined,
): FiringScheduleDomainRef | undefined =>
  vo?.domainId == null ? undefined : { domainId: vo.domainId, name: vo.name ?? undefined };

const fromVo = (vo: FiringScheduleVo): FiringSchedule => ({
  id: vo.id ?? undefined,
  domain: domainFromVo(vo.domain),
  targetDomain: domainFromVo(vo.targetDomain),
  targetSeriesId: vo.targetSeriesId ?? undefined,
  scheduleDays: vo.scheduleDays ?? undefined,
  active: vo.active ?? false,
});

/**
 * Save payload: the backend stores domains by id, so flatten both domain refs
 * to a partial `{ domainId }` (the full localized VO is never sent back). Drops
 * the display-only `name`.
 */
const toVo = (schedule: FiringSchedule): FiringScheduleVo => ({
  id: schedule.id,
  domain: schedule.domain ? { domainId: schedule.domain.domainId } : null,
  targetDomain: schedule.targetDomain ? { domainId: schedule.targetDomain.domainId } : null,
  targetSeriesId: schedule.targetSeriesId,
  scheduleDays: schedule.scheduleDays,
  active: schedule.active,
});

/**
 * Firing-schedules CRUD + the manual exercise-regeneration action. Endpoint
 * contract:
 * - the LIST fetch carries `?lang=` (backend localizes domain/series names);
 * - PUT path id must equal the body id, derived from the same model id (the
 *   backend 400s on mismatch);
 * - the exercises action is a bodyless PUT answered with the run's failure counts.
 */
@Injectable({ providedIn: 'root' })
export class FiringSchedulesApiService {
  private readonly http = inject(HttpClient);
  private readonly langHelper = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(language?: string): Observable<FiringSchedule[]> {
    return this.http
      .get<FiringScheduleVo[]>(`${this.apiBaseUrl}/firing-schedules/all`, {
        params: this.langHelper.langParams(language),
      })
      .pipe(map((schedules) => schedules.map(fromVo)));
  }

  create(schedule: FiringSchedule): Observable<FiringSchedule> {
    return this.http
      .post<FiringScheduleVo>(
        `${this.apiBaseUrl}/firing-schedules/firing-schedule/`,
        toVo(schedule),
      )
      .pipe(map(fromVo));
  }

  update(schedule: FiringSchedule): Observable<FiringSchedule> {
    return this.http
      .put<FiringScheduleVo>(
        `${this.apiBaseUrl}/firing-schedules/firing-schedule/${schedule.id}`,
        toVo(schedule),
      )
      .pipe(map(fromVo));
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/firing-schedules/firing-schedule/${id}`);
  }

  /**
   * Runs the backend's exercise regeneration over the active schedules (bodyless PUT). Answers
   * when the run has finished, with the number of schedules, areas and cancellations it could not do.
   */
  updateFiringExercises(): Observable<FiringRunResult> {
    return this.http.put<FiringRunResult>(
      `${this.apiBaseUrl}/firing-schedules/update-firing-exercises`,
      null,
    );
  }
}
