import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { SourcesApiService } from '../../../../messages/services/api/sources-api.service';
import { SourcePickerDialog } from '../../source-picker-dialog/source-picker-dialog';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { SourceField } from './source-field';

class StubFields {
  readonly expanded = signal(true);
  isExpanded = () => this.expanded();
  edited = () => false;
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

class StubFacade {
  readonly messageState = signal<Message | undefined>({
    id: 'm',
    descs: [{ lang: 'en', source: '' }],
  });
  message = () => this.messageState();
  editableNow = () => true;
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [SourceField],
  template: `<app-source-field [sourcesField]="form.sources" />`,
})
class Host {
  readonly model = signal<{ sources: Record<string, string> }>({ sources: { en: '', da: '' } });
  readonly form = form(this.model);
}

function setup() {
  const fields = new StubFields();
  const facade = new StubFacade();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub() },
      { provide: SourcesApiService, useValue: { getAll: () => of([]), getSource: () => of(null) } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const textInputs = () => [
    ...el.querySelectorAll<HTMLInputElement>('input:not([type="checkbox"])'),
  ];
  return { fixture, host: fixture.componentInstance, fields, facade, el, textInputs };
}

describe('SourceField', () => {
  it('reads the primary desc source (descs[0]) for the collapsed viewer, not the UI-language desc', async () => {
    const { fixture, fields, facade, el } = setup();
    fields.expanded.set(false);
    facade.messageState.set({
      id: 'm',
      descs: [
        { lang: 'da', source: 'Primary DA source' },
        { lang: 'en', source: 'Secondary EN source' },
      ],
    });
    await fixture.whenStable();
    // Current UI language is 'en', but the viewer must read descs[0] (the Danish primary).
    expect(el.textContent).toContain('Primary DA source');
    expect(el.textContent).not.toContain('Secondary EN source');
  });

  it('renders the editable form sources (not the message descs)', async () => {
    const { fixture, host, facade, textInputs } = setup();
    host.form.sources().value.set({ en: 'user draft', da: '' });
    facade.messageState.set({ id: 'm', descs: [{ lang: 'en', source: 'Server generated EN' }] });
    await fixture.whenStable();
    expect(textInputs()[0].value).toBe('user draft');
    expect(textInputs()[0].disabled).toBe(false);
  });

  it('propagates a keystroke into the sources form slice (controlled component)', async () => {
    const { fixture, host, textInputs } = setup();
    await fixture.whenStable();
    const input = textInputs()[0];
    input.value = 'typed source';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.sources().value()['en']).toBe('typed source');
  });

  it('appends the picker output into the sources form slice, comma-joining any existing text', async () => {
    const { fixture, host } = setup();
    host.form.sources().value.set({ en: 'Existing', da: '' });
    await fixture.whenStable();

    // The picker hands its composed text back via (sourcesAdded) — drive it through the child.
    const dialog = fixture.debugElement.query(By.directive(SourcePickerDialog))
      .componentInstance as SourcePickerDialog;
    dialog.sourcesAdded.emit({ en: 'NAVTEX 5 May', da: 'NAVTEX 5. maj' });
    await fixture.whenStable();

    expect(host.form.sources().value()['en']).toBe('Existing, NAVTEX 5 May');
    expect(host.form.sources().value()['da']).toBe('NAVTEX 5. maj');
  });
});
