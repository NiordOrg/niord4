import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { DomainMessageSeriesRef } from '../../../../../core/domain/models/domain.model';
import type { Message } from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { IdField } from './id-field';

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
    id: 'm-1',
    messageSeries: { seriesId: 's' },
  });
  readonly seriesState = signal<readonly DomainMessageSeriesRef[]>([
    { seriesId: 's', mainType: 'NW' } as DomainMessageSeriesRef,
  ]);
  readonly numberEditableState = signal(false);
  readonly shortIdEditableState = signal(false);
  readonly sequenceTypeState = signal<string | undefined>('MANUAL');
  message = () => this.messageState();
  messageSeries = () => this.seriesState();
  messageNumberEditable = () => this.numberEditableState();
  shortIdEditable = () => this.shortIdEditableState();
  numberSequenceType = () => this.sequenceTypeState();
  editableNow = () => true;
  flushAdjust = vi.fn();
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [IdField],
  template: `
    <app-id-field
      [seriesField]="form.seriesId"
      [shortIdField]="form.shortId"
      [numberField]="form.number"
    />
  `,
})
class Host {
  readonly model = signal<{ seriesId: string; shortId: string; number: number | null }>({
    seriesId: 's',
    shortId: '',
    number: null,
  });
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
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const numberInput = () => el.querySelector<HTMLInputElement>('input[type="number"]');
  const shortIdInput = () =>
    el.querySelector<HTMLInputElement>('input[aria-label="editor.field.shortId"]');
  const badge = () => el.querySelector('app-message-id-badge');
  const row = () => el.querySelector('.ed-row')!;
  return {
    fixture,
    host: fixture.componentInstance,
    facade,
    el,
    numberInput,
    shortIdInput,
    badge,
    row,
  };
}

describe('IdField', () => {
  it('number editable ⇒ number input, short-id falls back to the read-only badge', async () => {
    const { fixture, facade, numberInput, shortIdInput, badge } = setup();
    facade.numberEditableState.set(true);
    facade.shortIdEditableState.set(false);
    await fixture.whenStable();
    expect(numberInput()).not.toBeNull();
    expect(shortIdInput()).toBeNull();
    expect(badge()).not.toBeNull();
  });

  it('short-id editable ⇒ short-id input and no number input (mutually exclusive)', async () => {
    const { fixture, facade, numberInput, shortIdInput, badge } = setup();
    facade.numberEditableState.set(false);
    facade.shortIdEditableState.set(true);
    await fixture.whenStable();
    expect(shortIdInput()).not.toBeNull();
    expect(numberInput()).toBeNull();
    expect(badge()).toBeNull();
  });

  it('hides the short-id row entirely when the sequence type is NONE', async () => {
    const { fixture, facade, shortIdInput, badge } = setup();
    facade.sequenceTypeState.set('NONE');
    facade.shortIdEditableState.set(false);
    await fixture.whenStable();
    expect(shortIdInput()).toBeNull();
    expect(badge()).toBeNull();
  });

  it('renders the invalid row treatment until a message series is set', async () => {
    const { fixture, facade, row } = setup();
    facade.messageState.set({ id: 'm-1' });
    await fixture.whenStable();
    expect(row().classList).toContain('invalid');

    facade.messageState.set({ id: 'm-1', messageSeries: { seriesId: 's' } });
    await fixture.whenStable();
    expect(row().classList).not.toContain('invalid');
  });

  it('flushes the debounced adjust on number-input blur', async () => {
    const { fixture, facade, numberInput } = setup();
    facade.numberEditableState.set(true);
    await fixture.whenStable();
    numberInput()!.dispatchEvent(new Event('blur', { bubbles: true }));
    expect(facade.flushAdjust).toHaveBeenCalled();
  });
});
