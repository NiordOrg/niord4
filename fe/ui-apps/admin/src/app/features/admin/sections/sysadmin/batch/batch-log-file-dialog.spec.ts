import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { BatchLogFileDialog } from './batch-log-file-dialog';

interface DialogInternals {
  visible: () => boolean;
  fileNames: () => readonly string[];
  selectedFile: () => string | undefined;
  content: () => string;
  open: (instanceId: number) => void;
  selectFile: (name: string) => void;
}

function setup() {
  const notifications = { error: vi.fn(), info: vi.fn(), success: vi.fn(), warn: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: NotificationService, useValue: notifications },
    ],
  });
  const fixture = TestBed.createComponent(BatchLogFileDialog);
  return {
    fixture,
    dialog: fixture.componentInstance as unknown as DialogInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

describe('BatchLogFileDialog', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the log file names on open', () => {
    const { fixture, dialog, httpTesting } = setup();
    dialog.open(3);
    fixture.detectChanges();
    httpTesting.expectOne('/rest/batch/instance/3/logs').flush(['a.log', 'b.log']);
    expect(dialog.fileNames()).toEqual(['a.log', 'b.log']);
    expect(dialog.visible()).toBe(true);
  });

  it('raises a keyed error toast when the names fetch fails', () => {
    const { fixture, dialog, httpTesting, notifications } = setup();
    dialog.open(3);
    fixture.detectChanges();
    httpTesting
      .expectOne('/rest/batch/instance/3/logs')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.logNamesError');
  });

  it('auto-loads the FULL file content when a name is selected (encodeURIComponent, no fromLineNo)', () => {
    const { fixture, dialog, httpTesting } = setup();
    dialog.open(4);
    fixture.detectChanges();
    httpTesting.expectOne('/rest/batch/instance/4/logs').flush(['run #1.log']);

    dialog.selectFile('run #1.log');
    fixture.detectChanges();
    const req = httpTesting.expectOne(
      `/rest/batch/instance/4/logs/${encodeURIComponent('run #1.log')}`,
    );
    expect(req.request.params.has('fromLineNo')).toBe(false);
    req.flush('the log body');
    expect(dialog.content()).toBe('the log body');
  });

  it('renders the content-fetch error body inline (no toast)', () => {
    const { fixture, dialog, httpTesting, notifications } = setup();
    dialog.open(4);
    fixture.detectChanges();
    httpTesting.expectOne('/rest/batch/instance/4/logs').flush(['a.log']);

    dialog.selectFile('a.log');
    fixture.detectChanges();
    httpTesting
      .expectOne('/rest/batch/instance/4/logs/a.log')
      .flush('file not found', { status: 404, statusText: 'Not Found' });
    expect(dialog.content()).toBe('file not found');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('re-selecting the active name is a no-op (non-uncheckable)', () => {
    const { fixture, dialog, httpTesting } = setup();
    dialog.open(4);
    fixture.detectChanges();
    httpTesting.expectOne('/rest/batch/instance/4/logs').flush(['a.log']);

    dialog.selectFile('a.log');
    fixture.detectChanges();
    httpTesting.expectOne('/rest/batch/instance/4/logs/a.log').flush('body');

    // Re-click the same name — no new content fetch.
    dialog.selectFile('a.log');
    fixture.detectChanges();
    httpTesting.expectNone('/rest/batch/instance/4/logs/a.log');
    expect(dialog.selectedFile()).toBe('a.log');
  });
});
