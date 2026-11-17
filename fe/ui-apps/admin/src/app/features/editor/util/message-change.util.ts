import type {
  BasePromulgation,
  Message,
  MessageDescription,
  MessageGeometryFeatureCollection,
  MessagePart,
} from '../../messages/models/message.model';

/**
 * Deep structural canonicalisation for change detection. Collapses every "absent-equivalent" value —
 * `''`, `null`, `false`, empty array, empty object — to `undefined` (i.e. dropped), and sorts object
 * keys so representation-only differences never read as a change. Array ORDER is preserved (it is
 * meaningful for parts / references), but empty items are dropped.
 *
 * This is the single normaliser the editor's change detection runs BOTH sides through, so the many
 * non-user rewrites of the working copy — the form sync's `mergeDescs` padding a blank language,
 * `''` vs `undefined`, an explicit `false` vs an absent boolean, `indexNo` renumbering — cannot light
 * a field's edited-dot or the unsaved-changes state. Only a genuine value difference survives.
 */
export function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) {
    const items = value.map(canonicalize).filter((item) => item !== undefined);
    return items.length > 0 ? items : undefined;
  }
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      const canonical = canonicalize((value as Record<string, unknown>)[key]);
      if (canonical !== undefined) {
        out[key] = canonical;
      }
    }
    return Object.keys(out).length > 0 ? out : undefined;
  }
  return value === '' || value === null || value === false ? undefined : value;
}

/** A stable, canonicalised serialisation — the comparison key both change-detection paths use. */
export function canonicalKey(value: unknown): string {
  return JSON.stringify(canonicalize(value) ?? null);
}

/**
 * Normalise a date to its instant (epoch ms) for comparison. Two representations of the SAME
 * moment must compare equal, because the editor mixes them: the backend serialises dates as epoch
 * NUMBERS (`1777620900000`), while the form round-trips them through `Date` and writes back an ISO
 * STRING (`'2026-05-01T07:35:00.000Z'`). So after any re-sync (e.g. a type change) the working copy
 * holds a string where the loaded baseline still holds the number — same instant, different type.
 * Reducing both to epoch ms makes them equal, so a date-less edit never dots the Dates field and a
 * reverted date clears cleanly. Returns `undefined` for a missing/invalid date.
 */
export function normalizeDate(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isNaN(value) ? undefined : value;
  }
  if (typeof value === 'string' || value instanceof Date) {
    const time = new Date(value).getTime();
    return Number.isNaN(time) ? undefined : time;
  }
  return undefined;
}

/** Normalise a part's event-date intervals to instants (order preserved). */
function eventDatesContent(
  intervals: readonly { allDay?: boolean; fromDate?: string; toDate?: string }[] | undefined,
): unknown {
  return (intervals ?? []).map((interval) => ({
    allDay: interval.allDay,
    from: normalizeDate(interval.fromDate),
    to: normalizeDate(interval.toDate),
  }));
}

// The desc text fields that count as editable content (per language).
const DESC_CONTENT_KEYS = [
  'title',
  'source',
  'vicinity',
  'publication',
  'internalPublication',
  'subject',
  'details',
] as const;

/**
 * Per-language projection of a `descs` array keyed by language, carrying only the editable text
 * fields. Keyed (not positional) so language order and blank-language padding are irrelevant; empty
 * fields drop out under {@link canonicalize}, so a language with no content vanishes entirely.
 */
function descsContent(descs: readonly MessageDescription[] | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const desc of descs ?? []) {
    if (!desc.lang) {
      continue;
    }
    const entry: Record<string, unknown> = {};
    for (const key of DESC_CONTENT_KEYS) {
      entry[key] = (desc as Record<string, unknown>)[key];
    }
    out[desc.lang] = entry;
  }
  return out;
}

/**
 * Drop the DERIVED, order-dependent `startCoordIndex` stamp from every geometry feature. It is a
 * message-wide running coordinate counter re-threaded on EVERY part commit (`reindexStartCoordIndices`),
 * so reordering parts changes it on features that did not otherwise change — it is not content and
 * must never count towards a part's identity or the unsaved state.
 */
export function stripCoordIndex(
  geometry: MessageGeometryFeatureCollection | undefined,
): MessageGeometryFeatureCollection | undefined {
  if (!geometry || !geometry.features?.length) {
    return geometry;
  }
  return {
    ...geometry,
    features: geometry.features.map((feature) => {
      if (!feature.properties || !('startCoordIndex' in feature.properties)) {
        return feature;
      }
      const properties = { ...(feature.properties as Record<string, unknown>) };
      delete properties['startCoordIndex'];
      return { ...feature, properties };
    }),
  };
}

/**
 * Change-detection projection of the promulgation channels. Keeps ONLY the channels the message
 * actually promulgates (`promulgate === true`), keyed by `typeId` so order and channel-set churn are
 * irrelevant, and drops two non-content things:
 *  - the server `id` — a channel removed on a Type change and re-added on revert comes back with a
 *    null id (`NavtexPromulgationService` recreates it), which is not a user edit; and
 *  - the stable `type` metadata object (its `typeId` is already the key).
 *
 * The effect: a channel that is NOT chosen (the common case in the reported bug) is absent from the
 * projection in EVERY state, so the backend showing/hiding/re-id-ing it as the message Type changes
 * — and reverting the Type — never counts as a change. Only a promulgating channel's content (or
 * turning a channel on/off) moves the projection.
 */
export function promulgationsContent(
  promulgations: readonly BasePromulgation[] | undefined,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of promulgations ?? []) {
    if (!p.promulgate) {
      continue;
    }
    const content: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(p as unknown as Record<string, unknown>)) {
      if (key === 'id' || key === 'type') {
        continue;
      }
      content[key] = value;
    }
    out[p.type.typeId] = content;
  }
  return out;
}

/** A message part's editable CONTENT — strips `indexNo` (order) and the derived `startCoordIndex`. */
export function partContent(part: MessagePart): unknown {
  return {
    type: part.type,
    eventDates: eventDatesContent(part.eventDates),
    showTime: part.showTime,
    showCoordinates: part.showCoordinates,
    hideSubject: part.hideSubject,
    descs: descsContent(part.descs),
    geometry: stripCoordIndex(part.geometry),
  };
}

/**
 * A canonical signature of a message's EDITABLE CONTENT, excluding server-derived / volatile fields
 * (`editorFields` visibility map, `revision`, `unackComments`, and each part's `indexNo` — the order
 * is captured by the array itself). Two messages with the same signature are content-equal, so the
 * editor is "unsaved" exactly when the working signature differs from the loaded baseline's.
 */
export function messageContentSignature(message: Message): string {
  const m = message;
  const content = {
    type: m.type,
    series: m.messageSeries?.seriesId,
    number: m.number,
    shortId: m.shortId,
    mainType: m.mainType,
    publishDateFrom: normalizeDate(m.publishDateFrom),
    publishDateTo: normalizeDate(m.publishDateTo),
    followUpDate: normalizeDate(m.followUpDate),
    originalInformation: m.originalInformation,
    autoTitle: m.autoTitle,
    separatePage: m.separatePage,
    horizontalDatum: m.horizontalDatum,
    thumbnailPath: m.thumbnailPath,
    descs: descsContent(m.descs),
    areas: m.areas,
    categories: m.categories,
    charts: m.charts,
    references: m.references,
    attachments: m.attachments,
    promulgations: promulgationsContent(m.promulgations),
    parts: (m.parts ?? []).map(partContent),
  };
  return canonicalKey(content);
}
