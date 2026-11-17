import type { BasePromulgation } from '../../models/message.model';

export type NavtexPriority = 'NONE' | 'ROUTINE' | 'IMPORTANT' | 'VITAL';

export interface NavtexPromulgation extends BasePromulgation {
  readonly priority?: NavtexPriority;
  readonly transmitters?: Readonly<Record<string, boolean>>;
  readonly preamble?: string;
  readonly text?: string;
}
