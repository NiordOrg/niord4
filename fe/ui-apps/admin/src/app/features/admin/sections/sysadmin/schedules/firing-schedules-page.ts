import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { finalize } from 'rxjs';
import { firingRunFailures } from '../../../../../shared/models/firing-run-result.model';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { Pager } from '../../../../../shared/components/pager/pager';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  FiringSchedule,
  FiringSchedulesApiService,
} from '../../../services/api/firing-schedules-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { FiringScheduleEditor, FiringScheduleEditorModel } from './firing-schedule-editor';
import { FiringSchedulesFacade } from './firing-schedules.facade';

/**
 * Firing Schedules section: a cross-domain, client-filtered, client-paged list
 * of firing-schedule config records with a create/edit drawer, confirm-delete,
 * and a cog "Update firing exercises…" action that manually triggers the
 * backend exercise-regeneration pass.
 */
@Component({
  selector: 'app-firing-schedules-page',
  imports: [
    AdminSectionPage,
    Pager,
    Icon,
    Menu,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    FiringScheduleEditor,
    TranslocoPipe,
  ],
  providers: [FiringSchedulesFacade, ConfirmService],
  templateUrl: './firing-schedules-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class FiringSchedulesPage {
  protected readonly facade = inject(FiringSchedulesFacade);
  protected readonly api = inject(FiringSchedulesApiService);
  private readonly domains = inject(DomainFacade);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly search = signal('');

  /**
   * Filter on the source/target domain names OR the raw target series id,
   * case-insensitive.
   */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const schedules = this.facade.schedules();
    if (!term) {
      return schedules;
    }
    return schedules.filter((schedule) => {
      const haystack = [
        schedule.domain?.name ?? '',
        schedule.targetDomain?.name ?? '',
        schedule.targetSeriesId ?? '',
      ]
        .join(' ')
        .toLowerCase();
      return haystack.includes(term);
    });
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  /** Materialize the readonly page slice into a plain array for the template. */
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  protected readonly editorModel = signal<FiringScheduleEditorModel | undefined>(undefined);

  private readonly exercisesLabel = translateSignal('admin.schedules.menu.updateExercises');

  /** True while an Update firing exercises request runs: the cog row is disabled meanwhile. */
  private readonly updatingExercises = signal(false);

  protected readonly cogItems = computed<MenuItem[]>(() => [
    {
      label: this.exercisesLabel(),
      disabled: this.updatingExercises(),
      command: () => this.updateFiringExercises(),
    },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addSchedule(): void {
    // Source domain defaults to the current session domain.
    const sessionDomainId = this.domains.sessionDomain()?.domainId;
    this.editorModel.set({
      mode: 'add',
      schedule: {
        domain: sessionDomainId ? { domainId: sessionDomainId } : undefined,
        active: false,
      },
    });
  }

  protected editSchedule(schedule: FiringSchedule): void {
    this.editorModel.set({ mode: 'edit', schedule });
  }

  protected deleteSchedule(schedule: FiringSchedule): void {
    // Friendly "source → target" summary, falling back to the numeric id.
    const summary =
      schedule.domain?.name && schedule.targetDomain?.name
        ? `${schedule.domain.name} → ${schedule.targetDomain.name}`
        : String(schedule.id ?? '');
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.schedules.confirmDelete.header'),
      message: this.transloco.translate('admin.schedules.confirmDelete.message', { summary }),
      accept: () => {
        if (schedule.id === undefined) {
          return;
        }
        this.api.delete(schedule.id).subscribe({
          next: () => {
            this.notifications.success('admin.schedules.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.schedules.toast.deleteError'),
        });
      },
    });
  }

  /**
   * Cog action: run the exercise regeneration and report how it ended (no
   * pre-confirm — the action is a sysadmin-only trigger). The call answers when
   * the run has finished: a clean run toasts success, a run with failures warns
   * with their number. A press while the request is in flight is ignored.
   */
  private updateFiringExercises(): void {
    if (this.updatingExercises()) {
      return;
    }
    this.updatingExercises.set(true);
    this.api
      .updateFiringExercises()
      .pipe(finalize(() => this.updatingExercises.set(false)))
      .subscribe({
        next: (result) => {
          const count = firingRunFailures(result);
          if (count === 0) {
            this.notifications.success('admin.schedules.toast.exercisesStarted');
          } else {
            this.notifications.warn(
              count === 1
                ? 'schedule.toast.exercisesPartlyFailedOne'
                : 'schedule.toast.exercisesPartlyFailed',
              { count },
            );
          }
        },
        error: () => this.notifications.error('admin.schedules.toast.exercisesError'),
      });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }
}
