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
import type { Message, MessageStatus } from '../../../messages/models/message.model';
import { EditorFacade } from '../../services/editor-facade';
import { MessageStatusService } from '../../services/message-status.service';
import { StatusFlow } from './status-flow';

interface SetupOptions {
  readonly status?: MessageStatus;
  readonly publishDateFrom?: string;
  readonly isEditor?: boolean;
  /** The sticky latch: set by any edit, even one undone since. */
  readonly dirty?: boolean;
  /** The facade's `hasUnsavedChanges`: an edit not undone, or a message never saved. */
  readonly unsaved?: boolean;
}

async function setup(opts: SetupOptions = {}) {
  const message = signal<Message | undefined>({
    id: 'm-1',
    status: opts.status ?? 'DRAFT',
    publishDateFrom: opts.publishDateFrom,
  } as Message);

  const facade = {
    isEditor: signal(opts.isEditor ?? true),
    dirty: signal(opts.dirty ?? false),
    hasUnsavedChanges: signal(opts.unsaved ?? false),
    message,
  };

  const statusService = {
    validateForPublish: vi.fn(() => true),
    referencedMessages: vi.fn(() => of([] as Message[])),
    verify: vi.fn(),
    publish: vi.fn(),
    revertToDraft: vi.fn(),
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

  const fixture = TestBed.createComponent(StatusFlow);

  // Auto-accept the component-scoped confirm so a CTA click flows straight through.
  const confirmService = fixture.debugElement.injector.get(ConfirmService);
  vi.spyOn(confirmService, 'confirmNeutral').mockImplementation((o) => o.accept());

  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, facade, statusService, notify };
}

type Fixture = Awaited<ReturnType<typeof setup>>['fixture'];

const nodeLabels = (fixture: Fixture): string[] =>
  Array.from(fixture.nativeElement.querySelectorAll('ol li'))
    .map((li) => (li as HTMLElement).textContent?.trim() ?? '')
    .filter((t) => t.includes('msg.status.'));

const button = (fixture: Fixture, fragment: string): HTMLButtonElement | undefined =>
  Array.from(
    fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
  ).find((b) => (b.textContent ?? '').includes(fragment));

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('StatusFlow — nodes per status', () => {
  it('DRAFT shows the 3 spine nodes and a Verify action', async () => {
    const { fixture } = await setup({ status: 'DRAFT' });
    const labels = nodeLabels(fixture);
    expect(labels.length).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('msg.status.DRAFT');
    expect(fixture.nativeElement.textContent).toContain('msg.status.PUBLISHED');
    expect(button(fixture, 'verify_short')).toBeTruthy();
  });

  it('VERIFIED (no scheduled date) shows the spine and a Publish action', async () => {
    const { fixture } = await setup({ status: 'VERIFIED' });
    expect(nodeLabels(fixture).length).toBe(3);
    expect(button(fixture, 'publish_short')).toBeTruthy();
    // \b so the Publish button key (editor.status.publish_short) isn't matched as the note.
    expect(fixture.nativeElement.textContent).not.toMatch(/editor\.status\.publish\b/);
  });

  it('VERIFIED with a publishDateFrom shows a scheduled note instead of Publish', async () => {
    const { fixture } = await setup({
      status: 'VERIFIED',
      publishDateFrom: '2026-08-01T10:00:00Z',
    });
    expect(button(fixture, 'publish_short')).toBeFalsy();
    expect(fixture.nativeElement.textContent).toMatch(/editor\.status\.publish\b/);
  });

  it('PUBLISHED shows the spine with no forward action (Cancel lives on the Status page)', async () => {
    const { fixture } = await setup({ status: 'PUBLISHED' });
    expect(nodeLabels(fixture).length).toBe(3);
    expect(button(fixture, 'verify_short')).toBeFalsy();
    expect(button(fixture, 'publish_short')).toBeFalsy();
  });

  it('EXPIRED appends a 4th terminal node and offers no action', async () => {
    const { fixture } = await setup({ status: 'EXPIRED' });
    const labels = nodeLabels(fixture);
    expect(labels.length).toBe(4);
    expect(fixture.nativeElement.textContent).toContain('msg.status.EXPIRED');
    expect(fixture.nativeElement.querySelectorAll('button').length).toBe(0);
  });

  it('CANCELLED appends a 4th terminal node and offers no action', async () => {
    const { fixture } = await setup({ status: 'CANCELLED' });
    expect(nodeLabels(fixture).length).toBe(4);
    expect(fixture.nativeElement.textContent).toContain('msg.status.CANCELLED');
  });

  it('DELETED renders no flow at all', async () => {
    const { fixture } = await setup({ status: 'DELETED' });
    expect(fixture.nativeElement.querySelector('ol')).toBeNull();
    expect(nodeLabels(fixture).length).toBe(0);
  });
});

describe('StatusFlow — actions', () => {
  it('Verify validates then verifies through the service', async () => {
    const { fixture, statusService } = await setup({ status: 'DRAFT' });
    button(fixture, 'verify_short')!.click();
    await fixture.whenStable();
    expect(statusService.validateForPublish).toHaveBeenCalled();
    expect(statusService.verify).toHaveBeenCalled();
  });

  it('the Draft node steps a VERIFIED message back to draft (un-verify)', async () => {
    const { fixture, statusService } = await setup({ status: 'VERIFIED' });
    const revertBtn = Array.from(
      fixture.nativeElement.querySelectorAll('ol button') as NodeListOf<HTMLButtonElement>,
    )[0];
    expect(revertBtn).toBeTruthy();
    revertBtn.click();
    await fixture.whenStable();
    expect(statusService.revertToDraft).toHaveBeenCalled();
  });

  it('the Draft node is NOT clickable while DRAFT (no step-back from the first state)', async () => {
    const { fixture } = await setup({ status: 'DRAFT' });
    expect(fixture.nativeElement.querySelector('ol button')).toBeNull();
  });

  it('opening publish looks up cascade-cancel references then shows the dialog', async () => {
    const { fixture, statusService } = await setup({ status: 'VERIFIED' });
    button(fixture, 'publish_short')!.click();
    await fixture.whenStable();
    expect(statusService.referencedMessages).toHaveBeenCalled();
  });

  it('Verify is disabled while the editor has unsaved changes (an edit, or a message never saved)', async () => {
    const { fixture } = await setup({ status: 'DRAFT', dirty: true, unsaved: true });
    expect(button(fixture, 'verify_short')!.disabled).toBe(true);
  });

  it('Publish and the step-back are disabled while the editor has unsaved changes', async () => {
    const { fixture } = await setup({ status: 'VERIFIED', dirty: true, unsaved: true });
    expect(button(fixture, 'publish_short')!.disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('ol button') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  it('an edit undone enables Verify again (the sticky dirty latch is not read)', async () => {
    const { fixture } = await setup({ status: 'DRAFT', dirty: true, unsaved: false });
    expect(button(fixture, 'verify_short')!.disabled).toBe(false);
  });

  it('an edit undone enables Publish and the step-back again', async () => {
    const { fixture } = await setup({ status: 'VERIFIED', dirty: true, unsaved: false });
    expect(button(fixture, 'publish_short')!.disabled).toBe(false);
    expect((fixture.nativeElement.querySelector('ol button') as HTMLButtonElement).disabled).toBe(
      false,
    );
  });

  it('a non-editor sees the stepper but no action button', async () => {
    const { fixture } = await setup({ status: 'DRAFT', isEditor: false });
    expect(nodeLabels(fixture).length).toBe(3);
    expect(button(fixture, 'verify_short')).toBeFalsy();
  });
});
