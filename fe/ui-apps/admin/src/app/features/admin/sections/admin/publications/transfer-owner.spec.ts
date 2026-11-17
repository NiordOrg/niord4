import { describe, expect, it } from 'vitest';

import type { Domain } from '../../../../../core/domain/models/domain.model';
import { otherAdminDomains } from './transfer-owner';

const CATALOGUE: readonly Domain[] = [
  { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
  { domainId: 'niord-annex', name: 'Annexes' } as Domain,
  { domainId: 'niord-fe', name: 'Firing exercises', active: false } as Domain,
];

/** Admin everywhere but the firing-exercise desk. */
const adminIn = (domainId: string): boolean => domainId !== 'niord-fe';

describe('otherAdminDomains', () => {
  /**
   * Two exclusions, and both are about not offering something that cannot work.
   *
   * The current owner is not a target — moving a publication to the desk that
   * already owns it is not a change — and a domain the caller does not
   * administer is refused server-side with a refusal naming exactly that.
   */
  it('drops the current owner and every domain the caller does not administer', () => {
    expect(otherAdminDomains(CATALOGUE, 'niord-nm', adminIn).map((d) => d.domainId)).toEqual([
      'niord-annex',
    ]);
  });

  /**
   * An INACTIVE domain stays on offer.
   *
   * A domain that is switched off is still a real desk that can be switched back
   * on, and hiding it makes a publication filed there unreachable; the picker
   * marks it instead.
   */
  it('keeps an inactive domain the caller administers', () => {
    expect(otherAdminDomains(CATALOGUE, 'niord-nm', () => true).map((d) => d.domainId)).toEqual([
      'niord-annex',
      'niord-fe',
    ]);
  });

  /** A blank or absent owner excludes nothing: there is no desk to exclude. */
  it('offers every administered domain when the owner is not known', () => {
    expect(otherAdminDomains(CATALOGUE, undefined, adminIn).map((d) => d.domainId)).toEqual([
      'niord-nm',
      'niord-annex',
    ]);
    expect(otherAdminDomains(CATALOGUE, '  ', adminIn)).toHaveLength(2);
  });

  /** An admin who administers one domain has nowhere to move anything to. */
  it('offers nothing when the caller administers only the owner', () => {
    expect(otherAdminDomains(CATALOGUE, 'niord-nm', (id) => id === 'niord-nm')).toEqual([]);
  });
});
