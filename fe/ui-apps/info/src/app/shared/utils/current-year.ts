/**
 * The calendar year an instant falls in, read in a given IANA zone — a label or an editable
 * seed, never a stored instant.
 *
 * It has a module of its own, and its own one-line `Intl` call rather than a call into the date
 * arithmetic next door, because the footer renders it on the first visit: reaching `zoned-date`
 * from the eager graph would put the whole of that module into the first visit's payload for a
 * four-digit number, and this site's readers are on a metered satellite link.
 *
 * The zone is never omitted, which is what separates this from `new Date().getFullYear()`: a
 * reader a few hours either side of a New Year the deployment has not reached yet would
 * otherwise be shown the wrong one.
 */
export function currentYearInZone(timeZone: string, now: Date = new Date()): number {
  return Number(new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric' }).format(now));
}
