import { Provider, signal } from '@angular/core';

import type { Domain } from '../../core/domain/models/domain.model';
import { DomainService } from '../../core/domain/services/domain.service';

/**
 * A session domain carrying nothing but a time zone — all the date/time controls read of
 * it. Pass no zone to exercise the "no domain yet" state, in which those controls disable
 * themselves rather than standing in the runner's own zone.
 *
 * Pick a zone that differs from the runner's, and assert exact ISO strings, epoch values
 * or {@link wallClockIn} readings against it. A spec that reads `getHours()` back off a
 * result proves only that the runner and the code agree, which they always do.
 */
export function provideTestSessionZone(timeZone?: string): Provider {
  const sessionDomain = signal<Domain | undefined>(
    timeZone ? ({ domainId: 'test-domain', timeZone } as Domain) : undefined,
  );
  return { provide: DomainService, useValue: { sessionDomain } };
}

/**
 * The wall clock `instant` reads as in `timeZone`, as `YYYY-MM-DD HH:mm:ss`.
 *
 * Read straight from `Intl`, so an assertion against it states the user-visible fact
 * ("the stored instant is 12:00 in Tokyo") without committing the spec to an offset —
 * which matters for the zones whose rules have moved in recent tzdb releases. Where the
 * offset is genuinely fixed by history, assert the ISO string instead; it is stronger.
 */
export function wallClockIn(timeZone: string, instant: Date): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const part = (type: string): string => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}`;
}
