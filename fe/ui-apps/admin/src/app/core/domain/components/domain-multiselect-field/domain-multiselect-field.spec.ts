import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { DomainMultiselectField } from './domain-multiselect-field';
import { DomainFacade } from '../../facades/domain.facade';
import type { Domain } from '../../models/domain.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

const DOMAINS: Domain[] = [
  { domainId: 'nw', name: 'NW', active: true },
  { domainId: 'nm', name: 'NM', active: true },
];

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: DomainFacade, useValue: { availableDomains: () => DOMAINS } },
    ],
  });
  const fixture = TestBed.createComponent(DomainMultiselectField);
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance };
}

describe('DomainMultiselectField', () => {
  it('renders a checkbox per available domain', () => {
    const { fixture } = setup();
    expect(fixture.nativeElement.querySelectorAll('input[type=checkbox]').length).toBe(2);
  });

  it('toggles a domain into and out of the value', () => {
    const { fixture, comp } = setup();
    const boxes: HTMLInputElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('input[type=checkbox]'),
    );
    boxes[0].click();
    expect(comp.value().map((d) => d.domainId)).toEqual(['nw']);
    boxes[0].click();
    expect(comp.value()).toEqual([]);
  });

  it('reflects the initial value as checked', () => {
    const { fixture, comp } = setup();
    comp.value.set([DOMAINS[1]]);
    fixture.detectChanges();
    const boxes: HTMLInputElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('input[type=checkbox]'),
    );
    expect(boxes[0].checked).toBe(false);
    expect(boxes[1].checked).toBe(true);
  });
});
