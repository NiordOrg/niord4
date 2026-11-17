import { Component, computed, inject, signal, input } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import type { TwitterSettings } from './promulgation-admin.model';

interface TwitterSettingsFormModel {
  format: string;
  includeThumbnail: boolean;
  apiKey: string;
  apiSecret: string;
  accessToken: string;
  accessTokenSecret: string;
}

const EMPTY: TwitterSettingsFormModel = {
  format: '',
  includeThumbnail: true,
  apiKey: '',
  apiSecret: '',
  accessToken: '',
  accessTokenSecret: '',
};

/**
 * Twitter admin plugin: the type drawer's "Settings" row, whose Update
 * Settings… button opens a modal dialog ABOVE the drawer (never a second
 * drawer). Unlike the NAVTEX/SafetyNET plugins there is no list↔edit swap —
 * the settings are a SINGLETON per type and the dialog is the one form.
 *
 * Behavioral notes carried by this component:
 * - create-on-demand: the settings GET legitimately returns a null body while
 *   the type has none yet — a null body OR a load error silently flips the
 *   dialog to add-mode with fresh defaults (thumbnail on, credentials empty);
 *   a loaded record opens in edit-mode with every persisted value seeded into
 *   the inputs — including the two secrets, which the backend returns in
 *   clear text and the form renders into password inputs;
 * - a successful save CLOSES the dialog (unlike the sibling plugins' return
 *   to an in-dialog list); Close dismisses without saving;
 * - save is gated on "changed AND valid" (Save is disabled while pristine or
 *   invalid), with all four credentials required.
 *
 * Deliberately, save failures surface as distinct error toasts and keep the
 * dialog (and the entered values) open.
 */
@Component({
  selector: 'app-twitter-settings-plugin',
  imports: [Dialog, FormField, ProgressBar, TranslocoPipe],
  templateUrl: './twitter-settings-plugin.html',
  host: { class: 'contents' },
})
export class TwitterSettingsPlugin {
  private readonly api = inject(PromulgationAdminApiService);
  private readonly notifications = inject(NotificationService);

  /** The persisted type's immutable `typeId` — keys the settings singleton. */
  readonly typeId = input.required<string>();
  /**
   * Part of the fixed plugin-input contract (the outlet always binds it). The
   * dialog title is a fixed "OAuth Settings", so the name is not displayed here.
   */
  readonly typeName = input.required<string>();

  protected readonly dialogVisible = signal(false);
  /** `undefined` while the settings (re)load; then the create-on-demand flip's outcome. */
  protected readonly mode = signal<'add' | 'edit' | undefined>(undefined);
  protected readonly saving = signal(false);

  /**
   * The record the form opened on (the loaded singleton, or the add-mode
   * defaults) — the save payload spreads it so backend-side fields the form
   * does not touch round-trip untouched. Read imperatively at save time only.
   */
  private baseSettings: TwitterSettings | undefined;

  private readonly formModel = signal<TwitterSettingsFormModel>({ ...EMPTY });
  protected readonly form = form(this.formModel, (path) => {
    // The four credentials are required; format is optional.
    required(path.apiKey);
    required(path.apiSecret);
    required(path.accessToken);
    required(path.accessTokenSecret);
  });

  /**
   * Net change against the opened values — the "not pristine" half of the save
   * gate. Signal Forms' `dirty` tracks interaction and never clears on revert,
   * so a serialized baseline comparison stands in.
   */
  private readonly baselineJson = signal(JSON.stringify(EMPTY));
  private readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  /** Save disabled while pristine or invalid (and while loading/saving). */
  protected readonly saveDisabled = computed(
    () => this.mode() === undefined || !this.changed() || this.form().invalid() || this.saving(),
  );

  protected openDialog(): void {
    this.dialogVisible.set(true);
    this.load();
  }

  /**
   * (Re)load the singleton on every dialog open. A null body (no settings
   * yet) OR an error flips to add-mode — silently: the flip IS the
   * create-on-demand affordance, not a failure state.
   */
  private load(): void {
    this.mode.set(undefined);
    this.api.getTwitterSettings(this.typeId()).subscribe({
      next: (settings) => (settings ? this.openEditor('edit', settings) : this.flipToCreate()),
      error: () => this.flipToCreate(),
    });
  }

  /** Add-mode defaults: thumbnail on, format + credentials empty. */
  private flipToCreate(): void {
    this.openEditor('add', {
      promulgationType: { typeId: this.typeId() },
      includeThumbnail: true,
    });
  }

  private openEditor(mode: 'add' | 'edit', base: TwitterSettings): void {
    this.baseSettings = base;
    const next: TwitterSettingsFormModel = {
      format: base.format ?? '',
      includeThumbnail: base.includeThumbnail ?? false,
      apiKey: base.apiKey ?? '',
      apiSecret: base.apiSecret ?? '',
      accessToken: base.accessToken ?? '',
      accessTokenSecret: base.accessTokenSecret ?? '',
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.mode.set(mode);
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const mode = this.mode();
    const base = this.baseSettings;
    // Guards the Enter-submit path on the same gate as the footer button.
    if (mode === undefined || base === undefined || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const settings: TwitterSettings = {
      ...base,
      // Parent ref in the body — the backend 400s when it mismatches the URL.
      promulgationType: { typeId: this.typeId() },
      // Blank optional → undefined, so it is OMITTED from the JSON body.
      format: value.format || undefined,
      includeThumbnail: value.includeThumbnail,
      apiKey: value.apiKey,
      apiSecret: value.apiSecret,
      accessToken: value.accessToken,
      accessTokenSecret: value.accessTokenSecret,
    };
    const add = mode === 'add';
    const request = add
      ? this.api.createTwitterSettings(settings)
      : this.api.updateTwitterSettings(settings);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          add
            ? 'admin.promulgation.twitter.toast.created'
            : 'admin.promulgation.twitter.toast.updated',
        );
        // A successful save CLOSES the dialog (unlike the NAVTEX/SafetyNET
        // plugins' return to an in-dialog list).
        this.dialogVisible.set(false);
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error(
          add
            ? 'admin.promulgation.twitter.toast.createError'
            : 'admin.promulgation.twitter.toast.updateError',
        );
      },
    });
  }
}
