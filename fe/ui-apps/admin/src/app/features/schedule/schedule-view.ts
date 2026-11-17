import { Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import { finalize } from 'rxjs';

import { Icon } from '../../shared/icons/icon';
import { APP_CONFIG } from '../../core/config/app-config.token';
import { DomainFacade } from '../../core/domain/facades/domain.facade';
import { LanguageService } from '../../core/main-shell/services/language.service';
import { NotificationService } from '../../core/notifications/notification.service';
import {
  type FiringRunResult,
  firingRunFailures,
} from '../../shared/models/firing-run-result.model';
import { ConfirmService } from '../../shared/services/confirm.service';
import { DatePicker } from '../../shared/forms/date-picker/date-picker';
import { Confirm } from '../../shared/components/confirm/confirm';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../shared/components/error-banner/error-banner';
import { FilterPanel } from '../../shared/components/filter-panel/filter-panel';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import { BaseMapLayers } from '../../shared/components/ol-map/base-map-layers';
import { computeInitialMapView } from '../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { OlMap, type OlMapView } from '@niord/map/ol-map';
import { TreePicker } from '../../shared/components/tree-picker/tree-picker';
// Sanctioned cross-feature reuse from the messages area/map kit:
import { OlAreasLayer } from '../messages/components/map-layers/ol-areas-layer';
import type { AreaRef } from '../messages/models/messages-filter-params.model';
import { TimeScheduleEditor } from './components/time-schedule-editor/time-schedule-editor';
import { ScheduleApiService } from './services/api/schedule-api.service';
import { ScheduleFacade } from './schedule.facade';

/**
 * Firing Area Schedule page — centered content (messages/editor layout). Filter
 * bar (area-name query + area popover-select), date navigation, a top-area-grouped
 * schedule table with an inline per-row interval editor, a side-by-side
 * firing-areas map, and the Update Firing Exercises action.
 */
@Component({
  selector: 'app-schedule-view',
  imports: [
    TranslocoPipe,
    Icon,
    DatePicker,
    EmptyState,
    ErrorBanner,
    FilterPanel,
    ProgressBar,
    TreePicker,
    OlMap,
    BaseMapLayers,
    OlAreasLayer,
    OlLayerSwitcher,
    TimeScheduleEditor,
    Confirm,
  ],
  // The map shell is provided here so the projected layers + the layer switcher
  // (all inside <app-ol-map>) share OlMap's reused instance (skipSelf factory).
  // ConfirmService is re-provided so the component-scoped <app-confirm> renders this page's confirms.
  providers: [ScheduleFacade, MapShellService, ConfirmService],
  templateUrl: './schedule-view.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class ScheduleView {
  protected readonly facade = inject(ScheduleFacade);
  private readonly domain = inject(DomainFacade);
  private readonly config = inject(APP_CONFIG);
  private readonly language = inject(LanguageService);
  private readonly scheduleApi = inject(ScheduleApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly firingScheduleDomain = computed(
    () => this.domain.capabilities().firingSchedule,
  );

  /** Area id under the pointer in the table — highlighted on the map. */
  protected readonly hoveredAreaId = signal<number | undefined>(undefined);

  protected readonly areasName = translateSignal('messages.map.layers.areas');
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  // --- Area filter (popover tree-select over the area roots) ---
  private readonly rootsResource = httpResource<readonly AreaRef[]>(
    () => ({
      url: `${this.config.apiBaseUrl}/areas/area-roots`,
      params: { lang: this.language.selectedLanguage() },
    }),
    { defaultValue: [] },
  );
  // Guard the read: httpResource.value() throws in the error state (a failed /areas/area-roots
  // would otherwise throw inside this computed during change detection and blank the view).
  protected readonly areaRoots = computed(() =>
    this.rootsResource.hasValue() ? [...this.rootsResource.value()] : [],
  );
  protected readonly selectedAreaIds = computed(() => this.facade.selectedAreas().map((a) => a.id));

  protected onAreasChange(areas: readonly AreaRef[]): void {
    this.facade.selectedAreas.set([...areas]);
  }

  protected removeArea(area: AreaRef): void {
    this.facade.selectedAreas.set(this.facade.selectedAreas().filter((a) => a.id !== area.id));
  }

  protected clearAreas(): void {
    this.facade.selectedAreas.set([]);
  }

  /** Current-language area name (the search is lang-scoped, so descs[0] is the active language). */
  protected areaName(area: AreaRef): string {
    return area.descs?.[0]?.name ?? '';
  }

  /** True while an Update Firing Exercises request runs: the button is disabled meanwhile. */
  protected readonly updatingExercises = signal(false);

  /**
   * Confirm, then regenerate the firing-exercise messages from the active schedules. The run
   * answers what it could not do: a clean run toasts success, a run with failures warns with their
   * number. A press while a run of this page is in flight is ignored.
   */
  protected updateExercises(): void {
    if (this.updatingExercises()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('schedule.confirmUpdateExercises.header'),
      message: this.transloco.translate('schedule.confirmUpdateExercises.message'),
      accept: () => {
        if (this.updatingExercises()) {
          return;
        }
        this.updatingExercises.set(true);
        this.scheduleApi
          .updateFiringExercises()
          .pipe(finalize(() => this.updatingExercises.set(false)))
          .subscribe({
            next: (result) => this.reportRun(result),
            error: () => this.notifications.error('schedule.toast.exercisesError'),
          });
      },
    });
  }

  private reportRun(result: FiringRunResult | null): void {
    const count = firingRunFailures(result);
    if (count === 0) {
      this.notifications.success('schedule.toast.exercisesUpdated');
    } else {
      this.notifications.warn(
        count === 1
          ? 'schedule.toast.exercisesPartlyFailedOne'
          : 'schedule.toast.exercisesPartlyFailed',
        { count },
      );
    }
  }
}
