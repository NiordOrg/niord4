import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { DomainService } from '../../../../core/domain/services/domain.service';
import type { MainType, Message, MessageStatus } from '../../../messages/models/message.model';
import { EditorFacade } from '../../services/editor-facade';
import { MessageStatusService } from '../../services/message-status.service';
import { StatusActionPanel } from './status-action-panel';

interface SetupOptions {
  readonly status?: MessageStatus;
  readonly mainType?: MainType;
  readonly publishDateFrom?: string;
  readonly isEditor?: boolean;
  /** The sticky latch: set by any edit, even one undone since. */
  readonly dirty?: boolean;
  /** The facade's `hasUnsavedChanges`: an edit not undone, or a message never saved. */
  readonly unsaved?: boolean;
  readonly valid?: boolean;
  /** The status service's `busy`: a status change is on its way to the backend. */
  readonly busy?: boolean;
}

async function setup(opts: SetupOptions = {}) {
  const message = signal<Message | undefined>({
    id: 'm-1',
    status: opts.status ?? 'DRAFT',
    mainType: opts.mainType,
    publishDateFrom: opts.publishDateFrom,
  } as Message);

  const facade = {
    isEditor: signal(opts.isEditor ?? true),
    dirty: signal(opts.dirty ?? false),
    hasUnsavedChanges: signal(opts.unsaved ?? false),
    message,
  };

  const statusService = {
    busy: signal(opts.busy ?? false),
    validateForPublish: vi.fn(() => opts.valid ?? true),
    referencedMessages: vi.fn(() => of([] as Message[])),
    verify: vi.fn(),
    delete: vi.fn(),
    publish: vi.fn(),
    cancel: vi.fn(),
    copy: vi.fn(),
  };

  const notify = { error: vi.fn(), success: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
      provideNiordIcons(),
      { provide: EditorFacade, useValue: facade },
      { provide: MessageStatusService, useValue: statusService },
      { provide: NotificationService, useValue: notify },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(StatusActionPanel);

  // The panel re-provides its own component-scoped ConfirmService (so its confirms render
  // through its own <app-confirm>). Grab that instance and auto-accept: the mocked
  // confirmNeutral / confirmDestructive run their `accept` callback synchronously, so a
  // click on Verify/Delete flows straight through to the status service without a rendered
  // overlay to drive.
  const confirmService = fixture.debugElement.injector.get(ConfirmService);
  const confirmNeutral = vi.fn();
  const confirmDestructive = vi.fn();
  vi.spyOn(confirmService, 'confirmNeutral').mockImplementation((o) => {
    confirmNeutral(o);
    o.accept();
  });
  vi.spyOn(confirmService, 'confirmDestructive').mockImplementation((o) => {
    confirmDestructive(o);
    o.accept();
  });

  fixture.autoDetectChanges();
  await fixture.whenStable();

  return { fixture, facade, statusService, notify, confirmNeutral, confirmDestructive };
}

type Fixture = Awaited<ReturnType<typeof setup>>['fixture'];

function actionButtons(fixture: Fixture): HTMLButtonElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
}

function findButton(fixture: Fixture, keyFragment: string): HTMLButtonElement | undefined {
  return actionButtons(fixture).find((b) => (b.textContent ?? '').includes(keyFragment));
}

const q = (sel: string) => document.querySelector(sel);

afterEach(() => {
  // Dispose any leaked overlay panels across the shared single-fork TestBed.
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('StatusActionPanel — action visibility per status', () => {
  it('shows the localized status label for the current message', async () => {
    const { fixture } = await setup({ status: 'PUBLISHED' });
    expect(fixture.nativeElement.textContent).toContain('msg.status.PUBLISHED');
  });

  it('DRAFT offers Verify, Delete and Copy but neither Publish nor Cancel', async () => {
    const { fixture } = await setup({ status: 'DRAFT' });
    expect(findButton(fixture, 'verifyAction')).toBeTruthy();
    expect(findButton(fixture, 'deleteAction')).toBeTruthy();
    expect(findButton(fixture, 'copyAction')).toBeTruthy();
    expect(findButton(fixture, 'publishAction')).toBeFalsy();
    expect(findButton(fixture, 'cancelAction')).toBeFalsy();
  });

  it('VERIFIED (no scheduled publish) offers Publish, Delete and Copy but not Verify', async () => {
    const { fixture } = await setup({ status: 'VERIFIED' });
    expect(findButton(fixture, 'publishAction')).toBeTruthy();
    expect(findButton(fixture, 'deleteAction')).toBeTruthy();
    expect(findButton(fixture, 'copyAction')).toBeTruthy();
    expect(findButton(fixture, 'verifyAction')).toBeFalsy();
    // \b so the Publish button key (editor.status.publishAction) isn't matched as the note.
    expect(fixture.nativeElement.textContent).not.toMatch(/editor\.status\.publish\b/);
  });

  it('VERIFIED with a publishDateFrom replaces the Publish button with a scheduled note', async () => {
    const { fixture } = await setup({
      status: 'VERIFIED',
      publishDateFrom: '2026-08-01T10:00:00Z',
    });
    expect(findButton(fixture, 'publishAction')).toBeFalsy();
    expect(fixture.nativeElement.textContent).toMatch(/editor\.status\.publish\b/);
    // Delete + Copy still stand alongside the note.
    expect(findButton(fixture, 'deleteAction')).toBeTruthy();
    expect(findButton(fixture, 'copyAction')).toBeTruthy();
  });

  it('PUBLISHED offers Cancel and Copy but not Verify, Publish or Delete', async () => {
    const { fixture } = await setup({ status: 'PUBLISHED' });
    expect(findButton(fixture, 'cancelAction')).toBeTruthy();
    expect(findButton(fixture, 'copyAction')).toBeTruthy();
    expect(findButton(fixture, 'verifyAction')).toBeFalsy();
    expect(findButton(fixture, 'publishAction')).toBeFalsy();
    expect(findButton(fixture, 'deleteAction')).toBeFalsy();
  });

  it.each<MessageStatus>(['DRAFT', 'VERIFIED', 'PUBLISHED', 'CANCELLED'])(
    'always offers Copy to an editor on a %s message',
    async (status) => {
      const { fixture } = await setup({ status });
      expect(findButton(fixture, 'copyAction')).toBeTruthy();
    },
  );
});

describe('StatusActionPanel — editor gating and unsaved changes', () => {
  it('renders no action buttons for a non-editor', async () => {
    const { fixture } = await setup({ status: 'DRAFT', isEditor: false });
    expect(actionButtons(fixture)).toHaveLength(0);
  });

  it.each<MessageStatus>(['DRAFT', 'VERIFIED', 'PUBLISHED'])(
    'disables every action button of a %s message while the editor has unsaved changes',
    async (status) => {
      const { fixture } = await setup({ status, dirty: true, unsaved: true });
      const buttons = actionButtons(fixture);
      expect(buttons.length).toBeGreaterThan(0);
      expect(buttons.every((b) => b.disabled)).toBe(true);
    },
  );

  it.each<MessageStatus>(['DRAFT', 'VERIFIED', 'PUBLISHED'])(
    'enables the action buttons of a %s message again once an edit is undone (the sticky dirty latch is not read)',
    async (status) => {
      const { fixture } = await setup({ status, dirty: true, unsaved: false });
      const buttons = actionButtons(fixture);
      expect(buttons.length).toBeGreaterThan(0);
      expect(buttons.every((b) => !b.disabled)).toBe(true);
    },
  );
});

describe('StatusActionPanel — busy while a status change runs', () => {
  it.each<MessageStatus>(['DRAFT', 'VERIFIED', 'PUBLISHED'])(
    'disables every action button of a %s message while a status change runs',
    async (status) => {
      const { fixture } = await setup({ status, busy: true });
      const buttons = actionButtons(fixture);
      expect(buttons.length).toBeGreaterThan(0);
      expect(buttons.every((b) => b.disabled)).toBe(true);
    },
  );

  it('a second Verify while the first runs reaches no confirm and no status change', async () => {
    const { fixture, statusService, confirmNeutral } = await setup({ status: 'DRAFT' });
    findButton(fixture, 'verifyAction')!.click();
    await fixture.whenStable();
    expect(statusService.verify).toHaveBeenCalledTimes(1);

    // The first request is on its way: the service reports busy.
    statusService.busy.set(true);
    await fixture.whenStable();
    findButton(fixture, 'verifyAction')!.click();
    await fixture.whenStable();
    expect(confirmNeutral).toHaveBeenCalledTimes(1);
    expect(statusService.verify).toHaveBeenCalledTimes(1);

    // The answer came back: the buttons are enabled again.
    statusService.busy.set(false);
    await fixture.whenStable();
    expect(actionButtons(fixture).every((b) => !b.disabled)).toBe(true);
  });
});

describe('StatusActionPanel — validate before opening a confirm/dialog', () => {
  it('an invalid message blocks Verify: no confirm is raised and nothing transitions', async () => {
    const { fixture, statusService, confirmNeutral } = await setup({
      status: 'DRAFT',
      valid: false,
    });
    findButton(fixture, 'verifyAction')!.click();
    await fixture.whenStable();
    expect(statusService.validateForPublish).toHaveBeenCalled();
    expect(confirmNeutral).not.toHaveBeenCalled();
    expect(statusService.verify).not.toHaveBeenCalled();
  });

  it('an invalid message blocks Publish: no reference lookup and no dialog opens', async () => {
    const { fixture, statusService } = await setup({ status: 'VERIFIED', valid: false });
    findButton(fixture, 'publishAction')!.click();
    await fixture.whenStable();
    expect(statusService.referencedMessages).not.toHaveBeenCalled();
    expect(q('.dialog-panel')).toBeNull();
  });
});

describe('StatusActionPanel — dispatch to the right flow', () => {
  it('Verify raises a neutral confirm whose accept verifies', async () => {
    const { fixture, statusService, confirmNeutral } = await setup({ status: 'DRAFT' });
    findButton(fixture, 'verifyAction')!.click();
    await fixture.whenStable();
    expect(confirmNeutral).toHaveBeenCalledTimes(1);
    expect(statusService.verify).toHaveBeenCalledTimes(1);
  });

  it('Delete raises a destructive confirm whose accept deletes', async () => {
    const { fixture, statusService, confirmDestructive } = await setup({ status: 'DRAFT' });
    findButton(fixture, 'deleteAction')!.click();
    await fixture.whenStable();
    expect(confirmDestructive).toHaveBeenCalledTimes(1);
    expect(statusService.delete).toHaveBeenCalledTimes(1);
  });

  it('Publish (valid) looks up references, opens the publish dialog, and confirm publishes', async () => {
    const { fixture, statusService } = await setup({ status: 'VERIFIED' });
    findButton(fixture, 'publishAction')!.click();
    await fixture.whenStable();

    expect(statusService.referencedMessages).toHaveBeenCalledTimes(1);
    expect(q('.dialog-panel')).not.toBeNull();
    expect(q('.dialog-title')?.textContent).toContain('publishDialog');

    // Confirming the dialog dispatches publish with the (unchecked) cascade flag.
    (q('.dialog-foot .btn-primary') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(statusService.publish).toHaveBeenCalledWith(false);
  });

  it('Cancel opens the cancel dialog carrying the mainType, and confirm cancels', async () => {
    const { fixture, statusService } = await setup({ status: 'PUBLISHED', mainType: 'NM' });
    findButton(fixture, 'cancelAction')!.click();
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(q('.dialog-title')?.textContent).toContain('cancelDialog');
    // An NM message forwards `mainType`, so the create-cancellation checkbox is offered.
    expect(q('#create-cancellation')).not.toBeNull();

    (q('.dialog-foot .btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(statusService.cancel).toHaveBeenCalledWith(false);
  });

  it('Copy dispatches straight to the copy flow without a confirm', async () => {
    const { fixture, statusService, confirmNeutral, confirmDestructive } = await setup({
      status: 'DRAFT',
    });
    findButton(fixture, 'copyAction')!.click();
    await fixture.whenStable();
    expect(statusService.copy).toHaveBeenCalledTimes(1);
    expect(confirmNeutral).not.toHaveBeenCalled();
    expect(confirmDestructive).not.toHaveBeenCalled();
  });
});
