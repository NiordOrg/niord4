import { MessageChartsPipe } from './message-charts.pipe';

describe('MessageChartsPipe', () => {
  let pipe: MessageChartsPipe;

  beforeEach(() => {
    pipe = new MessageChartsPipe();
  });

  it('should return empty for undefined', () => {
    expect(pipe.transform(undefined)).toBe('');
  });

  it('should return empty for empty array', () => {
    expect(pipe.transform([])).toBe('');
  });

  it('should format a single chart', () => {
    expect(pipe.transform([{ chartNumber: '102' }])).toBe('102');
  });

  it('should format a chart with international number', () => {
    expect(pipe.transform([{ chartNumber: '102', internationalNumber: 1302 }])).toBe(
      '102 (INT 1302)',
    );
  });

  it('should format multiple charts', () => {
    const result = pipe.transform([
      { chartNumber: '100' },
      { chartNumber: '200', internationalNumber: 2000 },
    ]);
    expect(result).toBe('100, 200 (INT 2000)');
  });
});
