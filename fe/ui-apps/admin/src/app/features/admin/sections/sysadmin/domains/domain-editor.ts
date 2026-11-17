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
} from '@angular/core';
import { disabled, form, FormField, max, min, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { Select } from '../../../../../shared/forms/select/select';
import { AreasField } from '../../../../messages/components/areas-field/areas-field';
import { CategoriesField } from '../../../../messages/components/categories-field/categories-field';
import { MessageSeriesField } from '../../../../messages/components/message-series-field/message-series-field';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';
import {
  MESSAGE_SORT_ORDER_OPTIONS,
  ZOOM_MAX,
  ZOOM_MIN,
  buildDomainPayload,
  timeZoneOptions,
  type DomainFormValue,
} from './domain-rules';
import type { DomainEditorModel } from './domains-page';
import { DomainsFacade } from './domains.facade';

type DomainFormModel = { -readonly [K in keyof DomainFormValue]: DomainFormValue[K] };

const EMPTY: DomainFormModel = {
  domainId: '',
  active: true,
  sortOrder: 0,
  name: '',
  timeZone: '',
  lat: null,
  lon: null,
  zoomLevel: null,
  messageSortOrder: '',
  color: '',
  areas: [],
  categories: [],
  messageSeries: [],
  publish: false,
  atons: false,
  templates: false,
};

/**
 * Create/edit/copy form for a domain, hosted in the shared drawer. Signal
 * Forms over the flat scalar fields; the three cross-domain pickers (areas,
 * categories, message series) are `FormValueControl`s bound straight into the
 * form, so a pick flips the save gate like any typed edit.
 *
 * Behavioral notes carried by this component:
 * - `domainId` is the immutable PK (≡ the Keycloak client id) — locked on
 *   edit, cleared + editable again on copy;
 * - the timezone is mandatory — the domain's dates are read and written in it,
 *   so a domain lacking one is unsavable until a zone is chosen (a persisted
 *   blank included: the form opens on it and Save stays shut until it is
 *   answered); a persisted timezone missing from the `Intl` list is prepended
 *   so it displays and survives an untouched save;
 * - a blank persisted message sort order renders empty and is never forced to a
 *   value;
 * - the color input is free text with a live swatch — invalid strings render
 *   no/wrong swatch;
 * - the Keycloak status row shows in edit mode only; `inKeycloak` false and
 *   null (lookup failed) alike offer "Create in Keycloak". The provisioning
 *   POST is void, so success re-fetches the list and the row updates in place
 *   while the drawer stays open.
 */
@Component({
  selector: 'app-domain-editor',
  imports: [
    AreasField,
    CategoriesField,
    Drawer,
    FormField,
    MessageSeriesField,
    Select,
    TranslocoPipe,
  ],
  templateUrl: './domain-editor.html',
  host: { class: 'contents' },
})
export class DomainEditor {
  private readonly api = inject(DomainsAdminApiService);
  private readonly facade = inject(DomainsFacade);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<DomainEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);
  protected readonly provisioning = signal(false);

  private readonly formModel = signal<DomainFormModel>({ ...EMPTY });
  protected readonly form = form(this.formModel, (path) => {
    required(path.domainId);
    // domainId is the immutable PK (the PUT path id ≡ Keycloak client id) — locked on edit.
    disabled(path.domainId, { when: () => this.model()?.mode === 'edit' });
    // The backend refuses a domain without a name (400) before it makes the domain's Keycloak client.
    required(path.name);
    required(path.timeZone);
    min(path.zoomLevel, ZOOM_MIN);
    max(path.zoomLevel, ZOOM_MAX);
  });

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does. The picker
   * arrays live in the form model, so picks flip the gate too.
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
      ? 'admin.domains.editor.editTitle'
      : 'admin.domains.editor.addTitle',
  );

  /** Canonical zones + the persisted-but-missing value prepended (alias/deprecated). */
  protected readonly timeZones = computed(() => timeZoneOptions(this.model()?.domain.timeZone));

  protected readonly sortOrderOptions = computed(() => {
    this.language.selectedLanguage();
    return MESSAGE_SORT_ORDER_OPTIONS.map(({ value, labelKey }) => ({
      value,
      label: this.transloco.translate(labelKey),
    }));
  });

  /** Drives the live swatch — shown only while the free-text color is non-empty. */
  protected readonly colorValue = computed(() => this.formModel().color);

  /**
   * Keycloak state for the status row, read from the FRESH list entry when one
   * exists so a post-provisioning re-fetch updates the row in place while the
   * drawer stays open. Tri-state: only `true` means the client exists — false,
   * null and absent (Keycloak unreachable) alike offer the Create button.
   */
  protected readonly inKeycloak = computed(() => {
    const model = this.model();
    const domains = this.facade.domains();
    if (!model) {
      return false;
    }
    const fresh = domains.find((domain) => domain.domainId === model.domain.domainId);
    return (fresh ?? model.domain).inKeycloak === true;
  });

  /** Locked while the POST is in flight AND while the follow-up list re-fetch runs. */
  protected readonly provisionDisabled = computed(
    () => this.provisioning() || this.facade.loading(),
  );

  private readonly domainIdField = viewChild<ElementRef<HTMLInputElement>>('domainIdField');

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — not on every keystroke.
        untracked(() => this.rebuildForm(model.domain));
      }
    });

    // Initial focus lands on the Keycloak ID input on add/copy
    // only; in edit mode the input is disabled, so the drawer's default focus
    // stands. The macrotask runs after the drawer's own microtask ✕-focus.
    effect(() => {
      const field = this.domainIdField()?.nativeElement;
      const mode = this.model()?.mode;
      if (field && mode === 'add') {
        setTimeout(() => field.focus(), 0);
      }
    });
  }

  private rebuildForm(domain: AdminDomain): void {
    const next: DomainFormModel = {
      domainId: domain.domainId,
      active: domain.active ?? false,
      sortOrder: domain.sortOrder ?? null,
      name: domain.name ?? '',
      timeZone: domain.timeZone ?? '',
      lat: domain.lat ?? null,
      lon: domain.lon ?? null,
      zoomLevel: domain.zoomLevel ?? null,
      messageSortOrder: domain.messageSortOrder ?? '',
      color: domain.color ?? '',
      areas: [...(domain.areas ?? [])],
      categories: [...(domain.categories ?? [])],
      // Shallow-map the stored refs into the picker's series shape (the ref's
      // readonly types[] is not assignable to the picker's mutable one).
      messageSeries: (domain.messageSeries ?? []).map((series) => ({
        seriesId: series.seriesId,
        mainType: series.mainType,
        name: series.name,
        types: series.types ? [...series.types] : undefined,
      })),
      publish: domain.publish ?? false,
      atons: domain.atons ?? false,
      templates: domain.templates ?? false,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
    this.provisioning.set(false);
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button.
    if (!model || this.saveDisabled()) {
      return;
    }
    const domain = buildDomainPayload(this.formModel());
    const request = model.mode === 'add' ? this.api.create(domain) : this.api.update(domain);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.domains.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.domains.toast.saveError');
      },
    });
  }

  /**
   * Provision the domain's Keycloak client. The POST is void — the new state
   * is learned only by re-fetching the list, which the status row reads (the
   * drawer stays open, the row flips in place). A failure toasts and keeps
   * the drawer open.
   */
  protected provision(): void {
    const model = this.model();
    if (!model || this.provisionDisabled()) {
      return;
    }
    this.provisioning.set(true);
    this.api.createInKeycloak(buildDomainPayload(this.formModel())).subscribe({
      next: () => {
        this.provisioning.set(false);
        this.facade.refresh();
      },
      error: () => {
        this.provisioning.set(false);
        this.notifications.error('admin.domains.toast.provisionError');
      },
    });
  }
}
