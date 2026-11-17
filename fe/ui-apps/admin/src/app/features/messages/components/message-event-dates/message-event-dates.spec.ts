import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { DomainService } from '../../../../core/domain/services/domain.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../models/message.model';
import { MessageEventDates } from './message-event-dates';

function setup(inputs: Record<string, unknown>) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      // No session domain -> LocaleFormatService formats in the default timezone.
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(MessageEventDates);
  for (const [key, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(key, value);
  }
  fixture.detectChanges();
  return fixture;
}

const outerSpan = (fixture: { nativeElement: HTMLElement }): HTMLElement | null =>
  fixture.nativeElement.querySelector('span.whitespace-nowrap');
const moreIndicator = (fixture: { nativeElement: HTMLElement }): HTMLElement | null =>
  fixture.nativeElement.querySelector('span[title]');

const PAST = '2000-01-01T00:00:00Z';
const FUTURE = '2999-01-01T00:00:00Z';

describe('MessageEventDates — the date text carries no status colour', () => {
  // A passed follow-up date is flagged on the ROW (`.row-attention` rail + title, applied by
  // RowAttentionDirective), never by recolouring the date. These cases lock that split so the
  // two cues cannot both creep back in and fight each other.
  it('leaves an overdue follow-up date in ordinary body colour', () => {
    const fixture = setup({
      message: { id: 'm', followUpDate: PAST } as Message,
      type: 'FOLLOW_UP_DATE',
    });
    expect(outerSpan(fixture)?.className).not.toMatch(/text-(overdue|danger)/);
  });

  it('leaves a future follow-up date in ordinary body colour too', () => {
    const fixture = setup({
      message: { id: 'm', followUpDate: FUTURE } as Message,
      type: 'FOLLOW_UP_DATE',
    });
    expect(outerSpan(fixture)?.className).not.toMatch(/text-(overdue|danger)/);
  });
});

describe('MessageEventDates — first-line "…" more indicator', () => {
  const twoIntervalMessage = () =>
    ({
      id: 'm',
      parts: [
        {
          eventDates: [
            { fromDate: '2026-01-15T00:00:00Z', allDay: true },
            { fromDate: '2026-02-20T00:00:00Z', allDay: true },
          ],
        },
      ],
    }) as Message;

  it('shows the "…" indicator (listing every interval, newline-joined) with >1 interval on the first line', () => {
    const fixture = setup({ message: twoIntervalMessage(), type: 'DATE', firstLine: true });

    const title = fixture.componentInstance.moreTitle();
    expect(title).not.toBe('');
    expect(title.split('\n')).toHaveLength(2);

    const indicator = moreIndicator(fixture);
    expect(indicator).not.toBeNull();
    expect(indicator?.textContent).toContain('…'); // the … glyph
    expect(indicator?.getAttribute('title')).toBe(title);
  });

  it('shows no indicator when there is only a single interval', () => {
    const fixture = setup({
      message: {
        id: 'm',
        parts: [{ eventDates: [{ fromDate: '2026-01-15T00:00:00Z', allDay: true }] }],
      } as Message,
      type: 'DATE',
      firstLine: true,
    });
    expect(fixture.componentInstance.moreTitle()).toBe('');
    expect(moreIndicator(fixture)).toBeNull();
  });

  it('shows no indicator when not the first line, even with >1 interval', () => {
    const fixture = setup({ message: twoIntervalMessage(), type: 'DATE', firstLine: false });
    expect(fixture.componentInstance.moreTitle()).toBe('');
    expect(moreIndicator(fixture)).toBeNull();
  });

  it('shows no indicator for a non-DATE column', () => {
    const fixture = setup({
      message: twoIntervalMessage(),
      type: 'FOLLOW_UP_DATE',
      firstLine: true,
    });
    expect(fixture.componentInstance.moreTitle()).toBe('');
    expect(moreIndicator(fixture)).toBeNull();
  });
});
