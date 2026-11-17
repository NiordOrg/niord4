export type MessageTagType = 'PRIVATE' | 'DOMAIN' | 'PUBLIC';

export interface MessageTag {
  readonly tagId: string;
  readonly name: string;
  readonly type: MessageTagType;
  readonly created?: string;
  readonly expiryDate?: string;
  readonly messageCount: number;
  readonly locked: boolean;
  /**
   * Marks a stub built from a URL-only `tagId` (e.g. shared/bookmarked filter
   * URLs). All non-id fields are placeholders and MUST be re-fetched via
   * `TagsApiService.getTagsByIds()` before being shown or relied on for logic.
   * Consumers that depend on `type`/`messageCount`/`locked` should check this
   * flag and trigger hydration.
   */
  readonly _placeholder?: true;
}
