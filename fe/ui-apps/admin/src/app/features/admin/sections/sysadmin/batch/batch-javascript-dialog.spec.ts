import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { BatchJavaScriptDialog } from './batch-javascript-dialog';

interface DialogInternals {
  visible: () => boolean;
  submitDisabled: () => boolean;
  form: BatchJavaScriptDialog['form'];
  open: () => void;
  submit: () => void;
}

/**
 * Auto-accept confirm stub: fires the neutral `accept` callback synchronously.
 * `active`/`dismiss` are stubbed (empty) so the mounted `<app-confirm>` — which
 * injects this same scoped ConfirmService — renders nothing and never throws.
 */
const autoAcceptConfirm = {
  active: () => undefined,
  dismiss: () => undefined,
  confirmNeutral: (options: { accept: () => void }) => options.accept(),
};

function setup(confirm = autoAcceptConfirm) {
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
  // The dialog provides its own ConfirmService; override it to auto-accept.
  TestBed.overrideComponent(BatchJavaScriptDialog, {
    add: { providers: [{ provide: ConfirmService, useValue: confirm }] },
  });
  const fixture = TestBed.createComponent(BatchJavaScriptDialog);
  fixture.detectChanges();
  return {
    fixture,
    dialog: fixture.componentInstance as unknown as DialogInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

describe('BatchJavaScriptDialog', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('Schedule is disabled while the code is blank or whitespace-only', () => {
    const { fixture, dialog } = setup();
    dialog.open();
    fixture.detectChanges();
    expect(dialog.submitDisabled()).toBe(true);

    dialog.form.javaScript().value.set('   ');
    fixture.detectChanges();
    expect(dialog.submitDisabled()).toBe(true);

    dialog.form.javaScript().value.set('doThing()');
    fixture.detectChanges();
    expect(dialog.submitDisabled()).toBe(false);
  });

  it('a non-empty submit confirms then POSTs the code and shows the scheduled toast', () => {
    const { fixture, dialog, httpTesting, notifications } = setup();
    dialog.open();
    dialog.form.javaScript().value.set('doThing()');
    fixture.detectChanges();

    dialog.submit();
    const req = httpTesting.expectOne('/rest/batch/execute-javascript');
    expect(req.request.body).toEqual({ scriptName: 'javascript.js', javaScript: 'doThing()' });
    req.flush('OK');
    expect(notifications.info).toHaveBeenCalledWith('admin.batch.toast.scheduled');
    expect(dialog.visible()).toBe(false);
  });

  it('a failed POST raises a keyed error toast and keeps the dialog open', () => {
    const { fixture, dialog, httpTesting, notifications } = setup();
    dialog.open();
    dialog.form.javaScript().value.set('doThing()');
    fixture.detectChanges();

    dialog.submit();
    httpTesting
      .expectOne('/rest/batch/execute-javascript')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.scheduleError');
    expect(dialog.visible()).toBe(true);
  });

  it('does NOT schedule when the confirm is rejected (no accept callback)', () => {
    const rejectConfirm = {
      active: () => undefined,
      dismiss: () => undefined,
      confirmNeutral: () => undefined,
    };
    const { fixture, dialog, httpTesting } = setup(rejectConfirm);
    dialog.open();
    dialog.form.javaScript().value.set('doThing()');
    fixture.detectChanges();

    dialog.submit();
    httpTesting.expectNone('/rest/batch/execute-javascript');
  });

  it('the <form> submit binding drives submit() (not just a direct call)', () => {
    const { fixture, dialog, httpTesting } = setup();
    dialog.open();
    dialog.form.javaScript().value.set('doThing()');
    fixture.detectChanges();

    // The dialog portals to the CDK overlay; drive the real submit event.
    const formEl = document.querySelector('form');
    expect(formEl).not.toBeNull();
    formEl!.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));

    httpTesting.expectOne('/rest/batch/execute-javascript').flush('OK'); // auto-accept confirm → POST
    expect(dialog.visible()).toBe(false);
  });
});
