/** The backend's `MessageIdMatch.MatchType`: what a message-id suggestion matched. */
export const MESSAGE_ID_MATCH_TYPES = ['UID', 'SHORT_ID', 'TEXT'] as const;

export type MessageIdMatchType = (typeof MESSAGE_ID_MATCH_TYPES)[number];

export const KNOWN_MATCH_TYPES: ReadonlySet<MessageIdMatchType> = new Set(MESSAGE_ID_MATCH_TYPES);

/** The label of each match type; a match on the message's uid reads as its database id. */
export const MATCH_TYPE_LABEL_KEYS: Readonly<Record<MessageIdMatchType, string>> = {
  UID: 'messages.matchType.ID',
  SHORT_ID: 'messages.matchType.SHORT_ID',
  TEXT: 'messages.matchType.TEXT',
};

export interface MessageIdSearchResult {
  readonly messageId: string;
  readonly type?: MessageIdMatchType | string;
  readonly status?: string;
  readonly title?: string;
}
