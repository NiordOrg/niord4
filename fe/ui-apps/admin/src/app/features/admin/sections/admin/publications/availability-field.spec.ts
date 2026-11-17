import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Availability } from '../../../services/api/publication-series-api.service';
import { PublicationAvailabilityField } from './availability-field';

@Component({
  imports: [PublicationAvailabilityField],
  template: `
    <app-publication-availability-field
      [(value)]="availability"
      [(domainIds)]="domainIds"
      [ownerId]="ownerId()"
    />
  `,
})
class Host {
  readonly availability = signal<Availability>('SELECTED_DOMAINS');
  readonly domainIds = signal<readonly string[]>([]);
  readonly ownerId = signal<string | undefined>('niord-nm');
}

/**
 * The catalogue, which answers with the ACTIVE domains only.
 *
 * That is the whole point of the orphan case below: a domain deactivated after a
 * publication was shared with it simply stops appearing here.
 */
const catalogue = signal<readonly Domain[]>([
  { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
  { domainId: 'niord-annex', name: 'Annexes' } as Domain,
  { domainId: 'niord-fa', name: 'Firing areas', active: false } as Domain,
]);

function setup() {
  catalogue.set([
    { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
    { domainId: 'niord-annex', name: 'Annexes' } as Domain,
    { domainId: 'niord-fa', name: 'Firing areas', active: false } as Domain,
  ]);
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: DomainFacade,
        useValue: { availableDomains: catalogue } as unknown as DomainFacade,
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    element: fixture.nativeElement as HTMLElement,
  };
}

const orphans = (element: HTMLElement) =>
  Array.from(element.querySelectorAll('[data-testid="availability-orphan"]'));

const checkboxes = (element: HTMLElement) =>
  Array.from(element.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));

describe('PublicationAvailabilityField', () => {
  afterEach(() => TestBed.resetTestingModule());

  /** The owner is already the owner: a tickbox for it could only ever be wrong. */
  it('offers every catalogue domain but the owner', () => {
    const { element } = setup();

    const labels = Array.from(element.querySelectorAll('label')).map((l) => l.textContent ?? '');
    expect(labels.some((t) => t.includes('Annexes'))).toBe(true);
    expect(labels.some((t) => t.includes('Notices to Mariners'))).toBe(false);
    // An inactive domain the catalogue still carries stays tickable, and says so.
    expect(labels.some((t) => t.includes('Firing areas') && t.includes('common.inactive'))).toBe(
      true,
    );
  });

  /**
   * THE CASE THIS CONTROL WAS MISSING.
   *
   * A share with a domain deactivated after the fact is absent from the
   * catalogue, so a list rendering only what it recognises showed the admin
   * nothing — no way to see the share, and no way to drop it.
   */
  it('shows a stored share the catalogue no longer carries, greyed and removable', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set(['niord-annex', 'niord-gone']);
    await fixture.whenStable();

    const rows = orphans(element);
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('niord-gone');
    expect(rows[0].textContent).toContain('common.inactive');
    // No tickbox: there is nothing to turn back on from here, and one would say
    // the domain could be re-picked.
    expect(rows[0].querySelector('input[type="checkbox"]')).toBeNull();
    expect(rows[0].querySelector('button')).not.toBeNull();
  });

  it('drops an orphaned share when its remove control is used', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set(['niord-annex', 'niord-gone']);
    await fixture.whenStable();

    orphans(element)[0].querySelector('button')!.click();
    await fixture.whenStable();

    expect(host.domainIds()).toEqual(['niord-annex']);
    expect(orphans(element)).toHaveLength(0);
  });

  /**
   * A save made for another reason must not quietly drop the share.
   *
   * The control renders it and offers to remove it; leaving it alone leaves it
   * in the list, which is what the payload sends back.
   */
  it('leaves an orphaned share in the list when nothing touches it', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set(['niord-gone']);
    await fixture.whenStable();

    // Ticking a different desk must not disturb it.
    checkboxes(element)[0].click();
    await fixture.whenStable();

    expect(host.domainIds()).toContain('niord-gone');
  });

  /** An orphaned share is still a share, so the list is not empty and the rule passes. */
  it('counts an orphaned share as a selection', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set(['niord-gone']);
    await fixture.whenStable();

    expect(element.textContent).not.toContain('admin.publications.availability.mustPickOne');
  });

  it('refuses selected-domains with nothing selected, in place', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set([]);
    await fixture.whenStable();

    expect(element.textContent).toContain('admin.publications.availability.mustPickOne');

    host.domainIds.set(['niord-annex']);
    await fixture.whenStable();
    expect(element.textContent).not.toContain('admin.publications.availability.mustPickOne');
  });

  /** The rule is about the middle option alone; the other two carry no list. */
  it('says nothing about an empty list under the other two options', async () => {
    const { fixture, element, host } = setup();
    host.domainIds.set([]);

    for (const option of ['OWNER_ONLY', 'ALL_DOMAINS'] as const) {
      host.availability.set(option);
      await fixture.whenStable();
      expect(element.textContent).not.toContain('admin.publications.availability.mustPickOne');
      // And the list itself is gone: a set of desks under "this domain only"
      // describes nothing.
      expect(element.querySelector('[data-testid="availability-domains"]')).toBeNull();
    }
  });

  /** Nothing to share with at all, and nothing orphaned either. */
  it('says there is nowhere to share when the catalogue holds only the owner', async () => {
    const { fixture, element } = setup();
    catalogue.set([{ domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain]);
    await fixture.whenStable();

    expect(element.textContent).toContain('admin.publications.availability.noOtherDomains');
  });

  /**
   * THREE ANSWERS AND NOTHING ELSE.
   *
   * The field's own title already says what the question is, the labels say what
   * the answers are, and the list the middle answer owns appears directly under
   * it the moment it is picked. Explaining any of that again — per option, or
   * once for the group — is reading a writer has to do before they may make a
   * choice they have already understood.
   */
  it('carries no explanatory copy: not per option, and not for the group', () => {
    const { element } = setup();

    const text = element.textContent ?? '';
    expect(text).not.toContain('admin.publications.availability.groupHint');
    expect(text).not.toContain('admin.publications.availability.selectBelow');
    for (const option of ['OWNER_ONLY', 'SELECTED_DOMAINS', 'ALL_DOMAINS']) {
      expect(text).not.toContain(`admin.publications.availability.hint.${option}`);
    }
  });

  /** The labels themselves are untouched — the answers are still all three. */
  it('renders the three answers as their own labels', () => {
    const { element } = setup();

    const labels = Array.from(element.querySelectorAll('label')).map((l) =>
      (l.textContent ?? '').trim(),
    );
    for (const option of ['OWNER_ONLY', 'SELECTED_DOMAINS', 'ALL_DOMAINS']) {
      expect(labels).toContain(`admin.publications.availability.${option}`);
    }
  });
});
