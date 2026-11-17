import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { provideTestLanguages } from '../../../../../shared/testing/language-testing';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TitleField } from './title-field';

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
    descs: [{ lang: 'en', title: '' }],
  });
  message = () => this.messageState();
  editableNow = () => true;
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [TitleField],
  template: `<app-title-field [titlesField]="form.titles" [autoTitleField]="form.autoTitle" />`,
})
class Host {
  readonly model = signal<{ titles: Record<string, string>; autoTitle: boolean }>({
    titles: { en: '', da: '' },
    autoTitle: false,
  });
  readonly form = form(this.model);
}

function setup() {
  const fields = new StubFields();
  const facade = new StubFacade();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideTestLanguages(),
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub() },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const textInputs = () => [
    ...el.querySelectorAll<HTMLInputElement>('input:not([type="checkbox"])'),
  ];
  const autoCheckbox = () => el.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
  return { fixture, host: fixture.componentInstance, fields, facade, el, textInputs, autoCheckbox };
}

describe('TitleField', () => {
  it('shows the primary desc title and a lock glyph when auto-generated (collapsed viewer)', async () => {
    const { fixture, host, fields, facade, el } = setup();
    fields.expanded.set(false);
    facade.messageState.set({ id: 'm', descs: [{ lang: 'en', title: 'Storm warning' }] });
    await fixture.whenStable();
    expect(el.textContent).toContain('Storm warning');
    expect(el.querySelector('.ed-row-val app-icon')).toBeNull();

    host.form.autoTitle().value.set(true);
    await fixture.whenStable();
    expect(el.querySelector('.ed-row-val app-icon')).not.toBeNull();
  });

  it('flags a foreign primary-desc language against the current UI language', async () => {
    const { fixture, fields, facade, el } = setup();
    fields.expanded.set(false);
    facade.messageState.set({ id: 'm', descs: [{ lang: 'da', title: 'Stormvarsel' }] });
    await fixture.whenStable();
    // Collapsed viewer: the only <img> is the foreign-language flag.
    expect(el.querySelector('img')).not.toBeNull();
  });

  it('marks a foreign language the build ships no flag for by its code', async () => {
    const { fixture, fields, facade, el } = setup();
    fields.expanded.set(false);
    facade.messageState.set({ id: 'm', descs: [{ lang: 'kl', title: 'X' }] });
    await fixture.whenStable();
    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('app-language-flag')?.textContent?.trim()).toBe('KL');
  });

  it('renders the copied-back server titles while auto-title locks the inputs', async () => {
    const { fixture, host, facade, textInputs, autoCheckbox } = setup();
    host.form.titles().value.set({ en: 'user draft', da: '' });
    facade.messageState.set({ id: 'm', descs: [{ lang: 'en', title: 'Server generated EN' }] });
    host.form.autoTitle().value.set(true);
    await fixture.whenStable();

    expect(autoCheckbox().checked).toBe(true);
    // Locked → the input mirrors the message desc (server copy-back), not the stale form value.
    expect(textInputs()[0].value).toBe('Server generated EN');
    expect(textInputs()[0].disabled).toBe(true);
  });

  it('renders the form titles (not the message descs) when auto-title is off', async () => {
    const { fixture, host, facade, textInputs } = setup();
    host.form.titles().value.set({ en: 'user draft', da: '' });
    facade.messageState.set({ id: 'm', descs: [{ lang: 'en', title: 'Server generated EN' }] });
    await fixture.whenStable();
    expect(textInputs()[0].value).toBe('user draft');
    expect(textInputs()[0].disabled).toBe(false);
  });

  it('propagates a keystroke into the titles form slice (controlled component)', async () => {
    const { fixture, host, textInputs } = setup();
    await fixture.whenStable();
    const input = textInputs()[0];
    input.value = 'typed title';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.titles().value()['en']).toBe('typed title');
  });

  it('seeds the form titles from the server descs when auto-title is switched off', async () => {
    const { fixture, host, facade, autoCheckbox } = setup();
    host.form.titles().value.set({ en: 'stale', da: '' });
    facade.messageState.set({ id: 'm', descs: [{ lang: 'en', title: 'Adjust-generated' }] });
    host.form.autoTitle().value.set(true);
    await fixture.whenStable();

    autoCheckbox().checked = false;
    autoCheckbox().dispatchEvent(new Event('change', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.autoTitle().value()).toBe(false);
    expect(host.form.titles().value()['en']).toBe('Adjust-generated');
  });
});
