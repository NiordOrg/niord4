export const MESSAGE_DATE_COLUMN_TYPES = ['DATE', 'PUBLISH_DATE', 'FOLLOW_UP_DATE'] as const;
export type MessageDateColumnType = (typeof MESSAGE_DATE_COLUMN_TYPES)[number];

export const KNOWN_MESSAGE_DATE_COLUMN_TYPES: ReadonlySet<MessageDateColumnType> = new Set(
  MESSAGE_DATE_COLUMN_TYPES,
);

export function isMessageDateColumnType(value: unknown): value is MessageDateColumnType {
  return (
    typeof value === 'string' && KNOWN_MESSAGE_DATE_COLUMN_TYPES.has(value as MessageDateColumnType)
  );
}
