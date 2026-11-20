import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of, Subject, throwError } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessageMailDialog } from './message-mail-dialog';
import { MailRequest, MailSender, MessageMailDialogService } from './message-mail-dialog.service';

@Component({
  imports: [MessageMailDialog],
  template: `<app-message-mail-dialog />`,
})
class Host {}

interface Internals {
  form: MessageMailDialog['form'];
  sendDisabled: () => boolean;
  onSend: () => void;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, service: TestBed.inject(MessageMailDialogService) };
}

function editorOf(fixture: ReturnType<typeof setup>['fixture']): Internals {
  return fixture.debugElement.children[0].componentInstance as unknown as Internals;
}

/** Fills the form with a sendable mail. */
function fill(editor: Internals): void {
  editor.form.mailSubject().value.set('Subject');
  editor.form.emailAddresses().value.set(['a@b.com']);
  editor.form.mailMessage().value.set('Body');
}

const TYPED: MailRequest = {
  emailAddresses: ['a@b.com'],
  mailSubject: 'Subject',
  mailMessage: 'Body',
  language: 'en',
};

describe('MessageMailDialog', () => {
  it('gates Send on a change + valid subject + ≥1 recipient', () => {
    const { fixture, service } = setup();
    service.open(['m1', 'm2', 'm3'], () => of(undefined)).subscribe();
    fixture.detectChanges();
    const editor = editorOf(fixture);

    expect(editor.sendDisabled()).toBe(true); // empty on open

    editor.form.mailSubject().value.set('Subject');
    expect(editor.sendDisabled()).toBe(true); // no recipient yet

    editor.form.emailAddresses().value.set(['a@b.com']);
    editor.form.mailMessage().value.set('Body');
    expect(editor.sendDisabled()).toBe(false);
  });

  it('runs the send itself and closes only when it succeeds, then emits the MailRequest', () => {
    const { fixture, service } = setup();
    const answer = new Subject<void>();
    const sender = vi.fn<MailSender>(() => answer);
    let emitted: MailRequest | undefined;
    service.open(['m1'], sender).subscribe((r) => (emitted = r));
    fixture.detectChanges();
    const editor = editorOf(fixture);
    fill(editor);

    editor.onSend();

    expect(sender).toHaveBeenCalledWith(TYPED);
    // While the send runs the dialog stays open, and Send cannot be pressed twice.
    expect(service.visible()).toBe(true);
    expect(service.sending()).toBe(true);
    expect(editor.sendDisabled()).toBe(true);
    expect(emitted).toBeUndefined();

    answer.next();
    answer.complete();

    expect(service.visible()).toBe(false);
    expect(service.sending()).toBe(false);
    expect(emitted).toEqual(TYPED);
  });

  it('a failed send keeps the dialog open with what was typed, and Send can be pressed again', () => {
    const { fixture, service } = setup();
    let fail = true;
    const sender = vi.fn<MailSender>(() =>
      fail ? throwError(() => new Error('refused')) : of(undefined),
    );
    let emitted: MailRequest | undefined;
    let closed = false;
    service.open(['m1'], sender).subscribe({
      next: (r) => (emitted = r),
      complete: () => (closed = true),
    });
    fixture.detectChanges();
    const editor = editorOf(fixture);
    fill(editor);

    editor.onSend();
    fixture.detectChanges();

    expect(service.visible()).toBe(true);
    expect(closed).toBe(false);
    expect(editor.form.mailSubject().value()).toBe('Subject');
    expect(editor.form.emailAddresses().value()).toEqual(['a@b.com']);
    expect(editor.form.mailMessage().value()).toBe('Body');
    expect(service.sending()).toBe(false);
    expect(editor.sendDisabled()).toBe(false);

    fail = false;
    editor.onSend();

    expect(sender).toHaveBeenCalledTimes(2);
    expect(service.visible()).toBe(false);
    expect(emitted).toEqual(TYPED);
  });

  it('a send that ends after the dialog was closed and opened again does not close the new one', () => {
    const { fixture, service } = setup();
    const first = new Subject<void>();
    service.open(['m1'], () => first).subscribe();
    fixture.detectChanges();
    fill(editorOf(fixture));
    editorOf(fixture).onSend();

    service.dismiss();
    fixture.detectChanges();
    let emitted: MailRequest | undefined;
    service.open(['m2'], () => of(undefined)).subscribe((r) => (emitted = r));
    fixture.detectChanges();

    first.next();
    first.complete();

    expect(service.visible()).toBe(true);
    expect(service.sending()).toBe(false);
    expect(emitted).toBeUndefined();
  });

  it("shows the list's warning when the messages come from a list the search index was rebuilt under", () => {
    const { fixture, service } = setup();
    const warning = () => document.querySelector('[data-testid="mail-index-incomplete"]');

    service.open(['m1'], () => of(undefined), true).subscribe();
    fixture.detectChanges();
    expect(warning()?.textContent?.trim()).toBe('messages.list.indexIncomplete');

    service.dismiss();
    fixture.detectChanges();
    service.open(['m1'], () => of(undefined)).subscribe();
    fixture.detectChanges();
    expect(warning()).toBeNull();
  });

  it('is described by the warning, so a screen reader announces it before Send', () => {
    const { fixture, service } = setup();
    const panel = () => document.querySelector('.dialog-panel');

    service.open(['m1'], () => of(undefined), true).subscribe();
    fixture.detectChanges();
    const warning = document.querySelector('[data-testid="mail-index-incomplete"]');
    expect(warning?.id).toBeTruthy();
    expect(panel()?.getAttribute('aria-describedby')).toBe(warning?.id);

    service.dismiss();
    fixture.detectChanges();
    service.open(['m1'], () => of(undefined)).subscribe();
    fixture.detectChanges();
    expect(panel()?.hasAttribute('aria-describedby')).toBe(false);
  });

  it('resets to empty each time the dialog re-opens (no leak from the prior open)', () => {
    const { fixture, service } = setup();
    service.open(['m1'], () => of(undefined)).subscribe();
    fixture.detectChanges();
    editorOf(fixture).form.mailSubject().value.set('Old subject');
    service.dismiss();
    fixture.detectChanges();

    service.open(['m2'], () => of(undefined)).subscribe();
    fixture.detectChanges();
    const editor = editorOf(fixture);
    expect(editor.form.mailSubject().value()).toBe('');
    expect(editor.sendDisabled()).toBe(true);
  });
});
