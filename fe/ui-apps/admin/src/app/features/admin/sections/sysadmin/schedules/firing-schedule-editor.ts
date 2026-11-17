import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, min, required, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { Select } from '../../../../../shared/forms/select/select';
import {
  FiringSchedule,
  FiringSchedulesApiService,
} from '../../../services/api/firing-schedules-api.service';

export interface FiringScheduleEditorModel {
  readonly mode: 'add' | 'edit';
  readonly schedule: FiringSchedule;
}

interface DomainOption {
  readonly label: string;
  readonly value: string;
}

interface ScheduleFormModel {
  sourceDomainId: string;
  targetDomainId: string;
  targetSeriesId: string;
  scheduleDays: number | null;
  active: boolean;
}

/**
 * Create/edit form for a firing schedule, hosted in the shared drawer. Five
 * fields: source domain, target domain, target series, scheduled days, active.
 *
 * The three domain/series selects are required; scheduled-days is optional
 * (empty means the firingExerciseScheduleDays setting) and, when given, a
 * whole number of 1 or more; active is a plain
 * checkbox. The target-series option list is dependent: it repopulates from the
 * selected TARGET domain's `messageSeries`, clearing the current series when the
 * target domain changes, and auto-selecting the sole series when the target
 * domain has exactly one. A failed save keeps the drawer open with values intact.
 */
@Component({
  selector: 'app-firing-schedule-editor',
  imports: [Drawer, FormField, Select, TranslocoPipe],
  templateUrl: './firing-schedule-editor.html',
  host: { class: 'contents' },
})
export class FiringScheduleEditor {
  private readonly api = inject(FiringSchedulesApiService);
  private readonly domains = inject(DomainFacade);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<FiringScheduleEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  /** Editable-domain picker options (source + target share the same list). */
  protected readonly domainOptions = computed<DomainOption[]>(() =>
    this.domains
      .editableDomains()
      .map((domain) => ({ label: domain.name ?? domain.domainId, value: domain.domainId })),
  );

  private readonly formModel = signal<ScheduleFormModel>({
    sourceDomainId: '',
    targetDomainId: '',
    targetSeriesId: '',
    scheduleDays: null,
    active: false,
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.sourceDomainId);
    required(path.targetDomainId);
    required(path.targetSeriesId);
    // scheduleDays is an optional horizon: empty means the
    // firingExerciseScheduleDays setting. A value is a whole number of 1 or
    // more: a negative one made the hourly run cancel every current firing
    // exercise of the schedule, and the backend reads 1.5 as 1 without a word,
    // so this is the one place a fraction is caught. `min` also puts min="1"
    // on the input through [formField].
    min(path.scheduleDays, 1);
    validate(path.scheduleDays, ({ value }) => {
      const days = value();
      return days === null || Number.isInteger(days) ? null : { kind: 'wholeNumber' };
    });
  });

  /** The scheduled days break the rule above; the field shows why. */
  protected readonly scheduleDaysInvalid = computed(() => this.form.scheduleDays().invalid());

  /**
   * Target-series options for the selected target domain — the domain VO's
   * `messageSeries[].seriesId`. Value === label === raw series id (no name
   * lookup, matching the list column).
   */
  protected readonly seriesOptions = computed<DomainOption[]>(() => {
    const targetId = this.form.targetDomainId().value();
    if (!targetId) {
      return [];
    }
    const domain = this.domains.editableDomains().find((d) => d.domainId === targetId);
    return (domain?.messageSeries ?? []).map((series) => ({
      label: series.seriesId,
      value: series.seriesId,
    }));
  });

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.schedules.editor.editTitle'
      : 'admin.schedules.editor.addTitle',
  );

  /** Last target domain the dependent-series handler acted on (see the effect below). */
  private prevTargetDomain = '';

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — not on every
        // keystroke or option recompute.
        untracked(() => this.rebuildForm(model.schedule));
      }
    });

    // Dependent-series behaviour: when the target domain changes, clear the
    // series and re-derive its options,
    // auto-selecting the sole series when the new target has exactly one. The
    // effect reads only targetDomainId, so writing targetSeriesId never re-runs
    // it. `prevTargetDomain` is seeded synchronously in rebuildForm to the opened
    // value, so the first settle after a (re)open is a no-op (the persisted series
    // is preserved) — a reactive `rebuilding` flag can't gate an async effect.
    effect(() => {
      const targetId = this.form.targetDomainId().value();
      untracked(() => {
        if (targetId === this.prevTargetDomain) {
          return;
        }
        this.prevTargetDomain = targetId;
        const seriesIds = this.seriesIdsFor(targetId);
        this.form.targetSeriesId().value.set(seriesIds.length === 1 ? seriesIds[0] : '');
      });
    });
  }

  private seriesIdsFor(targetDomainId: string): readonly string[] {
    const domain = this.domains.editableDomains().find((d) => d.domainId === targetDomainId);
    return (domain?.messageSeries ?? []).map((series) => series.seriesId);
  }

  private rebuildForm(schedule: FiringSchedule): void {
    const sourceDomainId =
      schedule.domain?.domainId ?? this.domains.sessionDomain()?.domainId ?? '';
    const targetDomainId = schedule.targetDomain?.domainId ?? '';

    // On edit, the persisted series stays; only when the target domain has
    // exactly one series is it auto-selected (a no-op when that one series IS
    // the persisted value).
    const seriesIds = this.seriesIdsFor(targetDomainId);
    const targetSeriesId = seriesIds.length === 1 ? seriesIds[0] : (schedule.targetSeriesId ?? '');

    const next: ScheduleFormModel = {
      sourceDomainId,
      targetDomainId,
      targetSeriesId,
      scheduleDays: schedule.scheduleDays ?? null,
      active: schedule.active,
    };
    // Seed the guard to the opened target so the dependent-series effect's first
    // run doesn't clobber the persisted series.
    this.prevTargetDomain = targetDomainId;
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const schedule: FiringSchedule = {
      id: model.schedule.id,
      domain: { domainId: value.sourceDomainId },
      targetDomain: { domainId: value.targetDomainId },
      targetSeriesId: value.targetSeriesId,
      scheduleDays: value.scheduleDays ?? undefined,
      active: value.active,
    };
    const request = model.mode === 'add' ? this.api.create(schedule) : this.api.update(schedule);
    this.saving.set(true);
    request.subscribe({
      next: () => this.saved.emit(),
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.schedules.toast.saveError');
      },
    });
  }
}
