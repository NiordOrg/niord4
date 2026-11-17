// Selection toggle is a native visually-hidden checkbox (keeps a11y + keyboard)
// wrapped in a label whose <app-icon> star reflects the checked state (outline
// star → filled star, surface-400 → accent).
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it } from 'vitest';

import { MessageSelectionToggle } from './message-selection-toggle';
import { MessageSelectionService } from '../../services/message-selection.service';
import type { Message } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function makeMessage(id: string): Message {
  return { id };
}

function setup(initial?: Message) {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideTestTransloco()],
  });

  const selection = TestBed.inject(MessageSelectionService);
  const fixture = TestBed.createComponent(MessageSelectionToggle);
  fixture.componentRef.setInput('message', initial ?? makeMessage('m-1'));
  fixture.detectChanges();
  return { fixture, selection, component: fixture.componentInstance };
}

describe('MessageSelectionToggle', () => {
  it('reports unchecked when message is not selected', () => {
    const { component } = setup();
    expect(component.checked()).toBe(false);
  });

  it('reports checked when message is in selection service', () => {
    const { component, selection } = setup();
    selection.select(makeMessage('m-1'));
    expect(component.checked()).toBe(true);
  });

  it('toggle adds the message to the selection service', () => {
    const { component, selection } = setup();
    component.onToggle();
    expect(selection.isSelected('m-1')).toBe(true);
  });

  it('toggle removes the message when already selected', () => {
    const { component, selection } = setup();
    selection.select(makeMessage('m-1'));
    component.onToggle();
    expect(selection.isSelected('m-1')).toBe(false);
  });

  it('renders an outline star when unselected and a filled star when selected', () => {
    const { fixture, selection } = setup();
    const icon = () => fixture.nativeElement.querySelector('app-icon') as HTMLElement;
    expect(icon().classList).toContain('text-surface-400');
    expect(icon().classList).not.toContain('text-accent');

    selection.select(makeMessage('m-1'));
    fixture.detectChanges();
    expect(icon().classList).toContain('text-accent');
    expect(icon().classList).not.toContain('text-surface-400');
  });

  it('inputId is derived from message.id', () => {
    const { component } = setup(makeMessage('msg-42'));
    expect(component.inputId()).toBe('msg-select-msg-42');
  });
});
