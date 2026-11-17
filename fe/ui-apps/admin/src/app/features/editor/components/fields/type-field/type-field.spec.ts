import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message, MessageType } from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TypeField } from './type-field';

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
  readonly messageState = signal<Message | undefined>(undefined);
  readonly editableNowState = signal(true);
  readonly validTypes = signal<ReadonlySet<string> | null>(null);
  message = () => this.messageState();
  editableNow = () => this.editableNowState();
  // `null` → all valid; a set → only listed types valid (the domain-series OR-computation).
  isTypeValid = (type: string) => {
    const set = this.validTypes();
    return set ? set.has(type) : true;
  };
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [TypeField],
  template: `<app-type-field [field]="form.type" />`,
})
class Host {
  readonly model = signal<{ type: '' | MessageType }>({ type: '' });
  readonly form = form(this.model);
}

function setup(message?: Message) {
  const fields = new StubFields();
  const facade = new StubFacade();
  if (message) {
    facade.messageState.set(message);
  }
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub() },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const row = () => el.querySelector('.ed-row')!;
  const optionButtons = () => [...el.querySelectorAll<HTMLButtonElement>('[role="group"] button')];
  return { fixture, host: fixture.componentInstance, fields, facade, el, row, optionButtons };
}

describe('TypeField', () => {
  it('renders the invalid row treatment until both mainType and type are set', async () => {
    const { fixture, facade, row } = setup({ id: 'm', mainType: 'NW' });
    await fixture.whenStable();
    expect(row().classList).toContain('invalid');

    facade.messageState.set({ id: 'm', mainType: 'NW', type: 'COASTAL_WARNING' });
    await fixture.whenStable();
    expect(row().classList).not.toContain('invalid');
  });

  it('renders the NW segmented set for an NW message and the NM set for an NM message', async () => {
    const { fixture, facade, optionButtons } = setup({ id: 'm', mainType: 'NW' });
    await fixture.whenStable();
    let labels = optionButtons().map((b) => b.textContent?.trim());
    expect(labels).toEqual([
      'msg.type.LOCAL_WARNING',
      'msg.type.COASTAL_WARNING',
      'msg.type.SUBAREA_WARNING',
      'msg.type.NAVAREA_WARNING',
    ]);

    facade.messageState.set({ id: 'm', mainType: 'NM' });
    await fixture.whenStable();
    labels = optionButtons().map((b) => b.textContent?.trim());
    expect(labels).toEqual([
      'msg.type.TEMPORARY_NOTICE',
      'msg.type.PRELIMINARY_NOTICE',
      'msg.type.PERMANENT_NOTICE',
      'msg.type.MISCELLANEOUS_NOTICE',
    ]);
  });

  it('writes only the type field on select (auto-deduce + adjust stay host-side)', async () => {
    const { fixture, host, optionButtons } = setup({ id: 'm', mainType: 'NW' });
    await fixture.whenStable();
    optionButtons()[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.type().value()).toBe('COASTAL_WARNING');
  });

  it('hides options the domain series does not permit', async () => {
    const { fixture, facade, optionButtons } = setup({ id: 'm', mainType: 'NW' });
    facade.validTypes.set(new Set(['COASTAL_WARNING', 'LOCAL_WARNING']));
    await fixture.whenStable();
    // Only the two permitted types render; SUBAREA_WARNING / NAVAREA_WARNING are hidden entirely.
    expect(optionButtons().map((b) => b.textContent?.trim())).toEqual([
      'msg.type.LOCAL_WARNING',
      'msg.type.COASTAL_WARNING',
    ]);
    expect(optionButtons().every((b) => !b.disabled)).toBe(true);
  });
});
