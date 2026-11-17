import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../core/notifications/notification.service';
import { SortAreaDialogService } from '../dialogs/sort-area-dialog/sort-area-dialog.service';
import type { GroupedMessageEntry } from './message-list.facade';
import { MessageListFacade } from './message-list.facade';
import { MessageAreaSortLauncher } from './message-area-sort.launcher';
import { TagsApiService } from './api/tags-api.service';

function entry(id: string, headingId?: number): GroupedMessageEntry {
  return {
    message: { id } as GroupedMessageEntry['message'],
    areaHeading: headingId === undefined ? undefined : ({ id: headingId } as never),
  };
}

describe('MessageAreaSortLauncher', () => {
  const createTempMessageTag = vi.fn(() => of({ tagId: 'temp-1' }));
  const openWithTag = vi.fn(() => of(undefined));
  const openInteractive = vi.fn(() => of(undefined));
  const refresh = vi.fn();
  let grouped: readonly GroupedMessageEntry[] = [];

  function build(): MessageAreaSortLauncher {
    TestBed.configureTestingModule({
      providers: [
        MessageAreaSortLauncher,
        { provide: TagsApiService, useValue: { createTempMessageTag } },
        { provide: SortAreaDialogService, useValue: { openWithTag, openInteractive } },
        { provide: MessageListFacade, useValue: { groupedMessages: () => grouped, refresh } },
        { provide: NotificationService, useValue: { error: vi.fn(), success: vi.fn() } },
      ],
    });
    return TestBed.inject(MessageAreaSortLauncher);
  }

  beforeEach(() => vi.clearAllMocks());

  it('collects the contiguous runs under every heading matching the area id (ttl 30)', () => {
    grouped = [entry('a', 1), entry('b'), entry('c', 2), entry('d'), entry('e', 1), entry('f')];
    build().sortAreaHeading({ id: 1 } as never);

    expect(createTempMessageTag).toHaveBeenCalledWith(['a', 'b', 'e', 'f'], 30);
    expect(openWithTag).toHaveBeenCalledWith('temp-1');
  });

  it('refreshes the list once the dialog closes', () => {
    grouped = [entry('a', 1)];
    build().sortAreaHeading({ id: 1 } as never);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('does nothing when no message falls under the heading', () => {
    grouped = [entry('a', 2), entry('b')];
    build().sortAreaHeading({ id: 1 } as never);
    expect(createTempMessageTag).not.toHaveBeenCalled();
    expect(openWithTag).not.toHaveBeenCalled();
  });

  it('sortInteractive opens the dialog with the seed status and refreshes on close', () => {
    grouped = [];
    build().sortInteractive('DRAFT');
    expect(openInteractive).toHaveBeenCalledWith('DRAFT');
    expect(refresh).toHaveBeenCalledTimes(1);
  });
});
