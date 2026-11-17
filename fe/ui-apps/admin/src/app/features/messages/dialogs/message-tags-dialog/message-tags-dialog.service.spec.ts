import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { MessageTagsDialogService } from './message-tags-dialog.service';
import type { MessageTag } from '../../../../shared/models/message-tag.model';

function mkTag(): MessageTag {
  return { tagId: 't1', name: 'Tag 1', type: 'PRIVATE', messageCount: 0, locked: false };
}

function setup() {
  TestBed.configureTestingModule({ providers: [] });
  return TestBed.inject(MessageTagsDialogService);
}

describe('MessageTagsDialogService', () => {
  it('open() flips visible to true and sets includeLocked', () => {
    const s = setup();
    expect(s.visible()).toBe(false);
    s.open({ includeLocked: true }).subscribe();
    expect(s.visible()).toBe(true);
    expect(s.includeLocked()).toBe(true);
  });

  it('open() defaults includeLocked to false', () => {
    const s = setup();
    s.open().subscribe();
    expect(s.includeLocked()).toBe(false);
  });

  it('submit emits the tag exactly once and hides the dialog', () => {
    const s = setup();
    const tag = mkTag();
    const next = vi.fn();
    s.open().subscribe(next);
    s.submit(tag);
    expect(next).toHaveBeenCalledTimes(1);
    expect(next).toHaveBeenCalledWith(tag);
    expect(s.visible()).toBe(false);
  });

  it('dismiss() emits undefined and hides the dialog', () => {
    const s = setup();
    const next = vi.fn();
    s.open().subscribe(next);
    s.dismiss();
    expect(next).toHaveBeenCalledWith(undefined);
    expect(s.visible()).toBe(false);
  });

  it('dismiss() is a no-op when the dialog is not visible', () => {
    const s = setup();
    const next = vi.fn();
    s.open().subscribe(next);
    s.dismiss();
    next.mockClear();
    s.dismiss();
    expect(next).not.toHaveBeenCalled();
  });

  it('two consecutive opens emit only into the second subscriber', () => {
    const s = setup();
    const firstNext = vi.fn();
    s.open().subscribe(firstNext);
    s.dismiss();
    const secondNext = vi.fn();
    s.open().subscribe(secondNext);
    s.submit(mkTag());
    expect(firstNext).toHaveBeenCalledTimes(1); // received undefined on dismiss
    expect(firstNext).toHaveBeenCalledWith(undefined);
    expect(secondNext).toHaveBeenCalledTimes(1);
    expect(secondNext).toHaveBeenCalledWith(mkTag());
  });
});
