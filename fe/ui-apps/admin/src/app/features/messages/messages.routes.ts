import { Routes } from '@angular/router';

import { TAG_SELECTION_STORE } from '../../core/domain/services/tag-selection-store.token';
import { drawerUnsavedChangesGuard } from '../../shared/components/drawer/drawer-unsaved-changes.guard';
import { MESSAGE_TAGS_DATA_SOURCE } from '../../shared/forms/message-tags-field/message-tags-data.token';
import { Messages } from './messages';
import { paintBeforeView } from './paint-before-view.guard';
import { TagsApiService } from './services/api/tags-api.service';
import { LastTagSelectionService } from './services/last-tag-selection.service';
import { MessageTagActionsService } from './services/message-tag-actions.service';

const messagesRoutes: Routes = [
  {
    path: '',
    component: Messages,
    title: 'menu.messages',
    data: { help: { section: 'messages' } },
    canDeactivate: [drawerUnsavedChangesGuard],
    // Each view is built after one painted frame, so a tab click shows at once.
    canActivateChild: [paintBeforeView],
    providers: [
      { provide: TAG_SELECTION_STORE, useExisting: LastTagSelectionService },
      MessageTagActionsService,
      // Binds the tag filter panel's inline search to the real backend. The token's root default
      // returns empty arrays, so without this the field would silently never find a tag.
      { provide: MESSAGE_TAGS_DATA_SOURCE, useExisting: TagsApiService },
    ],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'table' },
      {
        path: 'table',
        loadComponent: () =>
          import('./views/messages-table-view/messages-table-view').then(
            (m) => m.MessagesTableView,
          ),
        title: 'view_mode.table',
      },
      {
        path: 'grid',
        loadComponent: () =>
          import('./views/messages-grid-view/messages-grid-view').then((m) => m.MessagesGridView),
        title: 'view_mode.grid',
      },
      {
        path: 'details',
        loadComponent: () =>
          import('./views/messages-details-view/messages-details-view').then(
            (m) => m.MessagesDetailsView,
          ),
        title: 'view_mode.details',
      },
      {
        path: 'map',
        loadComponent: () =>
          import('./views/messages-map-view/messages-map-view').then((m) => m.MessagesMapView),
        title: 'view_mode.map',
      },
      {
        path: 'selected',
        loadComponent: () =>
          import('./views/messages-selected-view/messages-selected-view').then(
            (m) => m.MessagesSelectedView,
          ),
        title: 'view_mode.selection',
      },
      { path: '**', redirectTo: 'table' },
    ],
  },
];

export default messagesRoutes;
