import { Routes } from '@angular/router';

import { TAG_SELECTION_STORE } from '../../core/domain/services/tag-selection-store.token';
import { LastTagSelectionService } from '../messages/services/last-tag-selection.service';
import { MessageTagActionsService } from '../messages/services/message-tag-actions.service';
import { EditorShell } from './editor';
import { templateExecutionGuard } from './guards/template-execution.guard';
import { templateSeedGuard } from './guards/template-seed.guard';
import { editorExitGuard, unsavedChangesGuard } from './guards/unsaved-changes.guard';
import { EditorCommentsStore } from './services/editor-comments.store';
import { EditorFacade } from './services/editor-facade';
import { EditorFieldsService } from './services/editor-fields.service';
import { MessageStatusService } from './services/message-status.service';

const loadEditForm = () => import('./views/edit-form/edit-form').then((m) => m.EditForm);

// Distinct path segments keep the URLs bookmarkable; each carries its own `editType`
// so the shared edit view knows which load path to run. The tag-action services are route-scoped
// here exactly as in the messages route, so the action menu and its tag picker can reuse them.
const editorRoutes: Routes = [
  {
    path: '',
    component: EditorShell,
    // The save-gate spans the WHOLE editor: unsaved edits survive tab switches (the facade is
    // shell-scoped), but leaving the editor entirely confirms first. The edit-family children
    // additionally guard intra-editor navigations that replace the working copy.
    canDeactivate: [editorExitGuard],
    providers: [
      EditorFacade,
      EditorCommentsStore,
      EditorFieldsService,
      MessageStatusService,
      { provide: TAG_SELECTION_STORE, useExisting: LastTagSelectionService },
      MessageTagActionsService,
    ],
    children: [
      {
        path: 'edit/:id',
        data: { editType: 'edit', help: { section: 'editor' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: loadEditForm,
      },
      {
        path: 'copy/:id/:referenceType',
        data: { editType: 'copy', help: { section: 'editor' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: loadEditForm,
      },
      {
        // Plain copy (no reference). The copy dialog picks between this and the
        // `copy/:id/:referenceType` route above (REFERENCE for an NM include-reference copy;
        // CANCELLATION rides the cancel→create-cancellation re-entry).
        path: 'copy/:id',
        data: { editType: 'copy', help: { section: 'editor' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: loadEditForm,
      },
      {
        // Template / new-blank entry. The Start screen's create buttons seed the transient template
        // store and navigate here. On a reload / deep-link the store is empty, so `templateSeedGuard`
        // redirects to the Start screen rather than letting the view surface a load error.
        path: 'edit',
        data: { editType: 'template', help: { section: 'editor' } },
        canActivate: [templateSeedGuard],
        canDeactivate: [unsavedChangesGuard],
        loadComponent: loadEditForm,
      },
      {
        // Template-execution view: a full editor-shell page (sibling of the edit view) reached from
        // the Start screen's "from a template" card and the in-editor "Run template…" launcher. It
        // consumes the transient execution seed and, on Execute, hands the rendered message to the
        // edit route. Its guard keys on the view's LOCAL dirtiness (the facade is untouched here).
        path: 'execute-template',
        data: { help: { section: 'message-template-execution' } },
        canDeactivate: [templateExecutionGuard],
        loadComponent: () =>
          import('./views/template-execution-view/template-execution-view').then(
            (m) => m.TemplateExecutionView,
          ),
      },
      {
        path: 'status/:id',
        data: { help: { section: 'status-editor-page' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./views/editor-status-page/editor-status-page').then((m) => m.EditorStatusPage),
      },
      {
        path: 'comments/:id',
        data: { help: { section: 'comments-editor-page' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./views/editor-comments-page/editor-comments-page').then(
            (m) => m.EditorCommentsPage,
          ),
      },
      {
        path: 'history/:id',
        data: { help: { section: 'history-editor-page' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./views/editor-history-page/editor-history-page').then(
            (m) => m.EditorHistoryPage,
          ),
      },
      {
        // Bare `/editor` is the new-message Start screen. Its create buttons seed the transient
        // template store and enter the routes above. It carries the replacement guard like every
        // other child: the facade may hold unsaved edits from a tab switch, and e.g. the drafts
        // widget's Edit action would replace them.
        path: '',
        pathMatch: 'full',
        data: { help: { section: 'editor-start' } },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./views/message-start/message-start').then((m) => m.MessageStart),
      },
    ],
  },
];

export default editorRoutes;
