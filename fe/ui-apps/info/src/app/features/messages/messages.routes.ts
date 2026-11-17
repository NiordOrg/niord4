import { Routes } from '@angular/router';
import { MessagesComponent } from './messages.component';

export const messagesRoutes: Routes = [
  {
    path: '',
    // The filter and the message list belong to MessagesComponent, which lists the store in its
    // own `providers` and hosts the outlet the three child views render in: the store is created
    // with that component and dies with it. It must not be listed here — the injector a route's
    // `providers` creates outlives the component the route activated, so a reader who leaves
    // /messages and comes back would find the list they left behind still loaded.
    component: MessagesComponent,
    children: [
      { path: '', redirectTo: 'details', pathMatch: 'full' },
      {
        path: 'details',
        title: 'MENU_DETAILS',
        loadComponent: () =>
          import('./components/message-details-view/message-details-view.component').then(
            (m) => m.MessageDetailsViewComponent,
          ),
      },
      {
        path: 'details/message/:messageId',
        title: 'MENU_DETAILS',
        loadComponent: () =>
          import('./components/message-details-view/message-details-view.component').then(
            (m) => m.MessageDetailsViewComponent,
          ),
      },
      {
        path: 'table',
        title: 'MENU_TABLE',
        loadComponent: () =>
          import('./components/message-table-view/message-table-view.component').then(
            (m) => m.MessageTableViewComponent,
          ),
      },
      {
        path: 'map',
        title: 'MENU_MAP',
        loadComponent: () =>
          import('./components/message-map-view/message-map-view.component').then(
            (m) => m.MessageMapViewComponent,
          ),
      },
    ],
  },
];
