import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { TranslocoService } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageListFacade } from '../../services/message-list.facade';
import { MessageSelectionService } from '../../services/message-selection.service';
import { MessagesApiService } from '../../services/api/messages-api.service';
import { MessageStatusUpdateDialog } from './message-status-update-dialog';
import type { Message, MessageStatus } from '../../models/message.model';

function mkMessage(id: string, status: MessageStatus | undefined): Message {
  return { id, status } as Message;
}

/** A message with a title, as the list delivers it (one language's description). */
function titled(id: string, status: MessageStatus, extra: Partial<Message> = {}): Message {
  return { id, status, descs: [{ lang: 'en', title: `Title ${id}` }], ...extra } as Message;
}

const FAR_FUTURE = '2999-01-01T12:00:00Z';
const PAST = '2001-01-01T12:00:00Z';

interface SetupOptions {
  readonly updateError?: boolean;
  /** What the PUT fails with, when it fails with something other than a plain error. */
  readonly updateFailure?: unknown;
}

interface DialogAccess {
  visible: () => boolean;
  rows: () => readonly Message[];
  bulkOptions: () => { from: MessageStatus; to: MessageStatus }[];
  targets: () => Map<string, MessageStatus>;
  hasTargets: () => boolean;
  setTarget: (id: string, status: MessageStatus | null) => void;
  applyBulk: (option: { from: MessageStatus; to: MessageStatus }) => void;
  save: () => void;
  close: () => void;
  rowReasons: () => ReadonlyMap<string, readonly GateReasonView[]>;
}

interface GateReasonView {
  readonly code: string;
  readonly types?: readonly string[];
  readonly date?: string | number;
}

function setup(opts: SetupOptions = {}) {
  const updateStatuses = vi.fn(() => {
    if (opts.updateFailure !== undefined) {
      return throwError(() => opts.updateFailure);
    }
    return opts.updateError ? throwError(() => new Error('boom')) : of(undefined);
  });
  const api = { updateStatuses } as unknown as MessagesApiService;

  const refresh = vi.fn();
  const list = { refresh } as unknown as MessageListFacade;

  const applyStatusUpdates = vi.fn();
  const selection = { applyStatusUpdates } as unknown as MessageSelectionService;

  const success = vi.fn();
  const error = vi.fn();
  const notifications = { success, error } as unknown as NotificationService;

  const descriptions = {
    getTitle: vi.fn(() => 'Title'),
  } as unknown as MessageDescriptionService;

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: MessagesApiService, useValue: api },
      { provide: MessageListFacade, useValue: list },
      { provide: MessageSelectionService, useValue: selection },
      { provide: NotificationService, useValue: notifications },
      { provide: MessageDescriptionService, useValue: descriptions },
      // The header's help anchor reads the help links when the dialog renders.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      // The scheduled reason formats its date through the session domain's zone; a stub keeps
      // the spec free of the domain service.
      {
        provide: LocaleFormatService,
        useValue: { formatDateTime: (value: unknown) => `on ${String(value)}` },
      },
    ],
  });

  const fixture = TestBed.createComponent(MessageStatusUpdateDialog);
  // The component declares its own `providers: [ConfirmService]` so its confirm is
  // component-scoped. Grab the resolved instance and auto-accept: spy on
  // `confirmNeutral` so `save()` resolves synchronously (the mounted <app-confirm>
  // is never rendered in this spec — the request-bus is driven directly).
  const confirmService = fixture.debugElement.injector.get(ConfirmService);
  const confirmNeutral = vi.fn();
  vi.spyOn(confirmService, 'confirmNeutral').mockImplementation((options) => {
    confirmNeutral();
    options.accept?.();
  });
  const comp = fixture.componentInstance;

  return {
    fixture,
    comp,
    access: comp as unknown as DialogAccess,
    updateStatuses,
    refresh,
    applyStatusUpdates,
    success,
    notifyError: error,
    confirmNeutral,
  };
}

describe('MessageStatusUpdateDialog — rows', () => {
  beforeEach(() => undefined);

  it('excludes terminal-status messages (no valid transitions)', () => {
    const { comp, access } = setup();
    comp.open([
      mkMessage('a', 'DRAFT'),
      mkMessage('b', 'CANCELLED'),
      mkMessage('c', 'EXPIRED'),
      mkMessage('d', 'DELETED'),
      mkMessage('e', 'PUBLISHED'),
    ]);
    expect(access.rows().map((m) => m.id)).toEqual(['a', 'e']);
  });

  it('open() shows the dialog', () => {
    const { comp, access } = setup();
    expect(access.visible()).toBe(false);
    comp.open([mkMessage('a', 'DRAFT')]);
    expect(access.visible()).toBe(true);
  });
});

describe('MessageStatusUpdateDialog — bulk options', () => {
  it('de-dups distinct from→to transitions across rows', () => {
    const { comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT'), mkMessage('b', 'DRAFT'), mkMessage('c', 'PUBLISHED')]);
    // DRAFT → VERIFIED, DRAFT → DELETED (de-duped across the two DRAFT rows),
    // plus PUBLISHED → CANCELLED.
    expect(access.bulkOptions()).toEqual([
      { from: 'DRAFT', to: 'VERIFIED' },
      { from: 'DRAFT', to: 'DELETED' },
      { from: 'PUBLISHED', to: 'CANCELLED' },
    ]);
  });
});

describe('MessageStatusUpdateDialog — per-row targets', () => {
  it('setTarget sets and clears a row target', () => {
    const { comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');
    expect(access.targets().get('a')).toBe('VERIFIED');
    expect(access.hasTargets()).toBe(true);
    access.setTarget('a', null);
    expect(access.targets().has('a')).toBe(false);
    expect(access.hasTargets()).toBe(false);
  });

  it('applyBulk assigns the target only to rows whose status matches `from`', () => {
    const { comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT'), mkMessage('b', 'DRAFT'), mkMessage('c', 'PUBLISHED')]);
    access.applyBulk({ from: 'DRAFT', to: 'VERIFIED' });
    const targets = access.targets();
    expect(targets.get('a')).toBe('VERIFIED');
    expect(targets.get('b')).toBe('VERIFIED');
    expect(targets.has('c')).toBe(false);
  });
});

describe('MessageStatusUpdateDialog — the Change all statuses control', () => {
  async function render(fixture: ReturnType<typeof setup>['fixture']): Promise<void> {
    TestBed.tick();
    await fixture.whenStable();
  }

  /** The bulk control is the dialog's first Select; the rows' own follow it. */
  function bulkSelect(fixture: ReturnType<typeof setup>['fixture']): HTMLElement {
    const doc = (fixture.nativeElement as HTMLElement).ownerDocument;
    return doc.querySelectorAll('app-select')[0] as HTMLElement;
  }

  function panelOptions(fixture: ReturnType<typeof setup>['fixture']): HTMLElement[] {
    const doc = (fixture.nativeElement as HTMLElement).ownerDocument;
    return [...doc.querySelectorAll<HTMLElement>('.sel-panel .sel-option')];
  }

  async function pickBulk(fixture: ReturnType<typeof setup>['fixture'], index: number) {
    (bulkSelect(fixture).querySelector('.sel-trigger') as HTMLButtonElement).click();
    await render(fixture);
    panelOptions(fixture)[index].dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
    );
    await render(fixture);
  }

  it('picking the same transition again applies it again', async () => {
    const { fixture, comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT'), mkMessage('b', 'DRAFT')]);
    await render(fixture);

    await pickBulk(fixture, 0);
    expect(access.targets().get('a')).toBe('VERIFIED');
    expect(access.targets().get('b')).toBe('VERIFIED');

    access.setTarget('a', null);
    access.setTarget('b', null);
    await render(fixture);
    expect(access.hasTargets()).toBe(false);

    await pickBulk(fixture, 0);
    expect(access.targets().get('a')).toBe('VERIFIED');
    expect(access.targets().get('b')).toBe('VERIFIED');
    expect(access.hasTargets()).toBe(true);
  });

  it('shows its placeholder after a pick and after the dialog is opened again, with no option marked', async () => {
    const { fixture, comp, access } = setup();
    const messages = [mkMessage('a', 'DRAFT'), mkMessage('b', 'PUBLISHED')];
    comp.open(messages);
    await render(fixture);

    await pickBulk(fixture, 1);
    expect(access.targets().get('a')).toBe('DELETED');
    expect(bulkSelect(fixture).querySelector('.sel-placeholder')).not.toBeNull();

    access.close();
    comp.open(messages);
    await render(fixture);
    expect(access.targets().size).toBe(0);
    expect(bulkSelect(fixture).querySelector('.sel-placeholder')).not.toBeNull();

    (bulkSelect(fixture).querySelector('.sel-trigger') as HTMLButtonElement).click();
    await render(fixture);
    expect(panelOptions(fixture).length).toBe(3);
    expect(
      panelOptions(fixture).filter((option) => option.getAttribute('aria-selected') === 'true'),
    ).toEqual([]);
  });
});

describe('MessageStatusUpdateDialog — save', () => {
  it('builds updates keyed on UUID id and drops invalid transitions', () => {
    const { comp, access, updateStatuses, confirmNeutral } = setup();
    comp.open([mkMessage('a', 'DRAFT'), mkMessage('b', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED'); // valid
    access.setTarget('b', 'PUBLISHED'); // invalid for a DRAFT row — dropped

    access.save();

    expect(confirmNeutral).toHaveBeenCalledTimes(1);
    expect(updateStatuses).toHaveBeenCalledTimes(1);
    expect(updateStatuses).toHaveBeenCalledWith([{ messageId: 'a', status: 'VERIFIED' }]);
  });

  it('is a no-op close when nothing valid is chosen (no updateStatuses call)', () => {
    const { comp, access, updateStatuses, confirmNeutral } = setup();
    comp.open([mkMessage('a', 'DRAFT')]);

    access.save();

    expect(access.visible()).toBe(false);
    expect(confirmNeutral).not.toHaveBeenCalled();
    expect(updateStatuses).not.toHaveBeenCalled();
  });

  it('success path patches the selection snapshots, refreshes the list, and closes', () => {
    const { comp, access, refresh, success, applyStatusUpdates } = setup();
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(success).toHaveBeenCalledWith('messages.statusUpdate.toast.success');
    // The Selection tab reads snapshot status → patch it so the badge recolours even if the
    // message drops out of the active filter after the change.
    expect(applyStatusUpdates).toHaveBeenCalledWith([{ messageId: 'a', status: 'VERIFIED' }]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(access.visible()).toBe(false);
  });

  it('error path does NOT patch the selection snapshots', () => {
    const { comp, access, applyStatusUpdates } = setup({ updateError: true });
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(applyStatusUpdates).not.toHaveBeenCalled();
  });

  it('error path notifies and leaves the dialog open', () => {
    const { comp, access, refresh, notifyError } = setup({ updateError: true });
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.error');
    expect(refresh).not.toHaveBeenCalled();
    expect(access.visible()).toBe(true);
  });

  it('a 409 with a detail toasts that nothing was changed, with the backend reason', () => {
    const detail = 'Invalid status transition DRAFT -> EXPIRED for message a';
    const { comp, access, refresh, notifyError, applyStatusUpdates } = setup({
      updateFailure: new HttpErrorResponse({ status: 409, error: { status: 409, detail } }),
    });
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.refused', { detail });
    expect(notifyError).not.toHaveBeenCalledWith('messages.statusUpdate.toast.error');
    expect(applyStatusUpdates).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(access.visible()).toBe(true);
  });

  it('a 409 without refusals keeps the detail toast and marks no row', () => {
    const detail = 'Invalid status transition DRAFT -> EXPIRED for message a';
    const { comp, access, notifyError } = setup({
      updateFailure: new HttpErrorResponse({
        status: 409,
        error: { status: 409, detail, refusals: [] },
      }),
    });
    comp.open([titled('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.refused', { detail });
    expect(access.rowReasons().size).toBe(0);
  });

  it('a 500 with a detail toasts the old error key', () => {
    const { comp, access, notifyError } = setup({
      updateFailure: new HttpErrorResponse({ status: 500, error: { detail: 'boom' } }),
    });
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.error');
    expect(notifyError).toHaveBeenCalledTimes(1);
  });
});

describe('MessageStatusUpdateDialog — the publish gate (MSG10)', () => {
  const refusedWith = (refusals: unknown[]) =>
    new HttpErrorResponse({
      status: 409,
      error: { status: 409, detail: 'Refused by the publish gate', refusals },
    });

  it('marks a row with no title when its target is Verify, before Save', () => {
    const { comp, access } = setup();
    comp.open([
      mkMessage('a', 'DRAFT'),
      { id: 'b', status: 'DRAFT', descs: [{ lang: 'en', title: '   ' }] } as Message,
      titled('c', 'DRAFT'),
    ]);
    access.setTarget('a', 'VERIFIED');
    access.setTarget('b', 'VERIFIED');
    access.setTarget('c', 'VERIFIED');

    expect(access.rowReasons().get('a')).toEqual([{ code: 'NO_TITLE' }]);
    expect(access.rowReasons().get('b')).toEqual([{ code: 'NO_TITLE' }]);
    expect(access.rowReasons().has('c')).toBe(false);
  });

  it('marks no row whose target the gate does not check, nor a row with no target', () => {
    const { comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT'), mkMessage('b', 'VERIFIED'), mkMessage('c', 'DRAFT')]);
    access.setTarget('a', 'DELETED');
    access.setTarget('b', 'DRAFT');

    expect(access.rowReasons().size).toBe(0);
  });

  it('marks a Publish of a message whose publish-from date is still ahead, with that date', () => {
    const { comp, access } = setup();
    comp.open([
      titled('a', 'VERIFIED', { publishDateFrom: FAR_FUTURE }),
      titled('b', 'VERIFIED', { publishDateFrom: PAST }),
      titled('c', 'VERIFIED'),
    ]);
    access.setTarget('a', 'PUBLISHED');
    access.setTarget('b', 'PUBLISHED');
    access.setTarget('c', 'PUBLISHED');

    expect(access.rowReasons().get('a')).toEqual([{ code: 'SCHEDULED', date: FAR_FUTURE }]);
    expect(access.rowReasons().has('b')).toBe(false);
    expect(access.rowReasons().has('c')).toBe(false);
  });

  it('does not mark a Verify of a scheduled message: the scheduler publishes it on its date', () => {
    const { comp, access } = setup();
    comp.open([titled('a', 'DRAFT', { publishDateFrom: FAR_FUTURE })]);
    access.setTarget('a', 'VERIFIED');

    expect(access.rowReasons().has('a')).toBe(false);
  });

  it('gives both reasons for a Publish of a scheduled message with no title', () => {
    const { comp, access } = setup();
    comp.open([{ id: 'a', status: 'VERIFIED', publishDateFrom: FAR_FUTURE } as Message]);
    access.setTarget('a', 'PUBLISHED');

    expect(access.rowReasons().get('a')).toEqual([
      { code: 'NO_TITLE' },
      { code: 'SCHEDULED', date: FAR_FUTURE },
    ]);
  });

  it('marks the rows of Change all statuses as well', () => {
    const { comp, access } = setup();
    comp.open([mkMessage('a', 'DRAFT'), titled('b', 'DRAFT')]);
    access.applyBulk({ from: 'DRAFT', to: 'VERIFIED' });

    expect([...access.rowReasons().keys()]).toEqual(['a']);
  });

  it('a marked row is still sent: the backend decides', () => {
    const { comp, access, updateStatuses } = setup();
    comp.open([mkMessage('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(updateStatuses).toHaveBeenCalledWith([{ messageId: 'a', status: 'VERIFIED' }]);
  });

  it('after a 409 that names refused rows, marks them with the backend reasons, toasts the count and stays open', () => {
    const { comp, access, notifyError, refresh, applyStatusUpdates } = setup({
      updateFailure: refusedWith([
        {
          messageId: 'a',
          shortId: 'NW-001-26',
          reasons: [{ code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX', 'SafetyNET'] }],
        },
        {
          messageId: 'b',
          shortId: 'NW-002-26',
          reasons: [{ code: 'SCHEDULED', date: FAR_FUTURE }],
        },
      ]),
    });
    comp.open([titled('a', 'DRAFT'), titled('b', 'VERIFIED'), titled('c', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');
    access.setTarget('b', 'PUBLISHED');
    access.setTarget('c', 'VERIFIED');

    access.save();

    expect(access.rowReasons().get('a')).toEqual([
      { code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX', 'SafetyNET'] },
    ]);
    expect(access.rowReasons().get('b')).toEqual([{ code: 'SCHEDULED', date: FAR_FUTURE }]);
    expect(access.rowReasons().has('c')).toBe(false);
    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.rowsRefused', {
      count: 2,
    });
    expect(notifyError).toHaveBeenCalledTimes(1);
    expect(applyStatusUpdates).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(access.visible()).toBe(true);
    // The choices stay, so the editor can fix the messages or drop those rows and save again.
    expect(access.targets().get('c')).toBe('VERIFIED');
  });

  it('one refused row toasts the one form; a reason given as its bare code and a row named by its short id are read', () => {
    const { comp, access, notifyError } = setup({
      updateFailure: refusedWith([
        { messageId: '5', shortId: 'NW-007-26', reasons: ['NO_TITLE', 'SOMETHING_NEW'] },
      ]),
    });
    comp.open([titled('a', 'DRAFT', { shortId: 'NW-007-26' })]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(access.rowReasons().get('a')).toEqual([{ code: 'NO_TITLE' }]);
    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.rowsRefusedOne', {
      count: 1,
    });
  });

  it('a 409 whose refusals name no row of the dialog falls back to the detail toast', () => {
    const { comp, access, notifyError } = setup({
      updateFailure: refusedWith([{ messageId: 'elsewhere', reasons: ['NO_TITLE'] }]),
    });
    comp.open([titled('a', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');

    access.save();

    expect(notifyError).toHaveBeenCalledWith('messages.statusUpdate.toast.refused', {
      detail: 'Refused by the publish gate',
    });
    expect(access.rowReasons().size).toBe(0);
  });

  it("changing a refused row's target drops the backend's mark; open() drops them all", () => {
    const { comp, access } = setup({
      updateFailure: refusedWith([
        { messageId: 'a', reasons: [{ code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX'] }] },
        { messageId: 'b', reasons: [{ code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX'] }] },
      ]),
    });
    const messages = [titled('a', 'DRAFT'), titled('b', 'DRAFT')];
    comp.open(messages);
    access.setTarget('a', 'VERIFIED');
    access.setTarget('b', 'VERIFIED');
    access.save();
    expect(access.rowReasons().size).toBe(2);

    access.setTarget('a', null);
    expect(access.rowReasons().has('a')).toBe(false);
    expect(access.rowReasons().has('b')).toBe(true);

    comp.open(messages);
    expect(access.rowReasons().size).toBe(0);
  });

  it('after a gate 409, a row the backend let pass loses the mark its list data gave it', () => {
    // The list carries one language's description: a title in another language reads as none.
    const { comp, access } = setup({
      updateFailure: refusedWith([
        { messageId: 'a', reasons: [{ code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX'] }] },
      ]),
    });
    comp.open([titled('a', 'DRAFT'), mkMessage('x', 'DRAFT')]);
    access.setTarget('a', 'VERIFIED');
    access.setTarget('x', 'VERIFIED');
    expect(access.rowReasons().get('x')).toEqual([{ code: 'NO_TITLE' }]);

    access.save();

    expect([...access.rowReasons().keys()]).toEqual(['a']);
  });

  it('shows the reasons under the row title', async () => {
    const { fixture, comp, access } = setup({
      updateFailure: refusedWith([
        { messageId: 'a', reasons: [{ code: 'MANDATORY_PROMULGATION_OFF', types: ['NAVTEX'] }] },
        { messageId: 'b', reasons: [{ code: 'NO_TITLE' }] },
        { messageId: 'c', reasons: [{ code: 'SCHEDULED', date: FAR_FUTURE }] },
      ]),
    });
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslationKey('messages.statusUpdate.reason.noTitle', 'No title');
    transloco.setTranslationKey(
      'messages.statusUpdate.reason.mandatoryOff',
      'Mandatory channel off: {{types}}',
    );
    transloco.setTranslationKey(
      'messages.statusUpdate.reason.scheduled',
      'Scheduled for {{date}}; it cannot be published before then',
    );
    comp.open([
      titled('a', 'DRAFT'),
      mkMessage('b', 'DRAFT'),
      titled('c', 'VERIFIED', { publishDateFrom: FAR_FUTURE }),
    ]);
    access.setTarget('a', 'VERIFIED');
    access.setTarget('b', 'VERIFIED');
    access.setTarget('c', 'PUBLISHED');
    access.save();
    TestBed.tick();
    await fixture.whenStable();

    const doc = (fixture.nativeElement as HTMLElement).ownerDocument;
    const marks = [...doc.querySelectorAll('[data-testid="status-row-reasons"]')].map((mark) =>
      mark.textContent?.trim(),
    );
    expect(marks).toEqual([
      'Mandatory channel off: NAVTEX',
      'No title',
      `Scheduled for on ${FAR_FUTURE}; it cannot be published before then`,
    ]);
  });
});
