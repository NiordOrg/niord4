import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import type { Message, MessageDescription } from '../../../../messages/models/message.model';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MessageDescService } from '../../../services/message-desc.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { SubjectField } from './subject-field';

class StubFacade {
  readonly editableNow = signal(true);
  readonly message = signal<Message>({
    id: 'm',
    parts: [
      {
        type: 'DETAILS',
        descs: [
          { lang: 'en', subject: 'Buoy adrift' },
          { lang: 'da', subject: '' },
        ],
        hideSubject: false,
      },
    ],
  });
  setPartSubject = vi.fn();
  setPartHideSubject = vi.fn();
  adjustDebounced = vi.fn();
  flushAdjust = vi.fn();
}

class StubFields {
  readonly expanded = signal(true);
  isExpanded = () => this.expanded();
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
}

const languageStub = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English', flagPath: 'en.png' },
    { value: 'da', label: 'Dansk', flagPath: 'da.png' },
  ],
};

const descStub = {
  descForLangOrDefault: (descs: MessageDescription[] | undefined, lang: string) =>
    descs?.find((d) => d.lang === lang) ?? descs?.[0],
};

@Component({
  imports: [SubjectField],
  template: `<app-subject-field [partIndex]="0" />`,
})
class HostComponent {}

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
      { provide: MessageDescService, useValue: descStub },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const textInputs = () => Array.from(el.querySelectorAll<HTMLInputElement>('input[type="text"]'));
  const checkbox = () => el.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
  return { fixture, facade, fields, el, textInputs, checkbox };
}

describe('SubjectField', () => {
  it('writes a keystroke through setPartSubject and debounces an adjust', async () => {
    const { fixture, facade, textInputs } = setup();
    await fixture.whenStable();
    const input = textInputs()[0];
    input.value = 'Wreck reported';
    input.dispatchEvent(new Event('input', { bubbles: true }));

    expect(facade.setPartSubject).toHaveBeenCalledWith(0, 'en', 'Wreck reported');
    expect(facade.adjustDebounced).toHaveBeenCalled();
    expect(facade.flushAdjust).not.toHaveBeenCalled();
  });

  it('flushes the pending adjust on blur', async () => {
    const { fixture, facade, textInputs } = setup();
    await fixture.whenStable();
    textInputs()[0].dispatchEvent(new Event('blur', { bubbles: true }));
    expect(facade.flushAdjust).toHaveBeenCalled();
  });

  it('shows the CHOSEN content language subject in the collapsed viewer (not descs[0])', async () => {
    const { fixture, facade, fields, el } = setup();
    fields.expanded.set(false);
    facade.message.set({
      id: 'm',
      parts: [
        {
          type: 'DETAILS',
          descs: [
            { lang: 'da', subject: 'Primary DA subject' },
            { lang: 'en', subject: 'Secondary EN subject' },
          ],
        },
      ],
    });
    await fixture.whenStable();
    // Current UI language is 'en' → the viewer reads the EN desc, not the first (DA) desc.
    expect(el.textContent).toContain('Secondary EN subject');
    expect(el.textContent).not.toContain('Primary DA subject');
  });

  it('toggles hide-subject through the facade', async () => {
    const { fixture, facade, checkbox } = setup();
    await fixture.whenStable();
    const box = checkbox();
    box.checked = true;
    box.dispatchEvent(new Event('change', { bubbles: true }));
    expect(facade.setPartHideSubject).toHaveBeenCalledWith(0, true);
  });
});
