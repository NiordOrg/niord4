export const MESSAGE_STATUSES = [
  'DRAFT',
  'VERIFIED',
  'PUBLISHED',
  'CANCELLED',
  'EXPIRED',
  'DELETED',
] as const;

export type MessageStatus = (typeof MESSAGE_STATUSES)[number];

export const KNOWN_MESSAGE_STATUSES: ReadonlySet<MessageStatus> = new Set(MESSAGE_STATUSES);
