import type { Message } from './message.model';

export interface MessageSearchResult {
  readonly data: readonly Message[];
  readonly total: number;
  readonly size?: number;
  readonly startIndex?: number;
  readonly description?: string;
  /**
   * `true` when a free-text search was answered while the backend's search index was still being
   * filled after a restart, so messages may be missing from `data`; absent on every other answer.
   */
  readonly indexIncomplete?: boolean;
}
