import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { EmptySelection } from './empty-selection';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup() {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco()],
  });
  const fixture = TestBed.createComponent(EmptySelection);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance };
}

describe('EmptySelection', () => {
  it('exposes the selected.empty title via translateSignal', () => {
    const { component } = setup();
    expect(component.title()).toBe('messages.selected.empty.title');
  });

  it('exposes the selected.empty body via translateSignal', () => {
    const { component } = setup();
    expect(component.body()).toBe('messages.selected.empty.body');
  });

  it('passes the star icon to <app-empty-state>', () => {
    const { fixture } = setup();
    const emptyState = fixture.nativeElement.querySelector('app-empty-state');
    expect(emptyState).not.toBeNull();
    expect(emptyState.getAttribute('icon')).toBe('lucideStar');
  });
});
