const UNITS = ['KB', 'MB', 'GB'] as const;

/**
 * A byte count as short readable text: below 1024 bytes the whole count (`"1023 B"`), otherwise one
 * decimal in the first of KB, MB and GB whose value, rounded to that decimal, is below 1024
 * (`"1.0 MB"`, never `"1024.0 KB"`). Sizes beyond that stay in GB. Units are binary (1 KB = 1024 B),
 * as in the file uploader and the attachments table that show them.
 */
export function formatByteSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  let value = bytes;
  for (let i = 0; i < UNITS.length; i++) {
    value /= 1024;
    const rounded = Math.round(value * 10) / 10;
    if (rounded < 1024 || i === UNITS.length - 1) {
      return `${rounded.toFixed(1)} ${UNITS[i]}`;
    }
  }
  // Unreachable: the last unit always returns.
  return `${bytes} B`;
}
