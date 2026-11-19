import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ScheduledMail } from '../../../services/api/scheduled-mails-api.service';
import { MailDetailsDialog } from './mail-details-dialog';

@Component({
  imports: [MailDetailsDialog],
  template: `<app-mail-details-dialog
    [mail]="mail()"
    [sendDateLabel]="sendDateLabel()"
    (closed)="closedCount = closedCount + 1"
  />`,
})
class Host {
  readonly mail = signal<ScheduledMail | undefined>(undefined);
  readonly sendDateLabel = signal('2026-06-11 10:00');
  closedCount = 0;
}

const baseMail: ScheduledMail = {
  id: 7,
  status: 'PENDING',
  sender: 'niord@example.org',
  subject: 'Warning issued',
  recipients: [
    { recipientType: 'TO', address: 'a@x.dk' },
    { recipientType: 'TO', address: 'b@x.dk' },
    { recipientType: 'BCC', address: 'c@x.dk' },
  ],
  contents: '<p>Hello</p>',
};

async function setup(mail: ScheduledMail, sendDateLabel = '2026-06-11 10:00') {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.mail.set(mail);
  fixture.componentInstance.sendDateLabel.set(sendDateLabel);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, doc: fixture.nativeElement.ownerDocument as Document };
}

describe('MailDetailsDialog', () => {
  afterEach(() => {
    // The dialog body portals to the body-level cdk-overlay-container; dispose any
    // leaked panel across the shared single-fork TestBed before the next test.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('groups recipients by type and omits empty type rows (no CC row here)', async () => {
    const { doc } = await setup(baseMail);
    const text = doc.body.textContent ?? '';
    expect(text).toContain('a@x.dk, b@x.dk');
    expect(text).toContain('admin.mails.details.bcc');
    expect(text).not.toContain('admin.mails.details.cc'); // careful: bcc contains 'cc'
  });

  it('labels the send date Due while PENDING, and shows no error row without lastError', async () => {
    const { doc } = await setup(baseMail);
    const text = doc.body.textContent ?? '';
    expect(text).toContain('admin.mails.details.due');
    expect(text).not.toContain('admin.mails.details.sent');
    expect(text).toContain('2026-06-11 10:00');
    expect(text).not.toContain('admin.mails.details.error');
  });

  it('labels the send date Sent for a sent mail', async () => {
    const { doc } = await setup({ ...baseMail, status: 'SENT', attempts: 1 });
    const text = doc.body.textContent ?? '';
    expect(text).toContain('admin.mails.details.sent');
    expect(text).not.toContain('admin.mails.details.due');
    expect(text).toContain('2026-06-11 10:00');
  });

  it('labels the send date Last attempt for a mail that failed for good', async () => {
    const { doc } = await setup({ ...baseMail, status: 'ERROR', attempts: 5, lastError: 'x' });
    const text = doc.body.textContent ?? '';
    expect(text).toContain('admin.mails.details.lastAttempt');
    expect(text).not.toContain('admin.mails.details.sent');
    expect(text).not.toContain('admin.mails.details.due');
    expect(text).toContain('2026-06-11 10:00');
  });

  it('leaves the date row out for a failure record, a mail in Error never attempted', async () => {
    const { doc } = await setup({
      ...baseMail,
      status: 'ERROR',
      attempts: 0,
      lastError: 'No template',
    });
    const text = doc.body.textContent ?? '';
    expect(text).not.toContain('admin.mails.details.lastAttempt');
    expect(text).not.toContain('admin.mails.details.due');
    expect(text).not.toContain('2026-06-11 10:00');
    expect(text).toContain('No template');
  });

  it('leaves the date row out when the mail has no send date', async () => {
    const { doc } = await setup(baseMail, '');
    const text = doc.body.textContent ?? '';
    expect(text).not.toContain('admin.mails.details.due');
    expect(text).not.toContain('admin.mails.details.sent');
    expect(text).not.toContain('admin.mails.details.lastAttempt');
  });

  it('renders the error row with attempts when a send error exists', async () => {
    const { doc } = await setup({
      ...baseMail,
      status: 'ERROR',
      lastError: 'SMTP down',
      attempts: 3,
    });
    const text = doc.body.textContent ?? '';
    expect(text).toContain('SMTP down');
    expect(text).toContain('admin.mails.details.attempts');
  });

  it('renders the mail body in a sandboxed iframe — full fidelity, scripts inert', async () => {
    const { doc } = await setup(baseMail);
    const iframe = doc.querySelector('.dialog-panel iframe') as HTMLIFrameElement;
    expect(iframe).not.toBeNull();
    expect(iframe.getAttribute('sandbox')).toBe(''); // empty sandbox = nothing allowed
    expect(iframe.getAttribute('srcdoc')).toContain('<p>Hello</p>');
    expect(iframe.getAttribute('title')).toBeTruthy();
  });

  it('injects the inline mail stylesheet so the preview is not unstyled', async () => {
    const { doc } = await setup(baseMail);
    const iframe = doc.querySelector('.dialog-panel iframe') as HTMLIFrameElement;
    const srcdoc = iframe.getAttribute('srcdoc') ?? '';
    // The frame is empty-sandbox, so linked mail.css can't load — the styles
    // must travel inline as a <style> block while the body stays intact.
    expect(srcdoc).toContain('<style>');
    expect(srcdoc).toContain('table.message-table');
    expect(srcdoc).toContain('<p>Hello</p>');
  });

  it('strips dead linked-stylesheet tags from the sandboxed body', async () => {
    const { doc } = await setup({
      ...baseMail,
      contents:
        '<html><head><link rel="stylesheet" href="/css/templates/mail.css"></head>' +
        '<body><p>Hello</p></body></html>',
    });
    const iframe = doc.querySelector('.dialog-panel iframe') as HTMLIFrameElement;
    const srcdoc = iframe.getAttribute('srcdoc') ?? '';
    expect(srcdoc).not.toContain('<link');
    expect(srcdoc).toContain('<style>');
    expect(srcdoc).toContain('<p>Hello</p>');
  });
});
