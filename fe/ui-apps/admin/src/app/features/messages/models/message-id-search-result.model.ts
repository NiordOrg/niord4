export const MESSAGE_ID_MATCH_TYPES = ['ID', 'SHORT_ID', 'TEXT'] as const;

export type MessageIdMatchType = (typeof MESSAGE_ID_MATCH_TYPES)[number];

export const KNOWN_MATCH_TYPES: ReadonlySet<MessageIdMatchType> = new Set(MESSAGE_ID_MATCH_TYPES);

export interface MessageIdSearchResult {
  readonly messageId: string;
  readonly type?: MessageIdMatchType | string;
  readonly status?: string;
  readonly title?: string;
}
