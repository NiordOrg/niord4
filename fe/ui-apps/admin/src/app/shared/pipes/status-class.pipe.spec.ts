import { describe, expect, it } from 'vitest';

import { MESSAGE_STATUSES } from '../models/message-status.model';
import { StatusClassPipe } from './status-class.pipe';

describe('StatusClassPipe', () => {
  it('maps each MessageStatus to its kebab-case state class', () => {
    const pipe = new StatusClassPipe();
    expect(pipe.transform('PUBLISHED')).toBe('is-published');
    expect(pipe.transform('DRAFT')).toBe('is-draft');
    expect(pipe.transform('VERIFIED')).toBe('is-verified');
    expect(pipe.transform('CANCELLED')).toBe('is-cancelled');
    expect(pipe.transform('EXPIRED')).toBe('is-expired');
    expect(pipe.transform('DELETED')).toBe('is-deleted');
  });

  it('covers every status defined in MESSAGE_STATUSES (drift guard)', () => {
    // Adding a new status to the union without a matching .is-* class in
    // components.css would silently render with unset --status-* vars. This
    // test fails loud if the union grows.
    const pipe = new StatusClassPipe();
    const expectedClasses = [
      'is-draft',
      'is-verified',
      'is-published',
      'is-cancelled',
      'is-expired',
      'is-deleted',
    ];
    const actual = MESSAGE_STATUSES.map((s) => pipe.transform(s));
    expect(actual.sort()).toEqual(expectedClasses.sort());
  });

  it('returns empty string for undefined or null status', () => {
    // The Layer 3 widget classes (.status-badge etc.) have CSS fallback values
    // for --status-bg/fg/bd, so an empty state class renders the widget in a
    // muted neutral treatment instead of unstyled-invisible. Letting the pipe
    // pass undefined/null cleanly avoids wrapper components needing their own
    // guards.
    const pipe = new StatusClassPipe();
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform(null)).toBe('');
  });
});
