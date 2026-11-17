/** Danger from this many days out — the certificate needs replacing now. */
const CRITICAL_DAYS = 14;
/** Warning from this many days out — start the renewal. */
const WARNING_DAYS = 60;

/**
 * Text tone for a certificate's remaining lifetime: danger at 14 days or less
 * (including already-expired, which is negative), warning up to 60 days, plain
 * body text beyond that. Both certificate surfaces read the thresholds from
 * here so the overview and the certificate detail can never disagree.
 */
export function expiryToneClass(daysToExpiry: number): string {
  if (daysToExpiry <= CRITICAL_DAYS) {
    return 'text-danger';
  }
  if (daysToExpiry <= WARNING_DAYS) {
    return 'text-warning';
  }
  return '';
}

/** A certificate whose validity has already lapsed — rendered with an explicit EXPIRED marker. */
export function isExpired(daysToExpiry: number): boolean {
  return daysToExpiry < 0;
}
