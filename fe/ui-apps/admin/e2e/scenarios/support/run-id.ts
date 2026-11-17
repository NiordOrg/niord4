/**
 * The run's marker, `E2E-<UTC yyyymmddHHMMss>-<4 random characters>`. A scenario file takes one
 * when it loads and writes it into every name it gives (a vicinity, a series, a mail address), so
 * a second run on the same database never meets the first run's rows.
 */
/** Any run's marker, to find what an earlier run left behind. */
export const RUN_MARKER = /E2E-\d{14}-[A-Z2-9]{4}/;

export function newRunMarker(): string {
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let random = '';
  for (let i = 0; i < 4; i++) random += alphabet[Math.floor(Math.random() * alphabet.length)];
  return `E2E-${stamp}-${random}`;
}
