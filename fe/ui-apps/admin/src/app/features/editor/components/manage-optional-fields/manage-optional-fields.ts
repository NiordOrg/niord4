import { Component, computed, inject, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Menu } from '../../../../shared/components/menu/menu';
import { MenuItemDef } from '../../../../shared/components/menu/menu-item-def.directive';
import { MenuTrigger } from '../../../../shared/components/menu-trigger/menu-trigger';
import type { MenuItem } from '../../../../shared/models/menu-item.model';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';

/**
 * Maps an `editorFields` field id (the backend map keys them in snake_case) to its i18n label key.
 * The label leaves live under the flat `msg.field.<id>` keyspace. `time` is an alternate
 * spelling of `event_dates` and resolves to the same label (defensive key aliasing — the backend
 * map may use either spelling).
 */
const FIELD_LABEL_KEY: Readonly<Record<string, string>> = {
  type: 'msg.field.type',
  orig_info: 'msg.field.orig_info',
  id: 'msg.field.id',
  title: 'msg.field.title',
  references: 'msg.field.references',
  event_dates: 'msg.field.event_dates',
  time: 'msg.field.event_dates',
  areas: 'msg.field.areas',
  categories: 'msg.field.categories',
  positions: 'msg.field.positions',
  charts: 'msg.field.charts',
  subject: 'msg.field.subject',
  description: 'msg.field.description',
  part_types: 'msg.field.part_types',
  attachments: 'msg.field.attachments',
  note: 'msg.field.note',
  publication: 'msg.field.publication',
  source: 'msg.field.source',
  prohibition: 'msg.field.prohibition',
  signals: 'msg.field.signals',
  layout: 'msg.field.layout',
};

/** Resolve a field id to its label key, falling back to the flat `msg.field.<id>` leaf if unmapped. */
function labelKeyFor(fieldId: string): string {
  return FIELD_LABEL_KEY[fieldId] ?? `msg.field.${fieldId}`;
}

/** A manage-fields menu row: its label plus whether the field is currently shown (drives the dot). */
interface OptionalFieldItem extends MenuItem {
  readonly chosen: boolean;
}

/**
 * Form-level "Manage optional fields…" control shown below the editor panels. In a multi-select
 * dropdown styled like the messages filter dropdown (a selection dot marks each shown field), an
 * editor toggles each optional (opt-in-able) field on or off. Toggling reveals a field in its home
 * section (Preamble / Misc) or hides it again; the menu stays open across toggles. Visibility
 * choices are session state — dropped on reload.
 *
 * The set of manageable fields and the toggle action are owned by the fields engine; this control
 * only renders them. `isEditor`-gated (a plain `user` proof-reads and sees no editing affordances)
 * and hidden entirely when there is nothing to manage.
 */
@Component({
  selector: 'app-manage-optional-fields',
  imports: [TranslocoPipe, Menu, MenuItemDef, MenuTrigger],
  template: `
    @if (visible()) {
      <div
        class="mt-4 flex flex-wrap items-center gap-3 border-t border-dashed border-surface-200 pt-3 dark:border-surface-700"
      >
        <app-menu-trigger
          icon="lucideListChecks"
          [label]="'menu.action.fields.more' | transloco"
          [menu]="menu"
        />
        <app-menu #menu [model]="items()" [ariaLabel]="'editor.manageFields.menuAria' | transloco">
          <ng-template appMenuItemDef let-item>
            <span class="flex-1">{{ item.label }}</span>
            @if (chosenDot(item)) {
              <span class="status-dot is-active" aria-hidden="true"></span>
              <span class="sr-only">{{ 'common.active' | transloco }}</span>
            }
          </ng-template>
        </app-menu>
      </div>
    }
  `,
  host: { class: 'contents' },
})
export class ManageOptionalFields {
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly facade = inject(EditorFacade);
  private readonly fields = inject(EditorFieldsService);

  /** The dropdown's trigger — re-opened after each toggle so the menu behaves multi-select. */
  private readonly menuTrigger = viewChild.required(MenuTrigger);

  /** The control renders only for an editor with at least one optional field to manage. */
  protected readonly visible = computed<boolean>(
    () => this.facade.isEditor() && this.fields.manageableFields().length > 0,
  );

  /** One menu row per optional field, labelled + flagged with its current shown state. */
  readonly items = computed<OptionalFieldItem[]>(() => {
    // Read the active language so these translate() calls (untracked) re-run on a language switch.
    this.language.selectedLanguage();
    return this.fields.manageableFields().map((fieldId) => ({
      label: this.transloco.translate(labelKeyFor(fieldId)),
      chosen: this.fields.showField(fieldId),
      command: () => {
        this.fields.toggleOptIn(fieldId);
        // CDK dismisses the menu on every item click — re-open it so toggling reads as multi-select.
        queueMicrotask(() => this.menuTrigger().reopen());
      },
    }));
  });

  /** True for a currently-shown field — gets the accent "chosen" dot. */
  protected chosenDot(item: MenuItem): boolean {
    return (item as OptionalFieldItem).chosen === true;
  }
}
