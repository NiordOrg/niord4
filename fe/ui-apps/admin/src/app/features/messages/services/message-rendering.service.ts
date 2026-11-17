import { Injectable } from '@angular/core';

import { Message } from '../models/message.model';

@Injectable({ providedIn: 'root' })
export class MessageRenderingService {
  getIdSuffix(message: Message): string {
    if (message.mainType !== 'NM') {
      return '';
    }
    switch (message.type) {
      case 'TEMPORARY_NOTICE':
        return ' (T)';
      case 'PRELIMINARY_NOTICE':
        return ' (P)';
      default:
        return '';
    }
  }
}
