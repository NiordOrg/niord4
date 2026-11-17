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
import { disabled, form, FormField, pattern, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  formStringToJsonObject,
  jsonObjectToFormString,
  jsonStringValid,
} from '../../../../../shared/forms/json-syntax.validator';
import { DomainMultiselectField } from '../../../../../shared/forms/domain-multiselect-field/domain-multiselect-field';
import { SingleTagField } from '../../../../../shared/forms/single-tag-field/single-tag-field';
import type {
  MessageReport,
  MessageReportProperties,
} from '../../../../../shared/models/print/message-report.model';
import type { ReportParams } from '../../../../../shared/models/print/print-settings.model';
import { MessagePrintService } from '../../../../messages/services/message-print.service';
import { CodeTextInput } from '../../../components/code-text-input/code-text-input';
import { ScriptResourcesField } from '../../../components/script-resources-field/script-resources-field';
import {
  MessageReportsAdminApiService,
  SystemMessageReport,
} from '../../../services/api/message-reports-admin-api.service';

export interface ReportEditorModel {
  readonly mode: 'add' | 'edit';
  readonly report: SystemMessageReport;
}

interface ReportFormModel {
  reportId: string;
  name: string;
  sortOrder: number;
  templatePath: string;
  domains: NonNullable<SystemMessageReport['domains']>;
  properties: string;
  params: string;
}

/**
 * Create/edit print-report form in the shared drawer. `reportId` is immutable on
 * edit; the template path uses the shared single-path picker (FM-only); domains is
 * a multiselect (empty = all); `properties`/`params` are JSON editors (parse-
 * validated, Save-gated); a test-print row prints messages of a chosen tag using
 * the in-edit (possibly unsaved) report.
 */
@Component({
  selector: 'app-report-editor',
  imports: [
    Drawer,
    FormField,
    ScriptResourcesField,
    DomainMultiselectField,
    SingleTagField,
    CodeTextInput,
    TranslocoPipe,
  ],
  templateUrl: './report-editor.html',
  host: { class: 'contents' },
})
export class ReportEditor {
  private readonly api = inject(MessageReportsAdminApiService);
  private readonly printService = inject(MessagePrintService);
  private readonly notifications = inject(NotificationService);

  readonly model = input.required<ReportEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  /** Test-print tag (page-local, not part of the saved report form). */
  protected readonly testTagId = signal<string | null>(null);

  private readonly formModel = signal<ReportFormModel>({
    reportId: '',
    name: '',
    sortOrder: 10,
    templatePath: '',
    domains: [],
    properties: '',
    params: '',
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.reportId);
    // reportId is the immutable PK (the PUT path id) — locked on edit.
    disabled(path.reportId, { when: () => this.model()?.mode === 'edit' });
    required(path.name);
    // The template path is required and must be an FM template (the picker is FM-only).
    required(path.templatePath);
    pattern(path.templatePath, () => /^(?:.+\.(ftl|FTL))$/);
    // domains is optional (empty = all); properties/params JSON validity is gated below.
  });

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.reports.editor.editTitle'
      : 'admin.reports.editor.addTitle',
  );

  protected readonly propertiesInvalid = computed(
    () => !jsonStringValid(this.formModel().properties),
  );
  protected readonly paramsInvalid = computed(() => !jsonStringValid(this.formModel().params));

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
    () =>
      !this.changed() ||
      this.form().invalid() ||
      this.propertiesInvalid() ||
      this.paramsInvalid() ||
      this.saving(),
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => this.rebuildForm(model));
      }
    });
  }

  private rebuildForm(model: ReportEditorModel): void {
    const report = model.report;
    const next: ReportFormModel = {
      reportId: report.reportId,
      name: report.name,
      sortOrder: report.sortOrder ?? 10,
      templatePath: report.templatePath,
      domains: [...(report.domains ?? [])],
      properties: jsonObjectToFormString(report.properties),
      params: jsonObjectToFormString(report.params),
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.testTagId.set(null);
    this.saving.set(false);
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const raw = this.formModel();
    const report: SystemMessageReport = {
      reportId: raw.reportId,
      name: raw.name,
      sortOrder: raw.sortOrder,
      templatePath: raw.templatePath,
      // Narrow to the wire shape — don't post the full core Domain (areas, series, lat/lon…).
      domains: (raw.domains ?? []).map((domain) => ({
        domainId: domain.domainId,
        name: domain.name,
        active: domain.active,
      })),
      properties: (formStringToJsonObject(raw.properties) as Record<string, unknown>) ?? {},
      params: (formStringToJsonObject(raw.params) as Record<string, unknown>) ?? {},
    };
    const request = model.mode === 'add' ? this.api.create(report) : this.api.update(report);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.reports.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.reports.toast.saveError');
      },
    });
  }

  /**
   * Test-print the in-edit report. The PDF is rendered by
   * `reportId` on the backend; the in-edit `params`/`properties` only seed the
   * print dialog's fields + thumbnail-checkbox visibility. Always available — the
   * tag narrows the printed messages but is optional, and malformed in-progress
   * JSON is tolerated (it just seeds nothing) so the action never throws.
   */
  protected testPrint(): void {
    const raw = this.formModel();
    const report: MessageReport = {
      reportId: raw.reportId,
      name: raw.name,
      properties: this.safeJson<MessageReportProperties>(raw.properties),
      params: this.safeJson<ReportParams>(raw.params),
    };
    this.printService.printReportTest(report, this.testTagId() ?? undefined).subscribe();
  }

  /** Parse a JSON form string, tolerating malformed in-progress input (→ {}). */
  private safeJson<T>(raw: string): T {
    try {
      return (formStringToJsonObject(raw) as T) ?? ({} as T);
    } catch {
      return {} as T;
    }
  }
}
