import { Component, computed, inject, input, signal } from '@angular/core';
import { disabled, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { AreasField } from '../../../../messages/components/areas-field/areas-field';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import type { NavtexTransmitter } from './promulgation-admin.model';

interface TransmitterFormModel {
  name: string;
  active: boolean;
  areas: readonly AreaRef[];
}

const EMPTY: TransmitterFormModel = { name: '', active: true, areas: [] };

/**
 * NAVTEX admin plugin: the type drawer's "Transmitters" row, whose Manage…
 * button opens a modal dialog ABOVE the drawer (never a second drawer) with an
 * internal list↔edit swap over the type's transmitters.
 *
 * Behavioral notes carried by this component:
 * - the list loads on every dialog open, keyed by the persisted `typeId` with
 *   the current UI language (lang-resolved area names);
 * - transmitter `name` is the PK the PUT/DELETE paths are keyed on — required,
 *   and locked in edit mode;
 * - save and cancel both return to the in-dialog list and refetch it — the
 *   dialog stays open; only Close (or ✕/Esc) dismisses it;
 * - areas are edited as rich picker objects but saved as minimal `{id}` refs
 *   the backend re-resolves — stale desc payloads never round-trip.
 *
 * Deliberate behaviors: save is validity-gated (the required `name` blocks it),
 * delete asks for confirmation rather than deleting instantly, and every
 * load/mutation failure surfaces rather than being swallowed.
 */
@Component({
  selector: 'app-navtex-transmitters-plugin',
  imports: [AreasField, Confirm, Dialog, FormField, Icon, ProgressBar, TranslocoPipe],
  providers: [ConfirmService],
  templateUrl: './navtex-transmitters-plugin.html',
  host: { class: 'contents' },
})
export class NavtexTransmittersPlugin {
  private readonly api = inject(PromulgationAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /** The persisted type's immutable `typeId` — keys every transmitter endpoint. */
  readonly typeId = input.required<string>();
  /** The type's display name, carried into the dialog title. */
  readonly typeName = input.required<string>();

  protected readonly dialogVisible = signal(false);
  /** `undefined` while the list (re)loads. */
  protected readonly transmitters = signal<readonly NavtexTransmitter[] | undefined>(undefined);
  protected readonly loadFailed = signal(false);
  /** The in-dialog swap: `undefined` = list view, otherwise the editor view's mode. */
  protected readonly editing = signal<'add' | 'edit' | undefined>(undefined);
  protected readonly saving = signal(false);

  private readonly formModel = signal<TransmitterFormModel>({ ...EMPTY });
  protected readonly form = form(this.formModel, (path) => {
    required(path.name);
    // name is the PK the PUT/DELETE paths are keyed on — locked in edit mode.
    disabled(path.name, { when: () => this.editing() === 'edit' });
  });

  /** Validity-gated: the required `name` blocks the save. */
  protected readonly saveDisabled = computed(() => this.form().invalid() || this.saving());

  protected readonly headerText = computed(() => {
    this.language.selectedLanguage();
    return this.transloco.translate('admin.promulgation.navtex.title', {
      typeName: this.typeName(),
    });
  });

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
    this.transmitters.set(undefined);
    this.api.getTransmitters(this.typeId()).subscribe({
      next: (transmitters) => this.transmitters.set(transmitters),
      error: () => this.loadFailed.set(true),
    });
  }

  protected addTransmitter(): void {
    // New-transmitter defaults: active, no areas.
    this.openEditor('add', { ...EMPTY });
  }

  protected editTransmitter(transmitter: NavtexTransmitter): void {
    // The stored areas carry lang-resolved descs, so they seed the picker's chips directly.
    this.openEditor('edit', {
      name: transmitter.name,
      active: transmitter.active,
      areas: [...(transmitter.areas ?? [])],
    });
  }

  private openEditor(mode: 'add' | 'edit', value: TransmitterFormModel): void {
    this.formModel.set(value);
    this.editing.set(mode);
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const mode = this.editing();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (mode === undefined || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const transmitter: NavtexTransmitter = {
      // Parent ref in every body — the backend 400s when it mismatches the URL.
      promulgationType: { typeId: this.typeId() },
      name: value.name,
      active: value.active,
      // Minimal {id} refs the backend re-resolves — never the picker's rich objects.
      areas: value.areas.map((area) => ({ id: area.id })),
    };
    const add = mode === 'add';
    const request = add
      ? this.api.createTransmitter(this.typeId(), transmitter)
      : this.api.updateTransmitter(this.typeId(), transmitter);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          add
            ? 'admin.promulgation.navtex.toast.created'
            : 'admin.promulgation.navtex.toast.updated',
        );
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error(
          add
            ? 'admin.promulgation.navtex.toast.createError'
            : 'admin.promulgation.navtex.toast.updateError',
        );
      },
    });
  }

  protected deleteTransmitter(transmitter: NavtexTransmitter): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.promulgation.navtex.confirmDelete.header'),
      message: this.transloco.translate('admin.promulgation.navtex.confirmDelete.message', {
        name: transmitter.name,
      }),
      accept: () => {
        this.api.deleteTransmitter(this.typeId(), transmitter.name).subscribe({
          next: () => {
            this.notifications.success('admin.promulgation.navtex.toast.deleted');
            this.load();
          },
          error: () => this.notifications.error('admin.promulgation.navtex.toast.deleteError'),
        });
      },
    });
  }

  /** One lang-resolved area name per assigned area. */
  protected areaNames(transmitter: NavtexTransmitter): readonly string[] {
    return (transmitter.areas ?? []).map((area) => area.descs?.[0]?.name ?? '');
  }
}
