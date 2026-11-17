import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { DomainMultiselectField } from './domain-multiselect-field';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { provideTestTransloco } from '../../testing/transloco-testing';
import type { Domain } from '../../../core/domain/models/domain.model';

function mkDomain(domainId: string, overrides: Partial<Domain> = {}): Domain {
  return { domainId, name: domainId, ...overrides };
}

interface SetupOptions {
  readonly availableDomains?: readonly Domain[];
}

interface Internals {
  isChecked(domain: Domain): boolean;
  toggleDomain(domain: Domain): void;
  remove(domain: Domain): void;
  chipBackground(color: string | undefined): string | null;
  chipBorder(color: string | undefined): string | null;
  value(): readonly Domain[];
}

function setup(opts: SetupOptions = {}) {
  const availableDomains = signal<readonly Domain[]>(opts.availableDomains ?? []);

  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), { provide: DomainFacade, useValue: { availableDomains } }],
  });

  const fixture = TestBed.createComponent(DomainMultiselectField);
  fixture.detectChanges();
  const comp = fixture.componentInstance;
  const internals = comp as unknown as Internals;

  return { fixture, comp, internals, availableDomains };
}

describe('DomainMultiselectField', () => {
  it('holds the set value', () => {
    const { comp, internals } = setup();
    const domains = [mkDomain('niord-nw'), mkDomain('niord-nm')];

    comp.value.set(domains);

    expect(internals.value()).toEqual(domains);
  });

  it('clears to an empty array', () => {
    const { comp, internals } = setup();
    comp.value.set([mkDomain('niord-nw')]);

    comp.value.set([]);
    expect(internals.value()).toEqual([]);
  });

  it('isChecked matches by domainId', () => {
    const { comp, internals } = setup();
    comp.value.set([mkDomain('niord-nw')]);

    expect(internals.isChecked(mkDomain('niord-nw'))).toBe(true);
    expect(internals.isChecked(mkDomain('niord-nm'))).toBe(false);
  });

  it('toggleDomain adds an unchecked domain and emits valueChange', () => {
    const { comp, internals } = setup();
    const changes: (readonly Domain[])[] = [];
    comp.value.subscribe((v) => changes.push(v));

    const domain = mkDomain('niord-nw');
    internals.toggleDomain(domain);

    expect(internals.value()).toEqual([domain]);
    expect(changes).toEqual([[domain]]);
  });

  it('toggleDomain removes a checked domain by domainId and emits the reduced list', () => {
    const { comp, internals } = setup();
    const keep = mkDomain('niord-nw');
    const drop = mkDomain('niord-nm');
    comp.value.set([keep, drop]);
    const changes: (readonly Domain[])[] = [];
    comp.value.subscribe((v) => changes.push(v));

    internals.toggleDomain(mkDomain('niord-nm'));

    expect(internals.value()).toEqual([keep]);
    expect(changes).toEqual([[keep]]);
  });

  it('remove drops the domain by domainId and emits', () => {
    const { comp, internals } = setup();
    const keep = mkDomain('niord-nw');
    comp.value.set([keep, mkDomain('niord-nm')]);
    const changes: (readonly Domain[])[] = [];
    comp.value.subscribe((v) => changes.push(v));

    internals.remove(mkDomain('niord-nm'));

    expect(internals.value()).toEqual([keep]);
    expect(changes).toEqual([[keep]]);
  });

  it('chipBackground returns a soft color-mix tint when a color is given, else null', () => {
    const { internals } = setup();

    expect(internals.chipBackground('#ff0000')).toBe(
      'color-mix(in srgb, #ff0000 14%, transparent)',
    );
    expect(internals.chipBackground(undefined)).toBeNull();
  });

  it('chipBorder returns the raw color or null', () => {
    const { internals } = setup();

    expect(internals.chipBorder('#00ff00')).toBe('#00ff00');
    expect(internals.chipBorder(undefined)).toBeNull();
  });
});
