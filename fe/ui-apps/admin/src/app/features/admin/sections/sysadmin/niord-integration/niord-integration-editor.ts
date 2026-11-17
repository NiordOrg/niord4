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
import { applyEach, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { Select } from '../../../../../shared/forms/select/select';
import {
  NiordIntegration,
  NiordIntegrationsApiService,
} from '../../../services/api/niord-integrations-api.service';

export interface NiordIntegrationEditorModel {
  readonly mode: 'add' | 'edit';
  readonly integration: NiordIntegration;
}

interface SeriesOption {
  readonly label: string;
  readonly value: string;
}

interface MappingRow {
  sourceSeriesId: string;
  targetSeriesId: string;
}

interface NiordIntegrationFormModel {
  url: string;
  active: boolean;
  assignNewUids: boolean;
  createBaseData: boolean;
  messageSeriesMappings: MappingRow[];
}

/** The editor keeps at least one mapping row (seeded blank when empty). */
const blankRow = (): MappingRow => ({ sourceSeriesId: '', targetSeriesId: '' });

/**
 * Create/edit form for a Niord integration, hosted in the shared drawer.
 *
 * Header fields: a read-only ID (blank on add), three flags with the default
 * values (active=true, assignNewUids=true, createBaseData=false), and a
 * required URL (plain required text — NO URL-shape validation). Below
 * them, a repeating source→target message-series mapping FormArray that always
 * holds at least one row: seeded on add, seeded on edit when the persisted list
 * is empty, and re-seeded blank when the last row is deleted. The source input
 * is free text (no validator — it names an un-enumerable remote series); the
 * target select is required over the full local series list (value = label =
 * raw seriesId). The whole record is saved verbatim with no blank-row pruning.
 */
@Component({
  selector: 'app-niord-integration-editor',
  imports: [Drawer, FormField, Icon, Select, TranslocoPipe],
  templateUrl: './niord-integration-editor.html',
  host: { class: 'contents' },
})
export class NiordIntegrationEditor {
  private readonly api = inject(NiordIntegrationsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<NiordIntegrationEditorModel | undefined>();
  /** The full, cross-domain target-series option list (raw seriesIds). */
  readonly seriesIds = input.required<readonly string[]>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  /** Target-series options — value === label === raw seriesId. */
  protected readonly seriesOptions = computed<SeriesOption[]>(() =>
    this.seriesIds().map((seriesId) => ({ label: seriesId, value: seriesId })),
  );

  private readonly formModel = signal<NiordIntegrationFormModel>({
    url: '',
    active: true,
    assignNewUids: true,
    createBaseData: false,
    messageSeriesMappings: [],
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.url);
    // Each mapping's target series is required; source is free text (remote name).
    applyEach(path.messageSeriesMappings, (row) => {
      required(row.targetSeriesId);
    });
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
      ? 'admin.niord-integration.editor.editTitle'
      : 'admin.niord-integration.editor.addTitle',
  );

  /** The read-only ID shown in the header (blank on add). */
  protected readonly displayId = computed(() => {
    const id = this.model()?.integration.id;
    return id === undefined ? '' : String(id);
  });

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — not on every
        // keystroke or option recompute.
        untracked(() => this.rebuildForm(model.integration));
      }
    });
  }

  private rebuildForm(integration: NiordIntegration): void {
    const persisted = integration.messageSeriesMappings;
    // Seed one blank row on add / when the persisted list is empty; otherwise keep
    // the persisted rows (always ≥1 row in the editor).
    const rows: MappingRow[] =
      persisted.length === 0
        ? [blankRow()]
        : persisted.map((m) => ({
            sourceSeriesId: m.sourceSeriesId,
            targetSeriesId: m.targetSeriesId ?? '',
          }));
    const next: NiordIntegrationFormModel = {
      url: integration.url,
      active: integration.active,
      assignNewUids: integration.assignNewUids,
      createBaseData: integration.createBaseData,
      messageSeriesMappings: rows,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected addMapping(): void {
    this.formModel.update((m) => ({
      ...m,
      messageSeriesMappings: [...m.messageSeriesMappings, blankRow()],
    }));
  }

  /** Remove a mapping row; re-seed a blank when that emptied the list (≥1-row invariant). */
  protected removeMapping(index: number): void {
    this.formModel.update((m) => {
      const next = m.messageSeriesMappings.filter((_, i) => i !== index);
      return { ...m, messageSeriesMappings: next.length > 0 ? next : [blankRow()] };
    });
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const integration: NiordIntegration = {
      id: model.integration.id,
      url: value.url,
      active: value.active,
      assignNewUids: value.assignNewUids,
      createBaseData: value.createBaseData,
      // Send verbatim — every row, including blank/trailing rows; an empty
      // target stays undefined here and maps to null on the wire.
      messageSeriesMappings: value.messageSeriesMappings.map((row) => ({
        sourceSeriesId: row.sourceSeriesId,
        targetSeriesId: row.targetSeriesId === '' ? undefined : row.targetSeriesId,
      })),
    };
    const request =
      model.mode === 'add' ? this.api.create(integration) : this.api.update(integration);
    this.saving.set(true);
    request.subscribe({
      next: () => this.saved.emit(),
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.niord-integration.toast.saveError');
      },
    });
  }
}
