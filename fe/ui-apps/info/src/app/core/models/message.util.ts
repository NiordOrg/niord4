import { Area, Desc, GeoJsonFeature, Message } from './message.model';

/**
 * Functions over the message model: no injection, no HTTP, nothing that has to be resolved
 * before they can be called. They sit beside the model they read rather than on a service,
 * because a store, a print sheet and a map layer all need them and none of them needs a
 * service to hold them.
 */

/** The top of an area's lineage — the country the area belongs to. */
export function rootAreaOf(area: Area | undefined): Area | undefined {
  while (area?.parent) {
    area = area.parent;
  }
  return area;
}

/**
 * Every `descs` array ordered so the given language comes first: a view renders the first text
 * of each, so a reader gets their own language wherever the message has it. A copy: the fetched
 * message is shared and stays untouched.
 */
export function sortDescs(msg: Message, lang: string): Message {
  const sort = <T extends { readonly descs?: readonly Desc[] }>(item: T): T => {
    if (!item.descs || item.descs.length <= 1) return item;
    return {
      ...item,
      descs: [...item.descs].sort((a, b) => (a.lang === lang ? -1 : b.lang === lang ? 1 : 0)),
    };
  };
  return {
    ...sort(msg),
    parts: msg.parts?.map(sort),
    references: msg.references?.map(sort),
    attachments: msg.attachments?.map(sort),
    areas: msg.areas?.map(sort),
  };
}

/**
 * Message id → the area heading printed above it, set on the first message of each area run.
 * The heading is taken `maxLevels` down the lineage, so a country's sub-areas name themselves
 * rather than repeating the country on every group.
 */
export function computeAreaHeadings(
  messages: readonly Message[],
  maxLevels = 2,
): Map<string, Area> {
  const headings = new Map<string, Area>();
  let lastAreaId: number | undefined;
  for (const msg of messages) {
    if (msg.areas && msg.areas.length > 0) {
      const areas: Area[] = [];
      let current: Area | undefined = msg.areas[0];
      while (current) {
        areas.unshift(current);
        current = current.parent;
      }
      if (areas.length > 0) {
        const area = areas[Math.min(areas.length - 1, maxLevels - 1)];
        if (lastAreaId === undefined || area.id !== lastAreaId) {
          lastAreaId = area.id;
          headings.set(msg.id, area);
        }
      }
    }
  }
  return headings;
}

/**
 * The messages filed under any of the selected areas, an area's children counting as the area.
 * An empty selection is no filter at all.
 */
export function filterByAreaIds(
  messages: readonly Message[],
  selectedIds: ReadonlySet<number>,
): readonly Message[] {
  if (selectedIds.size === 0) return messages;

  return messages.filter((msg) => {
    if (!msg.areas) return false;
    return msg.areas.some((area) => {
      let current: Area | undefined = area;
      while (current) {
        if (selectedIds.has(current.id)) return true;
        current = current.parent;
      }
      return false;
    });
  });
}

/**
 * Whether a message is in force at the given instant: some interval of some part contains it,
 * both ends included and an open end reaching as far as it likes. A message that carries no
 * event dates at all counts as in force, because a warning the data cannot judge is never hidden.
 */
export function isActiveAt(msg: Message, instantMs: number): boolean {
  const intervals = (msg.parts ?? []).flatMap((part) => part.eventDates ?? []);
  if (intervals.length === 0) return true;
  return intervals.some(
    (interval) =>
      (interval.fromDate === undefined || interval.fromDate <= instantMs) &&
      (interval.toDate === undefined || instantMs <= interval.toDate),
  );
}

/** Every geometry a message carries, across all of its parts. */
export function featuresForMessage(msg: Message | undefined): readonly GeoJsonFeature[] {
  const features: GeoJsonFeature[] = [];
  if (msg?.parts) {
    for (const part of msg.parts) {
      if (part.geometry?.features?.length) {
        features.push(...part.geometry.features);
      }
    }
  }
  return features;
}
