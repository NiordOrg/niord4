import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { MessagePart } from '../../../messages/models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { PartTypeSwitcher } from './part-type-switcher';

class StubFacade {
  readonly editableNow = signal(true);
  setPartType = vi.fn();
}

/** `showPartType` is a spy so each test pins exactly which gates pass; the switcher filters on it. */
class StubFields {
  showPartType = vi.fn((part: MessagePart, gate: string) => Boolean(gate) || Boolean(part));
}

const languageStub = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English' },
    { value: 'da', label: 'Dansk' },
  ],
};

@Component({
  imports: [PartTypeSwitcher],
  template: `<app-part-type-switcher [partIndex]="partIndex()" [part]="part()" />`,
})
class HostComponent {
  readonly partIndex = signal(2);
  readonly part = signal<MessagePart>({ type: 'DETAILS' });
}

function setup() {
  const facade = new StubFacade();
  const fields = new StubFields();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: LanguageService, useValue: languageStub },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const host = fixture.componentInstance;
  const el = fixture.nativeElement as HTMLElement;
  const buttons = () => Array.from(el.querySelectorAll<HTMLButtonElement>('button'));
  return { fixture, host, facade, fields, buttons };
}

describe('PartTypeSwitcher', () => {
  it('renders a button per part-type whose gate passes (per the visibility predicates)', async () => {
    const { fixture, fields, buttons } = setup();
    // Only the `part_types`-gated group passes → PROHIBITION (prohibition) and SIGNALS (signals) hide.
    fields.showPartType.mockImplementation((_p, gate: string) => gate === 'part_types');
    fixture.componentInstance.part.set({ type: 'DETAILS' });
    await fixture.whenStable();

    const gates = fields.showPartType.mock.calls.map((c) => c[1]);
    expect(gates).toContain('prohibition');
    expect(gates).toContain('signals');
    // The four `part_types`-gated options render; the two extra-gated ones (PROHIBITION/SIGNALS) do not.
    expect(buttons().length).toBe(4);
  });

  it('renders every button when all gates pass', async () => {
    const { fixture, fields, buttons } = setup();
    fields.showPartType.mockReturnValue(true);
    fixture.componentInstance.part.set({ type: 'TIME' });
    await fixture.whenStable();
    // The six part types: DETAILS/TIME/POSITIONS/NOTE + PROHIBITION + SIGNALS.
    expect(buttons().length).toBe(6);
  });

  it('marks the active type via aria-pressed', async () => {
    const { fixture, buttons } = setup();
    await fixture.whenStable();
    const pressed = buttons().filter((b) => b.getAttribute('aria-pressed') === 'true');
    expect(pressed.length).toBe(1);
  });

  it('retypes the part through the facade on click (first visible option = DETAILS)', async () => {
    const { fixture, facade, buttons } = setup();
    await fixture.whenStable();
    buttons()[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(facade.setPartType).toHaveBeenCalledWith(2, 'DETAILS');
  });

  it('disables every button when the message is not editable', async () => {
    const { fixture, facade, buttons } = setup();
    facade.editableNow.set(false);
    await fixture.whenStable();
    expect(buttons().every((b) => b.disabled)).toBe(true);
  });
});
