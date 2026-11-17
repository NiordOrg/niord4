import type { Domain } from '../../../../../core/domain/models/domain.model';
import { MAIN_TYPES, type MainType } from '../../../../../shared/models/main-type.model';
import {
  MESSAGE_TYPES_BY_MAIN_TYPE,
  type MessageType,
  type PromulgationRequirement,
} from '../../../../messages/models/message.model';
import type { AdminPromulgationType } from './promulgation-admin.model';

/**
 * Pure derivation + serialization rules for the promulgation-type editor's
 * "Restrict Types" checkbox groups. The contract:
 *
 * - a main type is SUPPORTED when the type has no domains selected, or when
 *   any selected domain carries a message series of that main type;
 * - a group's checkboxes render only while its main type is supported;
 * - the save payload rebuilds `messageTypes` from the currently CHECKED keys
 *   of the currently SUPPORTED groups only — NW block first, then NM — so
 *   losing domain support silently prunes previously saved restrictions.
 */

/** The restrict-type candidates per main type — the shared ordered map (= payload order). */
export const RESTRICT_TYPE_GROUPS = MESSAGE_TYPES_BY_MAIN_TYPE;

/** Working checkbox state over all 8 message sub-types. */
export type RestrictTypeChecks = Readonly<Record<MessageType, boolean>>;

/**
 * Seed the working checks from the stored restriction set — raw membership,
 * deliberately NOT gated by domain support: a check whose group is hidden
 * stays latent in the working set, so regaining support in-session restores
 * it (the pruning happens at save time only).
 */
export function seedRestrictChecks(
  messageTypes: readonly MessageType[] | undefined,
): RestrictTypeChecks {
  const entries = MAIN_TYPES.flatMap((mainType) => RESTRICT_TYPE_GROUPS[mainType]).map(
    (type) => [type, messageTypes?.includes(type) ?? false] as const,
  );
  return Object.fromEntries(entries) as Record<MessageType, boolean>;
}

/**
 * Re-resolve stored domain VOs against the session domain catalog by
 * `domainId` so their `messageSeries` are FRESH — the copies embedded in a
 * stored type may be stale. An id missing from the catalog keeps the stored
 * VO, so the pick still displays and round-trips on save.
 */
export function resolveFreshDomains(
  stored: readonly Domain[] | undefined,
  catalog: readonly Domain[],
): readonly Domain[] {
  return (stored ?? []).map(
    (domain) => catalog.find((entry) => entry.domainId === domain.domainId) ?? domain,
  );
}

/**
 * Support rule: with NO domains selected every main type is supported;
 * otherwise a main type is supported when ANY selected domain carries a
 * message series of that main type.
 */
export function supportsMainType(domains: readonly Domain[], mainType: MainType): boolean {
  if (domains.length === 0) {
    return true;
  }
  return domains.some(
    (domain) => domain.messageSeries?.some((series) => series.mainType === mainType) ?? false,
  );
}

/**
 * The save-payload restriction set: checked keys of supported groups only,
 * NW block first then NM (serialization order). Checks whose main type
 * lost domain support are silently dropped; nothing checked ⇒ `[]`
 * (unrestricted — sent as-is, never omitted).
 */
export function serializeMessageTypes(
  checks: RestrictTypeChecks,
  domains: readonly Domain[],
): MessageType[] {
  return MAIN_TYPES.flatMap((mainType) =>
    supportsMainType(domains, mainType)
      ? RESTRICT_TYPE_GROUPS[mainType].filter((type) => checks[type])
      : [],
  );
}

/** The editor's flat working values (the restrict-type checks live beside, not inside, the form). */
export interface PromulgationTypeFormValue {
  readonly typeId: string;
  readonly name: string;
  readonly active: boolean;
  readonly requirement: PromulgationRequirement;
  readonly priority: number;
  /** `''` = any language — omitted from the payload. */
  readonly language: string;
  readonly domains: readonly Domain[];
  readonly scriptResourcePaths: readonly string[];
}

/**
 * Normalizes the editor's working state into the save payload. The opened
 * entity is spread first so backend-side fields the form never touches
 * survive an edit round-trip; `messageTypes` is rebuilt from the checked keys
 * (pruned by current domain support); `domains` travel as the picked catalog
 * VOs (the backend re-resolves by `domainId`); a blank language means "any"
 * and is omitted; blank picker rows are dropped.
 */
export function buildPromulgationTypePayload(
  opened: AdminPromulgationType,
  value: PromulgationTypeFormValue,
  checks: RestrictTypeChecks,
): AdminPromulgationType {
  return {
    ...opened,
    typeId: value.typeId,
    name: value.name,
    active: value.active,
    requirement: value.requirement,
    priority: value.priority,
    language: value.language || undefined,
    domains: [...value.domains],
    messageTypes: serializeMessageTypes(checks, value.domains),
    scriptResourcePaths: value.scriptResourcePaths.filter((path) => path.trim().length > 0),
  };
}
