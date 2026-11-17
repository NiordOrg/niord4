import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { PublicationForeignDomain } from './publication-foreign-domain';

const OWNER = 'niord-nm';

const editable = signal<readonly Domain[]>([
  { domainId: 'niord-dk', name: 'Navigational warnings' } as Domain,
  { domainId: OWNER, name: 'Notices to Mariners' } as Domain,
]);
const selectSessionDomain = vi.fn();

function setup(ownerDomainId = OWNER) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: DomainFacade,
        useValue: {
          editableDomains: editable,
          resolveById: (id: string) => editable().find((d) => d.domainId === id),
          selectSessionDomain,
        } as unknown as DomainFacade,
      },
    ],
  });
  const fixture = TestBed.createComponent(PublicationForeignDomain);
  fixture.componentRef.setInput('ownerDomainId', ownerDomainId);
  fixture.detectChanges();
  return {
    fixture,
    switchButton: () =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '[data-testid="foreign-domain-refusal"] button',
      ),
  };
}

describe('PublicationForeignDomain', () => {
  beforeEach(() => {
    editable.set([
      { domainId: 'niord-dk', name: 'Navigational warnings' } as Domain,
      { domainId: OWNER, name: 'Notices to Mariners' } as Domain,
    ]);
    selectSessionDomain.mockClear();
  });

  /**
   * The one switch that keeps the address it is on.
   *
   * A desk change from anywhere else leaves a publication page, because the
   * record belongs to the desk being left. Here the desk being chosen is the one
   * that can render this very page, so the address — query string included — is
   * the whole point of the offer, and the facade is told to keep it.
   */
  it('switches to the owning desk and keeps the address', () => {
    const { switchButton } = setup();

    switchButton()!.click();

    expect(selectSessionDomain).toHaveBeenCalledWith(OWNER, { keepAddress: true });
  });

  it('offers nothing when the reader holds no role on the owning desk', () => {
    const { switchButton } = setup('niord-annex');

    expect(switchButton()).toBeNull();
  });
});
