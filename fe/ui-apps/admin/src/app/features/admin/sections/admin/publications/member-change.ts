import type {
  IssueMember,
  LiveMessageState,
  MemberDriftField,
} from '../../../services/api/publication-series-api.service';
import { memberStatusKey } from './publication-status';

/**
 * What has changed under a member of a published issue, said in one line each.
 *
 * A published issue lists what its document listed, and the rows are never
 * rewritten from the live message. But the messages go on living, and the
 * status a reader sees beside a row is the one the message has NOW — so a row
 * whose message was cancelled last week reads "cancelled" in a list of things
 * that went out as published, and the one question that raises is whether the
 * message is still in the issue. It is. It was published when the issue was
 * decided at its cut-off, and what changed happened afterwards; these notes
 * exist to say so beside the status, in the reader's language.
 *
 * A note renders as an ICON whose tooltip is the sentence. On an issue of any
 * age, most rows have moved on — notices expire — so a written line on each of
 * them would be the norm rather than the exception, and a table full of
 * warnings warns about nothing. The icon says "something changed, hover for
 * what"; the sentence is there for the reader who asks.
 *
 * Pure on purpose: no injector, no HTTP, so the vocabulary can be walked against
 * the bundles by a spec instead of being spelled out in a template.
 */

export const CHANGE_KEY_PREFIX = 'admin.publications.change.';

/** Every field the server compares, in the order the notes read best. */
export const MEMBER_CHANGE_FIELDS: readonly MemberDriftField[] = ['exists', 'status', 'type'];

/**
 * Every sentence this module can choose, so a spec can walk them.
 *
 * More entries than there are fields: the two fields that quote a live value
 * need a wording for the case where the message no longer carries one, a
 * status change that took the message off the public site is a different
 * sentence from one that did not, and `why` is the explanation every note
 * carries as its hover text.
 */
export const MEMBER_CHANGE_KEYS: readonly string[] = [
  'gone',
  'status',
  'statusNotPublic',
  'statusChanged',
  'type',
  'typeChanged',
  'why',
].map((suffix) => CHANGE_KEY_PREFIX + suffix);

/** The explanation every note is hovered to: the message stays in the issue, and why. */
export const MEMBER_CHANGE_WHY_KEY = CHANGE_KEY_PREFIX + 'why';

/** One thing that changed, ready to render: an icon and the sentence behind it. */
export interface MemberChangeNote {
  readonly field: MemberDriftField;
  readonly icon: string;
  /** The sentence. Takes a `value` parameter exactly when `valueKey` is set. */
  readonly key: string;
  /** The key naming the live value the sentence quotes, where it quotes one. */
  readonly valueKey?: string;
}

const ICONS: Readonly<Record<MemberDriftField, string>> = {
  exists: 'lucideCircleX',
  status: 'lucideTriangleAlert',
  type: 'lucideTag',
};

/**
 * The notes a member row carries, or none at all.
 *
 * Ordered by this module rather than by the server's array, so two rows that
 * changed in the same fields put their notes in the same order.
 *
 * A field the server names but this build has no sentence for is dropped rather
 * than rendered as a key path: an unexplained line under a status says less
 * than no line at all, and the badge above it is unchanged either way.
 */
export function memberChangeNotes(
  member: Pick<IssueMember, 'drift' | 'current'> | undefined,
): readonly MemberChangeNote[] {
  const changed = new Set(member?.drift ?? []);
  if (changed.size === 0) {
    return [];
  }
  const current = member?.current;
  return MEMBER_CHANGE_FIELDS.filter((field) => changed.has(field)).map((field) => {
    const valueKey = valueKeyFor(field, current);
    return { field, icon: ICONS[field], key: keyFor(field, current, valueKey), valueKey };
  });
}

/**
 * Which sentence to use.
 *
 * A status change is worth two of them: "expired" and "cancelled, and so no
 * longer readable by the public" are both changes after the release, and only
 * the second means a reader following the document will find nothing.
 */
function keyFor(
  field: MemberDriftField,
  current: LiveMessageState | undefined,
  valueKey: string | undefined,
): string {
  if (field === 'exists') {
    return `${CHANGE_KEY_PREFIX}gone`;
  }
  if (field === 'status') {
    if (!valueKey) {
      return `${CHANGE_KEY_PREFIX}statusChanged`;
    }
    return current?.publiclyVisible === false
      ? `${CHANGE_KEY_PREFIX}statusNotPublic`
      : `${CHANGE_KEY_PREFIX}status`;
  }
  return `${CHANGE_KEY_PREFIX}${valueKey ? 'type' : 'typeChanged'}`;
}

/**
 * The key naming the value a sentence quotes, or `undefined` where it quotes none.
 *
 * A live message that carries no code for the field produces no key at all
 * rather than one built from an empty string: "changed to" followed by nothing
 * reads as a rendering fault, and the sentence without the value still carries
 * the finding.
 */
function valueKeyFor(
  field: MemberDriftField,
  current: LiveMessageState | undefined,
): string | undefined {
  if (field === 'status' && current?.status) {
    return memberStatusKey(current.status);
  }
  if (field === 'type' && current?.type) {
    return `msg.type.${current.type}`;
  }
  return undefined;
}
