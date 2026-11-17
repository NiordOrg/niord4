/**
 * Projects display text in preferred/default/first-available order without mutating stored descs.
 * Returns a new object, never an element of `descs`; do not use its identity for editing or indexOf.
 */
export function projectDesc<T extends { readonly lang?: string }>(
  descs: readonly T[] | undefined,
  preferredLang: string,
  defaultLang: string,
): T | undefined {
  if (!descs?.length) return undefined;
  const preferred = descs.find((desc) => desc.lang === preferredLang);
  const fallback = descs.find((desc) => desc.lang === defaultLang);
  const text: Record<string, string> = {};
  for (const desc of [...descs]
    .reverse()
    .concat(fallback ? [fallback] : [], preferred ? [preferred] : [])) {
    for (const [key, value] of Object.entries(desc)) {
      if (typeof value === 'string' && value.trim()) text[key] = value;
    }
  }
  return { ...(preferred ?? fallback ?? descs[0]), ...text };
}
