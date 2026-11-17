import type { Message } from './message.model';

export interface MessageSearchResult {
  readonly data: readonly Message[];
  readonly total: number;
  readonly size?: number;
  readonly startIndex?: number;
  readonly description?: string;
}
