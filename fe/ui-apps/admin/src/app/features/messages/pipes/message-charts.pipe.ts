import { Pipe, type PipeTransform } from '@angular/core';

import type { MessageChart } from '../models/message.model';

@Pipe({ name: 'messageCharts', pure: true })
export class MessageChartsPipe implements PipeTransform {
  transform(charts: MessageChart[] | undefined): string {
    if (!charts || charts.length === 0) {
      return '';
    }
    return charts.map((chart) => this.formatChart(chart)).join(', ');
  }

  private formatChart(chart: MessageChart): string {
    if (chart.internationalNumber) {
      return `${chart.chartNumber} (INT ${chart.internationalNumber})`;
    }
    return chart.chartNumber;
  }
}
