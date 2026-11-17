export const MAIN_TYPES = ['NW', 'NM'] as const;

export type MainType = (typeof MAIN_TYPES)[number];

export const KNOWN_MAIN_TYPES: ReadonlySet<MainType> = new Set(MAIN_TYPES);
