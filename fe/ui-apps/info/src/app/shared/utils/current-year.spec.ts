import { currentYearInZone } from './current-year';

describe('currentYearInZone', () => {
  // One instant, two zones, two different years — the assertion a reader of the browser's own
  // clock cannot pass, whichever machine the suite runs on.
  it('names the year the zone is in, not the year the runner is in', () => {
    const instant = new Date('2023-12-31T12:00:00Z');
    expect(currentYearInZone('Pacific/Kiritimati', instant)).toBe(2024);
    expect(currentYearInZone('America/Halifax', instant)).toBe(2023);
    expect(currentYearInZone('Europe/Copenhagen', instant)).toBe(2023);
  });

  it('turns the year at the zone’s own midnight', () => {
    const instant = new Date('2023-12-31T23:30:00Z');
    expect(currentYearInZone('Europe/Copenhagen', instant)).toBe(2024);
    expect(currentYearInZone('America/Halifax', instant)).toBe(2023);
  });

  it('reads the clock when no instant is given', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2023-12-31T23:30:00Z'));
    expect(currentYearInZone('Europe/Copenhagen')).toBe(2024);
    vi.useRealTimers();
  });
});
