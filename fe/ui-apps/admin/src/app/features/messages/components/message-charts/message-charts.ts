import { Component, input } from '@angular/core';

import { MessageChart } from '../../models/message.model';
import { MessageChartsPipe } from '../../pipes/message-charts.pipe';

@Component({
  selector: 'app-message-charts',
  template: `<span>{{ charts() | messageCharts }}</span>`,
  imports: [MessageChartsPipe],
})
export class MessageCharts {
  readonly charts = input<MessageChart[]>();
}
