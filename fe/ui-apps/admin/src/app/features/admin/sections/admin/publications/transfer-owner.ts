import { Injectable, inject } from '@angular/core';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';

/**
 * The desks a publication could be moved to, from where the caller is sitting.
 *
 * Two exclusions, and both are about not offering something that cannot work.
 * The current owner is not a target — moving a publication to the desk that
 * already owns it is not a change — and a domain the caller does not administer
 * is refused server-side with a refusal naming exactly that, so offering it
 * turns a rule into a round trip.
 *
 * Pure, so the rule can be walked without a TestBed: the caller supplies the
 * catalogue and the per-domain admin test.
 */
export function otherAdminDomains(
  domains: readonly Domain[],
  ownerId: string | undefined,
  isAdminIn: (domainId: string) => boolean,
): readonly Domain[] {
  const owner = ownerId?.trim();
  return domains.filter((domain) => domain.domainId !== owner && isAdminIn(domain.domainId));
}

/**
 * The transfer targets, resolved against the live catalogue and the live grants.
 *
 * A service rather than a computed on each screen because two screens ask the
 * same question — the series detail and the one-off drawer — and the answer also
 * decides whether either of them offers the action at all. Two copies of it
 * would be two places for the gate and the picker to disagree about which
 * domains are on the table.
 *
 * INACTIVE DOMAINS ARE KEPT. A domain that is switched off is still a real desk
 * that can be switched back on, and hiding it makes a publication filed there
 * unreachable; the picker greys it and says so instead.
 */
@Injectable({ providedIn: 'root' })
export class PublicationTransferTargets {
  private readonly domains = inject(DomainFacade);
  private readonly auth = inject(AuthFacade);

  /** Reads signals, so it composes inside a `computed()` at the call site. */
  targetsFor(ownerId: string | undefined): readonly Domain[] {
    return otherAdminDomains(this.domains.availableDomains(), ownerId, (domainId) =>
      this.auth.adminIn(domainId),
    );
  }
}
