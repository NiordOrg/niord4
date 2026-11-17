import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { MessageDateFormatterService } from '../../../../messages/services/message-date-formatter.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { DatesField } from './dates-field';

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
  readonly messageState = signal<Message | undefined>({ id: 'm' });
  message = () => this.messageState();
  editableNow = () => true;
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [DatesField],
  template: `
    <app-dates-field
      [fromField]="form.publishDateFrom"
      [toField]="form.publishDateTo"
      [followUpField]="form.followUpDate"
    />
  `,
})
class Host {
  readonly model = signal<{
    publishDateFrom: Date | null;
    publishDateTo: Date | null;
    followUpDate: Date | null;
  }>({ publishDateFrom: null, publishDateTo: null, followUpDate: null });
  readonly form = form(this.model);
}

function setup(summary: string) {
  const fields = new StubFields();
  const facade = new StubFacade();
  const formatter = { formatDateInterval: vi.fn(() => summary) };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub() },
      { provide: MessageDateFormatterService, useValue: formatter },
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, fields, el };
}

describe('DatesField', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
    );
  });

  it('summarises the publish range in the collapsed viewer', async () => {
    const { fixture, fields, el } = setup('4 Jul 2026 – 6 Jul 2026');
    fields.expanded.set(false);
    await fixture.whenStable();
    expect(el.querySelector('.ed-row-val.empty')).toBeNull();
    expect(el.textContent).toContain('4 Jul 2026 – 6 Jul 2026');
  });

  it('shows the empty summary when there is no publish range', async () => {
    const { fixture, fields, el } = setup('');
    fields.expanded.set(false);
    await fixture.whenStable();
    expect(el.querySelector('.ed-row-val.empty')?.textContent).toContain(
      'editor.field.publishDate.empty',
    );
  });

  it('renders From/To as date-time fields and the follow-up as a date-only picker', async () => {
    const { fixture, el } = setup('range');
    await fixture.whenStable();
    expect(el.querySelectorAll('app-date-time-field').length).toBe(2);
    // The follow-up is the one date picker NOT nested inside a date-time field (date-only, no clock).
    const standalonePickers = [...el.querySelectorAll('app-date-picker')].filter(
      (picker) => !picker.closest('app-date-time-field'),
    );
    expect(standalonePickers.length).toBe(1);
  });
});
