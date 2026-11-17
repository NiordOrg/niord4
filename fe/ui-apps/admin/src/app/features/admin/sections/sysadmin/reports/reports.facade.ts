import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError, map, startWith, switchMap } from 'rxjs/operators';

import {
  MessageReportsAdminApiService,
  SystemMessageReport,
} from '../../../services/api/message-reports-admin-api.service';

interface ReportsState {
  readonly reports: readonly SystemMessageReport[];
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: ReportsState = { reports: [], loading: true, error: false };

/** Page-scoped list state for Print Reports — refetch tick → `getAll()`. */
@Injectable()
export class ReportsFacade {
  private readonly api = inject(MessageReportsAdminApiService);

  private readonly refreshTick = signal(0);

  private readonly state = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getAll().pipe(
          map((reports): ReportsState => ({ reports, loading: false, error: false })),
          catchError(() => of<ReportsState>({ reports: [], loading: false, error: true })),
          startWith(LOADING),
        ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly reports = computed(() => this.state().reports);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
