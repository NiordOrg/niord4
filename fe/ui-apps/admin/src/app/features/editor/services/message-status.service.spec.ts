import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../languages/en/i18n/admin/en.json';
import { NotificationService } from '../../../core/notifications/notification.service';
import { MessageCopyDialogService } from '../../messages/dialogs/message-copy-dialog/message-copy-dialog.service';
import type { Message, MessageStatus } from '../../messages/models/message.model';
import { MessagesApiService } from '../../messages/services/api/messages-api.service';
import { EditorFacade } from './editor-facade';
import { MessageStatusService } from './message-status.service';

function valid(status: MessageStatus, extra: Partial<Message> = {}): Message {
  return {
    id: 'm-1',
    status,
    type: 'COASTAL_WARNING',
    messageSeries: { seriesId: 's' },
    descs: [{ lang: 'en', title: 'T' }],
    ...extra,
  };
}

function setup(message?: Message) {
  const msg = signal<Message | undefined>(message);
  const facade = { message: msg, reload: vi.fn(), bypassGuardOnce: vi.fn() };
  const api = {
    updateMessageStatus: vi.fn(() => of({} as Message)),
    referencedMessages: vi.fn(() => of([] as Message[])),
  };
  const notify = { error: vi.fn(), success: vi.fn() };
  const router = { navigate: vi.fn(() => Promise.resolve(true)) };
  const transloco = { translate: vi.fn((key: string) => key) };
  const copyDialog = {
    open: vi.fn((id: string) => of<string[] | undefined>(['/editor/copy', id])),
  };

  TestBed.configureTestingModule({
    providers: [
      MessageStatusService,
      { provide: EditorFacade, useValue: facade },
      { provide: MessagesApiService, useValue: api },
      { provide: NotificationService, useValue: notify },
      { provide: Router, useValue: router },
      { provide: TranslocoService, useValue: transloco },
      { provide: MessageCopyDialogService, useValue: copyDialog },
    ],
  });
  return { service: TestBed.inject(MessageStatusService), facade, api, notify, router, copyDialog };
}

describe('MessageStatusService', () => {
  describe('verify', () => {
    it('aborts on a non-DRAFT message', () => {
      const { service, api, notify } = setup(valid('VERIFIED'));
      service.verify();
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.onlyDraft');
    });

    it('aborts with a missing-fields toast when canPublish fails', () => {
      const { service, api, notify } = setup(valid('DRAFT', { messageSeries: undefined }));
      service.verify();
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith(
        'editor.status.error.missingFields',
        expect.objectContaining({ fields: expect.stringContaining('editor.status.field.series') }),
      );
    });

    it('refuses Verify with a text that names Verify, since Verify and Publish share the gate', () => {
      // The toast's key is the one Publish uses too; its English text must not speak of publishing only.
      expect(en.editor.status.error.missingFields).toBe(
        'Cannot verify or publish — missing: {{fields}}',
      );
    });

    it('transitions DRAFT → VERIFIED, then reloads and toasts', () => {
      const { service, api, facade, notify } = setup(valid('DRAFT'));
      service.verify();
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'VERIFIED', undefined);
      expect(facade.reload).toHaveBeenCalled();
      expect(notify.success).toHaveBeenCalledWith('editor.status.toast.verified');
    });
  });

  describe('revertToDraft', () => {
    it('aborts on a non-VERIFIED message', () => {
      const { service, api, notify } = setup(valid('DRAFT'));
      service.revertToDraft();
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.onlyVerifiedRevert');
    });

    it('transitions VERIFIED → DRAFT (un-verify), then reloads and toasts', () => {
      const { service, api, facade, notify } = setup(valid('VERIFIED'));
      service.revertToDraft();
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'DRAFT', undefined);
      expect(facade.reload).toHaveBeenCalled();
      expect(notify.success).toHaveBeenCalledWith('editor.status.toast.revertedToDraft');
    });
  });

  describe('publish', () => {
    it('aborts on a non-VERIFIED message', () => {
      const { service, api, notify } = setup(valid('DRAFT'));
      service.publish(false);
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.onlyVerified');
    });

    it('sends cancelReferencedMessages only when checked', () => {
      const { service, api } = setup(valid('VERIFIED'));
      service.publish(true);
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'PUBLISHED', {
        cancelReferencedMessages: true,
      });
    });

    it('omits the cascade param when unchecked', () => {
      const { service, api } = setup(valid('VERIFIED'));
      service.publish(false);
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'PUBLISHED', undefined);
    });

    it('VERIFIED-but-invalid: toasts missing fields and does not update status', () => {
      const { service, api, notify } = setup(valid('VERIFIED', { type: undefined }));
      service.publish(false);
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith(
        'editor.status.error.missingFields',
        expect.anything(),
      );
    });
  });

  describe('validateForPublish', () => {
    it('returns true for a valid message without toasting', () => {
      const { service, notify } = setup(valid('DRAFT'));
      expect(service.validateForPublish()).toBe(true);
      expect(notify.error).not.toHaveBeenCalled();
    });

    it('returns false and toasts the missing fields when invalid', () => {
      const { service, notify } = setup(valid('DRAFT', { type: undefined }));
      expect(service.validateForPublish()).toBe(false);
      expect(notify.error).toHaveBeenCalledWith(
        'editor.status.error.missingFields',
        expect.anything(),
      );
    });
  });

  describe('referencedMessages', () => {
    it('looks up the four cascade reference types in PUBLISHED status', () => {
      const { service, api } = setup(valid('VERIFIED'));
      service.referencedMessages().subscribe();
      expect(api.referencedMessages).toHaveBeenCalledWith(
        'm-1',
        ['CANCELLATION', 'REPETITION', 'REPETITION_NEW_TIME', 'UPDATE'],
        'PUBLISHED',
      );
    });
  });

  describe('delete', () => {
    it('aborts when the message is neither DRAFT nor VERIFIED', () => {
      const { service, api, notify } = setup(valid('PUBLISHED'));
      service.delete();
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.onlyDraftVerified');
    });

    it('transitions to DELETED', () => {
      const { service, api } = setup(valid('VERIFIED'));
      service.delete();
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'DELETED', undefined);
    });
  });

  describe('cancel', () => {
    it('aborts on a non-PUBLISHED message', () => {
      const { service, api, notify } = setup(valid('DRAFT'));
      service.cancel(false);
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.onlyPublished');
    });

    it('NM + create-cancellation navigates to copy/:id/CANCELLATION (allow-listed), no reload', () => {
      const { service, api, facade, router } = setup(valid('PUBLISHED', { mainType: 'NM' }));
      service.cancel(true);
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'CANCELLED');
      expect(facade.bypassGuardOnce).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/editor/copy', 'm-1', 'CANCELLATION']);
      expect(facade.reload).not.toHaveBeenCalled();
    });

    it('NW (even with the box checked) reloads instead of navigating', () => {
      const { service, facade, router, notify } = setup(valid('PUBLISHED', { mainType: 'NW' }));
      service.cancel(true);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(facade.reload).toHaveBeenCalled();
      expect(notify.success).toHaveBeenCalledWith('editor.status.toast.cancelled');
    });

    it('failure: toasts cancelFailed and does not reload or navigate', () => {
      const { service, api, notify, facade, router } = setup(
        valid('PUBLISHED', { mainType: 'NW' }),
      );
      api.updateMessageStatus.mockReturnValueOnce(throwError(() => new Error('boom')));
      service.cancel(false);
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.cancelFailed');
      expect(facade.reload).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('copy', () => {
    it('opens the copy dialog for the current message id + main type', () => {
      const { service, copyDialog } = setup(valid('PUBLISHED', { mainType: 'NM' }));
      service.copy();
      expect(copyDialog.open).toHaveBeenCalledWith('m-1', 'NM');
    });

    it('navigates the dialog result, allow-listed past the unsaved-changes guard', () => {
      const { service, facade, router } = setup(valid('PUBLISHED'));
      service.copy();
      expect(facade.bypassGuardOnce).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/editor/copy', 'm-1']);
    });

    it('does not bypass the guard or navigate when the dialog is cancelled', () => {
      const { service, facade, router, copyDialog } = setup(valid('PUBLISHED'));
      copyDialog.open.mockReturnValueOnce(of(undefined));
      service.copy();
      expect(facade.bypassGuardOnce).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('transition failure', () => {
    it('toasts the error key and does not reload', () => {
      const { service, api, notify, facade } = setup(valid('DRAFT'));
      api.updateMessageStatus.mockReturnValueOnce(throwError(() => new Error('boom')));
      service.verify();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.verifyFailed');
      expect(facade.reload).not.toHaveBeenCalled();
    });

    it('a 409 with a detail toasts the refusal with the backend reason and reloads the message', () => {
      const { service, api, notify, facade } = setup(valid('VERIFIED'));
      api.updateMessageStatus.mockReturnValueOnce(throwError(() => refused(DETAIL)));
      service.publish(true);
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.refusedReloaded', {
        detail: DETAIL,
      });
      expect(notify.error).not.toHaveBeenCalledWith('editor.status.error.publishFailed');
      // The message changed meanwhile (a stale tab): the panel must show its status as it is now.
      expect(facade.reload).toHaveBeenCalledTimes(1);
      expect(notify.success).not.toHaveBeenCalled();
    });

    it('a 500 with a detail still toasts the old error key and does not reload', () => {
      const { service, api, notify, facade } = setup(valid('VERIFIED'));
      api.updateMessageStatus.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'boom' } })),
      );
      service.publish(false);
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.publishFailed');
      expect(notify.error).toHaveBeenCalledTimes(1);
      expect(facade.reload).not.toHaveBeenCalled();
    });

    it('a 409 without a detail toasts the old error key and reloads the message', () => {
      const { service, api, notify, facade } = setup(valid('DRAFT'));
      api.updateMessageStatus.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 409, error: null })),
      );
      service.verify();
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.verifyFailed');
      expect(facade.reload).toHaveBeenCalledTimes(1);
    });
  });

  describe('cancel refused', () => {
    it('a 409 with a detail toasts the refusal with the backend reason and reloads the message', () => {
      const { service, api, notify, facade, router } = setup(
        valid('PUBLISHED', { mainType: 'NM' }),
      );
      api.updateMessageStatus.mockReturnValueOnce(throwError(() => refused(DETAIL)));
      service.cancel(true);
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.refusedReloaded', {
        detail: DETAIL,
      });
      expect(facade.reload).toHaveBeenCalledTimes(1);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('a 500 toasts cancelFailed and does not reload', () => {
      const { service, api, notify, facade } = setup(valid('PUBLISHED', { mainType: 'NW' }));
      api.updateMessageStatus.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'boom' } })),
      );
      service.cancel(false);
      expect(notify.error).toHaveBeenCalledWith('editor.status.error.cancelFailed');
      expect(facade.reload).not.toHaveBeenCalled();
    });
  });

  describe('busy while a status change runs', () => {
    it('is not busy before any change', () => {
      const { service } = setup(valid('DRAFT'));
      expect(service.busy()).toBe(false);
    });

    it.each<[string, MessageStatus, (s: MessageStatusService) => void]>([
      ['verify', 'DRAFT', (s) => s.verify()],
      ['revertToDraft', 'VERIFIED', (s) => s.revertToDraft()],
      ['publish', 'VERIFIED', (s) => s.publish(false)],
      ['delete', 'DRAFT', (s) => s.delete()],
      ['cancel', 'PUBLISHED', (s) => s.cancel(false)],
    ])('a second %s while the first request runs sends nothing', (_name, status, act) => {
      const { service, api, facade, notify } = setup(valid(status, { mainType: 'NW' }));
      const answer = new Subject<Message>();
      api.updateMessageStatus.mockReturnValue(answer);

      act(service);
      expect(service.busy()).toBe(true);
      act(service);
      expect(api.updateMessageStatus).toHaveBeenCalledTimes(1);

      answer.next({} as Message);
      answer.complete();
      expect(service.busy()).toBe(false);
      expect(facade.reload).toHaveBeenCalledTimes(1);
      expect(notify.success).toHaveBeenCalledTimes(1);
    });

    it('a call made while a different change runs sends nothing either', () => {
      const { service, api } = setup(valid('VERIFIED'));
      api.updateMessageStatus.mockReturnValue(new Subject<Message>());
      service.publish(false);
      service.delete();
      expect(api.updateMessageStatus).toHaveBeenCalledTimes(1);
      expect(api.updateMessageStatus).toHaveBeenCalledWith('m-1', 'PUBLISHED', undefined);
    });

    it('is no longer busy after a failed change, so the officer can try again', () => {
      const { service, api } = setup(valid('DRAFT'));
      const answer = new Subject<Message>();
      api.updateMessageStatus.mockReturnValueOnce(answer);
      service.verify();
      expect(service.busy()).toBe(true);
      answer.error(new HttpErrorResponse({ status: 500 }));
      expect(service.busy()).toBe(false);

      service.verify();
      expect(api.updateMessageStatus).toHaveBeenCalledTimes(2);
    });

    it('a change refused before any request (wrong status) does not make it busy', () => {
      const { service, api } = setup(valid('VERIFIED'));
      service.verify();
      expect(api.updateMessageStatus).not.toHaveBeenCalled();
      expect(service.busy()).toBe(false);
    });
  });
});

const DETAIL = 'Invalid status transition PUBLISHED -> CANCELLED for message NW-001-26';

function refused(detail: string): HttpErrorResponse {
  return new HttpErrorResponse({ status: 409, error: { status: 409, detail } });
}
