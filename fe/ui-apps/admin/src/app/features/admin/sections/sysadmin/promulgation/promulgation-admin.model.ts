import type { Domain } from '../../../../../core/domain/models/domain.model';
import type {
  MessageArea,
  MessageType,
  PromulgationRequirement,
  PromulgationType,
} from '../../../../messages/models/message.model';

/**
 * Sysadmin promulgation-type catalog models. Mirrors the backend VOs
 * (`PromulgationTypeVo`, `PromulgationServiceVo`, `NavtexTransmitterVo`,
 * `SafetyNetAreaVo` + its four Jackson `type`-discriminated subclasses,
 * `TwitterSettingsVo`) — the editor-side per-message promulgation models live in
 * the messages hub and stay untouched.
 */

/**
 * The requirement select options in display order. The union type itself
 * is owned by the messages hub (`PromulgationRequirement`); this array only
 * enumerates it for option lists and is compile-checked against it.
 */
export const PROMULGATION_REQUIREMENTS = [
  'OPTIONAL',
  'DEFAULT',
  'MANDATORY',
] as const satisfies readonly PromulgationRequirement[];

/**
 * A promulgation service registered in the backend's service registry — drives
 * the "Add Promulgation Type" dropdown (`serviceName` is backend display data,
 * not a translation key). Never hardcode the service list: a country's backend
 * may add a service, and a serviceId without a registered admin plugin
 * (`audio`, say) renders no plugin section at all.
 */
export interface PromulgationServiceRef {
  readonly serviceId: string;
  readonly serviceName: string;
}

/**
 * Full admin detail shape of a promulgation type. The list GET returns these
 * complete VOs — edit copies the list row without a detail refetch, so nothing
 * may narrow this model at the list boundary. `typeId` is a
 * user-entered free-text string PK, immutable after create, that keys every
 * nested plugin URL.
 */
export interface AdminPromulgationType extends Omit<PromulgationType, 'messageTypes'> {
  readonly active?: boolean;
  readonly priority: number;
  /**
   * Full catalog domain VOs. The stored copies may carry stale `messageSeries`;
   * consumers must re-resolve by `domainId` against the session domain catalog.
   */
  readonly domains?: readonly Domain[];
  /** Restricts which message sub-types offer this channel; absent/empty = no restriction. */
  readonly messageTypes?: readonly MessageType[];
  readonly scriptResourcePaths?: readonly string[];
}

/**
 * Parent reference every nested plugin body (transmitter / SafetyNET area /
 * Twitter settings) must carry — the backend 400s when it does not match the
 * `{typeId}` URL segment.
 */
export interface PromulgationTypeParentRef {
  readonly typeId: string;
}

/**
 * NAVTEX transmitter. `name` is a free-text PK within its promulgation type —
 * immutable in edit; the PUT/DELETE paths are name-keyed. `areas` arrive as
 * full area VOs with lang-resolved `descs`; save payloads only need
 * `{id}`-bearing entries.
 */
export interface NavtexTransmitter {
  readonly promulgationType: PromulgationTypeParentRef;
  readonly name: string;
  readonly active: boolean;
  readonly areas: readonly MessageArea[];
}

export const SAFETYNET_AREA_TYPES = ['CIRCULAR', 'RECTANGULAR', 'COASTAL', 'NAVAREA'] as const;
export type SafetyNetAreaType = (typeof SAFETYNET_AREA_TYPES)[number];

export const SAFETYNET_REPETITIONS = [
  'REPETITION_01',
  'REPETITION_11',
  'REPETITION_16',
  'REPETITION_42',
] as const;
export type SafetyNetRepetition = (typeof SAFETYNET_REPETITIONS)[number];

/** The 21 NAVAREA/METAREA identifiers (roman numerals I–XXI). */
export const SAFETYNET_NAVAREAS = [
  'I',
  'II',
  'III',
  'IV',
  'V',
  'VI',
  'VII',
  'VIII',
  'IX',
  'X',
  'XI',
  'XII',
  'XIII',
  'XIV',
  'XV',
  'XVI',
  'XVII',
  'XVIII',
  'XIX',
  'XX',
  'XXI',
] as const;
export type SafetyNetNavarea = (typeof SAFETYNET_NAVAREAS)[number];

/** The coastal-area letter codes (A–Z). */
export const SAFETYNET_COASTAL_AREAS = [
  'A',
  'B',
  'C',
  'D',
  'E',
  'F',
  'G',
  'H',
  'I',
  'J',
  'K',
  'L',
  'M',
  'N',
  'O',
  'P',
  'Q',
  'R',
  'S',
  'T',
  'U',
  'V',
  'W',
  'X',
  'Y',
  'Z',
] as const;
export type SafetyNetCoastalAreaCode = (typeof SAFETYNET_COASTAL_AREAS)[number];

/**
 * Fields common to every SafetyNET area shape. `name` is a free-text PK within
 * its promulgation type (the PUT/DELETE paths are name-keyed). A blank
 * `repetition` must be OMITTED from save payloads — never sent as `''`.
 */
interface SafetyNetAreaBase {
  readonly promulgationType: PromulgationTypeParentRef;
  readonly name: string;
  readonly active: boolean;
  readonly sortOrder: number;
  readonly repetition?: SafetyNetRepetition;
  readonly messageAreaPrefix?: string;
}

export interface SafetyNetCircularArea extends SafetyNetAreaBase {
  readonly type: Extract<SafetyNetAreaType, 'CIRCULAR'>;
  /** "Lat Lon" center coordinate pair. */
  readonly center?: string;
  /** Radius in nautical miles. */
  readonly radius?: string;
}

export interface SafetyNetRectangularArea extends SafetyNetAreaBase {
  readonly type: Extract<SafetyNetAreaType, 'RECTANGULAR'>;
  /** "Lat Lon" south-west corner coordinate pair. */
  readonly swCorner?: string;
  readonly northings?: string;
  readonly eastings?: string;
}

/** The COASTAL shape carries a NAVAREA too (the backend VO extends the NAVAREA shape). */
export interface SafetyNetCoastalArea extends SafetyNetAreaBase {
  readonly type: Extract<SafetyNetAreaType, 'COASTAL'>;
  readonly navarea?: SafetyNetNavarea;
  readonly coastalArea?: SafetyNetCoastalAreaCode;
}

export interface SafetyNetNavareaArea extends SafetyNetAreaBase {
  readonly type: Extract<SafetyNetAreaType, 'NAVAREA'>;
  readonly navarea?: SafetyNetNavarea;
}

/**
 * SafetyNET distribution area — discriminated on the Jackson `type` property
 * stamped by the backend (`CIRCULAR` / `RECTANGULAR` / `COASTAL` / `NAVAREA`).
 */
export type SafetyNetArea =
  | SafetyNetCircularArea
  | SafetyNetRectangularArea
  | SafetyNetCoastalArea
  | SafetyNetNavareaArea;

/**
 * Twitter OAuth settings — a singleton per promulgation type, keyed on the
 * parent `typeId` (create-on-demand: the GET returns a null body until the
 * first save). The four credentials round-trip in clear text from the API —
 * backend behavior, rendered into password inputs.
 */
export interface TwitterSettings {
  readonly promulgationType: PromulgationTypeParentRef;
  readonly format?: string;
  readonly includeThumbnail?: boolean;
  readonly apiKey?: string;
  readonly apiSecret?: string;
  readonly accessToken?: string;
  readonly accessTokenSecret?: string;
}
