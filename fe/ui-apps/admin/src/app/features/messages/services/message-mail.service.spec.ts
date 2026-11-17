import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse, HttpParams } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { NEVER, of, throwError, type Observable } from 'rxjs';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import {
  MessageMailDialogService,
  type MailRequest,
  type MailSender,
} from '../dialogs/message-mail-dialog/message-mail-dialog.service';
import { MessageMailApiService, type MessageMailBody } from './api/message-mail-api.service';
import { TagsApiService } from './api/tags-api.service';
import { MessageMailService } from './message-mail.service';

const REQUEST: MailRequest = {
  emailAddresses: ['a@example.org', 'b@example.org'],
  mailSubject: 'Subject',
  mailMessage: 'Body',
};

interface Opts {
  readonly dialogResult?: MailRequest | undefined;
  readonly tempTagError?: boolean;
  readonly sendError?: unknown;
}

function setup(opts: Opts = {}) {
  let sender: MailSender | undefined;
  // The dialog runs the send itself: the fake keeps the sender it is given, so a test
  // can press Send through it, and answers the dialog's result only when a test asks for one.
  const open = vi.fn<
    (ids: readonly string[], send: MailSender) => Observable<MailRequest | undefined>
  >((...args) => {
    sender = args[1];
    return 'dialogResult' in opts ? of(opts.dialogResult) : NEVER;
  });
  const createTempMessageTag = vi.fn(() =>
    opts.tempTagError ? throwError(() => new Error('temp')) : of({ tagId: 'temp-1' }),
  );
  const send = vi.fn<(params: HttpParams, body: MessageMailBody) => Observable<void>>(() =>
    opts.sendError === undefined ? of(undefined) : throwError(() => opts.sendError),
  );
  const success = vi.fn();
  const error = vi.fn();

  TestBed.configureTestingModule({
    providers: [
      MessageMailService,
      { provide: MessageMailDialogService, useValue: { open } },
      { provide: TagsApiService, useValue: { createTempMessageTag } },
      { provide: MessageMailApiService, useValue: { send } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('da') } },
      { provide: NotificationService, useValue: { success, error } },
    ],
  });

  /** Press Send in the dialog: runs the sender the service handed it. */
  function pressSend(request: MailRequest = REQUEST) {
    if (sender === undefined) throw new Error('the dialog was not opened');
    const outcome = { done: false, failed: false };
    sender(request).subscribe({
      complete: () => (outcome.done = true),
      error: () => (outcome.failed = true),
    });
    return outcome;
  }

  return {
    service: TestBed.inject(MessageMailService),
    open,
    pressSend,
    createTempMessageTag,
    send,
    success,
    error,
  };
}

/** A refusal as the backend answers it to a text-typed request: the problem document unparsed. */
function refusal(detail: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status: 400,
    error: JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, detail }),
  });
}

describe('MessageMailService', () => {
  it('uses the selected model language without changing the UI language', () => {
    const { service, pressSend, send } = setup();
    service.mailSelection(['m1']).subscribe();
    pressSend({ ...REQUEST, language: 'kl' });
    expect(send.mock.calls[0][0].get('lang')).toBe('kl');
    expect(TestBed.inject(LanguageService).selectedLanguage()).toBe('da');
  });

  it('dismiss (no MailRequest) → no temp tag, no send, no toast; completes with undefined', () => {
    const { service, createTempMessageTag, send, success, error } = setup({
      dialogResult: undefined,
    });
    const next = vi.fn();
    service.mailSelection(['m1']).subscribe(next);

    expect(createTempMessageTag).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
    expect(success).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(next).toHaveBeenCalledWith(undefined);
  });

  it('completes with undefined when the dialog closes after a send', () => {
    const { service } = setup({ dialogResult: REQUEST });
    const next = vi.fn();
    service.mailSelection(['m1']).subscribe(next);
    expect(next).toHaveBeenCalledWith(undefined);
  });

  it('success: temp tag → send → "queued" toast; the query carries tag and lang, the body the mail', () => {
    const { service, pressSend, createTempMessageTag, send, success, error } = setup();
    service.mailSelection(['m1', 'm2']).subscribe();
    // Nothing is sent before Send is pressed in the dialog.
    expect(createTempMessageTag).not.toHaveBeenCalled();

    const outcome = pressSend();

    expect(createTempMessageTag).toHaveBeenCalledWith(['m1', 'm2']);
    const [params, body] = send.mock.calls[0];
    expect(params.get('tag')).toBe('temp-1');
    expect(params.get('lang')).toBe('da');
    expect(params.keys()).toEqual(['tag', 'lang']);
    expect(body).toEqual({
      mailTo: ['a@example.org', 'b@example.org'],
      mailSubject: 'Subject',
      mailMessage: 'Body',
    });
    // The backend only queues a ScheduledMail and answers "Message mail scheduled"; the mail
    // job sends it later, so the toast says queued, never sent.
    expect(success).toHaveBeenCalledWith('messages.mail.queued');
    expect(error).not.toHaveBeenCalled();
    expect(outcome).toEqual({ done: true, failed: false });
  });

  it('temp-tag failure → error toast, send never called, and the dialog hears of the failure', () => {
    const { service, pressSend, send, success, error } = setup({ tempTagError: true });
    service.mailSelection(['m1']).subscribe();

    const outcome = pressSend();

    expect(send).not.toHaveBeenCalled();
    expect(success).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('messages.mail.error');
    // The sender fails, so the dialog stays open with what was typed.
    expect(outcome).toEqual({ done: false, failed: true });
  });

  it('a refused send names the refusal in the toast and fails the sender', () => {
    const { service, pressSend, success, error } = setup({
      sendError: refusal("'someone' is not a mail address"),
    });
    service.mailSelection(['m1']).subscribe();

    const outcome = pressSend();

    expect(success).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('messages.mail.refused', {
      detail: "'someone' is not a mail address",
    });
    expect(outcome).toEqual({ done: false, failed: true });
  });

  it('a send failure with no detail keeps the fixed text', () => {
    const { service, pressSend, success, error } = setup({
      sendError: new HttpErrorResponse({ status: 0 }),
    });
    service.mailSelection(['m1']).subscribe();

    const outcome = pressSend();

    expect(success).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('messages.mail.error');
    expect(outcome).toEqual({ done: false, failed: true });
  });
});
