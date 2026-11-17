import { describe, expect, it } from 'vitest';

import { MESSAGE_STATUSES } from '../../../models/message.model';
import { STATUS_DISPLAY_ORDER } from './status-filter-panel';

describe('STATUS_DISPLAY_ORDER', () => {
  it('covers exactly the same set as MESSAGE_STATUSES', () => {
    // Set-equality assertion — a missing or duplicated entry would silently
    // drop a status from the filter UI.
    expect([...STATUS_DISPLAY_ORDER].sort()).toEqual([...MESSAGE_STATUSES].sort());
  });
});
