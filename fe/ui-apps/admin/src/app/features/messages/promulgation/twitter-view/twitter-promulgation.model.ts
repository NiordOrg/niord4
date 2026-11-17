import type { BasePromulgation } from '../../models/message.model';

export interface TwitterPromulgation extends BasePromulgation {
  readonly tweet?: string;
}
