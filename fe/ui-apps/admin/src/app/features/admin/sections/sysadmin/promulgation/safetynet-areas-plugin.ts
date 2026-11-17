import { Component, computed, inject, input, signal } from '@angular/core';
import { disabled, form, FormField, min, pattern, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Select } from '../../../../../shared/forms/select/select';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import {
  SAFETYNET_AREA_TYPES,
  SAFETYNET_COASTAL_AREAS,
  SAFETYNET_NAVAREAS,
  SAFETYNET_REPETITIONS,
  type SafetyNetArea,
  type SafetyNetAreaType,
  type SafetyNetCoastalAreaCode,
  type SafetyNetNavarea,
  type SafetyNetRepetition,
} from './promulgation-admin.model';

/** "Lat Lon" position format: a signed integer pair. */
const LAT_LON_PATTERN = /^-?\d+\s+-?\d+$/;
/** Unsigned-integer format (radius / northings / eastings). */
const UNSIGNED_INT_PATTERN = /^\d+$/;

/**
 * Flat superset of every shape's fields — only the opened shape's fields are
 * rendered, validated and serialized. Blank selects hold `''` in the form and
 * are OMITTED from save payloads.
 */
interface SafetyNetAreaFormModel {
  type: SafetyNetAreaType;
  name: string;
  active: boolean;
  repetition: SafetyNetRepetition | '';
  messageAreaPrefix: string;
  sortOrder: number | null;
  center: string;
  radius: string;
  swCorner: string;
  northings: string;
  eastings: string;
  navarea: SafetyNetNavarea | '';
  coastalArea: SafetyNetCoastalAreaCode | '';
}

/**
 * New-area seeding, shape-exact: common defaults plus only the opened shape's
 * own fields; `repetition`/`messageAreaPrefix` start absent.
 */
function seedArea(typeId: string, type: SafetyNetAreaType): SafetyNetArea {
  const common = {
    promulgationType: { typeId },
    name: '',
    sortOrder: 100,
    active: true,
  };
  switch (type) {
    case 'CIRCULAR':
      return { ...common, type, center: '', radius: '' };
    case 'RECTANGULAR':
      return { ...common, type, swCorner: '', northings: '', eastings: '' };
    case 'COASTAL':
      return { ...common, type, navarea: undefined, coastalArea: undefined };
    case 'NAVAREA':
      return { ...common, type, navarea: undefined };
  }
}

/** The A–Z coastal-area options, generated from the literal source. */
const COASTAL_AREA_OPTIONS = SAFETYNET_COASTAL_AREAS.map((value) => ({ value, label: value }));

/**
 * SafetyNET admin plugin: the type drawer's "Areas" row, whose Manage… button
 * opens a modal dialog ABOVE the drawer (never a second drawer) with an
 * internal list↔edit swap over the type's polymorphic distribution areas.
 *
 * Behavioral notes carried by this component:
 * - the list loads on every dialog open, keyed by the persisted `typeId` with
 *   the current UI language;
 * - the four shapes (CIRCULAR / RECTANGULAR / COASTAL / NAVAREA) are fixed at
 *   add time via the footer's Add Area menu — the edit view shows the shape
 *   read-only and renders only that shape's fields, seeded per shape;
 * - a blank `repetition` is OMITTED from save payloads — never sent as `''`;
 * - save and cancel both return to the in-dialog list and refetch it — the
 *   dialog stays open; only Close (or ✕/Esc) dismisses it;
 * - save builds on the opened working object, so backend-side fields the form
 *   does not touch for that shape survive an edit round-trip.
 *
 * Deliberate behaviors: area `name` is the PK the PUT/DELETE paths are keyed
 * on, so it is locked in edit mode (an editable name would silently break
 * rename); the required/pattern rules actually gate the save; delete asks for
 * confirmation; and every load/mutation failure surfaces.
 */
@Component({
  selector: 'app-safetynet-areas-plugin',
  imports: [
    Confirm,
    Dialog,
    FormField,
    Icon,
    Menu,
    MenuTrigger,
    ProgressBar,
    Select,
    TranslocoPipe,
  ],
  providers: [ConfirmService],
  templateUrl: './safetynet-areas-plugin.html',
  host: { class: 'contents' },
})
export class SafetyNetAreasPlugin {
  private readonly api = inject(PromulgationAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /** The persisted type's immutable `typeId` — keys every area endpoint. */
  readonly typeId = input.required<string>();
  /** The type's display name, carried into the dialog title. */
  readonly typeName = input.required<string>();

  protected readonly dialogVisible = signal(false);
  /** `undefined` while the list (re)loads. */
  protected readonly areas = signal<readonly SafetyNetArea[] | undefined>(undefined);
  protected readonly loadFailed = signal(false);
  /** The in-dialog swap: `undefined` = list view, otherwise the editor view's mode. */
  protected readonly editing = signal<'add' | 'edit' | undefined>(undefined);
  protected readonly saving = signal(false);

  /**
   * The working object the edit view opened on (the per-shape seed on add, the
   * list row on edit) — the save payload spreads it so fields the form does
   * not touch for that shape round-trip untouched. Not rendered, read
   * imperatively at save time only.
   */
  private baseArea: SafetyNetArea | undefined;

  private readonly formModel = signal<SafetyNetAreaFormModel>(
    this.toFormModel(seedArea('', 'NAVAREA')),
  );

  protected readonly form = form(this.formModel, (path) => {
    required(path.name);
    // name is the PK the PUT/DELETE paths are keyed on — locked in edit mode
    // (an editable name-keyed PUT would silently break rename).
    disabled(path.name, { when: () => this.editing() === 'edit' });
    required(path.sortOrder);
    min(path.sortOrder, 0);
    // Shape-conditional rules — these required/pattern constraints apply per
    // shape and gate the save.
    const circular = () => this.formModel().type === 'CIRCULAR';
    const rectangular = () => this.formModel().type === 'RECTANGULAR';
    required(path.center, { when: circular });
    pattern(path.center, LAT_LON_PATTERN, { when: circular });
    required(path.radius, { when: circular });
    pattern(path.radius, UNSIGNED_INT_PATTERN, { when: circular });
    required(path.swCorner, { when: rectangular });
    pattern(path.swCorner, LAT_LON_PATTERN, { when: rectangular });
    required(path.northings, { when: rectangular });
    pattern(path.northings, UNSIGNED_INT_PATTERN, { when: rectangular });
    required(path.eastings, { when: rectangular });
    pattern(path.eastings, UNSIGNED_INT_PATTERN, { when: rectangular });
  });

  /** The opened shape — drives the conditional field rows (fixed at add time). */
  protected readonly shape = computed(() => this.formModel().type);

  /** Validity-gated: the required/pattern rules block the save. */
  protected readonly saveDisabled = computed(() => this.form().invalid() || this.saving());

  protected readonly headerText = computed(() => {
    this.language.selectedLanguage();
    return this.transloco.translate('admin.promulgation.safetynet.title', {
      typeName: this.typeName(),
    });
  });

  /** One Add Area entry per shape, in menu order. */
  protected readonly addItems = computed<MenuItem[]>(() => {
    this.language.selectedLanguage();
    return SAFETYNET_AREA_TYPES.map((type) => ({
      label: this.transloco.translate(this.shapeKey(type)),
      command: () => this.addArea(type),
    }));
  });

  /** Blank (selectable) + the four localized repetition labels. */
  protected readonly repetitionOptions = computed(() => {
    this.language.selectedLanguage();
    return [
      { value: '' as SafetyNetRepetition | '', label: '' },
      ...SAFETYNET_REPETITIONS.map((value) => ({
        value: value as SafetyNetRepetition | '',
        label: this.transloco.translate(`admin.promulgation.safetynet.repetition.${value}`),
      })),
    ];
  });

  /** The 21 NAVAREA/METAREA options with their country labels. */
  protected readonly navareaOptions = computed(() => {
    this.language.selectedLanguage();
    return SAFETYNET_NAVAREAS.map((value) => ({
      value,
      label: this.transloco.translate(`admin.promulgation.safetynet.navareaOptions.${value}`),
    }));
  });

  protected readonly coastalAreaOptions = COASTAL_AREA_OPTIONS;

  /** The localized shape-label key ("Circular" / … / "NAVAREA") — never the raw enum. */
  protected shapeKey(type: SafetyNetAreaType): string {
    return `admin.promulgation.safetynet.shape.${type.toLowerCase()}`;
  }

  protected openDialog(): void {
    this.dialogVisible.set(true);
    this.load();
  }

  /**
   * (Re)load the list — also the return-to-list path after save, cancel and
   * delete (every one of those refetches). A load failure renders inline in
   * the dialog with a retry.
   */
  protected load(): void {
    this.editing.set(undefined);
    this.loadFailed.set(false);
    this.areas.set(undefined);
    this.api.getSafetyNetAreas(this.typeId()).subscribe({
      next: (areas) => this.areas.set(areas),
      error: () => this.loadFailed.set(true),
    });
  }

  protected addArea(type: SafetyNetAreaType): void {
    this.openEditor('add', seedArea(this.typeId(), type));
  }

  protected editArea(area: SafetyNetArea): void {
    this.openEditor('edit', area);
  }

  private openEditor(mode: 'add' | 'edit', base: SafetyNetArea): void {
    this.baseArea = base;
    this.formModel.set(this.toFormModel(base));
    this.editing.set(mode);
    this.form().reset();
    this.saving.set(false);
  }

  private toFormModel(base: SafetyNetArea): SafetyNetAreaFormModel {
    return {
      type: base.type,
      name: base.name,
      active: base.active,
      repetition: base.repetition ?? '',
      messageAreaPrefix: base.messageAreaPrefix ?? '',
      sortOrder: base.sortOrder,
      center: base.type === 'CIRCULAR' ? (base.center ?? '') : '',
      radius: base.type === 'CIRCULAR' ? (base.radius ?? '') : '',
      swCorner: base.type === 'RECTANGULAR' ? (base.swCorner ?? '') : '',
      northings: base.type === 'RECTANGULAR' ? (base.northings ?? '') : '',
      eastings: base.type === 'RECTANGULAR' ? (base.eastings ?? '') : '',
      navarea: base.type === 'COASTAL' || base.type === 'NAVAREA' ? (base.navarea ?? '') : '',
      coastalArea: base.type === 'COASTAL' ? (base.coastalArea ?? '') : '',
    };
  }

  /**
   * The save payload: the opened working object, overlaid with the common
   * fields and only the opened shape's own fields. Blank optionals override to
   * `undefined` so they are OMITTED from the JSON body (a blank `repetition`
   * and blank selects are dropped) — never `''`.
   */
  private buildPayload(base: SafetyNetArea): SafetyNetArea {
    const value = this.formModel();
    const common = {
      // Parent ref in every body — the backend 400s when it mismatches the URL.
      promulgationType: { typeId: this.typeId() },
      name: value.name,
      active: value.active,
      sortOrder: value.sortOrder ?? 0,
      repetition: value.repetition || undefined,
      messageAreaPrefix: value.messageAreaPrefix || undefined,
    };
    switch (base.type) {
      case 'CIRCULAR':
        return { ...base, ...common, center: value.center, radius: value.radius };
      case 'RECTANGULAR':
        return {
          ...base,
          ...common,
          swCorner: value.swCorner,
          northings: value.northings,
          eastings: value.eastings,
        };
      case 'COASTAL':
        return {
          ...base,
          ...common,
          navarea: value.navarea || undefined,
          coastalArea: value.coastalArea || undefined,
        };
      case 'NAVAREA':
        return { ...base, ...common, navarea: value.navarea || undefined };
    }
  }

  protected save(): void {
    const mode = this.editing();
    const base = this.baseArea;
    // Guards the Enter-submit path on the same gate as the footer button.
    if (mode === undefined || base === undefined || this.saveDisabled()) {
      return;
    }
    const area = this.buildPayload(base);
    const add = mode === 'add';
    const request = add
      ? this.api.createSafetyNetArea(this.typeId(), area)
      : this.api.updateSafetyNetArea(this.typeId(), area);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          add
            ? 'admin.promulgation.safetynet.toast.created'
            : 'admin.promulgation.safetynet.toast.updated',
        );
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error(
          add
            ? 'admin.promulgation.safetynet.toast.createError'
            : 'admin.promulgation.safetynet.toast.updateError',
        );
      },
    });
  }

  protected deleteArea(area: SafetyNetArea): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.promulgation.safetynet.confirmDelete.header'),
      message: this.transloco.translate('admin.promulgation.safetynet.confirmDelete.message', {
        name: area.name,
      }),
      accept: () => {
        this.api.deleteSafetyNetArea(this.typeId(), area.name).subscribe({
          next: () => {
            this.notifications.success('admin.promulgation.safetynet.toast.deleted');
            this.load();
          },
          error: () => this.notifications.error('admin.promulgation.safetynet.toast.deleteError'),
        });
      },
    });
  }
}
