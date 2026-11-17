import { Location } from '@angular/common';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { filter, take } from 'rxjs/operators';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTriggerFor } from '../../../../../shared/components/menu/menu-trigger.directive';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { ScriptResourceTypeBadge } from '../../../components/script-resource-type-badge/script-resource-type-badge';
import {
  ScriptResource,
  ScriptResourcesApiService,
  ScriptResourceType,
} from '../../../services/api/script-resources-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ScriptResourcesFacade } from './script-resources.facade';
import { ScriptResourceEditor, ScriptResourceEditorModel } from './script-resource-editor';
import { ScriptResourceImportDialog } from './script-resource-import-dialog';

/**
 * Script Resources (sysadmin) — list + drawer editor for the FM/JS template
 * catalog. Add offers a Freemarker/JavaScript choice; the cog carries Export
 * (ticketed) and Import (JSON, previewed before anything is written). An optional `?path=` deep link looks the path up
 * among the stored resources and edits the exact match; a path with no stored
 * resource is reported, never created. The page then self-clears the URL
 * without re-triggering. A query param (not a path segment)
 * is used because resource paths contain slashes — a `%2F`-encoded path segment
 * is rejected by the SPA-fallback server on a full navigation.
 */
@Component({
  selector: 'app-script-resources-page',
  imports: [
    AdminSectionPage,
    Icon,
    Menu,
    MenuTriggerFor,
    Confirm,
    MenuTrigger,
    Pager,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    ScriptResourceEditor,
    ScriptResourceImportDialog,
    ScriptResourceTypeBadge,
    TranslocoPipe,
  ],
  providers: [ScriptResourcesFacade, ConfirmService],
  templateUrl: './script-resources-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class ScriptResourcesPage {
  protected readonly facade = inject(ScriptResourcesFacade);
  protected readonly api = inject(ScriptResourcesApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(ScriptResourceImportDialog);

  protected readonly search = signal('');
  protected readonly editorModel = signal<ScriptResourceEditorModel | undefined>(undefined);

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const resources = this.facade.resources();
    if (term === '') {
      return resources;
    }
    return resources.filter((resource) => resource.path.toLowerCase().includes(term));
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  /** Highlight the row currently open in the edit drawer. */
  protected readonly editingId = computed(() => {
    const model = this.editorModel();
    return model?.mode === 'edit' ? model.resource.id : undefined;
  });

  private readonly newFreemarkerLabel = translateSignal('admin.scriptResources.menu.newFreemarker');
  private readonly newJavaScriptLabel = translateSignal('admin.scriptResources.menu.newJavaScript');
  protected readonly addItems = computed<MenuItem[]>(() => [
    {
      label: this.newFreemarkerLabel(),
      icon: 'lucideFile',
      command: () => this.addResource('FM'),
    },
    {
      label: this.newJavaScriptLabel(),
      icon: 'lucideFile',
      command: () => this.addResource('JS'),
    },
  ]);

  private readonly exportLabel = translateSignal('admin.scriptResources.menu.export');
  private readonly importLabel = translateSignal('admin.scriptResources.menu.import');
  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), icon: 'lucideUpload', command: () => this.export() },
    {
      label: this.importLabel(),
      icon: 'lucideDownload',
      command: () => this.importDialog().open(),
    },
  ]);

  constructor() {
    // Read the optional `?path=` ONCE on entry, look the referenced resource up,
    // then self-clear the URL (Location.replaceState — no re-navigation, so the
    // open drawer survives and the look-up never re-fires). The entry keeps its
    // state: it holds the router's page id, without which a guard's No on a later
    // Back or Forward onto this entry could not put the history back.
    const path = inject(ActivatedRoute).snapshot.queryParamMap.get('path');
    if (path) {
      this.handleDeepLink(path);
      const location = inject(Location);
      location.replaceState('/sysadmin/script-resources', '', location.getState());
    }
  }

  /**
   * Looks the path up in the list the facade loads (its first settled state, so no
   * second request) and never writes. A failed load does nothing here: the list's
   * error banner already reports it. No match opens no drawer: an empty add form
   * saved over a path the server still renders from its own files would replace
   * that template with an empty row.
   */
  private handleDeepLink(rawPath: string): void {
    const path = rawPath.startsWith('/') ? rawPath.slice(1) : rawPath;
    toObservable(this.facade.state)
      .pipe(
        filter((state) => !state.loading),
        take(1),
      )
      .subscribe((state) => {
        if (state.error) {
          return;
        }
        const match = state.resources.find((resource) => resource.path === path);
        if (match) {
          this.editResource(match);
        } else {
          this.notifications.warn('admin.scriptResources.toast.notFound', { path });
        }
      });
  }

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addResource(type: ScriptResourceType): void {
    this.editorModel.set({ mode: 'add', resource: { type, path: '', content: '' } });
  }

  protected editResource(resource: ScriptResource): void {
    this.editorModel.set({ mode: 'edit', resource });
  }

  /** Copy = add a clone: drop the id and truncate the path to its directory. */
  protected copyResource(resource: ScriptResource): void {
    const slash = resource.path.lastIndexOf('/');
    const path = slash !== -1 ? resource.path.slice(0, slash + 1) : '';
    this.editorModel.set({
      mode: 'add',
      resource: { type: resource.type, path, content: resource.content },
    });
  }

  protected deleteResource(resource: ScriptResource): void {
    if (resource.id === undefined) {
      return;
    }
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.scriptResources.confirmDelete.header'),
      message: this.transloco.translate('admin.scriptResources.confirmDelete.message', {
        path: resource.path,
      }),
      accept: () => {
        this.api.delete(resource.id!).subscribe({
          next: () => {
            this.notifications.success('admin.scriptResources.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.scriptResources.toast.deleteError'),
        });
      },
    });
  }

  private async export(): Promise<void> {
    try {
      await this.downloads.openTicketed(this.api.exportUrl(), 'admin');
    } catch {
      this.notifications.error('admin.scriptResources.toast.exportError');
    }
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }
}
