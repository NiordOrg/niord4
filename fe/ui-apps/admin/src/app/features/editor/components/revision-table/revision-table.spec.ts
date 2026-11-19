import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { DomainService } from '../../../../core/domain/services/domain.service';
import type { MessageHistory } from '../../../messages/models/message-history.model';
import { RevisionTable } from './revision-table';

function rev(version: number, created = '2026-06-01T10:00:00Z'): MessageHistory {
  return {
    version,
    created,
    user: 'alice',
    status: 'DRAFT',
    snapshot: '{}',
  };
}

function setup(revisions: readonly MessageHistory[], selected: readonly MessageHistory[]) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(RevisionTable);
  fixture.componentRef.setInput('revisions', revisions);
  fixture.componentRef.setInput('selected', selected);
  fixture.detectChanges();
  return fixture;
}

const rowCount = (fixture: { nativeElement: HTMLElement }) =>
  fixture.nativeElement.querySelectorAll('tbody tr').length;
const checkboxCount = (fixture: { nativeElement: HTMLElement }) =>
  fixture.nativeElement.querySelectorAll('input[type=checkbox]').length;

describe('RevisionTable', () => {
  it('renders no body rows for an empty history — header only, NO empty-revisions row (audit C1)', () => {
    const fixture = setup([], []);
    expect(rowCount(fixture)).toBe(0);
  });

  it('shows a checkbox on every row while fewer than two are selected', () => {
    const fixture = setup([rev(3), rev(2), rev(1)], []);
    expect(rowCount(fixture)).toBe(3);
    expect(checkboxCount(fixture)).toBe(3);
  });

  it('hides the checkbox on unselected rows once two are selected (the ≤2 cap)', () => {
    const revisions = [rev(3), rev(2), rev(1)];
    const fixture = setup(revisions, [revisions[0], revisions[1]]);
    // Three rows still render, but only the two selected keep a checkbox.
    expect(rowCount(fixture)).toBe(3);
    expect(checkboxCount(fixture)).toBe(2);
  });

  it('ticks only the selected one of two entries with the same version number', async () => {
    // A message carried over from an older system can hold two entries numbered alike.
    const revisions = [rev(4), rev(3, '2016-05-02T09:00:00Z'), rev(3, '2016-05-01T09:00:00Z')];
    const fixture = setup(revisions, [revisions[2]]);
    await fixture.whenStable(); // ngModel writes the checked state after a microtask
    const boxes = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>(
        'input[type=checkbox]',
      ),
    ];
    expect(rowCount(fixture)).toBe(3);
    expect(boxes.map((box) => box.checked)).toEqual([false, false, true]);
  });
});
