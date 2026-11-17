import { describe, expect, it } from 'vitest';

import type { MessageChart } from '../../../../messages/models/message.model';
import type { ChartRef } from '../../../../messages/models/messages-filter-params.model';
import { sortChartsByScale, toMessageChart } from './charts-field-row';

/** Build a chart with an optional `scale` (a wire field the shared `MessageChart` doesn't declare). */
function chart(chartNumber: string, scale?: number): MessageChart {
  return { chartNumber, ...(scale === undefined ? {} : { scale }) } as MessageChart;
}

describe('sortChartsByScale', () => {
  it('orders charts ascending by scale (the most-detailed / smallest-scale first)', () => {
    const sorted = sortChartsByScale([chart('A', 300000), chart('B', 50000), chart('C', 100000)]);
    expect(sorted.map((c) => c.chartNumber)).toEqual(['B', 'C', 'A']);
  });

  it('sorts a scale-less chart last (default 10,000,000)', () => {
    const sorted = sortChartsByScale([chart('none'), chart('small', 10000), chart('big', 500000)]);
    expect(sorted.map((c) => c.chartNumber)).toEqual(['small', 'big', 'none']);
  });

  it('does not mutate the input array (returns a fresh sorted copy)', () => {
    const input = [chart('A', 300000), chart('B', 50000)];
    const sorted = sortChartsByScale(input);
    expect(input.map((c) => c.chartNumber)).toEqual(['A', 'B']);
    expect(sorted).not.toBe(input);
  });
});

describe('toMessageChart', () => {
  it('drops the search-only fields but keeps chartNumber / internationalNumber', () => {
    const ref: ChartRef = {
      chartNumber: '101',
      internationalNumber: 4567,
      horizontalDatum: 'WGS-84',
      name: 'Kattegat',
      fullChartNumber: '101 (INT 4567)',
    };

    expect(toMessageChart(ref)).toEqual({ chartNumber: '101', internationalNumber: 4567 });
  });

  it('preserves the wire scale/active so picker-selected charts stay sortable by scale', () => {
    const ref = {
      chartNumber: '9',
      internationalNumber: 3,
      name: 'Skagerrak',
      active: false,
      scale: 100000,
    } as ChartRef;

    expect(toMessageChart(ref)).toEqual({
      chartNumber: '9',
      internationalNumber: 3,
      active: false,
      scale: 100000,
    });
  });
});
