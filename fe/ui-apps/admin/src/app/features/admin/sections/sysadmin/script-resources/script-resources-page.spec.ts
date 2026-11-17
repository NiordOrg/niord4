import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ScriptResource } from '../../../services/api/script-resources-api.service';
import { ScriptResourcesPage } from './script-resources-page';
import { ScriptResourceEditorModel } from './script-resource-editor';

interface PageInternals {
  editorModel: () => ScriptResourceEditorModel | undefined;
  copyResource: (resource: ScriptResource) => void;
  facade: { error: () => boolean };
  cogItems: () => { command?: () => void }[];
  onImportClosed: () => void;
}

function setup(path: string | null, stored: ScriptResource[] | 'fail' = []) {
  const replaceState = vi.fn();
  const warn = vi.fn();
  // The router's own state of the entry (its page id); the self-clear must keep it.
  const routerState = { navigationId: 4, ɵrouterPageId: 2 };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      // `subscribe` is the overlay's: a dialog listens for navigation to close itself.
      {
        provide: Location,
        useValue: {
          replaceState,
          getState: () => routerState,
          subscribe: () => ({ unsubscribe: () => undefined }),
        },
      },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn },
      },
      {
        provide: TicketedDownloadService,
        useValue: { openTicketed: vi.fn(() => Promise.resolve()), openPublic: vi.fn() },
      },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(path ? { path } : {}) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(ScriptResourcesPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  // The facade loads the list once change detection runs; the deep link reads the
  // facade's state, so a single list request answers both.
  fixture.detectChanges();
  const listRequests: TestRequest[] = httpTesting.match(
    (r) => r.url === '/rest/script-resources/all',
  );
  listRequests.forEach((r) =>
    stored === 'fail'
      ? r.flush('boom', { status: 500, statusText: 'Server Error' })
      : r.flush(stored),
  );
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    replaceState,
    routerState,
    warn,
    listRequests,
  };
}

describe('ScriptResourcesPage — Copy', () => {
  it('clones as a new resource: drops the id and truncates the path to its directory', () => {
    const { page } = setup(null);
    page.copyResource({ id: 9, type: 'FM', path: 'templates/sub/report.ftl', content: 'BODY' });
    const model = page.editorModel();
    expect(model?.mode).toBe('add');
    expect(model?.resource.id).toBeUndefined();
    expect(model?.resource.path).toBe('templates/sub/');
    expect(model?.resource.content).toBe('BODY');
  });

  it('truncates a path with no slash to empty', () => {
    const { page } = setup(null);
    page.copyResource({ id: 1, type: 'JS', path: 'top.js', content: '' });
    expect(page.editorModel()?.resource.path).toBe('');
  });
});

describe('ScriptResourcesPage — Import', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it("the cog's Import opens the dialog that previews before it writes, not the one-file upload", async () => {
    const { fixture, page, httpTesting } = setup(null);
    page.cogItems()[1].command!();
    await fixture.whenStable();
    const doc = fixture.nativeElement.ownerDocument as Document;
    expect(doc.querySelector('#scriptResourceImportFile')).not.toBeNull();
    expect(doc.querySelector('#jsonImportFile')).toBeNull();
    // Opening asks nothing of the backend; a preview waits for a file.
    httpTesting.expectNone('/rest/script-resources/import-preview');
    httpTesting.expectNone('/rest/script-resources/upload-script-resources');
  });

  it('closing the import refetches the list', () => {
    const { page, httpTesting } = setup(null);
    page.onImportClosed();
    TestBed.tick();
    httpTesting.expectOne((r) => r.url === '/rest/script-resources/all').flush([]);
  });
});

describe('ScriptResourcesPage — ?path= deep-link', () => {
  it('looks the path up and edits the exact match, then self-clears the URL', () => {
    const { page, replaceState, routerState, warn, listRequests } = setup('templates/x.ftl', [
      { id: 3, type: 'FM', path: 'templates/x.ftl', content: 'C' },
    ]);
    const model = page.editorModel();
    expect(model?.mode).toBe('edit');
    expect(model?.resource.id).toBe(3);
    expect(replaceState).toHaveBeenCalledWith('/sysadmin/script-resources', '', routerState);
    expect(warn).not.toHaveBeenCalled();
    // The look-up reuses the facade's list: one plain list read, no path parameter.
    expect(listRequests.length).toBe(1);
    expect(listRequests[0].request.params.has('path')).toBe(false);
  });

  it('reports a path with no stored resource and opens nothing', () => {
    const { page, replaceState, routerState, warn, listRequests } = setup('/new/thing.js', []);
    expect(page.editorModel()).toBeUndefined();
    // The leading slash is stripped before the look-up and in the notice.
    expect(warn).toHaveBeenCalledWith('admin.scriptResources.toast.notFound', {
      path: 'new/thing.js',
    });
    expect(replaceState).toHaveBeenCalledWith('/sysadmin/script-resources', '', routerState);
    expect(listRequests.length).toBe(1);
    expect(listRequests[0].request.params.has('path')).toBe(false);
  });

  it('does nothing of its own when the list fails to load: the error banner reports it', () => {
    const { page, replaceState, routerState, warn, listRequests, httpTesting } = setup(
      'templates/x.ftl',
      'fail',
    );
    expect(page.editorModel()).toBeUndefined();
    expect(warn).not.toHaveBeenCalled();
    expect(replaceState).toHaveBeenCalledWith('/sysadmin/script-resources', '', routerState);
    // No request of its own, so no second failure to go unhandled.
    expect(listRequests.length).toBe(1);
    httpTesting.verify();
    expect(page.facade.error()).toBe(true);
  });
});
