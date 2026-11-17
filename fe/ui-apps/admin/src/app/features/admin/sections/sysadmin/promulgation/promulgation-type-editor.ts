import { NgComponentOutlet } from '@angular/common';
import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
  type Type,
} from '@angular/core';
import { disabled, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { DomainMultiselectField } from '../../../../../shared/forms/domain-multiselect-field/domain-multiselect-field';
import { Select } from '../../../../../shared/forms/select/select';
import { MAIN_TYPES, type MainType } from '../../../../../shared/models/main-type.model';
import type { MessageType } from '../../../../messages/models/message.model';
import { ScriptResourcesField } from '../../../components/script-resources-field/script-resources-field';
import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import {
  PROMULGATION_ADMIN_PLUGINS,
  type PromulgationAdminPluginInputs,
} from './promulgation-admin-plugin.token';
import { PROMULGATION_REQUIREMENTS } from './promulgation-admin.model';
import {
  RESTRICT_TYPE_GROUPS,
  type PromulgationTypeFormValue,
  type RestrictTypeChecks,
  buildPromulgationTypePayload,
  resolveFreshDomains,
  seedRestrictChecks,
  supportsMainType,
} from './promulgation-type-rules';
import type { PromulgationTypeEditorModel } from './promulgation-types-page';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

type PromulgationTypeFormModel = {
  -readonly [K in keyof PromulgationTypeFormValue]: PromulgationTypeFormValue[K];
};

const EMPTY: PromulgationTypeFormModel = {
  typeId: '',
  name: '',
  active: true,
  requirement: 'OPTIONAL',
  priority: 0,
  language: '',
  domains: [],
  scriptResourcePaths: [],
};

/**
 * Create/edit form for a promulgation type, hosted in the shared drawer.
 * Signal Forms over the scalar fields plus the domain and script-path picker
 * controls; the "Restrict Types" checkbox map lives OUTSIDE the form as a
 * working signal folded into the baseline compare, so toggles flip the save
 * gate like any typed edit.
 *
 * Behavioral notes carried by this component:
 * - edit opens on the copied list row — no detail refetch (the list GET
 *   returns full VOs); the row object itself is never mutated;
 * - the working domains are re-resolved against the session catalog by
 *   `domainId` so the restrict-types support check reads FRESH message
 *   series, never the possibly-stale copies stored on the type;
 * - each restrict group renders only while its main type is supported by the
 *   selected domains (none selected = all supported); save serializes the
 *   checked keys of the supported groups only, NW block first then NM —
 *   deselecting the last domain of a main type silently prunes its saved
 *   sub-types;
 * - `typeId` is the immutable string PK (keys every nested plugin URL) —
 *   locked on edit; `serviceId` is fixed at add time and only displayed.
 */
@Component({
  selector: 'app-promulgation-type-editor',
  imports: [
    LanguageFlag,
    Drawer,
    DomainMultiselectField,
    FormField,
    NgComponentOutlet,
    ScriptResourcesField,
    Select,
    TranslocoPipe,
  ],
  templateUrl: './promulgation-type-editor.html',
  host: { class: 'contents' },
})
export class PromulgationTypeEditor {
  private readonly api = inject(PromulgationAdminApiService);
  private readonly domainFacade = inject(DomainFacade);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<PromulgationTypeEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  protected readonly mainTypes = MAIN_TYPES;
  protected readonly restrictGroups = RESTRICT_TYPE_GROUPS;

  private readonly formModel = signal<PromulgationTypeFormModel>({ ...EMPTY });
  protected readonly form = form(this.formModel, (path) => {
    required(path.typeId);
    // typeId is the immutable string PK (the PUT path id) — locked on edit.
    disabled(path.typeId, { when: () => this.model()?.mode === 'edit' });
    required(path.name);
  });

  /**
   * Working restrict-type checks, kept OUTSIDE the form. Seeded once per open
   * from the entity's `messageTypes` as raw membership (not gated by domain
   * support), so checks survive domain add/remove in-session — the support
   * gate applies at render and at save only.
   */
  protected readonly checks = signal<RestrictTypeChecks>(seedRestrictChecks(undefined));

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does. The
   * checkbox map is folded in so a toggle flips the gate too.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify([this.formModel(), this.checks()]) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.promulgation.editor.editTitle'
      : 'admin.promulgation.editor.addTitle',
  );

  /**
   * Per-group support over the CURRENT domain selection (fresh-catalog
   * objects — see `rebuildForm`). The domains value is read first and
   * unconditionally so the computed tracks every selection change.
   */
  protected readonly supportedGroups = computed<Record<MainType, boolean>>(() => {
    const domains = this.formModel().domains;
    return {
      NW: supportsMainType(domains, 'NW'),
      NM: supportsMainType(domains, 'NM'),
    };
  });

  protected readonly requirementOptions = computed(() => {
    this.language.selectedLanguage();
    return PROMULGATION_REQUIREMENTS.map((value) => ({
      value,
      label: this.transloco.translate(`admin.promulgation.requirement.${value}`),
    }));
  });

  /** The configured model languages (flag + name) plus the blank "any language" option. */
  protected readonly languageOptions = computed(() => {
    this.language.selectedLanguage();
    return [
      {
        value: '',
        label: this.transloco.translate('admin.promulgation.editor.anyLanguage'),
        flagPath: undefined as string | undefined,
      },
      ...this.language.modelLanguages.map(({ value, label, flagPath }) => ({
        value,
        label,
        flagPath,
      })),
    ];
  });

  private readonly adminPlugins = inject(PROMULGATION_ADMIN_PLUGINS, { optional: true }) ?? [];

  /**
   * The registered admin plugin for the opened type's serviceId — persisted
   * (edit-mode) types only, since every nested plugin endpoint requires an
   * existing typeId; add mode shows the "save the type first" hint instead.
   * No registered entry ⇒ `null` ⇒ no plugin section renders at all — a
   * service without admin-side config is a first-class case, not an error.
   */
  protected readonly pluginComponent = computed<Type<unknown> | null>(() => {
    const model = this.model();
    if (model?.mode !== 'edit') {
      return null;
    }
    return (
      this.adminPlugins.find((plugin) => plugin.serviceId === model.type.serviceId)?.component ??
      null
    );
  });

  /** Persisted identifiers only — plugin surfaces are independent of the dirty form values. */
  protected readonly pluginInputs = computed<Record<string, unknown>>(() => {
    const type = this.model()?.type;
    return {
      typeId: type?.typeId ?? '',
      typeName: type?.name ?? '',
    } satisfies PromulgationAdminPluginInputs;
  });

  private readonly typeIdField = viewChild<ElementRef<HTMLInputElement>>('typeIdField');

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — not on every keystroke.
        untracked(() => this.rebuildForm(model));
      }
    });

    // Initial focus lands on the Type ID input — add mode only; in edit mode
    // the input is disabled, so the drawer's default focus stands. The
    // macrotask runs after the drawer's own microtask ✕-focus.
    effect(() => {
      const field = this.typeIdField()?.nativeElement;
      const mode = this.model()?.mode;
      if (field && mode === 'add') {
        setTimeout(() => field.focus(), 0);
      }
    });
  }

  private rebuildForm(model: PromulgationTypeEditorModel): void {
    const type = model.type;
    const next: PromulgationTypeFormModel = {
      typeId: type.typeId,
      name: type.name,
      active: type.active ?? false,
      requirement: type.requirement ?? 'OPTIONAL',
      priority: type.priority ?? 0,
      language: type.language ?? '',
      domains: resolveFreshDomains(type.domains, this.domainFacade.availableDomains()),
      scriptResourcePaths: [...(type.scriptResourcePaths ?? [])],
    };
    const checks = seedRestrictChecks(type.messageTypes);
    this.formModel.set(next);
    this.checks.set(checks);
    this.baselineJson.set(JSON.stringify([next, checks]));
    this.form().reset();
    this.saving.set(false);
  }

  protected groupLabelKey(mainType: MainType): string {
    return mainType === 'NW'
      ? 'admin.promulgation.editor.nwTypes'
      : 'admin.promulgation.editor.nmTypes';
  }

  protected toggleType(type: MessageType): void {
    this.checks.update((checks) => ({ ...checks, [type]: !checks[type] }));
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (!model || this.saveDisabled()) {
      return;
    }
    const payload = buildPromulgationTypePayload(model.type, this.formModel(), this.checks());
    const add = model.mode === 'add';
    const request = add ? this.api.createType(payload) : this.api.updateType(payload);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          add ? 'admin.promulgation.toast.created' : 'admin.promulgation.toast.updated',
        );
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error(
          add ? 'admin.promulgation.toast.createError' : 'admin.promulgation.toast.updateError',
        );
      },
    });
  }
}
