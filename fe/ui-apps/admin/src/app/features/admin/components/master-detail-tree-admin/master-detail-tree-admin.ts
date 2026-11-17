import { Component, input, output } from '@angular/core';
import { ModelLanguageSelection } from '../../services/model-language-selection';
import { ModelLanguagePicker } from '../model-language-picker/model-language-picker';
import { TranslocoPipe } from '@jsverse/transloco';

import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import {
  EntityTree,
  type EntityTreeMove,
} from '../../../../shared/components/entity-tree/entity-tree';
import type { HierarchicalEntity } from '../../../../shared/components/entity-tree/hierarchical-entity.model';
import { Menu } from '../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';
import type { MenuItem } from '../../../../shared/models/menu-item.model';
import { AdminSectionPage } from '../../shell/admin-section-page';

/** The static i18n keys a master-detail admin section supplies to the shared shell. */
export interface MasterDetailAdminConfig {
  readonly titleKey: string;
  readonly descriptionKey: string;
  /** "New X" (nothing selected) and "New sub-X" (a node is selected). */
  readonly addLabelKey: string;
  readonly addSubLabelKey: string;
  readonly moveUpKey: string;
  readonly moveDownKey: string;
  readonly deleteKey: string;
  readonly actionsAriaLabelKey: string;
  readonly loadErrorKey: string;
  readonly retryKey: string;
  readonly filterPlaceholderKey: string;
}

/**
 * Presentational master-detail shell shared by the tree-admin sections (Areas, Categories): the
 * section chrome + toolbar (New / New-sub · move up/down · delete · cog) + two-pane grid (filterable
 * drag-to-re-parent `EntityTree` | projected inline editor). Owns no state and no dialogs — the host
 * page provides the entities/enablement inputs, the cog `MenuItem`s, and mounts its own
 * import/confirm/feature dialogs as siblings. The editor is projected into the detail pane.
 */
@Component({
  selector: 'app-master-detail-tree-admin',
  imports: [
    AdminSectionPage,
    ModelLanguagePicker,
    EntityTree,
    Menu,
    MenuTrigger,
    ProgressBar,
    ErrorBanner,
    Icon,
    TranslocoPipe,
  ],
  templateUrl: './master-detail-tree-admin.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class MasterDetailTreeAdmin<T extends HierarchicalEntity = HierarchicalEntity> {
  readonly config = input.required<MasterDetailAdminConfig>();
  readonly modelLanguage = input<ModelLanguageSelection>();

  readonly entities = input.required<readonly T[]>();
  readonly loading = input(false);
  readonly error = input(false);

  readonly hasSelection = input(false);
  readonly moveDisabled = input(true);
  /** Disables the New button (e.g. Categories blocks Add while editing a TEMPLATE). */
  readonly addDisabled = input(false);
  readonly selectedIds = input<readonly (number | string)[]>([]);
  readonly cogItems = input<MenuItem[]>([]);
  readonly dnd = input(true);
  /** Opt-in template-node badge (e.g. Categories flags TEMPLATE nodes). */
  readonly flagTemplate = input(false);
  readonly templatePredicate = input<(entity: T) => boolean>(() => false);

  readonly nodeSelected = output<T>();
  readonly add = output<void>();
  readonly moveUp = output<void>();
  readonly moveDown = output<void>();
  readonly deleteRequested = output<void>();
  readonly nodeMoved = output<EntityTreeMove>();
  readonly retry = output<void>();
}
