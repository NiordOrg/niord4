import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import type { MessageTagType } from '../../models/message-tag.model';
import { provideFakeAuthFacade } from '../../testing/fake-auth';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { TagScopeSelect } from './tag-scope-select';

@Component({
  imports: [TagScopeSelect],
  template: `<app-tag-scope-select [value]="value()" (valueChange)="changes.push($event)" />`,
})
class Host {
  readonly value = signal<readonly MessageTagType[]>([]);
  readonly changes: (readonly MessageTagType[])[] = [];
}

function setup(isLoggedIn: boolean) {
  TestBed.configureTestingModule({
    providers: [provideFakeAuthFacade({ isAuthenticated: isLoggedIn }), provideTestTransloco()],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance };
}

function chipValues(fixture: ReturnType<typeof setup>['fixture']): string[] {
  const select = fixture.debugElement.children[0].componentInstance as TagScopeSelect;
  return (select as unknown as { typeChips: () => readonly { value: string }[] })
    .typeChips()
    .map((chip) => chip.value);
}

describe('TagScopeSelect', () => {
  it('logged-in users get all three scopes, private first', () => {
    const { fixture } = setup(true);
    expect(chipValues(fixture)).toEqual(['PRIVATE', 'DOMAIN', 'PUBLIC']);
  });

  it('anonymous users get no PRIVATE scope (they have no private tags)', () => {
    const { fixture } = setup(false);
    expect(chipValues(fixture)).toEqual(['DOMAIN', 'PUBLIC']);
  });

  it('renders one toggle button per scope through the segmented control', () => {
    const { fixture } = setup(false);
    const buttons = fixture.nativeElement.querySelectorAll('button.seg-btn');
    expect(buttons.length).toBeGreaterThanOrEqual(2);
  });
});
