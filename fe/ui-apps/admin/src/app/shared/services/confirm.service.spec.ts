import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ConfirmService } from './confirm.service';

function setup() {
  TestBed.configureTestingModule({ providers: [ConfirmService] });
  return TestBed.inject(ConfirmService);
}

describe('ConfirmService', () => {
  it('starts with no active confirm', () => {
    expect(setup().active()).toBeUndefined();
  });

  it('confirmDestructive publishes an active confirm with intent=destructive + passthrough', () => {
    const service = setup();
    const accept = vi.fn();

    service.confirmDestructive({ header: 'H', message: 'M', accept });

    const active = service.active();
    expect(active).toBeDefined();
    expect(active!.intent).toBe('destructive');
    expect(active!.header).toBe('H');
    expect(active!.message).toBe('M');
    expect(active!.accept).toBe(accept);
  });

  it('confirmNeutral publishes intent=neutral', () => {
    const service = setup();
    service.confirmNeutral({ message: 'M', accept: vi.fn() });
    expect(service.active()!.intent).toBe('neutral');
  });

  it('passes optional accept/reject labels through', () => {
    const service = setup();
    service.confirmDestructive({
      message: 'M',
      acceptLabel: 'Yes',
      rejectLabel: 'No',
      accept: vi.fn(),
    });
    const active = service.active()!;
    expect(active.acceptLabel).toBe('Yes');
    expect(active.rejectLabel).toBe('No');
  });

  it('passes optional details through, and leaves them absent when omitted', () => {
    const service = setup();
    service.confirmDestructive({ message: 'M', details: ['one', 'two'], accept: vi.fn() });
    expect(service.active()!.details).toEqual(['one', 'two']);

    service.confirmNeutral({ message: 'M', accept: vi.fn() });
    expect(service.active()!.details).toBeUndefined();
  });

  it('dismiss clears the active confirm', () => {
    const service = setup();
    service.confirmNeutral({ message: 'M', accept: vi.fn() });
    service.dismiss();
    expect(service.active()).toBeUndefined();
  });
});
