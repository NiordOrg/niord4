import type { MessageTagType } from './message-tag.model';

/** Scope glyphs shared by every tag surface (manager dialog, picker, chips). */
export const TAG_TYPE_ICONS: Record<MessageTagType, string> = {
  PRIVATE: 'lucideUser',
  DOMAIN: 'lucideUsers',
  PUBLIC: 'lucideGlobe',
};
